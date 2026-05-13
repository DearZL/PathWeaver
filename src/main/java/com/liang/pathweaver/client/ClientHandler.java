package com.liang.pathweaver.client;

import com.liang.pathweaver.logic.BezierUtil;
import com.liang.pathweaver.network.C2SDeleteRegionPacket;
import com.liang.pathweaver.network.C2SGeneratePathPacket;
import com.liang.pathweaver.network.C2SMarkPathPointPacket;
import com.liang.pathweaver.network.C2SStartCornerPacket;
import com.liang.pathweaver.network.C2SSwitchModePacket;
import com.liang.pathweaver.network.ModMessages;
import net.minecraft.client.gui.screens.ConfirmScreen;
import com.liang.pathweaver.network.S2CUpdateStatePacket;
import com.liang.pathweaver.data.PathMode;
import com.liang.pathweaver.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class ClientHandler {

    // ---- 服务端同步的状态 ----
    private static BlockPos pendingCorner = null;
    private static final List<BlockPos[]> regions = new ArrayList<>();
    private static boolean hasTemplate = false;
    private static int templateLength = 1, templateWidth = 1, templateHeight = 1;
    private static Direction templateBaseDir = Direction.NORTH;
    private static final List<BlockPos> pathPoints = new ArrayList<>();
    private static PathMode pathMode = PathMode.LINEAR;
    // Template block data for per-block shape preview
    private static final List<String> templateBlockTypeIds = new ArrayList<>();
    private static final List<int[]>  templateBlockData    = new ArrayList<>();
    // Tile count computed each frame during preview render (used by HUD)
    private static int previewTileCount = 0;

    // 各框颜色（按索引循环）
    private static final float[][] BLOCK_TYPE_COLORS = {
            {0.40f, 0.80f, 1.00f},  // 0: 浅蓝
            {1.00f, 0.65f, 0.20f},  // 1: 橙
            {0.45f, 1.00f, 0.50f},  // 2: 绿
            {1.00f, 0.45f, 0.45f},  // 3: 红
            {1.00f, 0.95f, 0.30f},  // 4: 黄
            {0.85f, 0.40f, 1.00f},  // 5: 紫
            {0.30f, 0.95f, 0.95f},  // 6: 青
            {1.00f, 0.45f, 0.80f},  // 7: 粉
    };

    private static final float[][] REGION_COLORS = {
            {1.00f, 0.35f, 0.35f},  // 红
            {0.35f, 0.65f, 1.00f},  // 蓝
            {0.45f, 1.00f, 0.45f},  // 绿
            {1.00f, 1.00f, 0.35f},  // 黄
            {1.00f, 0.55f, 0.15f},  // 橙
            {0.75f, 0.35f, 1.00f},  // 紫
            {0.35f, 1.00f, 0.90f},  // 青
            {1.00f, 0.45f, 0.75f},  // 粉
    };

    public static void updateState(S2CUpdateStatePacket pkt) {
        pendingCorner = pkt.pendingCorner;
        regions.clear();
        regions.addAll(pkt.regions);
        hasTemplate = pkt.hasTemplate;
        templateLength = pkt.templateLength;
        templateWidth = pkt.templateWidth;
        templateHeight = pkt.templateHeight;
        templateBaseDir = pkt.templateBaseDir;
        pathPoints.clear();
        pathPoints.addAll(pkt.pathPoints);
        pathMode = pkt.pathMode;
        templateBlockTypeIds.clear();
        templateBlockTypeIds.addAll(pkt.templateBlockTypeIds);
        templateBlockData.clear();
        templateBlockData.addAll(pkt.templateBlockData);
    }

    // ---- 退出游戏时清空客户端状态 ----
    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        pendingCorner = null;
        regions.clear();
        hasTemplate = false;
        templateLength = 1;
        templateWidth = 1;
        templateHeight = 1;
        templateBaseDir = Direction.NORTH;
        pathPoints.clear();
        pathMode = PathMode.LINEAR;
        templateBlockTypeIds.clear();
        templateBlockData.clear();
        previewTileCount = 0;
        leftClickConsumed = false;
    }

    // ---- 左键防抖 ----
    private static boolean leftClickConsumed = false;

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton event) {
        if (event.getButton() == 0 && event.getAction() == org.lwjgl.glfw.GLFW.GLFW_RELEASE) {
            leftClickConsumed = false;
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide) return;
        if (!event.getEntity().getMainHandItem().is(ModItems.PATH_WEAVER_TOOL.get())) return;
        if (event.getEntity().isShiftKeyDown()) return; // shift+右键=确认模板，不拦截
        if (pathPoints.isEmpty()) return;               // 无路径点，直接放行
        if (pendingCorner != null) return;              // 已选第一角点，放行第二角点

        BlockPos clicked = event.getPos();
        event.setCanceled(true);
        event.setUseBlock(net.minecraftforge.eventbus.api.Event.Result.DENY);
        event.setUseItem(net.minecraftforge.eventbus.api.Event.Result.DENY);

        int count = pathPoints.size();
        Minecraft.getInstance().setScreen(new ConfirmScreen(
                accepted -> {
                    Minecraft.getInstance().setScreen(null);
                    if (accepted) {
                        ModMessages.CHANNEL.sendToServer(new C2SStartCornerPacket(clicked));
                    }
                },
                net.minecraft.network.chat.Component.literal("§6重新框选模板"),
                net.minecraft.network.chat.Component.literal(
                        "将清空当前所有路径点（共 §e" + count + " §f个），确认继续？")
        ));
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getEntity().getMainHandItem().is(ModItems.PATH_WEAVER_TOOL.get())) return;

        // 纯路径点模式：有模板 且 当前无框选区域 且 无待定角点
        boolean inPathPointMode = hasTemplate && regions.isEmpty() && pendingCorner == null;

        if (inPathPointMode) {
            // 路径点模式：拦截左键，发包
            event.setCanceled(true);
            event.setUseBlock(net.minecraftforge.eventbus.api.Event.Result.DENY);
            event.setUseItem(net.minecraftforge.eventbus.api.Event.Result.DENY);
            if (!event.getLevel().isClientSide) return;
            if (leftClickConsumed) return;
            leftClickConsumed = true;
            if (event.getEntity().isShiftKeyDown()) {
                ModMessages.CHANNEL.sendToServer(new C2SGeneratePathPacket());
            } else {
                ModMessages.CHANNEL.sendToServer(new C2SMarkPathPointPacket(event.getPos()));
            }
        } else {
            // 框选模式（包括重新选择模板时）
            if (isPosInAnyRegion(event.getPos())) {
                // 点中已有框内方块：删除最小重叠框
                event.setCanceled(true);
                event.setUseBlock(net.minecraftforge.eventbus.api.Event.Result.DENY);
                event.setUseItem(net.minecraftforge.eventbus.api.Event.Result.DENY);
                if (!event.getLevel().isClientSide) return;
                if (leftClickConsumed) return;
                leftClickConsumed = true;
                ModMessages.CHANNEL.sendToServer(new C2SDeleteRegionPacket(event.getPos()));
            } else {
                // 框外方块：拦截破坏，手持工具时不破坏方块
                event.setCanceled(true);
                event.setUseBlock(net.minecraftforge.eventbus.api.Event.Result.DENY);
                event.setUseItem(net.minecraftforge.eventbus.api.Event.Result.DENY);
            }
        }
    }

    // ---- Shift+滚轮切换模式 ----
    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!mc.player.getMainHandItem().is(ModItems.PATH_WEAVER_TOOL.get())) return;
        if (!mc.player.isShiftKeyDown()) return;
        event.setCanceled(true);
        ModMessages.CHANNEL.sendToServer(new C2SSwitchModePacket());
    }

    // ---- 粒子特效（每3tick，约6.7次/秒） ----
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (!mc.player.getMainHandItem().is(ModItems.PATH_WEAVER_TOOL.get())) return;
        long t = mc.level.getGameTime();
        if (t % 3 != 0) return;
        Level level = mc.level;

        // 待定角点：金色火花 burst
        if (pendingCorner != null) {
            spawnCornerBurst(level, pendingCorner, 1f, 0.85f, 0.1f, 4);
            if (t % 6 == 0 && mc.hitResult instanceof BlockHitResult bhr) {
                BlockPos target = bhr.getBlockPos();
                int mnX = Math.min(pendingCorner.getX(), target.getX());
                int mnY = Math.min(pendingCorner.getY(), target.getY());
                int mnZ = Math.min(pendingCorner.getZ(), target.getZ());
                int mxX = Math.max(pendingCorner.getX(), target.getX());
                int mxY = Math.max(pendingCorner.getY(), target.getY());
                int mxZ = Math.max(pendingCorner.getZ(), target.getZ());
                AABB prev = new AABB(mnX, mnY, mnZ, mxX + 1, mxY + 1, mxZ + 1);
                float hue = (t * 0.025f) % 1.0f;
                float[] rc = hsvToRgb(hue, 0.55f, 1.0f);
                spawnEdgeFlow(level, prev, rc[0], rc[1], rc[2], 3);
            }
        }

        // 已完成区域：角点火花 + 彩色粉尘 + 边缘流动
        for (int i = 0; i < regions.size(); i++) {
            float[] c = REGION_COLORS[i % REGION_COLORS.length];
            BlockPos[] r = regions.get(i);
            spawnCornerBurst(level, r[0], c[0], c[1], c[2], 3);
            spawnCornerBurst(level, r[1], c[0], c[1], c[2], 3);
            spawnColoredDust(level, r[0].getX() + 0.5, r[0].getY() + 0.5, r[0].getZ() + 0.5, c[0], c[1], c[2]);
            spawnColoredDust(level, r[1].getX() + 0.5, r[1].getY() + 0.5, r[1].getZ() + 0.5, c[0], c[1], c[2]);
            if (t % 6 == 0) {
                int minX = Math.min(r[0].getX(), r[1].getX()), maxX = Math.max(r[0].getX(), r[1].getX());
                int minY = Math.min(r[0].getY(), r[1].getY()), maxY = Math.max(r[0].getY(), r[1].getY());
                int minZ = Math.min(r[0].getZ(), r[1].getZ()), maxZ = Math.max(r[0].getZ(), r[1].getZ());
                spawnEdgeFlow(level, new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1), c[0], c[1], c[2], 2);
            }
        }

        // 路径点：青色上升粒子
        for (BlockPos pt : pathPoints) {
            spawnRisingSoul(level, pt.getX() + 0.5, pt.getY() + 1.0, pt.getZ() + 0.5);
        }
    }

    /** 在方块中心上方生成彩色粉尘粒子。 */
    private static void spawnColoredDust(Level level, double x, double y, double z, float r, float g, float b) {
        DustParticleOptions dust = new DustParticleOptions(new Vector3f(r, g, b), 1.2f);
        level.addParticle(dust, x, y, z,
                (Math.random() - 0.5) * 0.04,
                 0.02 + Math.random() * 0.04,
                (Math.random() - 0.5) * 0.04);
    }

    /** 在方块角落生成火花 burst（END_ROD 粒子散射）。 */
    private static void spawnCornerBurst(Level level, BlockPos pos, float r, float g, float b, int count) {
        if (pos == null) return;
        double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
        for (int i = 0; i < count; i++) {
            double ox = (Math.random() - 0.5) * 0.3;
            double oy = (Math.random() - 0.5) * 0.3;
            double oz = (Math.random() - 0.5) * 0.3;
            // 用彩色粉尘代替白色 END_ROD，颜色与框一致
            DustParticleOptions dust = new DustParticleOptions(new Vector3f(r, g, b), 0.8f);
            level.addParticle(dust, cx + ox, cy + oy, cz + oz,
                    ox * 0.15, 0.03 + Math.random() * 0.06, oz * 0.15);
        }
    }

    /** 沿 AABB 边缘随机位置的流动粒子。 */
    private static void spawnEdgeFlow(Level level, AABB box, float r, float g, float b, int count) {
        double[][] edges = {
            {box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ},
            {box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ},
            {box.maxX, box.minY, box.maxZ, box.minX, box.minY, box.maxZ},
            {box.minX, box.minY, box.maxZ, box.minX, box.minY, box.minZ},
            {box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ},
            {box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ},
            {box.maxX, box.maxY, box.maxZ, box.minX, box.maxY, box.maxZ},
            {box.minX, box.maxY, box.maxZ, box.minX, box.maxY, box.minZ},
            {box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ},
            {box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ},
            {box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ},
            {box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ},
        };
        for (int i = 0; i < count; i++) {
            double[] e = edges[(int)(Math.random() * edges.length)];
            double t = Math.random();
            double px = e[0] + (e[3] - e[0]) * t;
            double py = e[1] + (e[4] - e[1]) * t;
            double pz = e[2] + (e[5] - e[2]) * t;
            double dx = e[3] - e[0], dy = e[4] - e[1], dz = e[5] - e[2];
            double len = Math.sqrt(dx*dx + dy*dy + dz*dz);
            if (len > 0.001) { dx /= len; dy /= len; dz /= len; }
            DustParticleOptions dust = new DustParticleOptions(new Vector3f(
                    Math.min(1f, r + 0.3f), Math.min(1f, g + 0.3f), Math.min(1f, b + 0.3f)), 0.6f);
            level.addParticle(dust, px, py, pz, dx * 0.04, dy * 0.04 + 0.01, dz * 0.04);
        }
    }

    /** 路径点上方缓慢上升的青色粒子。 */
    private static void spawnRisingSoul(Level level, double x, double y, double z) {
        level.addParticle(net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME,
                x + (Math.random() - 0.5) * 0.25,
                y,
                z + (Math.random() - 0.5) * 0.25,
                0, 0.04 + Math.random() * 0.06, 0);
    }

    // ================================================================
    //  渲染
    // ================================================================
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!mc.player.getMainHandItem().is(ModItems.PATH_WEAVER_TOOL.get())) return;

        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource src = mc.renderBuffers().bufferSource();
        float time = mc.level.getGameTime() + event.getPartialTick();

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);

        VertexConsumer lines = src.getBuffer(RenderType.lines());

        // 1. 已完成的框选区域 — 体积光雾笼 + 扫描面 + 角柱 + 扫描线 + 角点标记
        float regPulse = (float)(Math.sin(time * 0.12) * 0.4 + 0.6);
        for (int i = 0; i < regions.size(); i++) {
            float[] c = REGION_COLORS[i % REGION_COLORS.length];
            BlockPos[] r = regions.get(i);
            int minX = Math.min(r[0].getX(), r[1].getX()), maxX = Math.max(r[0].getX(), r[1].getX());
            int minY = Math.min(r[0].getY(), r[1].getY()), maxY = Math.max(r[0].getY(), r[1].getY());
            int minZ = Math.min(r[0].getZ(), r[1].getZ()), maxZ = Math.max(r[0].getZ(), r[1].getZ());
            AABB box = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);

            // —— 体积光雾笼：5 层半透明网格面，从内核向外扩散形成雾化光芒 ——
            float auraPulse = (float)(Math.sin(time * 0.09 + i * 0.8f) * 0.25 + 0.75);
            renderGlowFaces(ps, lines, box,                   c[0], c[1], c[2], regPulse * auraPulse * 0.18f, 0.45f);
            renderGlowFaces(ps, lines, box.inflate(0.035),    c[0], c[1], c[2], regPulse * auraPulse * 0.12f, 0.55f);
            renderGlowFaces(ps, lines, box.inflate(0.08),     c[0], c[1], c[2], regPulse * auraPulse * 0.07f, 0.70f);
            renderGlowFaces(ps, lines, box.inflate(0.15),     c[0], c[1], c[2], regPulse * auraPulse * 0.04f, 0.90f);
            renderGlowFaces(ps, lines, box.inflate(0.26),     c[0], c[1], c[2], regPulse * auraPulse * 0.018f, 1.20f);

            // —— 正交双垂直扫描面（X/Z 方向各一，交错扫过整个体积） ——
            renderVerticalScanPlane(ps, lines, box, c[0], c[1], c[2], regPulse * 0.18f, time, 0);
            renderVerticalScanPlane(ps, lines, box, c[0], c[1], c[2], regPulse * 0.18f, time, 1);

            // —— 双横向扫描线 ——
            float sa = 0.28f + regPulse * 0.22f;
            for (float phase : new float[]{0f, (float) Math.PI}) {
                double scanY = box.minY + (box.maxY - box.minY) * ((float)(Math.sin(time * 0.07 + i * 1.4f + phase) * 0.5 + 0.5));
                drawThickLine(ps, lines, new Vec3(box.minX, scanY, box.minZ), new Vec3(box.maxX, scanY, box.minZ), c[0], c[1], c[2], sa, 0.028f);
                drawThickLine(ps, lines, new Vec3(box.maxX, scanY, box.minZ), new Vec3(box.maxX, scanY, box.maxZ), c[0], c[1], c[2], sa, 0.028f);
                drawThickLine(ps, lines, new Vec3(box.maxX, scanY, box.maxZ), new Vec3(box.minX, scanY, box.maxZ), c[0], c[1], c[2], sa, 0.028f);
                drawThickLine(ps, lines, new Vec3(box.minX, scanY, box.maxZ), new Vec3(box.minX, scanY, box.minZ), c[0], c[1], c[2], sa, 0.028f);
            }

            // —— 四角光束柱 + 中腰连接环 ——
            renderVerticalBeams(ps, lines, box, c[0], c[1], c[2], regPulse * 0.65f, time);

            // —— 边缘锐利轮廓线（在最外层勾勒清晰边界） ——
            renderDashedBox(ps, lines, box.inflate(0.005), c[0], c[1], c[2], regPulse * 0.55f, time, 0.90f + i * 0.10f);

            // —— 8 角括号 + 钻石 ——
            float bLen = (float)Math.max(0.18, Math.min(0.5,
                    Math.min(maxX - minX + 1, Math.min(maxY - minY + 1, maxZ - minZ + 1)) * 0.22));
            bLen += (float)(Math.sin(time * 0.2 + i * 1.1f) * 0.04);
            renderCornerBrackets(ps, lines, box, c[0], c[1], c[2], 1f, bLen);
            float dSize = 0.20f + regPulse * 0.10f;
            renderDiamondCorners(ps, lines, box, c[0], c[1], c[2], regPulse * 0.72f, dSize);

            // —— 角点十字 ——
            renderCrossMarker(ps, lines, r[0], time, c[0], c[1], c[2]);
            renderCrossMarker(ps, lines, r[1], time, c[0], c[1], c[2]);
        }

        // 2. 待定角点标记（金色粗十字 + 外层扩散大十字）
        if (pendingCorner != null) {
            float gp = (float)(Math.sin(time * 0.22) * 0.5 + 0.5);
            renderCrossMarker(ps, lines, pendingCorner, time, 1f, 0.90f, 0f);
            float sz = 0.50f + gp * 0.15f;
            double px = pendingCorner.getX() + 0.5, py = pendingCorner.getY() + 0.5, pz = pendingCorner.getZ() + 0.5;
            drawThickLine(ps, lines, new Vec3(px - sz, py, pz), new Vec3(px + sz, py, pz), 1f, 1f, 0.35f, 0.25f + gp * 0.35f, 0.022f);
            drawThickLine(ps, lines, new Vec3(px, py - sz, pz), new Vec3(px, py + sz, pz), 1f, 1f, 0.35f, 0.25f + gp * 0.35f, 0.022f);
            drawThickLine(ps, lines, new Vec3(px, py, pz - sz), new Vec3(px, py, pz + sz), 1f, 1f, 0.35f, 0.25f + gp * 0.35f, 0.022f);

            // 3. 动态预览框 — 简洁彩虹轮廓 + 半透明表面 + 角括号
            if (mc.hitResult instanceof BlockHitResult bhr) {
                BlockPos target = bhr.getBlockPos();
                int mnX = Math.min(pendingCorner.getX(), target.getX());
                int mnY = Math.min(pendingCorner.getY(), target.getY());
                int mnZ = Math.min(pendingCorner.getZ(), target.getZ());
                int mxX = Math.max(pendingCorner.getX(), target.getX());
                int mxY = Math.max(pendingCorner.getY(), target.getY());
                int mxZ = Math.max(pendingCorner.getZ(), target.getZ());
                AABB prev = new AABB(mnX, mnY, mnZ, mxX + 1, mxY + 1, mxZ + 1);

                float hue = (time * 0.018f) % 1.0f;
                float[] rc = hsvToRgb(hue, 0.50f, 1.0f);
                float pa = (float)(Math.sin(time * 0.25) * 0.15 + 0.35);

                // 单层半透明表面
                renderGlowFaces(ps, lines, prev, rc[0], rc[1], rc[2], pa * 0.16f, 0.55f);
                // 外层微光晕
                renderGlowFaces(ps, lines, prev.inflate(0.04), rc[0], rc[1], rc[2], pa * 0.08f, 0.75f);
                // 锐利轮廓虚线
                renderDashedBox(ps, lines, prev.inflate(0.005), rc[0], rc[1], rc[2], pa * 0.50f, time, 1.4f);
                // 8角括号
                renderCornerBrackets(ps, lines, prev, rc[0], rc[1], rc[2], pa * 1.4f, 0.25f);
            }
        }

        // 4. 路径点连线（粗渐变：绿→黄→橙）
        if (pathPoints.size() >= 2) {
            for (int i = 0; i + 1 < pathPoints.size(); i++) {
                float frac  = (float) i / (pathPoints.size() - 1);
                float frac1 = (float)(i + 1) / (pathPoints.size() - 1);
                Vec3 a = blockCenter(pathPoints.get(i));
                Vec3 b = blockCenter(pathPoints.get(i + 1));
                drawThickLineGradient(ps, lines, a, b,
                        gradR(frac), gradG(frac), gradB(frac),
                        gradR(frac1), gradG(frac1), gradB(frac1), 0.022f);
            }
        }

        // 5. 路径点标记（青色脉冲 + 信标光柱）
        for (int i = 0; i < pathPoints.size(); i++) {
            renderPathPointMarker(ps, lines, pathPoints.get(i), time, i);
        }

        // 6. 铺砖预览（脉冲绿色）
        if (hasTemplate && !pathPoints.isEmpty()) {
            renderTilePreview(ps, lines, time);
        }

        ps.popPose();
        src.endBatch(RenderType.lines());
    }

    // ---- 渐变颜色计算（绿→黄→橙） ----
    private static float gradR(float t) { return t < 0.5f ? t * 2f : 1f; }
    private static float gradG(float t) { return t < 0.5f ? 1f : 1f - (t - 0.5f); }
    private static float gradB(float t) { return 0f; }

    // ---- 角点十字标记（三轴交叉粗线，居于方块中心） ----
    private static void renderCrossMarker(PoseStack ps, VertexConsumer lines, BlockPos pos,
                                          float time, float r, float g, float b) {
        float pulse = (float)(Math.sin(time * 0.18) * 0.1 + 0.9);
        float size = 0.33f * pulse;
        double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
        drawThickLine(ps, lines, new Vec3(cx - size, cy, cz), new Vec3(cx + size, cy, cz), r, g, b, 1f, 0.020f);
        drawThickLine(ps, lines, new Vec3(cx, cy - size, cz), new Vec3(cx, cy + size, cz), r, g, b, 1f, 0.020f);
        drawThickLine(ps, lines, new Vec3(cx, cy, cz - size), new Vec3(cx, cy, cz + size), r, g, b, 1f, 0.020f);
    }

    // ---- 路径点标记（脉冲方框 + 光柱） ----
    private static void renderPathPointMarker(PoseStack ps, VertexConsumer lines,
                                               BlockPos pos, float time, int index) {
        float phase = (pos.getX() * 1.3f + pos.getZ() * 0.9f) * 0.5f;
        float pulse = (float)(Math.sin(time * 0.18 + phase) * 0.5 + 0.5);

        float half = 0.12f + pulse * 0.07f;
        double cx = pos.getX() + 0.5, cz = pos.getZ() + 0.5;
        double baseY = pos.getY() + 1.0;

        AABB box = new AABB(cx - half, baseY, cz - half, cx + half, baseY + half * 2, cz + half);
        renderBox(ps, lines, box, 0f, 0.75f + pulse * 0.25f, 1f, 0.55f + pulse * 0.45f);

        float beamH = 0.15f + pulse * 1.0f;
        float beamAlpha = 0.3f + pulse * 0.6f;
        float topY = (float)(baseY + half * 2);
        drawThickLine(ps, lines,
                new Vec3(cx, topY, cz),
                new Vec3(cx, topY + beamH, cz),
                0.2f, 0.85f, 1f, beamAlpha, 0.018f);
    }

    // ---- 铺砖预览（脉冲透明度 + 逐方块着色） ----
    private static void renderTilePreview(PoseStack ps, VertexConsumer lines, float time) {
        float pulse = (float)(Math.sin(time * 0.12) * 0.35 + 0.65);
        int tileCount = 0;

        if (pathMode == PathMode.LINEAR) {
            List<BezierUtil.BezierPoint> tiles = new ArrayList<>();
            for (int i = 0; i + 1 < pathPoints.size(); i++) {
                BlockPos from = pathPoints.get(i), to = pathPoints.get(i + 1);
                Direction dir = dominantDir(from, to);
                double dx = to.getX() - from.getX();
                double dz = to.getZ() - from.getZ();
                double segLen = Math.sqrt(dx * dx + dz * dz);
                if (segLen < 0.001) {
                    tiles.add(new BezierUtil.BezierPoint(from, dir));
                } else {
                    tiles.add(new BezierUtil.BezierPoint(from, dir));
                    for (double dist = templateLength; dist < segLen; dist += templateLength) {
                        double frac = dist / segLen;
                        int x = (int) Math.round(from.getX() + dx * frac);
                        int y = (int) Math.round(from.getY() + (to.getY() - from.getY()) * frac);
                        int z = (int) Math.round(from.getZ() + dz * frac);
                        tiles.add(new BezierUtil.BezierPoint(new BlockPos(x, y, z), dir));
                    }
                }
                // 段间转角：右转时在 B 补一枚入段方向 tile
                if (i + 2 < pathPoints.size()) {
                    Direction nextDir = dominantDir(to, pathPoints.get(i + 2));
                    if (dir != nextDir) {
                        int cross = dir.getStepX() * nextDir.getStepZ() - dir.getStepZ() * nextDir.getStepX();
                        if (cross > 0) {
                            int half = templateWidth / 2;
                            BlockPos wedgeOrigin = to.relative(dir.getOpposite(), templateLength - half - 1);
                            tiles.add(new BezierUtil.BezierPoint(wedgeOrigin, dir));
                        }
                    }
                }
            }
            for (BezierUtil.BezierPoint s : new LinkedHashSet<>(tiles)) {
                renderTileBlocks(ps, lines, s.pos(), s.direction(), pulse);
                tileCount++;
            }
        } else {
            List<BezierUtil.BezierPoint> samples =
                    BezierUtil.sampleMultiSegmentCurve(pathPoints, templateLength);
            for (BezierUtil.BezierPoint s : new LinkedHashSet<>(samples)) {
                renderTileBlocks(ps, lines, s.pos(), s.direction(), pulse);
                tileCount++;
            }
        }
        previewTileCount = tileCount;
    }

    /**
     * Renders one tile instance: a faint bounding-box outline + one colored 1×1×1 box per
     * non-air template block (color depends on block type index).
     */
    private static void renderTileBlocks(PoseStack ps, VertexConsumer lines,
                                          BlockPos origin, Direction pathDir, float pulse) {
        // Center origin (mirrors PathGenerator.centerOrigin)
        int half = templateWidth / 2;
        BlockPos centered = switch (pathDir) {
            case EAST  -> origin.offset(0, 0, -half);
            case WEST  -> origin.offset(0, 0,  half);
            case NORTH -> origin.offset(-half, 0, 0);
            case SOUTH -> origin.offset( half, 0, 0);
            default    -> origin;
        };
        int ox = centered.getX(), oy = centered.getY(), oz = centered.getZ();

        // Faint bounding box with glow face for overall shape
        AABB bbox = switch (pathDir) {
            case SOUTH -> new AABB(ox - templateWidth + 1, oy, oz,
                    ox + 1, oy + templateHeight, oz + templateLength);
            case EAST  -> new AABB(ox, oy, oz,
                    ox + templateLength, oy + templateHeight, oz + templateWidth);
            case WEST  -> new AABB(ox - templateLength + 1, oy, oz - templateWidth + 1,
                    ox + 1, oy + templateHeight, oz + 1);
            default    -> new AABB(ox, oy, oz - templateLength + 1,
                    ox + templateWidth, oy + templateHeight, oz + 1);
        };
        renderGlowFaces(ps, lines, bbox, 0.4f, 0.4f, 0.4f, pulse * 0.14f, 0.55f);

        // Per-block colored outlines (mirrors TemplateData.localToWorld)
        for (int[] bd : templateBlockData) {
            int lx = bd[0], ly = bd[1], lz = bd[2], typeIdx = bd[3];
            int wx = switch (pathDir) {
                case SOUTH -> ox - lx;
                case EAST  -> ox + lz;
                case WEST  -> ox - lz;
                default    -> ox + lx; // NORTH
            };
            int wz = switch (pathDir) {
                case SOUTH -> oz + lz;
                case EAST  -> oz + lx;
                case WEST  -> oz - lx;
                default    -> oz - lz; // NORTH
            };
            int wy = oy + ly;
            float[] c = BLOCK_TYPE_COLORS[typeIdx % BLOCK_TYPE_COLORS.length];
            AABB blockBox = new AABB(wx, wy, wz, wx + 1, wy + 1, wz + 1);
            renderBox(ps, lines, blockBox.inflate(0.02), c[0], c[1], c[2], pulse * 0.85f);
        }
    }

    // ---- 材料消耗 HUD（路径点选完预览时显示） ----
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!mc.player.getMainHandItem().is(ModItems.PATH_WEAVER_TOOL.get())) return;
        // 仅在纯路径点模式且有路径点时显示
        if (!hasTemplate || pathPoints.isEmpty() || !regions.isEmpty()) return;
        if (templateBlockData.isEmpty()) return;

        // 统计每种方块类型在一个 tile 中的数量
        Map<Integer, Integer> countPerType = new LinkedHashMap<>();
        for (int[] bd : templateBlockData) {
            countPerType.merge(bd[3], 1, Integer::sum);
        }
        if (countPerType.isEmpty()) return;

        GuiGraphics gui = event.getGuiGraphics();
        int sw = mc.getWindow().getGuiScaledWidth();

        // 构建显示行（小字，不加影）
        List<String> lines = new ArrayList<>();
        lines.add("§7[需消耗]");
        for (Map.Entry<Integer, Integer> e : countPerType.entrySet()) {
            int typeIdx = e.getKey();
            int total   = e.getValue() * previewTileCount;
            String registryId = typeIdx < templateBlockTypeIds.size()
                    ? templateBlockTypeIds.get(typeIdx) : "?";
            String displayName = getBlockDisplayName(registryId);
            lines.add("§f" + displayName + " §ex" + total);
        }

        // 计算最大宽度用于背景
        int maxW = 0;
        for (String l : lines) {
            int w = mc.font.width(net.minecraft.network.chat.Component.literal(l));
            if (w > maxW) maxW = w;
        }

        int x = sw - maxW - 6;
        int y = 10;
        int lineH = 9;
        int totalH = lines.size() * lineH;
        // 半透明黑底
        gui.fill(x - 2, y - 2, x + maxW + 2, y + totalH + 1, 0x80000000);
        for (String line : lines) {
            gui.drawString(mc.font, line, x, y, 0xFFFFFF, false);
            y += lineH;
        }
    }

    private static String getBlockDisplayName(String registryId) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(registryId);
            if (rl != null) {
                var block = ForgeRegistries.BLOCKS.getValue(rl);
                if (block != null) return block.getName().getString();
            }
        } catch (Exception ignored) {}
        // 回退：去掉命名空间，下划线换空格
        String[] parts = registryId.split(":");
        return (parts.length > 1 ? parts[1] : registryId).replace("_", " ");
    }

    // ---- 判断坐标是否在任意框内（客户端用） ----
    private static boolean isPosInAnyRegion(BlockPos pos) {
        for (BlockPos[] r : regions) {
            if (pos.getX() >= Math.min(r[0].getX(), r[1].getX())
             && pos.getX() <= Math.max(r[0].getX(), r[1].getX())
             && pos.getY() >= Math.min(r[0].getY(), r[1].getY())
             && pos.getY() <= Math.max(r[0].getY(), r[1].getY())
             && pos.getZ() >= Math.min(r[0].getZ(), r[1].getZ())
             && pos.getZ() <= Math.max(r[0].getZ(), r[1].getZ())) return true;
        }
        return false;
    }

    // ---- 低级渲染工具 ----

    private static void renderBox(PoseStack ps, VertexConsumer lines, AABB box,
                                   float r, float g, float b, float a) {
        net.minecraft.client.renderer.LevelRenderer.renderLineBox(ps, lines, box, r, g, b, a);
    }

    private static void drawLine(PoseStack ps, VertexConsumer lines,
                                  Vec3 from, Vec3 to, float r, float g, float b, float a) {
        Matrix4f mat = ps.last().pose();
        Matrix3f norm = ps.last().normal();
        Vector3f d = new Vector3f(
                (float)(to.x - from.x),
                (float)(to.y - from.y),
                (float)(to.z - from.z)).normalize();
        lines.vertex(mat, (float) from.x, (float) from.y, (float) from.z)
                .color(r, g, b, a).normal(norm, d.x, d.y, d.z).endVertex();
        lines.vertex(mat, (float) to.x, (float) to.y, (float) to.z)
                .color(r, g, b, a).normal(norm, d.x, d.y, d.z).endVertex();
    }

    /** Draw a thick line by rendering 5 parallel micro-offset lines. */
    private static void drawThickLine(PoseStack ps, VertexConsumer lines,
                                       Vec3 from, Vec3 to, float r, float g, float b, float a, float thickness) {
        Matrix4f mat = ps.last().pose();
        Matrix3f normMat = ps.last().normal();
        float dx = (float)(to.x - from.x), dy = (float)(to.y - from.y), dz = (float)(to.z - from.z);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.0001f) return;
        Vector3f d = new Vector3f(dx / len, dy / len, dz / len);
        Vector3f ref = Math.abs(d.y) > 0.99f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f p1 = new Vector3f();
        d.cross(ref, p1);
        p1.normalize();
        Vector3f p2 = new Vector3f();
        d.cross(p1, p2);
        p2.normalize();
        float half = thickness * 0.5f;
        float[][] offs = {{0, 0, 0}, {p1.x * half, p1.y * half, p1.z * half},
                          {-p1.x * half, -p1.y * half, -p1.z * half},
                          {p2.x * half, p2.y * half, p2.z * half},
                          {-p2.x * half, -p2.y * half, -p2.z * half}};
        float fx = (float) from.x, fy = (float) from.y, fz = (float) from.z;
        float tx = (float) to.x, ty = (float) to.y, tz = (float) to.z;
        for (int i = 0; i < offs.length; i++) {
            float[] o = offs[i];
            float ca = a * (i == 0 ? 1f : 0.5f);
            lines.vertex(mat, fx + o[0], fy + o[1], fz + o[2])
                    .color(r, g, b, ca).normal(normMat, d.x, d.y, d.z).endVertex();
            lines.vertex(mat, tx + o[0], ty + o[1], tz + o[2])
                    .color(r, g, b, ca).normal(normMat, d.x, d.y, d.z).endVertex();
        }
    }

    /** Draw a thick gradient line (5 parallel micro-offset lines with color gradient). */
    private static void drawThickLineGradient(PoseStack ps, VertexConsumer lines,
                                               Vec3 from, Vec3 to,
                                               float r0, float g0, float b0,
                                               float r1, float g1, float b1, float thickness) {
        Matrix4f mat = ps.last().pose();
        Matrix3f normMat = ps.last().normal();
        float dx = (float)(to.x - from.x), dy = (float)(to.y - from.y), dz = (float)(to.z - from.z);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.0001f) return;
        Vector3f d = new Vector3f(dx / len, dy / len, dz / len);
        Vector3f ref = Math.abs(d.y) > 0.99f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f p1 = new Vector3f();
        d.cross(ref, p1);
        p1.normalize();
        Vector3f p2 = new Vector3f();
        d.cross(p1, p2);
        p2.normalize();
        float half = thickness * 0.5f;
        float[][] offs = {{0, 0, 0}, {p1.x * half, p1.y * half, p1.z * half},
                          {-p1.x * half, -p1.y * half, -p1.z * half},
                          {p2.x * half, p2.y * half, p2.z * half},
                          {-p2.x * half, -p2.y * half, -p2.z * half}};
        float fx = (float) from.x, fy = (float) from.y, fz = (float) from.z;
        float tx = (float) to.x, ty = (float) to.y, tz = (float) to.z;
        for (int i = 0; i < offs.length; i++) {
            float[] o = offs[i];
            float ca = (i == 0 ? 1f : 0.45f);
            lines.vertex(mat, fx + o[0], fy + o[1], fz + o[2])
                    .color(r0, g0, b0, ca).normal(normMat, d.x, d.y, d.z).endVertex();
            lines.vertex(mat, tx + o[0], ty + o[1], tz + o[2])
                    .color(r1, g1, b1, ca).normal(normMat, d.x, d.y, d.z).endVertex();
        }
    }

    private static void drawLineGradient(PoseStack ps, VertexConsumer lines,
                                          Vec3 from, Vec3 to,
                                          float r0, float g0, float b0,
                                          float r1, float g1, float b1) {
        Matrix4f mat = ps.last().pose();
        Matrix3f norm = ps.last().normal();
        Vector3f d = new Vector3f(
                (float)(to.x - from.x),
                (float)(to.y - from.y),
                (float)(to.z - from.z)).normalize();
        lines.vertex(mat, (float) from.x, (float) from.y, (float) from.z)
                .color(r0, g0, b0, 1f).normal(norm, d.x, d.y, d.z).endVertex();
        lines.vertex(mat, (float) to.x, (float) to.y, (float) to.z)
                .color(r1, g1, b1, 1f).normal(norm, d.x, d.y, d.z).endVertex();
    }

    /** 在 AABB 的 8 个角各画 3 条向内延伸的粗短线（L 型括号标记）。 */
    private static void renderCornerBrackets(PoseStack ps, VertexConsumer lines, AABB box,
                                              float r, float g, float b, float a, float len) {
        double[] xs = {box.minX, box.maxX};
        double[] ys = {box.minY, box.maxY};
        double[] zs = {box.minZ, box.maxZ};
        double[] xd = {len, -len};
        double[] yd = {len, -len};
        double[] zd = {len, -len};
        for (int xi = 0; xi < 2; xi++) {
            for (int yi = 0; yi < 2; yi++) {
                for (int zi = 0; zi < 2; zi++) {
                    double cx = xs[xi], cy = ys[yi], cz = zs[zi];
                    drawThickLine(ps, lines, new Vec3(cx, cy, cz), new Vec3(cx + xd[xi], cy, cz), r, g, b, a, 0.018f);
                    drawThickLine(ps, lines, new Vec3(cx, cy, cz), new Vec3(cx, cy + yd[yi], cz), r, g, b, a, 0.018f);
                    drawThickLine(ps, lines, new Vec3(cx, cy, cz), new Vec3(cx, cy, cz + zd[zi]), r, g, b, a, 0.018f);
                }
            }
        }
    }

    /** Draw a box with animated dashed edges (racing light effect). */
    private static void renderDashedBox(PoseStack ps, VertexConsumer lines, AABB box,
                                         float r, float g, float b, float a, float time, float speed) {
        double[][] edges = {
            {box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ},
            {box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ},
            {box.maxX, box.minY, box.maxZ, box.minX, box.minY, box.maxZ},
            {box.minX, box.minY, box.maxZ, box.minX, box.minY, box.minZ},
            {box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ},
            {box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ},
            {box.maxX, box.maxY, box.maxZ, box.minX, box.maxY, box.maxZ},
            {box.minX, box.maxY, box.maxZ, box.minX, box.maxY, box.minZ},
            {box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ},
            {box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ},
            {box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ},
            {box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ},
        };
        int segments = 10;
        float dashRatio = 0.50f;
        for (int ei = 0; ei < edges.length; ei++) {
            double[] e = edges[ei];
            double dx = e[3] - e[0], dy = e[4] - e[1], dz = e[5] - e[2];
            for (int s = 0; s < segments; s++) {
                float phase = (time * speed + ei * 0.23f + (float) s / segments) % 1.0f;
                if (phase > dashRatio) continue;
                double t0 = (double) s / segments;
                double t1 = Math.min((double) (s + 1) / segments, t0 + dashRatio / segments);
                Vec3 from = new Vec3(e[0] + dx * t0, e[1] + dy * t0, e[2] + dz * t0);
                Vec3 to   = new Vec3(e[0] + dx * t1, e[1] + dy * t1, e[2] + dz * t1);
                drawLine(ps, lines, from, to, r, g, b, a);
            }
        }
    }

    /** Draw a wireframe grid on the bottom face (holographic floor projection). */
    private static void renderGridFloor(PoseStack ps, VertexConsumer lines, AABB box,
                                         float r, float g, float b, float a) {
        double w = box.maxX - box.minX;
        double d = box.maxZ - box.minZ;
        if (w < 0.5 || d < 0.5) return;
        double spacing = Math.max(1.0, Math.min(w, d) / 4.0);
        double y = box.minY - 0.005;
        for (double x = box.minX; x <= box.maxX + 0.001; x += spacing)
            drawLine(ps, lines, new Vec3(x, y, box.minZ), new Vec3(x, y, box.maxZ), r, g, b, a);
        for (double z = box.minZ; z <= box.maxZ + 0.001; z += spacing)
            drawLine(ps, lines, new Vec3(box.minX, y, z), new Vec3(box.maxX, y, z), r, g, b, a);
    }

    /** Draw small diamond (octahedron) sparkles at all 8 corners with thick lines. */
    private static void renderDiamondCorners(PoseStack ps, VertexConsumer lines, AABB box,
                                               float r, float g, float b, float a, float size) {
        double[] xs = {box.minX, box.maxX};
        double[] ys = {box.minY, box.maxY};
        double[] zs = {box.minZ, box.maxZ};
        float half = size * 0.5f;
        float thk = size * 0.22f;
        for (int xi = 0; xi < 2; xi++) {
            for (int yi = 0; yi < 2; yi++) {
                for (int zi = 0; zi < 2; zi++) {
                    double cx = xs[xi], cy = ys[yi], cz = zs[zi];
                    drawThickLine(ps, lines, new Vec3(cx - half, cy, cz), new Vec3(cx, cy + half, cz), r, g, b, a, thk);
                    drawThickLine(ps, lines, new Vec3(cx + half, cy, cz), new Vec3(cx, cy + half, cz), r, g, b, a, thk);
                    drawThickLine(ps, lines, new Vec3(cx, cy, cz - half), new Vec3(cx, cy + half, cz), r, g, b, a, thk);
                    drawThickLine(ps, lines, new Vec3(cx, cy, cz + half), new Vec3(cx, cy + half, cz), r, g, b, a, thk);
                    drawThickLine(ps, lines, new Vec3(cx - half, cy, cz), new Vec3(cx, cy - half, cz), r, g, b, a, thk);
                    drawThickLine(ps, lines, new Vec3(cx + half, cy, cz), new Vec3(cx, cy - half, cz), r, g, b, a, thk);
                    drawThickLine(ps, lines, new Vec3(cx, cy, cz - half), new Vec3(cx, cy - half, cz), r, g, b, a, thk);
                    drawThickLine(ps, lines, new Vec3(cx, cy, cz + half), new Vec3(cx, cy - half, cz), r, g, b, a, thk);
                }
            }
        }
    }

    /** Draw 4 bright vertical beams at the corners of the box. */
    private static void renderVerticalBeams(PoseStack ps, VertexConsumer lines, AABB box,
                                              float r, float g, float b, float a, float time) {
        double[] xs = {box.minX, box.maxX};
        double[] zs = {box.minZ, box.maxZ};
        float pulse = (float)(Math.sin(time * 0.13) * 0.3 + 0.7);
        float beamA = a * pulse;
        for (double x : xs) {
            for (double z : zs) {
                drawThickLine(ps, lines,
                        new Vec3(x, box.minY, z), new Vec3(x, box.maxY, z),
                        Math.min(1f, r + 0.25f), Math.min(1f, g + 0.25f), Math.min(1f, b + 0.25f),
                        beamA, 0.028f);
            }
        }
        // Connect beams with a subtle horizontal ring at mid-height
        float ringA = a * pulse * 0.45f;
        double my = (box.minY + box.maxY) * 0.5;
        drawThickLine(ps, lines, new Vec3(xs[0], my, zs[0]), new Vec3(xs[1], my, zs[0]), r, g, b, ringA, 0.012f);
        drawThickLine(ps, lines, new Vec3(xs[1], my, zs[0]), new Vec3(xs[1], my, zs[1]), r, g, b, ringA, 0.012f);
        drawThickLine(ps, lines, new Vec3(xs[1], my, zs[1]), new Vec3(xs[0], my, zs[1]), r, g, b, ringA, 0.012f);
        drawThickLine(ps, lines, new Vec3(xs[0], my, zs[1]), new Vec3(xs[0], my, zs[0]), r, g, b, ringA, 0.012f);
    }

    /** Render dense wireframe grids on all 6 faces of the AABB — creates a translucent "glow cage" surface. */
    private static void renderGlowFaces(PoseStack ps, VertexConsumer lines, AABB box,
                                         float r, float g, float b, float a, float spacing) {
        // Top face (Y = maxY, XZ plane)
        for (double x = box.minX; x <= box.maxX + 0.001; x += spacing)
            drawLine(ps, lines, new Vec3(x, box.maxY, box.minZ), new Vec3(x, box.maxY, box.maxZ), r, g, b, a);
        for (double z = box.minZ; z <= box.maxZ + 0.001; z += spacing)
            drawLine(ps, lines, new Vec3(box.minX, box.maxY, z), new Vec3(box.maxX, box.maxY, z), r, g, b, a);
        // Bottom face
        for (double x = box.minX; x <= box.maxX + 0.001; x += spacing)
            drawLine(ps, lines, new Vec3(x, box.minY, box.minZ), new Vec3(x, box.minY, box.maxZ), r, g, b, a);
        for (double z = box.minZ; z <= box.maxZ + 0.001; z += spacing)
            drawLine(ps, lines, new Vec3(box.minX, box.minY, z), new Vec3(box.maxX, box.minY, z), r, g, b, a);
        // North face (Z = minZ, XY plane)
        for (double x = box.minX; x <= box.maxX + 0.001; x += spacing)
            drawLine(ps, lines, new Vec3(x, box.minY, box.minZ), new Vec3(x, box.maxY, box.minZ), r, g, b, a);
        for (double y = box.minY; y <= box.maxY + 0.001; y += spacing)
            drawLine(ps, lines, new Vec3(box.minX, y, box.minZ), new Vec3(box.maxX, y, box.minZ), r, g, b, a);
        // South face
        for (double x = box.minX; x <= box.maxX + 0.001; x += spacing)
            drawLine(ps, lines, new Vec3(x, box.minY, box.maxZ), new Vec3(x, box.maxY, box.maxZ), r, g, b, a);
        for (double y = box.minY; y <= box.maxY + 0.001; y += spacing)
            drawLine(ps, lines, new Vec3(box.minX, y, box.maxZ), new Vec3(box.maxX, y, box.maxZ), r, g, b, a);
        // West face (X = minX, ZY plane)
        for (double z = box.minZ; z <= box.maxZ + 0.001; z += spacing)
            drawLine(ps, lines, new Vec3(box.minX, box.minY, z), new Vec3(box.minX, box.maxY, z), r, g, b, a);
        for (double y = box.minY; y <= box.maxY + 0.001; y += spacing)
            drawLine(ps, lines, new Vec3(box.minX, y, box.minZ), new Vec3(box.minX, y, box.maxZ), r, g, b, a);
        // East face
        for (double z = box.minZ; z <= box.maxZ + 0.001; z += spacing)
            drawLine(ps, lines, new Vec3(box.maxX, box.minY, z), new Vec3(box.maxX, box.maxY, z), r, g, b, a);
        for (double y = box.minY; y <= box.maxY + 0.001; y += spacing)
            drawLine(ps, lines, new Vec3(box.maxX, y, box.minZ), new Vec3(box.maxX, y, box.maxZ), r, g, b, a);
    }

    /** Vertical scanning plane that sweeps through the volume in X or Z direction. */
    private static void renderVerticalScanPlane(PoseStack ps, VertexConsumer lines, AABB box,
                                                  float r, float g, float b, float a, float time, int axis) {
        float scanT = (float)(Math.sin(time * 0.045 + axis * 1.5f) * 0.5 + 0.5);
        float spacing = 0.50f;
        if (axis == 0) {
            double sx = box.minX + (box.maxX - box.minX) * scanT;
            for (double y = box.minY; y <= box.maxY + 0.001; y += spacing)
                drawLine(ps, lines, new Vec3(sx, y, box.minZ), new Vec3(sx, y, box.maxZ), r, g, b, a);
            for (double z = box.minZ; z <= box.maxZ + 0.001; z += spacing)
                drawLine(ps, lines, new Vec3(sx, box.minY, z), new Vec3(sx, box.maxY, z), r, g, b, a);
        } else {
            double sz = box.minZ + (box.maxZ - box.minZ) * scanT;
            for (double y = box.minY; y <= box.maxY + 0.001; y += spacing)
                drawLine(ps, lines, new Vec3(box.minX, y, sz), new Vec3(box.maxX, y, sz), r, g, b, a);
            for (double x = box.minX; x <= box.maxX + 0.001; x += spacing)
                drawLine(ps, lines, new Vec3(x, box.minY, sz), new Vec3(x, box.maxY, sz), r, g, b, a);
        }
    }

    /** Convert HSV to RGB, returns float[3] with values in 0..1. */
    private static float[] hsvToRgb(float h, float s, float v) {
        h = h % 1f;
        if (h < 0) h += 1f;
        int i = (int) (h * 6);
        float f = h * 6 - i;
        float p = v * (1 - s);
        float q = v * (1 - f * s);
        float t = v * (1 - (1 - f) * s);
        return switch (i % 6) {
            case 0 -> new float[]{v, t, p};
            case 1 -> new float[]{q, v, p};
            case 2 -> new float[]{p, v, t};
            case 3 -> new float[]{p, q, v};
            case 4 -> new float[]{t, p, v};
            default -> new float[]{v, p, q};
        };
    }

    private static Vec3 blockCenter(BlockPos p) {
        return new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
    }

    private static Direction dominantDir(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}

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

        // ═══════════════════════════════════════════════════════
        // 1. 已完成的框选区域 — 霓虹光效 + 全息填充 + 角柱
        // ═══════════════════════════════════════════════════════
        float regPulse = (float)(Math.sin(time * 0.12) * 0.4 + 0.6);
        for (int i = 0; i < regions.size(); i++) {
            float[] c = REGION_COLORS[i % REGION_COLORS.length];
            BlockPos[] r = regions.get(i);
            int minX = Math.min(r[0].getX(), r[1].getX()), maxX = Math.max(r[0].getX(), r[1].getX());
            int minY = Math.min(r[0].getY(), r[1].getY()), maxY = Math.max(r[0].getY(), r[1].getY());
            int minZ = Math.min(r[0].getZ(), r[1].getZ()), maxZ = Math.max(r[0].getZ(), r[1].getZ());
            AABB box = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);

            // ── 全息填充表面：6面密集网格模拟半透明实体 ──
            float fillAlpha = regPulse * 0.55f;
            renderHologramFaces(ps, lines, box, c[0], c[1], c[2], fillAlpha, 0.25f);

            // ── 霓虹光晕：4 层扩散边框 (bloom effect) ──
            renderNeonBox(ps, lines, box, c[0], c[1], c[2], regPulse);

            // ── 正交双垂直扫描面 (能量感) ──
            renderVerticalScanPlane(ps, lines, box, c[0], c[1], c[2], regPulse * 0.55f, time, 0);
            renderVerticalScanPlane(ps, lines, box, c[0], c[1], c[2], regPulse * 0.55f, time, 1);

            // ── 横向扫描光环 ──
            float sa = 0.55f + regPulse * 0.35f;
            for (float phase : new float[]{0f, (float) Math.PI}) {
                double scanY = box.minY + (box.maxY - box.minY) * ((float)(Math.sin(time * 0.07 + i * 1.4f + phase) * 0.5 + 0.5));
                Vec3[] hRing = horizontalRingAt(box, scanY);
                for (int e = 0; e < 4; e++)
                    drawThickLine(ps, lines, hRing[e], hRing[(e+1)%4], c[0], c[1], c[2], sa, 0.060f);
            }

            // ── 四角能量柱 ──
            renderEnergyPillars(ps, lines, box, c[0], c[1], c[2], regPulse * 0.90f, time);

            // ── 跑马灯轮廓 ──
            renderRacingBorder(ps, lines, box.inflate(0.01), c[0], c[1], c[2], regPulse * 0.90f, time, 1.40f + i * 0.12f);

            // ── 角括号 + 能量钻 ──
            float bLen = (float)Math.max(0.30, Math.min(0.72,
                    Math.min(maxX - minX + 1, Math.min(maxY - minY + 1, maxZ - minZ + 1)) * 0.35));
            bLen += (float)(Math.sin(time * 0.2 + i * 1.1f) * 0.08);
            renderCornerBrackets(ps, lines, box, c[0], c[1], c[2], 1f, bLen);
            float dSize = 0.32f + regPulse * 0.18f;
            renderDiamondCorners(ps, lines, box, c[0], c[1], c[2], regPulse * 0.90f, dSize);

            // ── 角点十字 ──
            renderCrossMarker(ps, lines, r[0], time, c[0], c[1], c[2]);
            renderCrossMarker(ps, lines, r[1], time, c[0], c[1], c[2]);
        }

        // ═══════════════════════════════════════════════════════
        // 2. 待定角点 — 信标光柱 + 扩展光环 + 预览框
        // ═══════════════════════════════════════════════════════
        if (pendingCorner != null) {
            float gp = (float)(Math.sin(time * 0.22) * 0.5 + 0.5);
            double px = pendingCorner.getX() + 0.5, py = pendingCorner.getY() + 0.5, pz = pendingCorner.getZ() + 0.5;

            // ── 金色粗十字 ──
            renderCrossMarker(ps, lines, pendingCorner, time, 1f, 0.80f, 0f);
            float sz = 0.65f + gp * 0.25f;
            float markerAlpha = 0.70f + gp * 0.30f;
            drawThickLine(ps, lines, new Vec3(px - sz, py, pz), new Vec3(px + sz, py, pz), 1f, 0.85f, 0.05f, markerAlpha, 0.045f);
            drawThickLine(ps, lines, new Vec3(px, py - sz, pz), new Vec3(px, py + sz, pz), 1f, 0.85f, 0.05f, markerAlpha, 0.045f);
            drawThickLine(ps, lines, new Vec3(px, py, pz - sz), new Vec3(px, py, pz + sz), 1f, 0.85f, 0.05f, markerAlpha, 0.045f);

            // ── 垂直信标光柱 ──
            float beamH = 6f + gp * 3f;
            for (int b = 0; b < 3; b++) {
                float bx = (float)(px + (b == 0 ? 0 : (b == 1 ? 0.12 : -0.12)));
                float bz = (float)(pz + (b == 0 ? 0 : (b == 1 ? 0.12 : -0.12)));
                float ba = (0.50f + gp * 0.30f) * (b == 0 ? 1f : 0.35f);
                drawThickLine(ps, lines,
                        new Vec3(bx, py + 0.4f, bz), new Vec3(bx, py + beamH, bz),
                        1f, 0.88f, 0.05f, ba, b == 0 ? 0.06f : 0.03f);
            }

            // ── 扩展光环 (3 圈) ──
            for (int ring = 0; ring < 3; ring++) {
                float ringY = (float)(py + 0.3f + ring * 1.5f + gp * 0.6f);
                float ringR = 0.35f + ring * 0.15f + gp * 0.12f;
                float ringA = 0.45f - ring * 0.13f + gp * 0.15f;
                renderRingXZ(ps, lines, px, ringY, pz, ringR, 12, 1f, 0.90f, 0.10f, ringA, 0.025f);
            }

            // ── 动态预览框 ──
            if (mc.hitResult instanceof BlockHitResult bhr) {
                BlockPos target = bhr.getBlockPos();
                int mnX = Math.min(pendingCorner.getX(), target.getX());
                int mnY = Math.min(pendingCorner.getY(), target.getY());
                int mnZ = Math.min(pendingCorner.getZ(), target.getZ());
                int mxX = Math.max(pendingCorner.getX(), target.getX());
                int mxY = Math.max(pendingCorner.getY(), target.getY());
                int mxZ = Math.max(pendingCorner.getZ(), target.getZ());
                AABB prev = new AABB(mnX, mnY, mnZ, mxX + 1, mxY + 1, mxZ + 1);

                float hue = (time * 0.020f) % 1.0f;
                float[] rc = hsvToRgb(hue, 0.70f, 1.0f);
                float pa = (float)(Math.sin(time * 0.25) * 0.22 + 0.55);

                // 全息填充
                renderHologramFaces(ps, lines, prev, rc[0], rc[1], rc[2], pa * 0.42f, 0.22f);
                // 光晕外扩
                renderHologramFaces(ps, lines, prev.inflate(0.06), rc[0], rc[1], rc[2], pa * 0.20f, 0.38f);
                renderHologramFaces(ps, lines, prev.inflate(0.14), rc[0], rc[1], rc[2], pa * 0.09f, 0.58f);
                // 霓虹边框
                renderNeonBox(ps, lines, prev, rc[0], rc[1], rc[2], pa);
                // 跑马灯
                renderRacingBorder(ps, lines, prev.inflate(0.01), rc[0], rc[1], rc[2], pa * 0.85f, time, 1.80f);
                // 角括号
                renderCornerBrackets(ps, lines, prev, rc[0], rc[1], rc[2], pa * 1.8f, 0.40f);
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
                        gradR(frac1), gradG(frac1), gradB(frac1), 0.035f);
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

        // 7. 路径点序号（全息浮空数字）
        if (!pathPoints.isEmpty()) {
            renderWaypointLabels(ps, src, time, mc);
        }

        ps.popPose();
        src.endBatch();
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
        float r = 0.10f, g = 0.75f, b = 1f;

        double px = pos.getX() + 0.5, py = pos.getY() + 0.5, pz = pos.getZ() + 0.5;
        float markerAlpha = 0.55f + pulse * 0.45f;

        // ── Cyan cross marker ──
        float sz = 0.40f + pulse * 0.18f;
        drawThickLine(ps, lines, new Vec3(px - sz, py, pz), new Vec3(px + sz, py, pz), r, g, b, markerAlpha, 0.035f);
        drawThickLine(ps, lines, new Vec3(px, py - sz, pz), new Vec3(px, py + sz, pz), r, g, b, markerAlpha, 0.035f);
        drawThickLine(ps, lines, new Vec3(px, py, pz - sz), new Vec3(px, py, pz + sz), r, g, b, markerAlpha, 0.035f);

        // ── Beacon pillar ──
        float beamH = 2.5f + pulse * 1.5f;
        for (int beam = 0; beam < 3; beam++) {
            float bx = (float)(px + (beam == 0 ? 0 : (beam == 1 ? 0.10 : -0.10)));
            float bz = (float)(pz + (beam == 0 ? 0 : (beam == 1 ? 0.10 : -0.10)));
            float ba = (0.45f + pulse * 0.35f) * (beam == 0 ? 1f : 0.30f);
            drawThickLine(ps, lines,
                    new Vec3(bx, py + 0.3f, bz), new Vec3(bx, py + beamH, bz),
                    r, g, b, ba, beam == 0 ? 0.045f : 0.022f);
        }

        // ── Expanding rings ──
        for (int ring = 0; ring < 2; ring++) {
            float ringY = (float)(py + 0.15f + ring * 1.2f + pulse * 0.4f);
            float ringR = 0.25f + ring * 0.10f + pulse * 0.08f;
            float ringA = 0.40f - ring * 0.15f + pulse * 0.12f;
            renderRingXZ(ps, lines, px, ringY, pz, ringR, 10, r, g, b, ringA, 0.020f);
        }
    }

    // ---- 路径点序号（全息浮空数字，面朝镜头，透墙可见） ----
    private static void renderWaypointLabels(PoseStack ps, MultiBufferSource.BufferSource src,
                                              float time, Minecraft mc) {
        int n = pathPoints.size();
        for (int i = 0; i < n; i++) {
            BlockPos pos = pathPoints.get(i);
            float phase = (pos.getX() * 1.3f + pos.getZ() * 0.9f) * 0.5f;
            float pulse = (float) (Math.sin(time * 0.18 + phase) * 0.5 + 0.5);

            // 位置：标记框上方 + 光柱顶端
            double cx = pos.getX() + 0.5;
            double cy = pos.getY() + 1.62 + pulse * 1.15;
            double cz = pos.getZ() + 0.5;

            // 颜色：金→琥珀→青渐变
            float t = n > 1 ? (float) i / (float) (n - 1) : 0.5f;
            float hue = 0.13f - t * 0.08f;  // gold(0.13) → warm amber(0.05)
            float[] rgb = hsvToRgb(hue % 1f, 0.85f, 1.0f);
            int color = (255 << 24)
                    | ((int) (rgb[0] * 255) << 16)
                    | ((int) (rgb[1] * 255) << 8)
                    | (int) (rgb[2] * 255);

            String label = String.valueOf(i + 1);
            int textWidth = mc.font.width(label);

            ps.pushPose();
            ps.translate(cx, cy, cz);
            // 面朝镜头
            ps.mulPose(mc.gameRenderer.getMainCamera().rotation());
            float scale = 0.028f + pulse * 0.010f;
            ps.scale(-scale, -scale, scale);

            // 半透明暗底增强可读性
            int bgAlpha = (int) (40 + pulse * 60);
            int bgColor = (bgAlpha << 24) | 0x000000;

            mc.font.drawInBatch(
                    net.minecraft.network.chat.Component.literal(label),
                    -textWidth / 2f, 0f,
                    color, true,
                    ps.last().pose(),
                    src,
                    net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH,
                    bgColor,
                    0xF000F0  // fullbright
            );
            ps.popPose();
        }
    }

    // ---- 铺砖预览（脉冲透明度 + 逐方块着色） ----
    private static void renderTilePreview(PoseStack ps, VertexConsumer lines, float time) {
        float pulse = (float)(Math.sin(time * 0.12) * 0.35 + 0.65);
        int tileCount = 0;

        List<BezierUtil.BezierPoint> tiles = (pathMode == PathMode.LINEAR)
                ? BezierUtil.computeLinearTiles(pathPoints, templateLength)
                : BezierUtil.computeBezierTiles(pathPoints, templateLength);
        for (BezierUtil.BezierPoint s : tiles) {
            renderTileBlocks(ps, lines, s.pos(), s.direction(), pulse);
            tileCount++;
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
        int sh = mc.getWindow().getGuiScaledHeight();

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

        int lineH = 9;
        int totalH = lines.size() * lineH;
        // 放在准星右侧，垂直居中
        int x = sw / 2 + 12;
        int y = sh / 2 - totalH / 2;
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

    // ═══════════════════════════════════════════════════════
    //  新渲染系统 — 霓虹光效 / 全息填充 / 能量柱
    // ═══════════════════════════════════════════════════════

    /** Dense wireframe on all 6 faces — simulates a semi-transparent solid hologram. */
    private static void renderHologramFaces(PoseStack ps, VertexConsumer lines, AABB box,
                                             float r, float g, float b, float a, float spacing) {
        // Top face
        for (double x = box.minX; x <= box.maxX + 0.001; x += spacing)
            drawLine(ps, lines, new Vec3(x, box.maxY, box.minZ), new Vec3(x, box.maxY, box.maxZ), r, g, b, a);
        for (double z = box.minZ; z <= box.maxZ + 0.001; z += spacing)
            drawLine(ps, lines, new Vec3(box.minX, box.maxY, z), new Vec3(box.maxX, box.maxY, z), r, g, b, a);
        // Bottom face
        for (double x = box.minX; x <= box.maxX + 0.001; x += spacing)
            drawLine(ps, lines, new Vec3(x, box.minY, box.minZ), new Vec3(x, box.minY, box.maxZ), r, g, b, a);
        for (double z = box.minZ; z <= box.maxZ + 0.001; z += spacing)
            drawLine(ps, lines, new Vec3(box.minX, box.minY, z), new Vec3(box.maxX, box.minY, z), r, g, b, a);
        // Front face (Z = minZ)
        for (double x = box.minX; x <= box.maxX + 0.001; x += spacing)
            drawLine(ps, lines, new Vec3(x, box.minY, box.minZ), new Vec3(x, box.maxY, box.minZ), r, g, b, a);
        for (double y = box.minY; y <= box.maxY + 0.001; y += spacing)
            drawLine(ps, lines, new Vec3(box.minX, y, box.minZ), new Vec3(box.maxX, y, box.minZ), r, g, b, a);
        // Back face (Z = maxZ)
        for (double x = box.minX; x <= box.maxX + 0.001; x += spacing)
            drawLine(ps, lines, new Vec3(x, box.minY, box.maxZ), new Vec3(x, box.maxY, box.maxZ), r, g, b, a);
        for (double y = box.minY; y <= box.maxY + 0.001; y += spacing)
            drawLine(ps, lines, new Vec3(box.minX, y, box.maxZ), new Vec3(box.maxX, y, box.maxZ), r, g, b, a);
        // Left face (X = minX)
        for (double z = box.minZ; z <= box.maxZ + 0.001; z += spacing)
            drawLine(ps, lines, new Vec3(box.minX, box.minY, z), new Vec3(box.minX, box.maxY, z), r, g, b, a);
        for (double y = box.minY; y <= box.maxY + 0.001; y += spacing)
            drawLine(ps, lines, new Vec3(box.minX, y, box.minZ), new Vec3(box.minX, y, box.maxZ), r, g, b, a);
        // Right face (X = maxX)
        for (double z = box.minZ; z <= box.maxZ + 0.001; z += spacing)
            drawLine(ps, lines, new Vec3(box.maxX, box.minY, z), new Vec3(box.maxX, box.maxY, z), r, g, b, a);
        for (double y = box.minY; y <= box.maxY + 0.001; y += spacing)
            drawLine(ps, lines, new Vec3(box.maxX, y, box.minZ), new Vec3(box.maxX, y, box.maxZ), r, g, b, a);
    }

    /** Multi-pass glow edges — 4 layers of increasing width / decreasing alpha for bloom. */
    private static void renderNeonBox(PoseStack ps, VertexConsumer lines, AABB box,
                                       float r, float g, float b, float a) {
        float[][] layers = {
            {a * 0.85f, 0.040f},
            {a * 0.45f, 0.080f},
            {a * 0.22f, 0.150f},
            {a * 0.09f, 0.260f},
        };
        for (float[] layer : layers) {
            renderBoxEdges(ps, lines, box, r, g, b, layer[0], layer[1]);
        }
    }

    /** Draw all 12 edges of an AABB with thick lines. */
    private static void renderBoxEdges(PoseStack ps, VertexConsumer lines, AABB box,
                                        float r, float g, float b, float a, float thickness) {
        // Bottom face
        drawThickLine(ps, lines, new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.maxX, box.minY, box.minZ), r, g, b, a, thickness);
        drawThickLine(ps, lines, new Vec3(box.maxX, box.minY, box.minZ), new Vec3(box.maxX, box.minY, box.maxZ), r, g, b, a, thickness);
        drawThickLine(ps, lines, new Vec3(box.maxX, box.minY, box.maxZ), new Vec3(box.minX, box.minY, box.maxZ), r, g, b, a, thickness);
        drawThickLine(ps, lines, new Vec3(box.minX, box.minY, box.maxZ), new Vec3(box.minX, box.minY, box.minZ), r, g, b, a, thickness);
        // Top face
        drawThickLine(ps, lines, new Vec3(box.minX, box.maxY, box.minZ), new Vec3(box.maxX, box.maxY, box.minZ), r, g, b, a, thickness);
        drawThickLine(ps, lines, new Vec3(box.maxX, box.maxY, box.minZ), new Vec3(box.maxX, box.maxY, box.maxZ), r, g, b, a, thickness);
        drawThickLine(ps, lines, new Vec3(box.maxX, box.maxY, box.maxZ), new Vec3(box.minX, box.maxY, box.maxZ), r, g, b, a, thickness);
        drawThickLine(ps, lines, new Vec3(box.minX, box.maxY, box.maxZ), new Vec3(box.minX, box.maxY, box.minZ), r, g, b, a, thickness);
        // Verticals
        drawThickLine(ps, lines, new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.minX, box.maxY, box.minZ), r, g, b, a, thickness);
        drawThickLine(ps, lines, new Vec3(box.maxX, box.minY, box.minZ), new Vec3(box.maxX, box.maxY, box.minZ), r, g, b, a, thickness);
        drawThickLine(ps, lines, new Vec3(box.maxX, box.minY, box.maxZ), new Vec3(box.maxX, box.maxY, box.maxZ), r, g, b, a, thickness);
        drawThickLine(ps, lines, new Vec3(box.minX, box.minY, box.maxZ), new Vec3(box.minX, box.maxY, box.maxZ), r, g, b, a, thickness);
    }

    /** Corner energy pillars with mid-height connecting ring. */
    private static void renderEnergyPillars(PoseStack ps, VertexConsumer lines, AABB box,
                                              float r, float g, float b, float a, float time) {
        double[] xs = {box.minX, box.maxX};
        double[] zs = {box.minZ, box.maxZ};
        float pulse = (float)(Math.sin(time * 0.13) * 0.3 + 0.7);
        float beamA = a * pulse;
        for (double x : xs) {
            for (double z : zs) {
                drawThickLine(ps, lines,
                        new Vec3(x, box.minY, z), new Vec3(x, box.maxY, z),
                        Math.min(1f, r + 0.35f), Math.min(1f, g + 0.35f), Math.min(1f, b + 0.35f),
                        beamA, 0.055f);
            }
        }
        // Mid-height ring
        float ringA = a * pulse * 0.70f;
        double my = (box.minY + box.maxY) * 0.5;
        drawThickLine(ps, lines, new Vec3(xs[0], my, zs[0]), new Vec3(xs[1], my, zs[0]), r, g, b, ringA, 0.025f);
        drawThickLine(ps, lines, new Vec3(xs[1], my, zs[0]), new Vec3(xs[1], my, zs[1]), r, g, b, ringA, 0.025f);
        drawThickLine(ps, lines, new Vec3(xs[1], my, zs[1]), new Vec3(xs[0], my, zs[1]), r, g, b, ringA, 0.025f);
        drawThickLine(ps, lines, new Vec3(xs[0], my, zs[1]), new Vec3(xs[0], my, zs[0]), r, g, b, ringA, 0.025f);
    }

    /** Racing-light dashed border — faster, brighter dashes flowing around the box. */
    private static void renderRacingBorder(PoseStack ps, VertexConsumer lines, AABB box,
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
        int segments = 8;
        float dashRatio = 0.40f;
        for (int ei = 0; ei < edges.length; ei++) {
            double[] e = edges[ei];
            double dx = e[3] - e[0], dy = e[4] - e[1], dz = e[5] - e[2];
            for (int s = 0; s < segments; s++) {
                float phase = (time * speed + ei * 0.15f + (float) s / segments) % 1.0f;
                if (phase > dashRatio) continue;
                double t0 = (double) s / segments;
                double t1 = Math.min((double) (s + 1) / segments, t0 + dashRatio / segments);
                Vec3 from = new Vec3(e[0] + dx * t0, e[1] + dy * t0, e[2] + dz * t0);
                Vec3 to   = new Vec3(e[0] + dx * t1, e[1] + dy * t1, e[2] + dz * t1);
                // Thicker dash segments for visibility
                drawThickLine(ps, lines, from, to, r, g, b, a, 0.030f);
            }
        }
    }

    /** 4 corners of a horizontal ring at the given Y. */
    private static Vec3[] horizontalRingAt(AABB box, double y) {
        return new Vec3[] {
            new Vec3(box.minX, y, box.minZ),
            new Vec3(box.maxX, y, box.minZ),
            new Vec3(box.maxX, y, box.maxZ),
            new Vec3(box.minX, y, box.maxZ),
        };
    }

    /** Draw a circle in the XZ plane at (cx, y, cz) with radius r and numSegments. */
    private static void renderRingXZ(PoseStack ps, VertexConsumer lines,
                                      double cx, double y, double cz, double r, int segs,
                                      float red, float green, float blue, float alpha, float thickness) {
        for (int i = 0; i < segs; i++) {
            double a0 = i * 2.0 * Math.PI / segs;
            double a1 = (i + 1) * 2.0 * Math.PI / segs;
            Vec3 from = new Vec3(cx + Math.cos(a0) * r, y, cz + Math.sin(a0) * r);
            Vec3 to   = new Vec3(cx + Math.cos(a1) * r, y, cz + Math.sin(a1) * r);
            drawThickLine(ps, lines, from, to, red, green, blue, alpha, thickness);
        }
    }

}

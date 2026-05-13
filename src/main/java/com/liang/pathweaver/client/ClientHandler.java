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
            }
            // 框外方块：不拦截，正常破坏
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

    // ---- 粒子标记（每20tick） ----
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (!mc.player.getMainHandItem().is(ModItems.PATH_WEAVER_TOOL.get())) return;
        if (mc.level.getGameTime() % 20 != 0) return;
        Level level = mc.level;
        spawnDot(level, pendingCorner);
        for (BlockPos[] r : regions) {
            spawnDot(level, r[0]);
            spawnDot(level, r[1]);
        }
        for (BlockPos pt : pathPoints) spawnDot(level, pt);
    }

    private static void spawnDot(Level level, BlockPos pos) {
        if (pos == null) return;
        level.addParticle(net.minecraft.core.particles.ParticleTypes.END_ROD,
                pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 0, 0.03, 0);
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

        // 1. 已完成的框选区域（各自独立颜色 + 三层发光 + 角点括号 + 扫描线）
        float regPulse = (float)(Math.sin(time * 0.12) * 0.4 + 0.6); // 0.2→1.0
        for (int i = 0; i < regions.size(); i++) {
            float[] c = REGION_COLORS[i % REGION_COLORS.length];
            BlockPos[] r = regions.get(i);
            int minX = Math.min(r[0].getX(), r[1].getX()), maxX = Math.max(r[0].getX(), r[1].getX());
            int minY = Math.min(r[0].getY(), r[1].getY()), maxY = Math.max(r[0].getY(), r[1].getY());
            int minZ = Math.min(r[0].getZ(), r[1].getZ()), maxZ = Math.max(r[0].getZ(), r[1].getZ());
            AABB box = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);

            // 三层发光效果
            renderBox(ps, lines, box,                   c[0], c[1], c[2], regPulse);
            renderBox(ps, lines, box.inflate(0.025),    c[0], c[1], c[2], regPulse * 0.45f);
            renderBox(ps, lines, box.inflate(0.065),    c[0], c[1], c[2], regPulse * 0.20f);

            // 8角括号标记（长度随脉冲微动）
            float bLen = (float)Math.max(0.18, Math.min(0.5,
                    Math.min(maxX - minX + 1, Math.min(maxY - minY + 1, maxZ - minZ + 1)) * 0.22));
            bLen += (float)(Math.sin(time * 0.2 + i * 1.1f) * 0.04);
            renderCornerBrackets(ps, lines, box, c[0], c[1], c[2], 1f, bLen);

            // 横向扫描线（每框独立相位）
            float scanT = (float)(Math.sin(time * 0.07 + i * 1.4f) * 0.5 + 0.5);
            double scanY = box.minY + (box.maxY - box.minY) * scanT;
            float sa = 0.30f + regPulse * 0.25f;
            drawLine(ps, lines, new Vec3(box.minX, scanY, box.minZ), new Vec3(box.maxX, scanY, box.minZ), c[0], c[1], c[2], sa);
            drawLine(ps, lines, new Vec3(box.maxX, scanY, box.minZ), new Vec3(box.maxX, scanY, box.maxZ), c[0], c[1], c[2], sa);
            drawLine(ps, lines, new Vec3(box.maxX, scanY, box.maxZ), new Vec3(box.minX, scanY, box.maxZ), c[0], c[1], c[2], sa);
            drawLine(ps, lines, new Vec3(box.minX, scanY, box.maxZ), new Vec3(box.minX, scanY, box.minZ), c[0], c[1], c[2], sa);

            // 角点十字标记
            renderCrossMarker(ps, lines, r[0], time, c[0], c[1], c[2]);
            renderCrossMarker(ps, lines, r[1], time, c[0], c[1], c[2]);
        }

        // 2. 待定角点标记（金色十字 + 外层扩散大十字）
        if (pendingCorner != null) {
            float gp = (float)(Math.sin(time * 0.22) * 0.5 + 0.5);
            renderCrossMarker(ps, lines, pendingCorner, time, 1f, 0.90f, 0f);
            float sz = 0.50f + gp * 0.12f;
            double px = pendingCorner.getX() + 0.5, py = pendingCorner.getY() + 0.5, pz = pendingCorner.getZ() + 0.5;
            drawLine(ps, lines, new Vec3(px - sz, py, pz), new Vec3(px + sz, py, pz), 1f, 1f, 0.35f, 0.25f + gp * 0.35f);
            drawLine(ps, lines, new Vec3(px, py - sz, pz), new Vec3(px, py + sz, pz), 1f, 1f, 0.35f, 0.25f + gp * 0.35f);
            drawLine(ps, lines, new Vec3(px, py, pz - sz), new Vec3(px, py, pz + sz), 1f, 1f, 0.35f, 0.25f + gp * 0.35f);

            // 3. 动态预览框：待定角点 → 准星所指方块（白色半透明 + 括号）
            if (mc.hitResult instanceof BlockHitResult bhr) {
                BlockPos target = bhr.getBlockPos();
                int mnX = Math.min(pendingCorner.getX(), target.getX());
                int mnY = Math.min(pendingCorner.getY(), target.getY());
                int mnZ = Math.min(pendingCorner.getZ(), target.getZ());
                int mxX = Math.max(pendingCorner.getX(), target.getX());
                int mxY = Math.max(pendingCorner.getY(), target.getY());
                int mxZ = Math.max(pendingCorner.getZ(), target.getZ());
                float pa = (float)(Math.sin(time * 0.25) * 0.15 + 0.30);
                AABB prev = new AABB(mnX, mnY, mnZ, mxX + 1, mxY + 1, mxZ + 1);
                renderBox(ps, lines, prev, 1f, 1f, 1f, pa);
                renderCornerBrackets(ps, lines, prev, 1f, 1f, 1f, pa * 2f, 0.25f);
            }
        }

        // 4. 路径点连线（渐变：绿→黄→橙）
        if (pathPoints.size() >= 2) {
            for (int i = 0; i + 1 < pathPoints.size(); i++) {
                float frac  = (float) i / (pathPoints.size() - 1);
                float frac1 = (float)(i + 1) / (pathPoints.size() - 1);
                Vec3 a = blockCenter(pathPoints.get(i));
                Vec3 b = blockCenter(pathPoints.get(i + 1));
                drawLineGradient(ps, lines, a, b,
                        gradR(frac), gradG(frac), gradB(frac),
                        gradR(frac1), gradG(frac1), gradB(frac1));
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

    // ---- 角点十字标记（三轴交叉线，居于方块中心） ----
    private static void renderCrossMarker(PoseStack ps, VertexConsumer lines, BlockPos pos,
                                          float time, float r, float g, float b) {
        float pulse = (float)(Math.sin(time * 0.18) * 0.1 + 0.9);
        float size = 0.33f * pulse;
        double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
        drawLine(ps, lines, new Vec3(cx - size, cy, cz), new Vec3(cx + size, cy, cz), r, g, b, 1f);
        drawLine(ps, lines, new Vec3(cx, cy - size, cz), new Vec3(cx, cy + size, cz), r, g, b, 1f);
        drawLine(ps, lines, new Vec3(cx, cy, cz - size), new Vec3(cx, cy, cz + size), r, g, b, 1f);
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
        drawLine(ps, lines,
                new Vec3(cx, topY, cz),
                new Vec3(cx, topY + beamH, cz),
                0.2f, 0.85f, 1f, beamAlpha);
    }

    // ---- 铺砖预览（脉冲透明度 + 逐方块着色） ----
    private static void renderTilePreview(PoseStack ps, VertexConsumer lines, float time) {
        float pulse = (float)(Math.sin(time * 0.12) * 0.35 + 0.65);
        int tileCount = 0;

        if (pathMode == PathMode.LINEAR) {
            for (int i = 0; i + 1 < pathPoints.size(); i++) {
                BlockPos from = pathPoints.get(i), to = pathPoints.get(i + 1);
                Direction dir = dominantDir(from, to);
                double dx = to.getX() - from.getX();
                double dz = to.getZ() - from.getZ();
                double segLen = Math.sqrt(dx * dx + dz * dz);
                if (segLen < 0.001) {
                    renderTileBlocks(ps, lines, from, dir, pulse);
                    tileCount++;
                    continue;
                }
                renderTileBlocks(ps, lines, from, dir, pulse);
                tileCount++;
                for (double dist = templateLength; dist < segLen; dist += templateLength) {
                    double frac = dist / segLen;
                    int x = (int) Math.round(from.getX() + dx * frac);
                    int y = (int) Math.round(from.getY() + (to.getY() - from.getY()) * frac);
                    int z = (int) Math.round(from.getZ() + dz * frac);
                    renderTileBlocks(ps, lines, new BlockPos(x, y, z), dir, pulse);
                    tileCount++;
                }
            }
        } else {
            for (int i = 0; i + 2 < pathPoints.size(); i += 2) {
                List<BezierUtil.BezierPoint> samples = BezierUtil.sampleCurve(
                        pathPoints.get(i), pathPoints.get(i + 1), pathPoints.get(i + 2),
                        templateLength);
                for (BezierUtil.BezierPoint s : samples) {
                    renderTileBlocks(ps, lines, s.pos(), s.direction(), pulse);
                    tileCount++;
                }
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

        // Faint bounding box (shows overall shape even when all-air or data not yet received)
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
        renderBox(ps, lines, bbox.inflate(0.02), 0.5f, 0.5f, 0.5f, pulse * 0.18f);

        // Per-block colored boxes (mirrors TemplateData.localToWorld)
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
            renderBox(ps, lines, blockBox.inflate(0.02), c[0], c[1], c[2], pulse * 0.88f);
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

    /** 在 AABB 的 8 个角各画 3 条向内延伸的短线（L 型括号标记）。 */
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
                    drawLine(ps, lines, new Vec3(cx, cy, cz), new Vec3(cx + xd[xi], cy, cz), r, g, b, a);
                    drawLine(ps, lines, new Vec3(cx, cy, cz), new Vec3(cx, cy + yd[yi], cz), r, g, b, a);
                    drawLine(ps, lines, new Vec3(cx, cy, cz), new Vec3(cx, cy, cz + zd[zi]), r, g, b, a);
                }
            }
        }
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

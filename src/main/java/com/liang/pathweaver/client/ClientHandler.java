package com.liang.pathweaver.client;

import com.liang.pathweaver.logic.BezierUtil;
import com.liang.pathweaver.network.C2SGeneratePathPacket;
import com.liang.pathweaver.network.C2SMarkPathPointPacket;
import com.liang.pathweaver.network.C2SSwitchModePacket;
import com.liang.pathweaver.network.ModMessages;
import com.liang.pathweaver.network.S2CUpdateStatePacket;
import com.liang.pathweaver.data.PathMode;
import com.liang.pathweaver.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class ClientHandler {

    // ---- 服务端同步的状态 ----
    private static BlockPos corner1 = null;
    private static BlockPos corner2 = null;
    private static boolean hasTemplate = false;
    private static int templateLength = 1, templateWidth = 1, templateHeight = 1;
    private static Direction templateBaseDir = Direction.NORTH;
    private static final List<BlockPos> pathPoints = new ArrayList<>();
    private static PathMode pathMode = PathMode.LINEAR;

    public static void updateState(S2CUpdateStatePacket pkt) {
        corner1 = pkt.corner1;
        corner2 = pkt.corner2;
        hasTemplate = pkt.hasTemplate;
        templateLength = pkt.templateLength;
        templateWidth = pkt.templateWidth;
        templateHeight = pkt.templateHeight;
        templateBaseDir = pkt.templateBaseDir;
        pathPoints.clear();
        pathPoints.addAll(pkt.pathPoints);
        pathMode = pkt.pathMode;
    }

    // ---- 退出游戏时清空客户端状态 ----
    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        corner1 = null;
        corner2 = null;
        hasTemplate = false;
        templateLength = 1;
        templateWidth = 1;
        templateHeight = 1;
        templateBaseDir = Direction.NORTH;
        pathPoints.clear();
        pathMode = PathMode.LINEAR;
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
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getEntity().getMainHandItem().is(ModItems.PATH_WEAVER_TOOL.get())) return;
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
        spawnDot(level, corner1);
        spawnDot(level, corner2);
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

        // 1. 角点双环脉冲（金色）
        renderCornerMarker(ps, lines, corner1, time);
        renderCornerMarker(ps, lines, corner2, time);

        // 2. 路径点连线（渐变：绿→黄→橙）
        if (pathPoints.size() >= 2) {
            for (int i = 0; i + 1 < pathPoints.size(); i++) {
                float frac = (float) i / (pathPoints.size() - 1);
                float frac1 = (float) (i + 1) / (pathPoints.size() - 1);
                Vec3 a = blockCenter(pathPoints.get(i));
                Vec3 b = blockCenter(pathPoints.get(i + 1));
                // 绿(0,1,0) → 黄(1,1,0) → 橙(1,0.5,0)
                drawLineGradient(ps, lines, a, b,
                        gradR(frac), gradG(frac), gradB(frac),
                        gradR(frac1), gradG(frac1), gradB(frac1));
            }
        }

        // 3. 路径点标记（青色脉冲 + 信标光柱）
        for (int i = 0; i < pathPoints.size(); i++) {
            renderPathPointMarker(ps, lines, pathPoints.get(i), time, i);
        }

        // 4. 铺砖预览（脉冲绿色）
        if (hasTemplate && !pathPoints.isEmpty()) {
            renderTilePreview(ps, lines, time);
        }

        ps.popPose();
        src.endBatch(RenderType.lines());
    }

    // ---- 渐变颜色计算（绿→黄→橙） ----
    private static float gradR(float t) {
        return t < 0.5f ? t * 2f : 1f;
    }

    private static float gradG(float t) {
        return t < 0.5f ? 1f : 1f - (t - 0.5f);
    }

    private static float gradB(float t) {
        return 0f;
    }

    // ---- 角点双环 ----
    private static void renderCornerMarker(PoseStack ps, VertexConsumer lines, BlockPos pos, float time) {
        if (pos == null) return;
        float pulse = (float) (Math.sin(time * 0.15) * 0.5 + 0.5); // 0→1 脉冲

        // 内框：金黄色，固定大小
        float goldG = 0.7f + pulse * 0.3f;
        AABB inner = new AABB(
                pos.getX() + 0.28, pos.getY() + 0.93, pos.getZ() + 0.28,
                pos.getX() + 0.72, pos.getY() + 1.37, pos.getZ() + 0.72);
        renderBox(ps, lines, inner, 1f, goldG, 0f, 1f);

        // 外框：淡金，脉冲放大 + 渐隐
        double expand = 0.05 + pulse * 0.07;
        renderBox(ps, lines, inner.inflate(expand), 1f, 0.95f, 0.5f, 0.25f + pulse * 0.65f);
    }

    // ---- 路径点标记（脉冲方框 + 光柱） ----
    private static void renderPathPointMarker(PoseStack ps, VertexConsumer lines,
                                               BlockPos pos, float time, int index) {
        // 每个点用坐标做相位偏移，形成错落闪烁
        float phase = (pos.getX() * 1.3f + pos.getZ() * 0.9f) * 0.5f;
        float pulse = (float) (Math.sin(time * 0.18 + phase) * 0.5 + 0.5);

        float half = 0.12f + pulse * 0.07f;
        double cx = pos.getX() + 0.5, cz = pos.getZ() + 0.5;
        double baseY = pos.getY() + 1.0;

        // 青色脉冲方框
        AABB box = new AABB(cx - half, baseY, cz - half,
                cx + half, baseY + half * 2, cz + half);
        renderBox(ps, lines, box, 0f, 0.75f + pulse * 0.25f, 1f, 0.55f + pulse * 0.45f);

        // 向上的信标光柱（高度脉冲）
        float beamH = 0.15f + pulse * 1.0f;
        float beamAlpha = 0.3f + pulse * 0.6f;
        float topY = (float) (baseY + half * 2);
        drawLine(ps, lines,
                new Vec3(cx, topY, cz),
                new Vec3(cx, topY + beamH, cz),
                0.2f, 0.85f, 1f, beamAlpha);
    }

    // ---- 铺砖预览（脉冲透明度） ----
    private static void renderTilePreview(PoseStack ps, VertexConsumer lines, float time) {
        float pulse = (float) (Math.sin(time * 0.12) * 0.35 + 0.65); // 0.3→1.0

        if (pathMode == PathMode.LINEAR) {
            for (int i = 0; i + 1 < pathPoints.size(); i++) {
                BlockPos from = pathPoints.get(i), to = pathPoints.get(i + 1);
                Direction dir = dominantDir(from, to);
                double dx = to.getX() - from.getX();
                double dz = to.getZ() - from.getZ();
                double segLen = Math.sqrt(dx * dx + dz * dz);
                if (segLen < 0.001) {
                    renderTileBox(ps, lines, from, dir, pulse);
                    continue;
                }
                for (double dist = 0; dist <= segLen + 0.001; dist += templateLength) {
                    double frac = Math.min(dist / segLen, 1.0);
                    int x = (int) Math.round(from.getX() + dx * frac);
                    int y = (int) Math.round(from.getY() + (to.getY() - from.getY()) * frac);
                    int z = (int) Math.round(from.getZ() + dz * frac);
                    renderTileBox(ps, lines, new BlockPos(x, y, z), dir, pulse);
                }
            }
        } else {
            for (int i = 0; i + 2 < pathPoints.size(); i += 2) {
                List<BezierUtil.BezierPoint> samples = BezierUtil.sampleCurve(
                        pathPoints.get(i), pathPoints.get(i + 1), pathPoints.get(i + 2),
                        templateLength);
                for (BezierUtil.BezierPoint s : samples) {
                    renderTileBox(ps, lines, s.pos(), s.direction(), pulse);
                }
            }
        }
    }

    private static void renderTileBox(PoseStack ps, VertexConsumer lines,
                                       BlockPos origin, Direction pathDir, float pulse) {
        int half = templateWidth / 2;
        origin = switch (pathDir) {
            case EAST  -> origin.offset(0, 0, -half);
            case WEST  -> origin.offset(0, 0, half);
            case NORTH -> origin.offset(-half, 0, 0);
            case SOUTH -> origin.offset(half, 0, 0);
            default    -> origin;
        };
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        AABB box = switch (pathDir) {
            case SOUTH -> new AABB(ox - templateWidth + 1, oy, oz,
                    ox + 1, oy + templateHeight, oz + templateLength);
            case EAST  -> new AABB(ox, oy, oz,
                    ox + templateLength, oy + templateHeight, oz + templateWidth);
            case WEST  -> new AABB(ox - templateLength + 1, oy, oz - templateWidth + 1,
                    ox + 1, oy + templateHeight, oz + 1);
            default    -> new AABB(ox, oy, oz - templateLength + 1,
                    ox + templateWidth, oy + templateHeight, oz + 1);
        };
        // 绿色脉冲，颜色随 pulse 偏向青色
        float g = 0.85f + pulse * 0.15f;
        float b = pulse * 0.35f;
        renderBox(ps, lines, box.inflate(0.02), 0.05f, g, b, pulse * 0.9f);
    }

    // ---- 低级渲染工具 ----

    private static void renderBox(PoseStack ps, VertexConsumer lines, AABB box,
                                   float r, float g, float b, float a) {
        net.minecraft.client.renderer.LevelRenderer.renderLineBox(ps, lines, box, r, g, b, a);
    }

    /** 单色线段 */
    private static void drawLine(PoseStack ps, VertexConsumer lines,
                                  Vec3 from, Vec3 to, float r, float g, float b, float a) {
        Matrix4f mat = ps.last().pose();
        Matrix3f norm = ps.last().normal();
        Vector3f d = new Vector3f(
                (float) (to.x - from.x),
                (float) (to.y - from.y),
                (float) (to.z - from.z)).normalize();
        lines.vertex(mat, (float) from.x, (float) from.y, (float) from.z)
                .color(r, g, b, a).normal(norm, d.x, d.y, d.z).endVertex();
        lines.vertex(mat, (float) to.x, (float) to.y, (float) to.z)
                .color(r, g, b, a).normal(norm, d.x, d.y, d.z).endVertex();
    }

    /** 渐变线段（两端不同颜色） */
    private static void drawLineGradient(PoseStack ps, VertexConsumer lines,
                                          Vec3 from, Vec3 to,
                                          float r0, float g0, float b0,
                                          float r1, float g1, float b1) {
        Matrix4f mat = ps.last().pose();
        Matrix3f norm = ps.last().normal();
        Vector3f d = new Vector3f(
                (float) (to.x - from.x),
                (float) (to.y - from.y),
                (float) (to.z - from.z)).normalize();
        lines.vertex(mat, (float) from.x, (float) from.y, (float) from.z)
                .color(r0, g0, b0, 1f).normal(norm, d.x, d.y, d.z).endVertex();
        lines.vertex(mat, (float) to.x, (float) to.y, (float) to.z)
                .color(r1, g1, b1, 1f).normal(norm, d.x, d.y, d.z).endVertex();
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

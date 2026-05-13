package com.liang.pathweaver.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class BezierUtil {
    private static final int FINE_SAMPLES = 400;

    /**
     * 沿二阶贝塞尔曲线弧长等距采样放置点。
     *
     * 位置直接取自曲线（保证视觉上是曲线形状），
     * 方向取切线最近的四基本方向（用于模板旋转）。
     */
    public static List<BezierPoint> sampleCurve(BlockPos p0, BlockPos p1, BlockPos p2, int step) {
        Vec3 v0 = toVec(p0), v1 = toVec(p1), v2 = toVec(p2);

        // 建立弧长↔t 对应表
        double[] tTable = new double[FINE_SAMPLES + 1];
        double[] arcTable = new double[FINE_SAMPLES + 1];
        arcTable[0] = 0;
        tTable[0] = 0;
        Vec3 prev = v0;
        for (int i = 1; i <= FINE_SAMPLES; i++) {
            double t = (double) i / FINE_SAMPLES;
            Vec3 cur = bezier(v0, v1, v2, t);
            arcTable[i] = arcTable[i - 1] + prev.distanceTo(cur);
            tTable[i] = t;
            prev = cur;
        }
        double totalLen = arcTable[FINE_SAMPLES];

        // 弧长等距采样，保证曲线形状平滑
        List<BezierPoint> raw = new ArrayList<>();
        double target = 0;
        while (target <= totalLen - step * 0.5) {
            double t = arcLengthToT(arcTable, tTable, target);
            Vec3 pos = bezier(v0, v1, v2, t);
            Vec3 tangent = bezierTangent(v0, v1, v2, t);
            Direction dir = tangent.lengthSqr() > 1e-10
                    ? cardinalFromVec(tangent.normalize())
                    : (raw.isEmpty() ? Direction.NORTH : raw.get(raw.size() - 1).direction());
            int y = (int) Math.round(pos.y); // 贝塞尔 Y 由三个控制点共同决定
            raw.add(new BezierPoint(
                    new BlockPos((int) Math.round(pos.x), y, (int) Math.round(pos.z)), dir));
            target += step;
        }

        // 后处理：仅在左转弯（逆时针）时插入拐角补丁砖
        // 右转弯时相邻瓦片已自然重叠，不需要补丁；强行插入反而会在外侧多出一块突起
        // 判断左/右转：XZ 平面叉积 < 0 为左转，> 0 为右转
        return getBezierPoints(raw);
    }

    private static @NotNull List<BezierPoint> getBezierPoints(List<BezierPoint> raw) {
        List<BezierPoint> result = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            result.add(raw.get(i));
            if (i + 1 < raw.size() && raw.get(i).direction() != raw.get(i + 1).direction()) {
                Direction aDir = raw.get(i).direction();
                Direction bDir = raw.get(i + 1).direction();
                int cross = aDir.getStepX() * bDir.getStepZ() - aDir.getStepZ() * bDir.getStepX();
                if (cross < 0) { // 左转，内凹处有真实空隙，需要补丁砖
                    BlockPos a = raw.get(i).pos(), b = raw.get(i + 1).pos();
                    BlockPos corner = (aDir == Direction.EAST || aDir == Direction.WEST)
                            ? new BlockPos(b.getX(), b.getY(), a.getZ())
                            : new BlockPos(a.getX(), b.getY(), b.getZ());
                    if (!corner.equals(b)) {
                        result.add(new BezierPoint(corner, bDir));
                    }
                }
            }
        }
        return result;
    }

    private static double arcLengthToT(double[] arcTable, double[] tTable, double target) {
        if (target <= 0) return 0;
        if (target >= arcTable[arcTable.length - 1]) return 1;
        int lo = 0, hi = arcTable.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) / 2;
            if (arcTable[mid] <= target) lo = mid;
            else hi = mid;
        }
        double frac = (target - arcTable[lo]) / (arcTable[hi] - arcTable[lo]);
        return tTable[lo] + frac * (tTable[hi] - tTable[lo]);
    }

    private static Vec3 bezier(Vec3 p0, Vec3 p1, Vec3 p2, double t) {
        double mt = 1 - t;
        return new Vec3(
                mt * mt * p0.x + 2 * mt * t * p1.x + t * t * p2.x,
                mt * mt * p0.y + 2 * mt * t * p1.y + t * t * p2.y,
                mt * mt * p0.z + 2 * mt * t * p1.z + t * t * p2.z);
    }

    private static Vec3 bezierTangent(Vec3 p0, Vec3 p1, Vec3 p2, double t) {
        double mt = 1 - t;
        return new Vec3(
                2 * mt * (p1.x - p0.x) + 2 * t * (p2.x - p1.x),
                2 * mt * (p1.y - p0.y) + 2 * t * (p2.y - p1.y),
                2 * mt * (p1.z - p0.z) + 2 * t * (p2.z - p1.z));
    }

    public static Direction cardinalFromVec(Vec3 v) {
        if (Math.abs(v.x) >= Math.abs(v.z)) {
            return v.x >= 0 ? Direction.EAST : Direction.WEST;
        } else {
            return v.z >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    private static Vec3 toVec(BlockPos p) {
        return new Vec3(p.getX(), p.getY(), p.getZ());
    }

    public record BezierPoint(BlockPos pos, Direction direction) {}
}

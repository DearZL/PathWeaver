package com.liang.pathweaver.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class BezierUtil {
    private static final int FINE_SAMPLES = 400;

    // ---- shared direction helper ----

    public static Direction dominantDirection(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    // ---- public tile computation (used by server generator, client preview, and material counting) ----

    /**
     * Computes the deduplicated tile list for LINEAR mode.
     * Every segment tiles forward from {@code from} at {@code step} intervals.
     * If the final intermediate does not reach the segment endpoint a closing
     * tile is placed at {@code to} (natural forward tiling — "正向顺延").
     * Collinear segments have the closing tile deduplicated with the next
     * segment's start, so no extra column appears mid-path.
     */
    public static List<BezierPoint> computeLinearTiles(List<BlockPos> points, int step) {
        List<BezierPoint> tiles = new ArrayList<>();
        for (int i = 0; i + 1 < points.size(); i++) {
            BlockPos from = points.get(i);
            BlockPos to = points.get(i + 1);
            Direction dir = dominantDirection(from, to);
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double segLen = Math.sqrt(dx * dx + dz * dz);

            if (segLen < 0.001) {
                tiles.add(new BezierPoint(from, dir));
                continue;
            }

            tiles.add(new BezierPoint(from, dir));
            for (double dist = step; dist < segLen; dist += step) {
                double frac = dist / segLen;
                int x = (int) Math.round(from.getX() + dx * frac);
                int y = (int) Math.round(from.getY() + (to.getY() - from.getY()) * frac);
                int z = (int) Math.round(from.getZ() + dz * frac);
                tiles.add(new BezierPoint(new BlockPos(x, y, z), dir));
            }

            // Forward tiling: if the last intermediate doesn't reach "to",
            // place the closing tile at the natural forward position.
            int proj = distInDir(from, to, dir);
            int lastProj = distInDir(from,
                    tiles.get(tiles.size() - 1).pos(), dir);
            if (lastProj + step - 1 < proj) {
                tiles.add(new BezierPoint(to, dir));
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(tiles));
    }

    /** Projection of the vector {@code to - from} onto {@code dir}'s axis. */
    private static int distInDir(BlockPos from, BlockPos to, Direction dir) {
        return switch (dir) {
            case EAST  -> to.getX() - from.getX();
            case WEST  -> from.getX() - to.getX();
            case SOUTH -> to.getZ() - from.getZ();
            case NORTH -> from.getZ() - to.getZ();
            default    -> 0;
        };
    }

    /**
     * Computes the deduplicated tile list for BEZIER mode (with corner-gap patches).
     */
    public static List<BezierPoint> computeBezierTiles(List<BlockPos> points, int step) {
        List<BezierPoint> samples = sampleMultiSegmentCurve(points, step);
        List<BezierPoint> filled = getBezierPoints(samples);
        return new ArrayList<>(new LinkedHashSet<>(filled));
    }

    // ---- bezier curve sampling ----

    /**
     * 沿二阶贝塞尔曲线弧长等距采样放置点（单段便捷方法）。
     */
    public static List<BezierPoint> sampleCurve(BlockPos p0, BlockPos p1, BlockPos p2, int step) {
        return computeBezierTiles(List.of(p0, p1, p2), step);
    }

    /**
     * 多段二阶贝塞尔曲线（相邻段共享端点）整体弧长等距采样。
     * 跨段连续采样可避免每段独立采样在段间端点处放置间距 < step 的重复瓦片。
     */
    public static List<BezierPoint> sampleMultiSegmentCurve(List<BlockPos> points, int step) {
        int segments = (points.size() - 1) / 2;
        if (segments <= 0) return new ArrayList<>();

        Vec3[][] segVecs = new Vec3[segments][];
        double[][] segArc = new double[segments][];
        double[][] segT = new double[segments][];
        double[] cumLen = new double[segments + 1];

        for (int si = 0; si < segments; si++) {
            Vec3 v0 = toVec(points.get(si * 2));
            Vec3 v1 = toVec(points.get(si * 2 + 1));
            Vec3 v2 = toVec(points.get(si * 2 + 2));
            segVecs[si] = new Vec3[]{v0, v1, v2};
            segArc[si] = new double[FINE_SAMPLES + 1];
            segT[si] = new double[FINE_SAMPLES + 1];
            Vec3 prev = v0;
            for (int i = 1; i <= FINE_SAMPLES; i++) {
                double t = (double) i / FINE_SAMPLES;
                Vec3 cur = bezier(v0, v1, v2, t);
                segArc[si][i] = segArc[si][i - 1] + prev.distanceTo(cur);
                segT[si][i] = t;
                prev = cur;
            }
            cumLen[si + 1] = cumLen[si] + segArc[si][FINE_SAMPLES];
        }

        double totalLen = cumLen[segments];
        List<BezierPoint> raw = new ArrayList<>();
        double target = 0;
        while (target <= totalLen + 0.001) {
            // clamp target to valid range for the final sample
            double clampedTarget = Math.min(target, totalLen);
            int si = 0;
            while (si < segments - 1 && cumLen[si + 1] <= clampedTarget) si++;
            double localTarget = clampedTarget - cumLen[si];
            double t = arcLengthToT(segArc[si], segT[si], localTarget);
            Vec3[] v = segVecs[si];
            Vec3 pos = bezier(v[0], v[1], v[2], t);
            Vec3 tangent = bezierTangent(v[0], v[1], v[2], t);
            Direction dir = tangent.lengthSqr() > 1e-10
                    ? cardinalFromVec(tangent.normalize())
                    : (raw.isEmpty() ? Direction.NORTH : raw.get(raw.size() - 1).direction());
            int y = (int) Math.round(pos.y);
            raw.add(new BezierPoint(
                    new BlockPos((int) Math.round(pos.x), y, (int) Math.round(pos.z)), dir));
            if (target >= totalLen) break;
            target += step;
        }

        return raw;
    }

    // ---- corner gap filling for bezier curves ----

    /**
     * 后处理：转弯时插入拐角补丁砖填补空隙。
     * 左转（cross<0）：内角有空隙 → 在内角补砖
     * 右转（cross>0）：外角有空隙 → 在外角补砖
     */
    public static @NotNull List<BezierPoint> getBezierPoints(List<BezierPoint> raw) {
        List<BezierPoint> result = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            result.add(raw.get(i));
            if (i + 1 < raw.size() && raw.get(i).direction() != raw.get(i + 1).direction()) {
                Direction aDir = raw.get(i).direction();
                Direction bDir = raw.get(i + 1).direction();
                int cross = aDir.getStepX() * bDir.getStepZ() - aDir.getStepZ() * bDir.getStepX();
                BlockPos a = raw.get(i).pos(), b = raw.get(i + 1).pos();
                if (cross < 0) { // 左转，内角空隙
                    BlockPos corner = (aDir == Direction.EAST || aDir == Direction.WEST)
                            ? new BlockPos(b.getX(), b.getY(), a.getZ())
                            : new BlockPos(a.getX(), b.getY(), b.getZ());
                    if (!corner.equals(b)) {
                        result.add(new BezierPoint(corner, bDir));
                    }
                } else if (cross > 0) { // 右转，外角空隙
                    BlockPos corner = (aDir == Direction.EAST || aDir == Direction.WEST)
                            ? new BlockPos(a.getX(), b.getY(), b.getZ())
                            : new BlockPos(b.getX(), b.getY(), a.getZ());
                    if (!corner.equals(a) && !corner.equals(b)) {
                        result.add(new BezierPoint(corner, aDir));
                    }
                }
            }
        }
        return result;
    }

    // ---- arc-length parameterisation helpers ----

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

    // ---- quadratic bezier math ----

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

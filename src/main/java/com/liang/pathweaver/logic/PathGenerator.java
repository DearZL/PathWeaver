package com.liang.pathweaver.logic;

import com.liang.pathweaver.data.PathMode;
import com.liang.pathweaver.data.PlayerPathData;
import com.liang.pathweaver.data.TemplateData;
import com.liang.pathweaver.undo.UndoEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class PathGenerator {

    public static List<UndoEntry> generate(ServerLevel level, PlayerPathData data) {
        List<UndoEntry> undoEntries = new ArrayList<>();
        TemplateData template = data.template;
        List<BlockPos> points = data.pathPoints;

        if (data.pathMode == PathMode.LINEAR) {
            generateLinear(level, template, points, undoEntries);
        } else {
            generateBezier(level, template, points, undoEntries);
        }
        return undoEntries;
    }

    private static void generateLinear(ServerLevel level, TemplateData template,
                                        List<BlockPos> points, List<UndoEntry> undo) {
        List<BezierUtil.BezierPoint> tiles = new ArrayList<>();
        for (int i = 0; i + 1 < points.size(); i++) {
            BlockPos from = points.get(i);
            BlockPos to = points.get(i + 1);
            Direction dir = dominantDirection(from, to);
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double segLen = Math.sqrt(dx * dx + dz * dz);
            if (segLen < 0.001) {
                tiles.add(new BezierUtil.BezierPoint(from, dir));
            } else {
                tiles.add(new BezierUtil.BezierPoint(from, dir));
                for (double dist = template.length; dist < segLen; dist += template.length) {
                    double frac = dist / segLen;
                    int x = (int) Math.round(from.getX() + dx * frac);
                    int y = (int) Math.round(from.getY() + (to.getY() - from.getY()) * frac);
                    int z = (int) Math.round(from.getZ() + dz * frac);
                    tiles.add(new BezierUtil.BezierPoint(new BlockPos(x, y, z), dir));
                }
            }
            // 段间转角：左转时 centerOrigin 两向偏移均指向内角，模板自然交叠；
            // 右转时两向偏移背离，外角出现缺口，需在 B 补一枚入段方向的 tile。
            if (i + 2 < points.size()) {
                Direction nextDir = dominantDirection(to, points.get(i + 2));
                if (dir != nextDir) {
                    int cross = dir.getStepX() * nextDir.getStepZ() - dir.getStepZ() * nextDir.getStepX();
                    if (cross > 0) {
                        int half = template.width / 2;
                        BlockPos wedgeOrigin = to.relative(dir.getOpposite(), template.length - half - 1);
                        tiles.add(new BezierUtil.BezierPoint(wedgeOrigin, dir));
                    }
                }
            }
        }
        for (BezierUtil.BezierPoint p : new LinkedHashSet<>(tiles)) {
            placeTemplate(level, template, p.pos(), p.direction(), undo);
        }
    }

    private static void generateBezier(ServerLevel level, TemplateData template,
                                         List<BlockPos> points, List<UndoEntry> undo) {
        List<BezierUtil.BezierPoint> samples = BezierUtil.sampleMultiSegmentCurve(points, template.length);
        for (BezierUtil.BezierPoint sample : new LinkedHashSet<>(samples)) {
            placeTemplate(level, template, sample.pos(), sample.direction(), undo);
        }
    }

    private static void placeTemplate(ServerLevel level, TemplateData template,
                                       BlockPos origin, Direction pathDir, List<UndoEntry> undo) {
        origin = centerOrigin(origin, pathDir, template.width);
        Rotation rotation = template.getRotation(pathDir);
        for (Map.Entry<BlockPos, BlockState> entry : template.blocks.entrySet()) {
            BlockPos local = entry.getKey();
            BlockState state = entry.getValue();
            BlockPos world = TemplateData.localToWorld(
                    local.getX(), local.getY(), local.getZ(), origin, pathDir);

            if (!level.isLoaded(world)) continue;

            BlockState rotatedState = state.rotate(level, world, rotation);
            BlockState oldState = level.getBlockState(world);
            undo.add(new UndoEntry(world, oldState, level.dimension()));
            level.setBlock(world, rotatedState, 3);
        }
    }

    private static BlockPos centerOrigin(BlockPos origin, Direction pathDir, int width) {
        int half = width / 2;
        return switch (pathDir) {
            case EAST  -> origin.offset(0, 0, -half);
            case WEST  -> origin.offset(0, 0,  half);
            case NORTH -> origin.offset(-half, 0, 0);
            case SOUTH -> origin.offset( half, 0, 0);
            default    -> origin;
        };
    }

    private static Direction dominantDirection(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    /**
     * Returns the total items required per type, aggregated by Item (not BlockState),
     * so that different orientations of the same block are counted together.
     */
    public static Map<Item, Integer> collectRequiredBlocks(PlayerPathData data) {
        TemplateData template = data.template;
        List<BlockPos> points = data.pathPoints;

        // Count how many of each item one tile placement needs
        Map<Item, Integer> itemsPerTile = new HashMap<>();
        for (BlockState state : template.blocks.values()) {
            Item item = state.getBlock().asItem();
            if (item != Items.AIR) {
                itemsPerTile.merge(item, 1, Integer::sum);
            }
        }

        int tileCount = 0;
        if (data.pathMode == PathMode.LINEAR) {
            List<BezierUtil.BezierPoint> tiles = new ArrayList<>();
            for (int i = 0; i + 1 < points.size(); i++) {
                BlockPos from = points.get(i), to = points.get(i + 1);
                Direction dir = dominantDirection(from, to);
                double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
                double segLen = Math.sqrt(dx * dx + dz * dz);
                if (segLen < 0.001) {
                    tiles.add(new BezierUtil.BezierPoint(from, dir));
                } else {
                    tiles.add(new BezierUtil.BezierPoint(from, dir));
                    for (double dist = template.length; dist < segLen; dist += template.length) {
                        double frac = dist / segLen;
                        int x = (int) Math.round(from.getX() + dx * frac);
                        int y = (int) Math.round(from.getY() + (to.getY() - from.getY()) * frac);
                        int z = (int) Math.round(from.getZ() + dz * frac);
                        tiles.add(new BezierUtil.BezierPoint(new BlockPos(x, y, z), dir));
                    }
                }
                // 段间转角：右转时在 B 补一枚入段方向 tile（见 generateLinear 同处注释）
                if (i + 2 < points.size()) {
                    Direction nextDir = dominantDirection(to, points.get(i + 2));
                    if (dir != nextDir) {
                        int cross = dir.getStepX() * nextDir.getStepZ() - dir.getStepZ() * nextDir.getStepX();
                        if (cross > 0) {
                            int half = template.width / 2;
                            BlockPos wedgeOrigin = to.relative(dir.getOpposite(), template.length - half - 1);
                            tiles.add(new BezierUtil.BezierPoint(wedgeOrigin, dir));
                        }
                    }
                }
            }
            tileCount = new LinkedHashSet<>(tiles).size();
        } else {
            tileCount = new LinkedHashSet<>(
                    BezierUtil.sampleMultiSegmentCurve(points, template.length)).size();
        }

        final int total = tileCount;
        itemsPerTile.replaceAll((item, count) -> count * total);
        return itemsPerTile;
    }
}

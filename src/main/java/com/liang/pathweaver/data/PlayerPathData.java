package com.liang.pathweaver.data;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class PlayerPathData {
    public BlockPos pendingCorner = null;
    public final List<BlockPos[]> regions = new ArrayList<>(); // each element: {c1, c2}
    public TemplateData template = null;
    public final List<BlockPos> pathPoints = new ArrayList<>();
    public PathMode pathMode = PathMode.LINEAR;

    public boolean hasTemplate() { return template != null; }
    public boolean hasPendingCorner() { return pendingCorner != null; }
    public boolean hasRegions() { return !regions.isEmpty(); }

    public void addRegion(BlockPos c1, BlockPos c2) {
        regions.add(new BlockPos[]{c1, c2});
    }

    public void removeLastRegion() {
        if (!regions.isEmpty()) regions.remove(regions.size() - 1);
    }

    public boolean isPosInAnyRegion(BlockPos pos) {
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

    /** Returns the index of the smallest-volume region containing pos, or -1. */
    public int findSmallestRegionContaining(BlockPos pos) {
        int bestIdx = -1;
        long bestVol = Long.MAX_VALUE;
        for (int i = 0; i < regions.size(); i++) {
            BlockPos[] r = regions.get(i);
            int minX = Math.min(r[0].getX(), r[1].getX()), maxX = Math.max(r[0].getX(), r[1].getX());
            int minY = Math.min(r[0].getY(), r[1].getY()), maxY = Math.max(r[0].getY(), r[1].getY());
            int minZ = Math.min(r[0].getZ(), r[1].getZ()), maxZ = Math.max(r[0].getZ(), r[1].getZ());
            if (pos.getX() < minX || pos.getX() > maxX) continue;
            if (pos.getY() < minY || pos.getY() > maxY) continue;
            if (pos.getZ() < minZ || pos.getZ() > maxZ) continue;
            long vol = (long)(maxX-minX+1) * (maxY-minY+1) * (maxZ-minZ+1);
            if (vol < bestVol) { bestVol = vol; bestIdx = i; }
        }
        return bestIdx;
    }

    public void clearPathPoints() { pathPoints.clear(); }

    public void clearSelection() {
        regions.clear();
        pendingCorner = null;
    }
}

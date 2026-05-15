package com.liang.pathweaver.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

public record TemplateBlockData(BlockState state, CompoundTag blockEntityTag) {
    public TemplateBlockData {
        if (blockEntityTag != null) {
            blockEntityTag = blockEntityTag.copy();
        }
    }

    public CompoundTag blockEntityTagCopy() {
        return blockEntityTag == null ? null : blockEntityTag.copy();
    }
}

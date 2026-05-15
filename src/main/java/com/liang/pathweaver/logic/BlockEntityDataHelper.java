package com.liang.pathweaver.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class BlockEntityDataHelper {
    private BlockEntityDataHelper() {}

    public static CompoundTag capture(BlockEntity blockEntity) {
        return blockEntity == null ? null : blockEntity.saveWithoutMetadata();
    }

    public static void restore(Level level, BlockPos pos, BlockState state, CompoundTag data) {
        if (data == null || !state.hasBlockEntity() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null && state.getBlock() instanceof EntityBlock entityBlock) {
            blockEntity = entityBlock.newBlockEntity(pos, state);
            if (blockEntity != null) {
                level.setBlockEntity(blockEntity);
            }
        }

        if (blockEntity != null) {
            blockEntity.load(data.copy());
            blockEntity.setChanged();
            serverLevel.sendBlockUpdated(pos, state, state, 3);
        }
    }
}

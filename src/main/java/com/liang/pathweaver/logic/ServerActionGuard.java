package com.liang.pathweaver.logic;

import com.liang.pathweaver.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

public final class ServerActionGuard {
    private ServerActionGuard() {}

    public static boolean holdsTool(ServerPlayer player) {
        return player.getMainHandItem().is(ModItems.PATH_WEAVER_TOOL.get());
    }

    public static boolean canReach(ServerPlayer player, BlockPos pos) {
        return player.canReach(pos, 1.0D);
    }

    public static boolean canUseToolOn(ServerPlayer player, BlockPos pos) {
        return holdsTool(player) && canReach(player, pos);
    }
}

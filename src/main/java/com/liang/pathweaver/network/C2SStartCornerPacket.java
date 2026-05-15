package com.liang.pathweaver.network;

import com.liang.pathweaver.data.PlayerPathData;
import com.liang.pathweaver.item.PathWeaverTool;
import com.liang.pathweaver.logic.PathDataManager;
import com.liang.pathweaver.logic.ServerActionGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class C2SStartCornerPacket {
    private final BlockPos pos;

    public C2SStartCornerPacket(BlockPos pos) { this.pos = pos; }

    public static void encode(C2SStartCornerPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
    }

    public static C2SStartCornerPacket decode(FriendlyByteBuf buf) {
        return new C2SStartCornerPacket(buf.readBlockPos());
    }

    public static void handle(C2SStartCornerPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!ServerActionGuard.canUseToolOn(player, pkt.pos)) return;
            PlayerPathData data = PathDataManager.get(player.getUUID());
            if (!data.hasTemplate() || data.pathPoints.isEmpty() || data.hasPendingCorner() || data.hasRegions()) return;

            data.clearPathPoints();
            // Don't set pendingCorner yet — let the user right-click to place it.
            // This gives them a clear "now select the first corner" state after
            // confirming the dialog, instead of having the corner auto-placed
            // at the dialog-triggering click position.

            player.sendSystemMessage(Component.literal(
                    "§a[PathWeaver] 路径点已清空 | 右键方块框选角点1"));

            PathWeaverTool.syncState(player, data);
        });
        ctx.get().setPacketHandled(true);
    }
}

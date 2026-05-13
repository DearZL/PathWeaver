package com.liang.pathweaver.network;

import com.liang.pathweaver.data.PlayerPathData;
import com.liang.pathweaver.item.PathWeaverTool;
import com.liang.pathweaver.logic.PathDataManager;
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
            PlayerPathData data = PathDataManager.get(player.getUUID());

            data.clearPathPoints();
            data.pendingCorner = pkt.pos;

            String hint = data.hasTemplate()
                    ? " §7(重新框选模板中，旧模板暂留)"
                    : " | 再次右键确定角点2";
            player.sendSystemMessage(Component.literal(
                    "§a[PathWeaver] 框选角点1: " + fmtPos(pkt.pos) + hint));

            PathWeaverTool.syncState(player, data);
        });
        ctx.get().setPacketHandled(true);
    }

    private static String fmtPos(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }
}

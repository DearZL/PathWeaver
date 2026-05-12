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

public class C2SMarkPathPointPacket {
    private final BlockPos pos;

    public C2SMarkPathPointPacket(BlockPos pos) { this.pos = pos; }

    public static void encode(C2SMarkPathPointPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
    }

    public static C2SMarkPathPointPacket decode(FriendlyByteBuf buf) {
        return new C2SMarkPathPointPacket(buf.readBlockPos());
    }

    public static void handle(C2SMarkPathPointPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            PlayerPathData data = PathDataManager.get(player.getUUID());

            if (!data.hasTemplate()) {
                player.sendSystemMessage(Component.literal("§c[PathWeaver] 请先确认模板（右键选择角点, Shift+右键确认）！"));
                return;
            }

            if (data.pathPoints.size() >= 256) {
                player.sendSystemMessage(Component.literal("§c[PathWeaver] 路径点已达上限 256！"));
                return;
            }

            data.pathPoints.add(pkt.pos);
            int n = data.pathPoints.size();
            String modeHint = data.pathMode == com.liang.pathweaver.data.PathMode.BEZIER
                    ? " (" + data.pathMode.chineseName + "模式，每3点一段)" : "";
            player.sendSystemMessage(Component.literal(
                    "§a[PathWeaver] 路径点 #" + n + ": (" + pkt.pos.getX()
                    + "," + pkt.pos.getY() + "," + pkt.pos.getZ() + ")" + modeHint));

            PathWeaverTool.syncState(player, data);
        });
        ctx.get().setPacketHandled(true);
    }
}

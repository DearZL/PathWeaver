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

public class C2SDeleteRegionPacket {
    private final BlockPos pos;

    public C2SDeleteRegionPacket(BlockPos pos) { this.pos = pos; }

    public static void encode(C2SDeleteRegionPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
    }

    public static C2SDeleteRegionPacket decode(FriendlyByteBuf buf) {
        return new C2SDeleteRegionPacket(buf.readBlockPos());
    }

    public static void handle(C2SDeleteRegionPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            PlayerPathData data = PathDataManager.get(player.getUUID());
            // allow deletion only in selection mode (no template, or template but re-selecting)
            if (data.hasTemplate() && !data.hasRegions()) return;
            int idx = data.findSmallestRegionContaining(pkt.pos);
            if (idx >= 0) {
                data.regions.remove(idx);
                data.clearPathPoints();
                player.sendSystemMessage(Component.literal(
                        "§e[PathWeaver] 已删除框选区域 #" + (idx + 1)));
                PathWeaverTool.syncState(player, data);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}

package com.liang.pathweaver.network;

import com.liang.pathweaver.data.PlayerPathData;
import com.liang.pathweaver.item.PathWeaverTool;
import com.liang.pathweaver.logic.PathDataManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class C2SSwitchModePacket {

    public static void encode(C2SSwitchModePacket pkt, FriendlyByteBuf buf) {}

    public static C2SSwitchModePacket decode(FriendlyByteBuf buf) {
        return new C2SSwitchModePacket();
    }

    public static void handle(C2SSwitchModePacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            PlayerPathData data = PathDataManager.get(player.getUUID());
            data.pathMode = data.pathMode.next();
            data.pathPoints.clear();
            player.sendSystemMessage(Component.literal(
                    "§e[PathWeaver] 路径模式切换为: " + data.pathMode.chineseName
                    + " (" + data.pathMode.name() + ")，路径点已清除"));
            PathWeaverTool.syncState(player, data);
        });
        ctx.get().setPacketHandled(true);
    }
}

package com.liang.pathweaver.network;

import com.liang.pathweaver.undo.UndoManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class C2SUndoPacket {

    public static void encode(C2SUndoPacket pkt, FriendlyByteBuf buf) {}

    public static C2SUndoPacket decode(FriendlyByteBuf buf) {
        return new C2SUndoPacket();
    }

    public static void handle(C2SUndoPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            boolean ok = UndoManager.undo(player.getUUID(), player.getServer());
            player.sendSystemMessage(Component.literal(ok
                    ? "§a[PathWeaver] 已撤销上一次生成。"
                    : "§c[PathWeaver] 没有可撤销的操作。"));
        });
        ctx.get().setPacketHandled(true);
    }
}

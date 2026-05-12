package com.liang.pathweaver.network;

import com.liang.pathweaver.data.PlayerPathData;
import com.liang.pathweaver.item.PathWeaverTool;
import com.liang.pathweaver.logic.MaterialChecker;
import com.liang.pathweaver.logic.PathDataManager;
import com.liang.pathweaver.logic.PathGenerator;
import com.liang.pathweaver.undo.UndoEntry;
import com.liang.pathweaver.undo.UndoManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class C2SGeneratePathPacket {

    public static void encode(C2SGeneratePathPacket pkt, FriendlyByteBuf buf) {}

    public static C2SGeneratePathPacket decode(FriendlyByteBuf buf) {
        return new C2SGeneratePathPacket();
    }

    public static void handle(C2SGeneratePathPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            PlayerPathData data = PathDataManager.get(player.getUUID());

            if (!data.hasTemplate()) {
                player.sendSystemMessage(Component.literal("§c[PathWeaver] 没有模板！"));
                return;
            }
            int minPoints = data.pathMode == com.liang.pathweaver.data.PathMode.BEZIER ? 3 : 2;
            if (data.pathPoints.size() < minPoints) {
                player.sendSystemMessage(Component.literal(
                        "§c[PathWeaver] " + data.pathMode.chineseName + "模式路径点不足（需要至少 " + minPoints + " 个）！"));
                return;
            }

            Map<Item, Integer> required = PathGenerator.collectRequiredBlocks(data);
            if (!MaterialChecker.checkAndDeduct(player, required)) return;

            List<UndoEntry> undo = PathGenerator.generate(player.serverLevel(), data);
            UndoManager.push(player.getUUID(), undo);

            data.clearPathPoints();
            player.sendSystemMessage(Component.literal(
                    "§a[PathWeaver] 生成完成！共放置 " + undo.size() + " 个方块。Shift+右键可撤销。"));

            PathWeaverTool.syncState(player, data);
        });
        ctx.get().setPacketHandled(true);
    }
}

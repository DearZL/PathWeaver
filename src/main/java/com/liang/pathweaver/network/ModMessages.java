package com.liang.pathweaver.network;

import com.liang.pathweaver.PathWeaver;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModMessages {
    private static final String PROTOCOL = "1";
    public static SimpleChannel CHANNEL;
    private static int id = 0;

    public static void register() {
        CHANNEL = NetworkRegistry.newSimpleChannel(
                ResourceLocation.fromNamespaceAndPath(PathWeaver.MOD_ID, "main"),
                () -> PROTOCOL,
                PROTOCOL::equals,
                PROTOCOL::equals);

        CHANNEL.registerMessage(id++, C2SMarkPathPointPacket.class,
                C2SMarkPathPointPacket::encode, C2SMarkPathPointPacket::decode, C2SMarkPathPointPacket::handle);

        CHANNEL.registerMessage(id++, C2SGeneratePathPacket.class,
                C2SGeneratePathPacket::encode, C2SGeneratePathPacket::decode, C2SGeneratePathPacket::handle);

        CHANNEL.registerMessage(id++, C2SSwitchModePacket.class,
                C2SSwitchModePacket::encode, C2SSwitchModePacket::decode, C2SSwitchModePacket::handle);

        CHANNEL.registerMessage(id++, C2SUndoPacket.class,
                C2SUndoPacket::encode, C2SUndoPacket::decode, C2SUndoPacket::handle);

        CHANNEL.registerMessage(id++, S2CUpdateStatePacket.class,
                S2CUpdateStatePacket::encode, S2CUpdateStatePacket::decode, S2CUpdateStatePacket::handle);
    }
}

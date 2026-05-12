package com.liang.pathweaver;

import com.liang.pathweaver.network.ModMessages;
import com.liang.pathweaver.registry.ModCreativeTabs;
import com.liang.pathweaver.registry.ModItems;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(PathWeaver.MOD_ID)
public class PathWeaver {
    public static final String MOD_ID = "pathweaver";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PathWeaver(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        ModItems.ITEMS.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ModMessages::register);
    }
}

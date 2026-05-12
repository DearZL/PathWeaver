package com.liang.pathweaver.registry;

import com.liang.pathweaver.PathWeaver;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, PathWeaver.MOD_ID);

    public static final RegistryObject<CreativeModeTab> PATH_WEAVER_TAB = TABS.register("pathweaver_tab",
            () -> CreativeModeTab.builder()
                    .withTabsBefore(CreativeModeTabs.TOOLS_AND_UTILITIES)
                    .icon(() -> ModItems.PATH_WEAVER_TOOL.get().getDefaultInstance())
                    .title(Component.translatable("itemGroup.pathweaver"))
                    .displayItems((params, output) -> output.accept(ModItems.PATH_WEAVER_TOOL.get()))
                    .build());
}

package com.liang.pathweaver.registry;

import com.liang.pathweaver.PathWeaver;
import com.liang.pathweaver.item.PathWeaverTool;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, PathWeaver.MOD_ID);

    public static final RegistryObject<Item> PATH_WEAVER_TOOL =
            ITEMS.register("path_weaver_tool", PathWeaverTool::new);
}

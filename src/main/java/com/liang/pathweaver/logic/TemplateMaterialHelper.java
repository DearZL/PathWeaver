package com.liang.pathweaver.logic;

import com.liang.pathweaver.data.TemplateBlockData;
import com.liang.pathweaver.data.TemplateData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TemplateMaterialHelper {
    private TemplateMaterialHelper() {}

    public record Analysis(Map<Item, Integer> materialsPerTile, List<String> unsupportedBlocks) {}

    public static Analysis analyze(TemplateData template) {
        Map<Item, Integer> materials = new LinkedHashMap<>();
        List<String> unsupported = new ArrayList<>();

        for (TemplateBlockData block : template.blocks.values()) {
            BlockState state = block.state();
            if (isUnsupported(state)) {
                unsupported.add(describe(state));
                continue;
            }
            if (isFreeSecondaryHalf(state)) {
                continue;
            }
            materials.merge(state.getBlock().asItem(), 1, Integer::sum);
        }

        return new Analysis(materials, unsupported);
    }

    public static Map<Item, Integer> multiplyByTiles(TemplateData template, int tileCount) {
        Map<Item, Integer> perTile = analyze(template).materialsPerTile();
        Map<Item, Integer> total = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : perTile.entrySet()) {
            total.put(entry.getKey(), entry.getValue() * tileCount);
        }
        return total;
    }

    private static boolean isUnsupported(BlockState state) {
        return !state.getFluidState().isEmpty() || state.getBlock().asItem() == Items.AIR;
    }

    private static boolean isFreeSecondaryHalf(BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)) {
            return state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
        }
        return false;
    }

    private static String describe(BlockState state) {
        var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return key != null ? key.toString() : state.getBlock().getName().getString();
    }
}

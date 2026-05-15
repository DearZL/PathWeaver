package com.liang.pathweaver.network;

import com.liang.pathweaver.client.ClientHandler;
import com.liang.pathweaver.data.PathMode;
import com.liang.pathweaver.data.PlayerPathData;
import com.liang.pathweaver.data.TemplateBlockData;
import com.liang.pathweaver.logic.TemplateMaterialHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class S2CUpdateStatePacket {
    public final BlockPos pendingCorner;
    public final List<BlockPos[]> regions;
    public final boolean hasTemplate;
    public final int templateLength;
    public final int templateWidth;
    public final int templateHeight;
    public final Direction templateBaseDir;
    public final List<BlockPos> pathPoints;
    public final PathMode pathMode;
    // Template block data for shape preview: each int[] = {lx, ly, lz, typeIndex}
    public final List<String> templateBlockTypeIds;  // unique block registry names
    public final List<int[]>  templateBlockData;     // one entry per non-air template block
    public final List<String> templateMaterialItemIds;
    public final List<Integer> templateMaterialCountsPerTile;

    public S2CUpdateStatePacket(BlockPos pendingCorner, List<BlockPos[]> regions,
                                boolean hasTemplate,
                                int templateLength, int templateWidth, int templateHeight,
                                Direction templateBaseDir,
                                List<BlockPos> pathPoints, PathMode pathMode,
                                List<String> templateBlockTypeIds, List<int[]> templateBlockData,
                                List<String> templateMaterialItemIds, List<Integer> templateMaterialCountsPerTile) {
        this.pendingCorner = pendingCorner;
        this.regions = regions;
        this.hasTemplate = hasTemplate;
        this.templateLength = templateLength;
        this.templateWidth = templateWidth;
        this.templateHeight = templateHeight;
        this.templateBaseDir = templateBaseDir;
        this.pathPoints = pathPoints;
        this.pathMode = pathMode;
        this.templateBlockTypeIds = templateBlockTypeIds;
        this.templateBlockData = templateBlockData;
        this.templateMaterialItemIds = templateMaterialItemIds;
        this.templateMaterialCountsPerTile = templateMaterialCountsPerTile;
    }

    public static S2CUpdateStatePacket fromData(PlayerPathData data) {
        int len = 0, w = 0, h = 0;
        Direction dir = Direction.NORTH;
        List<String> typeIds = new ArrayList<>();
        List<int[]> blockData = new ArrayList<>();
        List<String> materialIds = new ArrayList<>();
        List<Integer> materialCounts = new ArrayList<>();

        if (data.template != null) {
            len = data.template.length;
            w   = data.template.width;
            h   = data.template.height;
            dir = data.template.baseDirection;

            // Build block type index map
            Map<String, Integer> typeMap = new LinkedHashMap<>();
            for (Map.Entry<BlockPos, TemplateBlockData> e : data.template.blocks.entrySet()) {
                ResourceLocation key = ForgeRegistries.BLOCKS.getKey(e.getValue().state().getBlock());
                String id = key != null ? key.toString() : "minecraft:stone";
                if (!typeMap.containsKey(id)) typeMap.put(id, typeMap.size());
                int idx = typeMap.get(id);
                BlockPos lp = e.getKey();
                blockData.add(new int[]{lp.getX(), lp.getY(), lp.getZ(), idx});
            }
            typeIds.addAll(typeMap.keySet());

            TemplateMaterialHelper.Analysis analysis = TemplateMaterialHelper.analyze(data.template);
            for (Map.Entry<Item, Integer> entry : analysis.materialsPerTile().entrySet()) {
                ResourceLocation key = ForgeRegistries.ITEMS.getKey(entry.getKey());
                materialIds.add(key != null ? key.toString() : "minecraft:air");
                materialCounts.add(entry.getValue());
            }
        }

        return new S2CUpdateStatePacket(
                data.pendingCorner,
                new ArrayList<>(data.regions),
                data.hasTemplate(),
                len, w, h, dir,
                new ArrayList<>(data.pathPoints), data.pathMode,
                typeIds, blockData, materialIds, materialCounts);
    }

    public static void encode(S2CUpdateStatePacket pkt, FriendlyByteBuf buf) {
        buf.writeBoolean(pkt.pendingCorner != null);
        if (pkt.pendingCorner != null) buf.writeBlockPos(pkt.pendingCorner);

        buf.writeInt(pkt.regions.size());
        for (BlockPos[] r : pkt.regions) { buf.writeBlockPos(r[0]); buf.writeBlockPos(r[1]); }

        buf.writeBoolean(pkt.hasTemplate);
        buf.writeInt(pkt.templateLength);
        buf.writeInt(pkt.templateWidth);
        buf.writeInt(pkt.templateHeight);
        buf.writeEnum(pkt.templateBaseDir);

        buf.writeInt(pkt.pathPoints.size());
        for (BlockPos p : pkt.pathPoints) buf.writeBlockPos(p);

        buf.writeEnum(pkt.pathMode);

        buf.writeInt(pkt.templateBlockTypeIds.size());
        for (String id : pkt.templateBlockTypeIds) buf.writeUtf(id);

        buf.writeInt(pkt.templateBlockData.size());
        for (int[] d : pkt.templateBlockData) {
            buf.writeInt(d[0]); buf.writeInt(d[1]); buf.writeInt(d[2]); buf.writeInt(d[3]);
        }

        buf.writeInt(pkt.templateMaterialItemIds.size());
        for (int i = 0; i < pkt.templateMaterialItemIds.size(); i++) {
            buf.writeUtf(pkt.templateMaterialItemIds.get(i));
            buf.writeInt(pkt.templateMaterialCountsPerTile.get(i));
        }
    }

    public static S2CUpdateStatePacket decode(FriendlyByteBuf buf) {
        BlockPos pending = buf.readBoolean() ? buf.readBlockPos() : null;

        int regCount = buf.readInt();
        List<BlockPos[]> regs = new ArrayList<>(regCount);
        for (int i = 0; i < regCount; i++) regs.add(new BlockPos[]{buf.readBlockPos(), buf.readBlockPos()});

        boolean tmpl = buf.readBoolean();
        int tLen = buf.readInt(), tW = buf.readInt(), tH = buf.readInt();
        Direction dir = buf.readEnum(Direction.class);

        int n = buf.readInt();
        List<BlockPos> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) pts.add(buf.readBlockPos());

        PathMode mode = buf.readEnum(PathMode.class);

        int nt = buf.readInt();
        List<String> typeIds = new ArrayList<>(nt);
        for (int i = 0; i < nt; i++) typeIds.add(buf.readUtf());

        int nb = buf.readInt();
        List<int[]> bData = new ArrayList<>(nb);
        for (int i = 0; i < nb; i++) bData.add(new int[]{buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt()});

        int nm = buf.readInt();
        List<String> materialIds = new ArrayList<>(nm);
        List<Integer> materialCounts = new ArrayList<>(nm);
        for (int i = 0; i < nm; i++) {
            materialIds.add(buf.readUtf());
            materialCounts.add(buf.readInt());
        }

        return new S2CUpdateStatePacket(
                pending, regs, tmpl, tLen, tW, tH, dir, pts, mode, typeIds, bData, materialIds, materialCounts);
    }

    public static void handle(S2CUpdateStatePacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.updateState(pkt)));
        ctx.get().setPacketHandled(true);
    }
}

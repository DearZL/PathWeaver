package com.liang.pathweaver.network;

import com.liang.pathweaver.client.ClientHandler;
import com.liang.pathweaver.data.PathMode;
import com.liang.pathweaver.data.PlayerPathData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class S2CUpdateStatePacket {
    public final BlockPos corner1;
    public final BlockPos corner2;
    public final boolean hasTemplate;
    // Template dimensions for client-side preview (only valid when hasTemplate=true)
    public final int templateLength;
    public final int templateWidth;
    public final int templateHeight;
    public final Direction templateBaseDir;
    public final List<BlockPos> pathPoints;
    public final PathMode pathMode;

    public S2CUpdateStatePacket(BlockPos corner1, BlockPos corner2, boolean hasTemplate,
                                int templateLength, int templateWidth, int templateHeight,
                                Direction templateBaseDir,
                                List<BlockPos> pathPoints, PathMode pathMode) {
        this.corner1 = corner1;
        this.corner2 = corner2;
        this.hasTemplate = hasTemplate;
        this.templateLength = templateLength;
        this.templateWidth = templateWidth;
        this.templateHeight = templateHeight;
        this.templateBaseDir = templateBaseDir;
        this.pathPoints = pathPoints;
        this.pathMode = pathMode;
    }

    public static S2CUpdateStatePacket fromData(PlayerPathData data) {
        int len = 0, w = 0, h = 0;
        Direction dir = Direction.NORTH;
        if (data.template != null) {
            len = data.template.length;
            w = data.template.width;
            h = data.template.height;
            dir = data.template.baseDirection;
        }
        return new S2CUpdateStatePacket(
                data.corner1, data.corner2, data.hasTemplate(),
                len, w, h, dir,
                new ArrayList<>(data.pathPoints), data.pathMode);
    }

    public static void encode(S2CUpdateStatePacket pkt, FriendlyByteBuf buf) {
        buf.writeBoolean(pkt.corner1 != null);
        if (pkt.corner1 != null) buf.writeBlockPos(pkt.corner1);
        buf.writeBoolean(pkt.corner2 != null);
        if (pkt.corner2 != null) buf.writeBlockPos(pkt.corner2);
        buf.writeBoolean(pkt.hasTemplate);
        buf.writeInt(pkt.templateLength);
        buf.writeInt(pkt.templateWidth);
        buf.writeInt(pkt.templateHeight);
        buf.writeEnum(pkt.templateBaseDir);
        buf.writeInt(pkt.pathPoints.size());
        for (BlockPos p : pkt.pathPoints) buf.writeBlockPos(p);
        buf.writeEnum(pkt.pathMode);
    }

    public static S2CUpdateStatePacket decode(FriendlyByteBuf buf) {
        BlockPos c1 = buf.readBoolean() ? buf.readBlockPos() : null;
        BlockPos c2 = buf.readBoolean() ? buf.readBlockPos() : null;
        boolean tmpl = buf.readBoolean();
        int len = buf.readInt(), w = buf.readInt(), h = buf.readInt();
        Direction dir = buf.readEnum(Direction.class);
        int n = buf.readInt();
        List<BlockPos> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) pts.add(buf.readBlockPos());
        PathMode mode = buf.readEnum(PathMode.class);
        return new S2CUpdateStatePacket(c1, c2, tmpl, len, w, h, dir, pts, mode);
    }

    public static void handle(S2CUpdateStatePacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.updateState(pkt)));
        ctx.get().setPacketHandled(true);
    }
}

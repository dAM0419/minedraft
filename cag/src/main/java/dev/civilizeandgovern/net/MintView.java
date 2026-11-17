package dev.civilizeandgovern.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** 주조대 화면 자료. */
public record MintView(BlockPos pos, String cityName, String currency, int copper, int rate, long treasury, long issuedTotal, boolean canIssue) {
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(cityName);
        buf.writeUtf(currency);
        buf.writeVarInt(copper);
        buf.writeVarInt(rate);
        buf.writeVarLong(treasury);
        buf.writeVarLong(issuedTotal);
        buf.writeBoolean(canIssue);
    }

    public static MintView read(FriendlyByteBuf buf) {
        return new MintView(buf.readBlockPos(), buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarLong(), buf.readVarLong(), buf.readBoolean());
    }
}

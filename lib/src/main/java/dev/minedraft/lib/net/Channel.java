package dev.minedraft.lib.net;

import dev.minedraft.lib.MinedraftLib;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 모드 하나의 통신 통로. 모든 메시지는 두 개의 페이로드(C2S, S2C)로 오간다. 모드마다 하나씩 만든다 (이름이 페이로드 ID).
 *
 * <pre>
 * 프레임: [종류 1바이트][플래그 1바이트][본문] (플래그 1 = 본문 압축)
 * 큰 프레임은 PART 조각으로 나눠 보내고 받는 쪽이 다시 붙인다.
 *   서버행: 조각 30KB (바닐라 사용자 정의 페이로드 한도 32KB 아래)
 *   클라이언트행: 조각 900KB (1MB 아래)
 * 종류 0 은 조각(PART)으로 예약돼 있다.
 * </pre>
 */
public final class Channel {
    /** 큰 프레임의 조각. 메시지 종류로 쓰지 않는다. */
    public static final int PART = 0;

    private static final int C2S_LIMIT = 30_000;
    private static final int S2C_LIMIT = 900_000;
    private static final int COMPRESS_OVER = 512;

    /** 서버 쪽 수신 처리기 (서버 주 스레드에서 불린다). */
    public interface ServerReceiver {
        void receive(ServerPlayer player, Frame frame);
    }

    /** 클라이언트 쪽 수신 처리기. 클라이언트 초기화에서 넣는다 (서버에서는 null). */
    @Nullable
    public volatile Consumer<Frame> clientReceiver;

    private final String name;
    private final ServerReceiver serverReceiver;
    private final CustomPacketPayload.Type<C2S> c2sType;
    private final CustomPacketPayload.Type<S2C> s2cType;
    private final StreamCodec<ByteBuf, C2S> c2sCodec;
    private final StreamCodec<ByteBuf, S2C> s2cCodec;
    private static final AtomicInteger NEXT_TRANSFER = new AtomicInteger(1);
    private final Reassembler clientParts = new Reassembler(256L << 20);
    private final Map<UUID, Reassembler> serverParts = new HashMap<>();

    public record Frame(int kind, FriendlyByteBuf buf) {}

    /** @param id 페이로드 이름 앞부분 (id + "_c2s", id + "_s2c") */
    public Channel(ResourceLocation id, ServerReceiver serverReceiver) {
        this.name = id.toString();
        this.serverReceiver = serverReceiver;
        this.c2sType = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), id.getPath() + "_c2s"));
        this.s2cType = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), id.getPath() + "_s2c"));
        this.c2sCodec = ByteBufCodecs.BYTE_ARRAY.map(b -> new C2S(c2sType, b), C2S::data);
        this.s2cCodec = ByteBufCodecs.BYTE_ARRAY.map(b -> new S2C(s2cType, b), S2C::data);
    }

    public record C2S(Type<C2S> payloadType, byte[] data) implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return payloadType;
        }
    }

    public record S2C(Type<S2C> payloadType, byte[] data) implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return payloadType;
        }
    }

    /** 모드 버스의 RegisterPayloadHandlersEvent 에서 부른다. */
    public void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(c2sType, c2sCodec, this::handleServer);
        registrar.playToClient(s2cType, s2cCodec, this::handleClient);
    }

    // ------------------------------------------------------------------
    // 보내기
    // ------------------------------------------------------------------

    public static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    public void toServer(int kind, Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buf = buffer();
        writer.accept(buf);
        byte[] frame = frame(kind, bytes(buf));
        for (byte[] part : split(frame, C2S_LIMIT)) PacketDistributor.sendToServer(new C2S(c2sType, part));
    }

    public void toPlayer(ServerPlayer player, int kind, Consumer<FriendlyByteBuf> writer) {
        toPlayer(player, kind, encode(writer));
    }

    /** 미리 인코딩한 본문을 여러 플레이어에게 보낼 때. */
    public void toPlayer(ServerPlayer player, int kind, byte[] body) {
        byte[] frame = frame(kind, body);
        for (byte[] part : split(frame, S2C_LIMIT)) PacketDistributor.sendToPlayer(player, new S2C(s2cType, part));
    }

    public static byte[] encode(Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buf = buffer();
        writer.accept(buf);
        return bytes(buf);
    }

    private static byte[] bytes(FriendlyByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        buf.release();
        return out;
    }

    private static byte[] frame(int kind, byte[] body) {
        boolean compress = body.length > COMPRESS_OVER;
        byte[] payload = compress ? deflate(body) : body;
        byte[] out = new byte[payload.length + 2];
        out[0] = (byte) kind;
        out[1] = (byte) (compress ? 1 : 0);
        System.arraycopy(payload, 0, out, 2, payload.length);
        return out;
    }

    private static byte[][] split(byte[] frame, int limit) {
        if (frame.length <= limit) return new byte[][]{frame};
        int chunk = limit - 16;
        int total = (frame.length + chunk - 1) / chunk;
        int transfer = NEXT_TRANSFER.getAndIncrement();
        byte[][] parts = new byte[total][];
        for (int i = 0; i < total; i++) {
            int from = i * chunk;
            int to = Math.min(frame.length, from + chunk);
            FriendlyByteBuf buf = buffer();
            buf.writeByte(PART);
            buf.writeByte(0);
            buf.writeVarInt(transfer);
            buf.writeVarInt(i);
            buf.writeVarInt(total);
            buf.writeVarInt(frame.length);
            buf.writeBytes(frame, from, to - from);
            parts[i] = bytes(buf);
        }
        return parts;
    }

    // ------------------------------------------------------------------
    // 받기
    // ------------------------------------------------------------------

    private void handleServer(C2S payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        Reassembler parts;
        synchronized (serverParts) {
            parts = serverParts.computeIfAbsent(player.getUUID(), k -> new Reassembler(64L << 20));
        }
        byte[] frame = parts.accept(payload.data());
        if (frame == null) return;
        Frame f = open(frame);
        if (f == null) return;
        context.enqueueWork(() -> {
            try {
                serverReceiver.receive(player, f);
            } catch (Exception e) {
                MinedraftLib.LOGGER.warn("Bad {} packet from {}", name, player.getGameProfile().getName(), e);
            } finally {
                f.buf().release();
            }
        });
    }

    private void handleClient(S2C payload, IPayloadContext context) {
        byte[] frame = clientParts.accept(payload.data());
        if (frame == null) return;
        Frame f = open(frame);
        if (f == null) return;
        context.enqueueWork(() -> {
            try {
                Consumer<Frame> receiver = clientReceiver;
                if (receiver != null) receiver.accept(f);
            } catch (Exception e) {
                MinedraftLib.LOGGER.warn("Bad {} packet from server", name, e);
            } finally {
                f.buf().release();
            }
        });
    }

    public void forgetPlayer(UUID player) {
        synchronized (serverParts) {
            serverParts.remove(player);
        }
    }

    public void resetClient() {
        clientParts.clear();
    }

    @Nullable
    private static Frame open(byte[] frame) {
        if (frame.length < 2) return null;
        int kind = frame[0] & 0xFF;
        boolean compressed = (frame[1] & 1) != 0;
        byte[] body = Arrays.copyOfRange(frame, 2, frame.length);
        if (compressed) {
            body = inflate(body);
            if (body == null) return null;
        }
        return new Frame(kind, new FriendlyByteBuf(Unpooled.wrappedBuffer(body)));
    }

    // ------------------------------------------------------------------

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 3));
        byte[] buf = new byte[65536];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    @Nullable
    private static byte[] inflate(byte[] data) {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 3);
        byte[] buf = new byte[65536];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                out.write(buf, 0, n);
                if (out.size() > (512 << 20)) return null;
            }
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    /** 조각 모으기. 조각이 아니면 그대로 돌려준다. */
    private static final class Reassembler {
        private final long maxSize;
        private final Map<Integer, Partial> partials = new HashMap<>();

        Reassembler(long maxSize) {
            this.maxSize = maxSize;
        }

        synchronized void clear() {
            partials.clear();
        }

        @Nullable
        synchronized byte[] accept(byte[] data) {
            if (data.length < 2) return null;
            if ((data[0] & 0xFF) != PART) return data;
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
            try {
                buf.readByte();
                buf.readByte();
                int transfer = buf.readVarInt();
                int index = buf.readVarInt();
                int total = buf.readVarInt();
                int size = buf.readVarInt();
                if (total <= 0 || total > 100_000 || index < 0 || index >= total || size <= 0 || size > maxSize) return null;
                long now = System.currentTimeMillis();
                partials.values().removeIf(p -> now - p.time > 120_000);
                Partial p = partials.computeIfAbsent(transfer, k -> new Partial(total, size, now));
                if (p.total != total || p.parts[index] != null) return null;
                byte[] chunk = new byte[buf.readableBytes()];
                buf.readBytes(chunk);
                p.parts[index] = chunk;
                p.received++;
                p.time = now;
                if (p.received < total) return null;
                partials.remove(transfer);
                byte[] out = new byte[p.size];
                int off = 0;
                for (byte[] part : p.parts) {
                    if (off + part.length > out.length) return null;
                    System.arraycopy(part, 0, out, off, part.length);
                    off += part.length;
                }
                return off == out.length ? out : null;
            } finally {
                buf.release();
            }
        }

        private static final class Partial {
            final int total;
            final int size;
            final byte[][] parts;
            int received;
            long time;

            Partial(int total, int size, long time) {
                this.total = total;
                this.size = size;
                this.parts = new byte[total][];
                this.time = time;
            }
        }
    }
}

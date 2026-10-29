package dev.designandbuild.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/**
 * 도면 아이템이 가리키는 설계. 설계 본체는 서버 저장 데이터에 있고, 아이템에는 ID 와 표시용 정보만 둔다.
 * 같은 ID 를 가진 사본은 모두 같은 설계를 공유한다 (연동 복사).
 */
public record BlueprintRef(UUID id, String name, int baseX, int baseZ, String dimension) {
    public static final Codec<BlueprintRef> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(BlueprintRef::id),
            Codec.STRING.fieldOf("name").forGetter(BlueprintRef::name),
            Codec.INT.fieldOf("base_x").forGetter(BlueprintRef::baseX),
            Codec.INT.fieldOf("base_z").forGetter(BlueprintRef::baseZ),
            Codec.STRING.fieldOf("dimension").forGetter(BlueprintRef::dimension)
    ).apply(i, BlueprintRef::new));

    public static final StreamCodec<ByteBuf, BlueprintRef> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, BlueprintRef::id,
            ByteBufCodecs.STRING_UTF8, BlueprintRef::name,
            ByteBufCodecs.VAR_INT, BlueprintRef::baseX,
            ByteBufCodecs.VAR_INT, BlueprintRef::baseZ,
            ByteBufCodecs.STRING_UTF8, BlueprintRef::dimension,
            BlueprintRef::new);

    public BlueprintRef withName(String newName) {
        return new BlueprintRef(id, newName, baseX, baseZ, dimension);
    }
}

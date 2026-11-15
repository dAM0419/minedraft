package dev.minedraft.lib.client.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.LevelTickAccess;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 블록 엔티티가 붙는 가짜 월드 (읽기 전용). 블록과 블록 엔티티는 {@link Source} 가 준다
 * (편집창: 설계 + 월드 사본, 투영: 유령 블록 + 실제 월드). 블록 엔티티 렌더러·모델이 이웃 블록과 블록 엔티티를 이 월드에서 찾는다.
 * 쓰기·소리·엔티티는 모두 무시한다. Create 의 Ponder 월드와 같은 방식.
 */
public final class FakeLevel extends Level {
    /** 가짜 월드가 보여 줄 내용. */
    public interface Source {
        BlockState blockAt(int x, int y, int z);

        @Nullable
        BlockEntity blockEntity(BlockPos pos);

        /**
         * 블록을 바꾸려 한다 (블록의 설치·제거 반응이 이웃을 바꿀 때). 받아들이면 true.
         * 기본은 읽기 전용: 무시한다. 시뮬레이션하는 쪽이 이 쓰기를 모아 설계에 반영한다.
         */
        default boolean setBlock(BlockPos pos, BlockState state) {
            return false;
        }

        /** 블록이 이 칸에 예약 틱을 걸었다 (시뮬레이션하는 쪽이 그 칸을 다시 판정하는 데 쓴다). 기본은 무시. */
        default void scheduledTick(BlockPos pos) {
        }

        /** 바이옴 (4칸 단위 좌표가 아니라 블록 좌표). 모르면 null = 실제 월드 것. */
        @Nullable
        default Holder<Biome> biome(int x, int y, int z) {
            return null;
        }
    }

    private final Source source;
    private final ClientLevel real;
    @Nullable
    private FakeLevel serverView;

    /** 같은 내용을 보는 서버인 척 하는 가짜 월드 (한 번 만들어 둔다). */
    public FakeLevel serverView() {
        if (!isClientSide) return this;
        if (serverView == null) serverView = new FakeLevel(source, real, minY, height, false);
        return serverView;
    }
    private final int minY, height;

    public FakeLevel(Source source, ClientLevel real, int minY, int height) {
        this(source, real, minY, height, true);
    }

    /**
     * clientSide = false: 서버인 척 하는 가짜 월드. "서버일 때만" 도는 블록 반응·멀티블록 합체 코드를 돌리려고 쓴다.
     * 진짜 서버 월드(ServerLevel)를 요구하는 코드는 여전히 실패한다 (부르는 쪽이 잡는다).
     */
    public FakeLevel(Source source, ClientLevel real, int minY, int height, boolean clientSide) {
        super((WritableLevelData) real.getLevelData(), real.dimension(), real.registryAccess(), real.dimensionTypeRegistration(),
                real::getProfiler, clientSide, false, 0, 0);
        this.source = source;
        this.real = real;
        this.minY = minY;
        this.height = height;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return source.blockAt(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return source.blockEntity(pos);
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
        return source.setBlock(pos.immutable(), state);
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean moving) {
        return source.setBlock(pos.immutable(), getFluidState(pos).createLegacyBlock());
    }

    @Override
    public void setBlockEntity(BlockEntity be) {
    }

    @Override
    public void removeBlockEntity(BlockPos pos) {
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return real.getLightEngine();
    }

    @Override
    public int getMaxLocalRawBrightness(BlockPos pos) {
        return 15;
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return real.getShade(direction, shade);
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return blockTicks;
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return fluidTicks;
    }

    /** 예약 틱은 실제로 돌리지 않고, 어느 칸에 걸렸는지만 Source 에 알린다. */
    private final LevelTickAccess<Block> blockTicks = new CapturingTicks<>();
    private final LevelTickAccess<Fluid> fluidTicks = new CapturingTicks<>();

    private final class CapturingTicks<T> implements LevelTickAccess<T> {
        @Override
        public boolean willTickThisTick(BlockPos pos, T type) {
            return false;
        }

        @Override
        public void schedule(net.minecraft.world.ticks.ScheduledTick<T> tick) {
            try {
                source.scheduledTick(tick.pos());
            } catch (Throwable ignored) {
            }
        }

        @Override
        public boolean hasScheduledTick(BlockPos pos, T type) {
            return false;
        }

        @Override
        public int count() {
            return 0;
        }
    }

    @Override
    public ChunkSource getChunkSource() {
        return real.getChunkSource();
    }

    @Override
    public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {
    }

    @Override
    public List<? extends Player> players() {
        return Collections.emptyList();
    }

    @Override
    public void playSeededSound(@Nullable Player player, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source,
                                float volume, float pitch, long seed) {
    }

    @Override
    public void playSeededSound(@Nullable Player player, Entity entity, Holder<SoundEvent> sound, SoundSource source, float volume,
                                float pitch, long seed) {
    }

    @Override
    public void playSound(@Nullable Player player, double x, double y, double z, SoundEvent sound, SoundSource source, float volume,
                          float pitch) {
    }

    @Override
    public void playSound(@Nullable Player player, Entity entity, SoundEvent sound, SoundSource source, float volume, float pitch) {
    }

    @Override
    public void addParticle(net.minecraft.core.particles.ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {
    }

    @Nullable
    @Override
    public Entity getEntity(int id) {
        return null;
    }

    @Override
    public boolean addFreshEntity(Entity entity) {
        return false;
    }

    @Override
    public TickRateManager tickRateManager() {
        return real.tickRateManager();
    }

    @Nullable
    @Override
    public MapItemSavedData getMapData(MapId id) {
        return null;
    }

    @Override
    public void setMapData(MapId id, MapItemSavedData data) {
    }

    @Override
    public MapId getFreeMapId() {
        return new MapId(0);
    }

    @Override
    public void destroyBlockProgress(int breaker, BlockPos pos, int progress) {
    }

    @Override
    public Scoreboard getScoreboard() {
        return real.getScoreboard();
    }

    @Override
    public RecipeManager getRecipeManager() {
        return real.getRecipeManager();
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        Holder<Biome> b = source.biome(x << 2, y << 2, z << 2);
        return b != null ? b : real.getUncachedNoiseBiome(x, y, z);
    }

    @Override
    public PotionBrewing potionBrewing() {
        return real.potionBrewing();
    }

    @Override
    public void updateNeighbourForOutputSignal(BlockPos pos, Block block) {
    }

    @Override
    public void gameEvent(@Nullable Entity entity, Holder<GameEvent> event, Vec3 pos) {
    }

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
    }

    @Override
    public String gatherChunkSourceStats() {
        return "minedraft editor";
    }

    @Override
    protected LevelEntityGetter<Entity> getEntities() {
        return NO_ENTITIES;
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return real.enabledFeatures();
    }

    // 높이는 편집창 영역 기준 (다른 모드가 기본 구현을 바꿔도 맞게 직접 계산)
    @Override
    public int getMinBuildHeight() {
        return minY;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMaxBuildHeight() {
        return getMinBuildHeight() + getHeight();
    }

    @Override
    public int getSectionsCount() {
        return getMaxSection() - getMinSection();
    }

    @Override
    public int getMinSection() {
        return SectionPos.blockToSectionCoord(getMinBuildHeight());
    }

    @Override
    public int getMaxSection() {
        return SectionPos.blockToSectionCoord(getMaxBuildHeight() - 1) + 1;
    }

    @Override
    public boolean isOutsideBuildHeight(int y) {
        return y < getMinBuildHeight() || y >= getMaxBuildHeight();
    }

    @Override
    public int getSectionIndex(int y) {
        return getSectionIndexFromSectionY(SectionPos.blockToSectionCoord(y));
    }

    @Override
    public int getSectionIndexFromSectionY(int sectionY) {
        return sectionY - getMinSection();
    }

    @Override
    public int getSectionYFromSectionIndex(int index) {
        return index + getMinSection();
    }

    // NeoForge 가 붙인 하루 시간 메서드
    @Override
    public void setDayTimeFraction(float v) {
    }

    @Override
    public float getDayTimeFraction() {
        return real.getDayTimeFraction();
    }

    @Override
    public float getDayTimePerTick() {
        return real.getDayTimePerTick();
    }

    @Override
    public void setDayTimePerTick(float v) {
    }

    private static final LevelEntityGetter<Entity> NO_ENTITIES = new LevelEntityGetter<>() {
        @Nullable
        @Override
        public Entity get(int id) {
            return null;
        }

        @Nullable
        @Override
        public Entity get(UUID id) {
            return null;
        }

        @Override
        public Iterable<Entity> getAll() {
            return Collections.emptyList();
        }

        @Override
        public <U extends Entity> void get(EntityTypeTest<Entity, U> test, AbortableIterationConsumer<U> consumer) {
        }

        @Override
        public void get(AABB box, Consumer<Entity> consumer) {
        }

        @Override
        public <U extends Entity> void get(EntityTypeTest<Entity, U> test, AABB box, AbortableIterationConsumer<U> consumer) {
        }
    };
}

package dev.minedraft.lib.client.editor;

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
 * 편집창 블록 엔티티가 붙는 가짜 월드 (읽기 전용). 블록은 편집창이 보는 블록(설계 + 월드 사본),
 * 블록 엔티티는 편집창이 만든 것. 블록 엔티티 렌더러·모델이 이웃 블록과 블록 엔티티를 이 월드에서 찾는다.
 * 쓰기·소리·엔티티는 모두 무시한다. Create 의 Ponder 월드와 같은 방식.
 */
final class EditorLevel extends Level {
    private final AreaEditorSession session;
    private final ClientLevel real;

    EditorLevel(AreaEditorSession session, ClientLevel real) {
        super((WritableLevelData) real.getLevelData(), real.dimension(), real.registryAccess(), real.dimensionTypeRegistration(),
                real::getProfiler, true, false, 0, 0);
        this.session = session;
        this.real = real;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return session.blockAt(pos.getX(), pos.getY(), pos.getZ(), false);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return session.blockEntities.get(pos);
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
        return false;
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean moving) {
        return false;
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
        return real.getBlockTicks();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return real.getFluidTicks();
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
        Holder<Biome> b = session.snapshot.biome(x << 2, y << 2, z << 2);
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
        return session.area.minY;
    }

    @Override
    public int getHeight() {
        return session.area.maxY - session.area.minY;
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

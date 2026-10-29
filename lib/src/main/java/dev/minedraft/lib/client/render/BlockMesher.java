package dev.minedraft.lib.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.Iterator;

/**
 * 블록들을 GPU 정점 버퍼로 굽는다 (편집창 섹션, 유령 블록, 썸네일 공용).
 * 정점 좌표는 원점(ox, oy, oz) 기준 상대 좌표다.
 */
public final class BlockMesher {
    public static final int SOLID = 0, CUTOUT_MIPPED = 1, CUTOUT = 2, TRANSLUCENT = 3, LAYER_COUNT = 4;

    private final ByteBufferBuilder[] memory = new ByteBufferBuilder[LAYER_COUNT];
    private final BufferBuilder[] builders = new BufferBuilder[LAYER_COUNT];
    private final ShiftAlphaConsumer[] wrappers = new ShiftAlphaConsumer[LAYER_COUNT];
    private final ShiftAlphaConsumer fluidWrapper = new ShiftAlphaConsumer();
    private final RandomSource random = RandomSource.create();
    private final PoseStack pose = new PoseStack();

    public BlockMesher() {
        for (int i = 0; i < LAYER_COUNT; i++) wrappers[i] = new ShiftAlphaConsumer();
    }

    public static RenderType layerType(int layer) {
        return switch (layer) {
            case SOLID -> RenderType.solid();
            case CUTOUT_MIPPED -> RenderType.cutoutMipped();
            case CUTOUT -> RenderType.cutout();
            default -> LibRenderTypes.TRANSLUCENT;
        };
    }

    private static int layerIndex(RenderType rt) {
        if (rt == RenderType.solid()) return SOLID;
        if (rt == RenderType.cutoutMipped()) return CUTOUT_MIPPED;
        if (rt == RenderType.cutout()) return CUTOUT;
        return TRANSLUCENT;
    }

    /** 구운 결과. 레이어별 정점 버퍼와, 블록 엔티티로 그려야 하는 블록 위치. */
    public static final class Mesh implements AutoCloseable {
        public final VertexBuffer[] buffers = new VertexBuffer[LAYER_COUNT];
        public long[] dynamic = new long[0];

        public boolean hasLayer(int layer) {
            return buffers[layer] != null;
        }

        public boolean isEmpty() {
            for (VertexBuffer b : buffers) if (b != null) return false;
            return dynamic.length == 0;
        }

        @Override
        public void close() {
            for (int i = 0; i < buffers.length; i++) {
                if (buffers[i] != null) {
                    buffers[i].close();
                    buffers[i] = null;
                }
            }
        }
    }

    private BufferBuilder builder(int layer) {
        if (builders[layer] == null) {
            if (memory[layer] == null) memory[layer] = new ByteBufferBuilder(1 << 18);
            builders[layer] = new BufferBuilder(memory[layer], VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        }
        return builders[layer];
    }

    /**
     * @param alpha          1 = 보통, 그보다 작으면 반투명 유령
     * @param allTranslucent 모든 면을 반투명 레이어로 (유령 블록)
     */
    public Mesh build(BlockAndTintGetter level, Iterator<BlockPos> positions, int ox, int oy, int oz,
                      float alpha, boolean allTranslucent) {
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        ModelBlockRenderer renderer = dispatcher.getModelRenderer();
        LongArrayList dynamic = new LongArrayList();
        ModelBlockRenderer.enableCaching();
        try {
            while (positions.hasNext()) {
                BlockPos pos = positions.next();
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) continue;
                try {
                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        int layer = allTranslucent ? TRANSLUCENT : layerIndex(ItemBlockRenderTypes.getRenderLayer(fluid));
                        fluidWrapper.set(builder(layer), (pos.getX() & ~15) - ox, (pos.getY() & ~15) - oy,
                                (pos.getZ() & ~15) - oz, alpha);
                        dispatcher.renderLiquid(pos, level, fluidWrapper, state, fluid);
                    }
                    RenderShape shape = state.getRenderShape();
                    if (shape == RenderShape.ENTITYBLOCK_ANIMATED) {
                        dynamic.add(pos.asLong());
                        continue;
                    }
                    if (shape != RenderShape.MODEL) continue;
                    BakedModel model = dispatcher.getBlockModel(state);
                    long seed = state.getSeed(pos);
                    ModelData data = model.getModelData(level, pos, state, level.getModelData(pos));
                    random.setSeed(seed);
                    for (RenderType rt : model.getRenderTypes(state, random, data)) {
                        int layer = allTranslucent ? TRANSLUCENT : layerIndex(rt);
                        VertexConsumer consumer = alpha < 1.0F
                                ? wrappers[layer].set(builder(layer), 0, 0, 0, alpha) : builder(layer);
                        pose.pushPose();
                        pose.translate(pos.getX() - ox, pos.getY() - oy, pos.getZ() - oz);
                        renderer.tesselateBlock(level, model, state, pos, pose, consumer, true, random, seed,
                                OverlayTexture.NO_OVERLAY, data, rt);
                        pose.popPose();
                    }
                } catch (Exception e) {
                    // 모드 블록 하나가 실패해도 나머지는 그린다
                }
            }
        } finally {
            ModelBlockRenderer.clearCache();
        }
        Mesh mesh = new Mesh();
        for (int i = 0; i < LAYER_COUNT; i++) {
            BufferBuilder b = builders[i];
            builders[i] = null;
            if (b == null) continue;
            MeshData data = b.build();
            if (data == null) continue;
            VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
            vb.bind();
            vb.upload(data);
            VertexBuffer.unbind();
            mesh.buffers[i] = vb;
        }
        mesh.dynamic = dynamic.toLongArray();
        return mesh;
    }

    public void close() {
        for (int i = 0; i < LAYER_COUNT; i++) {
            if (memory[i] != null) {
                memory[i].close();
                memory[i] = null;
            }
        }
    }
}

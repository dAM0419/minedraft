package dev.designandbuild.client.editor;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.designandbuild.client.ClientConfig;
import dev.minedraft.lib.client.render.LibRenderTypes;
import dev.minedraft.lib.client.render.BlockMesher;
import dev.minedraft.lib.client.render.EdgeMesher;
import dev.minedraft.lib.client.render.MeshDraw;
import dev.minedraft.lib.util.Box;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.IntPredicate;

/**
 * 편집창 3D 화면. 16×16×16 섹션마다 정점 버퍼를 구워 두고, 바뀐 섹션만 프레임 시간 예산 안에서 다시 굽는다.
 *
 * 층 자르기: 섹션마다 (자르기와 무관 / 전부 잘림 / 경계) 로 나눠,
 * 경계 섹션만 "잘린 아래"(lower, 잘린 블록을 공기로 보고 단면을 그림)와 "잘린 위"(upper) 를 따로 굽는다.
 */
public final class EditorRenderer implements AutoCloseable {
    public enum Pass { FULL, LOWER, UPPER }

    private enum CutClass { UNCUT, FULL_CUT, MIXED }

    private static final class Entry {
        final long key;
        BlockMesher.Mesh full;
        BlockMesher.Mesh lower;
        BlockMesher.Mesh upper;
        /** 블록 테두리 선 (없으면 null). */
        VertexBuffer fullEdges;
        VertexBuffer lowerEdges;
        VertexBuffer upperEdges;
        int cutStamp = -1;
        /** 단면 분류와 그것을 정한 단면 설정 번호 (그릴 때마다 다시 따지지 않게). */
        CutClass cls = CutClass.UNCUT;
        int clsStamp = Integer.MIN_VALUE;

        Entry(long key) {
            this.key = key;
        }

        void closeCut() {
            if (lower != null) lower.close();
            if (upper != null) upper.close();
            if (lowerEdges != null) lowerEdges.close();
            if (upperEdges != null) upperEdges.close();
            lower = upper = null;
            lowerEdges = upperEdges = null;
            cutStamp = -1;
        }

        void closeFull() {
            if (full != null) full.close();
            if (fullEdges != null) fullEdges.close();
            full = null;
            fullEdges = null;
        }

        void close() {
            closeFull();
            closeCut();
        }
    }

    private final EditorSession s;
    private final BlockMesher mesher = new BlockMesher();
    private final EdgeMesher edger = new EdgeMesher();
    private final Long2ObjectOpenHashMap<Entry> entries = new Long2ObjectOpenHashMap<>();
    private final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet();
    private int built, total;

    public EditorRenderer(EditorSession session) {
        this.s = session;
    }

    // ------------------------------------------------------------------
    // 무효화
    // ------------------------------------------------------------------

    public void markAll() {
        List<long[]> keys = new ArrayList<>();
        int minSec = SectionPos.blockToSectionCoord(s.renderMinY);
        int maxSec = SectionPos.blockToSectionCoord(s.model.maxY - 1);
        LongIterator it = s.model.chunks().iterator();
        while (it.hasNext()) {
            long c = it.nextLong();
            if (!s.snapshot.has(c)) continue;
            int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
            for (int sy = minSec; sy <= maxSec; sy++) {
                double d = Math.abs((cx * 16 + 8) - s.camera.tx) + Math.abs((cz * 16 + 8) - s.camera.tz)
                        + Math.abs((sy * 16 + 8) - s.camera.ty) * 0.5;
                keys.add(new long[]{SectionPos.asLong(cx, sy, cz), (long) d});
            }
        }
        keys.sort((a, b) -> Long.compare(a[1], b[1]));
        for (long[] k : keys) dirty.add(k[0]);
        // 영역 밖이 된 섹션은 버린다
        LongArrayList gone = new LongArrayList();
        for (long key : entries.keySet()) {
            if (!s.model.hasChunk(SectionPos.x(key), SectionPos.z(key)) || SectionPos.y(key) < minSec) gone.add(key);
        }
        for (long key : gone) {
            Entry e = entries.remove(key);
            if (e != null) e.close();
        }
        total = keys.size();
        built = 0;
    }

    /** 블록 하나가 바뀜: 그 섹션과 (경계면) 이웃 섹션. */
    public void markBlock(long pos) {
        int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        mark(sx, sy, sz);
        int lx = x & 15, ly = y & 15, lz = z & 15;
        if (lx == 0) mark(sx - 1, sy, sz);
        if (lx == 15) mark(sx + 1, sy, sz);
        if (ly == 0) mark(sx, sy - 1, sz);
        if (ly == 15) mark(sx, sy + 1, sz);
        if (lz == 0) mark(sx, sy, sz - 1);
        if (lz == 15) mark(sx, sy, sz + 1);
    }

    private void mark(int sx, int sy, int sz) {
        if (!s.model.hasChunk(sx, sz)) return;
        if (sy < SectionPos.blockToSectionCoord(s.renderMinY) || sy > SectionPos.blockToSectionCoord(s.model.maxY - 1)) return;
        dirty.add(SectionPos.asLong(sx, sy, sz));
    }

    /** 청크가 추가·제거됨: 그 청크와 이웃 청크 섹션 전부. */
    public void markChunkArea(long chunk) {
        int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
        int minSec = SectionPos.blockToSectionCoord(s.renderMinY);
        int maxSec = SectionPos.blockToSectionCoord(s.model.maxY - 1);
        int[][] offs = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : offs) {
            int x = cx + o[0], z = cz + o[1];
            for (int sy = minSec; sy <= maxSec; sy++) {
                long key = SectionPos.asLong(x, sy, z);
                if (s.model.hasChunk(x, z) && s.snapshot.has(ChunkPos.asLong(x, z))) {
                    dirty.add(key);
                } else {
                    Entry e = entries.remove(key);
                    if (e != null) e.close();
                    dirty.remove(key);
                }
            }
        }
    }

    public boolean isLoading() {
        return built < total && !dirty.isEmpty();
    }

    public float progress() {
        return total == 0 ? 1 : Math.min(1, built / (float) total);
    }

    // ------------------------------------------------------------------
    // 굽기
    // ------------------------------------------------------------------

    /** 프레임마다: 바뀐 섹션을 시간 예산 안에서 다시 굽는다. */
    public void update(long budgetNanos) {
        long deadline = System.nanoTime() + budgetNanos;
        while (!dirty.isEmpty() && System.nanoTime() < deadline) {
            long key = dirty.removeFirstLong();
            Entry e = entries.get(key);
            if (e == null) {
                e = new Entry(key);
                entries.put(key, e);
            }
            e.closeFull();
            e.clsStamp = Integer.MIN_VALUE; // 단면 예외 칸이 생겼을 수 있다: 분류를 다시
            int ox = SectionPos.x(key) << 4, oy = SectionPos.y(key) << 4, oz = SectionPos.z(key) << 4;
            e.full = mesher.build(s.world, new SectionIter(ox, oy, oz, null), ox, oy, oz, 1.0F, false);
            e.fullEdges = edger.build(s.world, null, this::sectionExists, ox, oy, oz);
            e.closeCut();
            built++;
        }
        if (!s.cutActive) return;
        // 경계 섹션의 잘린 버전
        for (Entry e : entries.values()) {
            if (System.nanoTime() >= deadline) break;
            if (e.cutStamp == s.cutStamp) continue;
            if (classify(e) != CutClass.MIXED) {
                e.closeCut();
                e.cutStamp = s.cutStamp;
                continue;
            }
            e.closeCut();
            int ox = SectionPos.x(e.key) << 4, oy = SectionPos.y(e.key) << 4, oz = SectionPos.z(e.key) << 4;
            IntPredicate below = i -> !s.isCut(ox + (i & 15), oy + (i >> 8), oz + ((i >> 4) & 15));
            e.lower = mesher.build(s.cutWorld, new SectionIter(ox, oy, oz, below), ox, oy, oz, 1.0F, false);
            e.lowerEdges = edger.build(s.cutWorld, null, this::sectionExists, ox, oy, oz);
            if (s.bubble) {
                IntPredicate above = i -> s.isCut(ox + (i & 15), oy + (i >> 8), oz + ((i >> 4) & 15));
                e.upper = mesher.build(s.world, new SectionIter(ox, oy, oz, above), ox, oy, oz, 1.0F, false);
                e.upperEdges = edger.build(s.world, s::isCut, this::sectionExists, ox, oy, oz);
            }
            e.cutStamp = s.cutStamp;
        }
    }

    private List<Box> regionsCache = List.of();
    private int regionsStamp = -1;

    private CutClass classify(Entry e) {
        if (!s.cutActive) return CutClass.UNCUT;
        if (e.clsStamp != s.cutStamp) {
            e.cls = classify(e.key);
            e.clsStamp = s.cutStamp;
        }
        return e.cls;
    }

    private CutClass classify(long key) {
        if (regionsStamp != s.cutStamp) {
            regionsCache = s.cutRegions();
            regionsStamp = s.cutStamp;
        }
        if (regionsCache.isEmpty()) return CutClass.UNCUT;
        int ox = SectionPos.x(key) << 4, oy = SectionPos.y(key) << 4, oz = SectionPos.z(key) << 4;
        Box sec = new Box(ox, oy, oz, ox + 15, oy + 15, oz + 15);
        Box near = sec.inflate(1, 1);
        boolean touches = false;
        for (Box r : regionsCache) {
            // 통째로 잘린 구역이라도 단면 예외 칸이 있으면 경계 구역처럼 그 칸만 따로 굽는다
            if (r.contains(ox, oy, oz) && r.contains(ox + 15, oy + 15, oz + 15)) {
                return s.hasCutExempt(key) ? CutClass.MIXED : CutClass.FULL_CUT;
            }
            if (near.intersects(r)) touches = true;
        }
        return touches ? CutClass.MIXED : CutClass.UNCUT;
    }

    // ------------------------------------------------------------------
    // 그리기
    // ------------------------------------------------------------------

    /**
     * FULL: 자르기 없이 전부. LOWER: 잘린 아래 (단면 포함). UPPER: 잘린 위만 (버블 바깥용).
     */
    public void draw(Pass pass, Matrix4f rotation, Matrix4f projection) {
        List<Entry> visible = new ArrayList<>();
        List<BlockMesher.Mesh> meshes = new ArrayList<>();
        Matrix4f pv = new Matrix4f(projection).mul(rotation);
        Vector4f v = new Vector4f();
        float margin = 16.0F * s.camera.scale / Math.max(1, Math.min(s.camera.width(), s.camera.height())) * 2.2F;
        for (Long2ObjectMap.Entry<Entry> me : entries.long2ObjectEntrySet()) {
            Entry e = me.getValue();
            BlockMesher.Mesh mesh = pick(e, pass);
            if (mesh == null || mesh.isEmpty()) continue;
            long key = e.key;
            float cx = (float) ((SectionPos.x(key) << 4) + 8 - s.camera.tx);
            float cy = (float) ((SectionPos.y(key) << 4) + 8 - s.camera.ty);
            float cz = (float) ((SectionPos.z(key) << 4) + 8 - s.camera.tz);
            pv.transform(v.set(cx, cy, cz, 1.0F));
            if (Math.abs(v.x) > 1 + margin || Math.abs(v.y) > 1 + margin) continue;
            visible.add(e);
            meshes.add(mesh);
        }
        for (int layer = 0; layer < BlockMesher.LAYER_COUNT; layer++) {
            if (layer == BlockMesher.TRANSLUCENT) sortFarToNear(visible, meshes, rotation);
            boolean begun = false;
            for (int i = 0; i < visible.size(); i++) {
                BlockMesher.Mesh mesh = meshes.get(i);
                if (!mesh.hasLayer(layer)) continue;
                if (!begun) {
                    MeshDraw.beginLayer(BlockMesher.layerType(layer), true);
                    begun = true;
                }
                long key = visible.get(i).key;
                Matrix4f mv = scratch.set(rotation).translate(
                        (float) ((SectionPos.x(key) << 4) - s.camera.tx),
                        (float) ((SectionPos.y(key) << 4) - s.camera.ty),
                        (float) ((SectionPos.z(key) << 4) - s.camera.tz));
                MeshDraw.draw(mesh.buffers[layer], mv, projection);
            }
            if (begun) MeshDraw.endLayer(BlockMesher.layerType(layer));
        }
        if (ClientConfig.get().blockOutlines) drawEdges(visible, pass, rotation, projection);
        drawDynamic(meshes, pass);
    }

    /** 섹션마다 만드는 모델뷰 행렬 (그리기 호출이 곧바로 셰이더에 넘기므로 하나를 돌려 쓴다). */
    private final Matrix4f scratch = new Matrix4f();

    /** 바닐라 선 셰이더가 뷰 좌표를 1/256 줄이는 것을 되돌린다 (원근 화면용 보정이라 직교 화면에선 선이 어긋난다). */
    private static final float VIEW_SHRINK = 1.0F - 1.0F / 256.0F;

    /**
     * 편집창 선 그리기용 뷰 행렬: 선 셰이더의 축소를 되돌리고, 화면 쪽으로 살짝 당겨 면에 묻히지 않게 한다.
     * 직교 투영이라 당겨도 화면 위치는 그대로이고 깊이만 바뀐다. 당기는 양은 약 1.5 화면 픽셀어치.
     */
    public static Matrix4f lineView(Matrix4f rotation, float realScale) {
        float bias = 2.5F / Math.max(1.0F, realScale);
        return new Matrix4f().scaling(1.0F / VIEW_SHRINK).translate(0.0F, 0.0F, bias).mul(rotation);
    }

    /** 블록 테두리: 옅은 회색. 많이 축소하면 선이 뭉쳐 화면을 덮으니 서서히 옅어진다. */
    private void drawEdges(List<Entry> visible, Pass pass, Matrix4f rotation, Matrix4f projection) {
        float px = s.camera.realScale();
        float alpha = 0.38F * Mth.clamp((px - 6.0F) / 12.0F, 0.0F, 1.0F);
        if (alpha <= 0.01F) return;
        Matrix4f view = lineView(rotation, px);
        boolean begun = false;
        for (Entry e : visible) {
            VertexBuffer vb = pickEdges(e, pass);
            if (vb == null) continue;
            if (!begun) {
                LibRenderTypes.OUTLINE_LINES.setupRenderState();
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
                begun = true;
            }
            long key = e.key;
            Matrix4f mv = scratch.set(view).translate(
                    (float) ((SectionPos.x(key) << 4) - s.camera.tx),
                    (float) ((SectionPos.y(key) << 4) - s.camera.ty),
                    (float) ((SectionPos.z(key) << 4) - s.camera.tz));
            MeshDraw.draw(vb, mv, projection);
        }
        if (begun) {
            VertexBuffer.unbind();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            LibRenderTypes.OUTLINE_LINES.clearRenderState();
        }
    }

    /** 테두리 선의 영역 가장자리 주인 판정용: 이 섹션이 그려지는 범위 안인가. */
    private boolean sectionExists(int sx, int sy, int sz) {
        return s.model.hasChunk(sx, sz) && sy >= SectionPos.blockToSectionCoord(s.renderMinY)
                && sy <= SectionPos.blockToSectionCoord(s.model.maxY - 1);
    }

    private VertexBuffer pickEdges(Entry e, Pass pass) {
        if (pass == Pass.FULL || !s.cutActive) return pass == Pass.UPPER ? null : e.fullEdges;
        CutClass c = classify(e);
        return switch (pass) {
            case LOWER -> c == CutClass.UNCUT ? e.fullEdges : c == CutClass.FULL_CUT ? null
                    : (e.lower != null ? e.lowerEdges : e.fullEdges);
            case UPPER -> c == CutClass.UNCUT ? null : c == CutClass.FULL_CUT ? e.fullEdges : e.upperEdges;
            default -> e.fullEdges;
        };
    }

    private BlockMesher.Mesh pick(Entry e, Pass pass) {
        if (pass == Pass.FULL || !s.cutActive) return pass == Pass.UPPER ? null : e.full;
        CutClass c = classify(e);
        return switch (pass) {
            case LOWER -> c == CutClass.UNCUT ? e.full : c == CutClass.FULL_CUT ? null : (e.lower != null ? e.lower : e.full);
            case UPPER -> c == CutClass.UNCUT ? null : c == CutClass.FULL_CUT ? e.full : e.upper;
            default -> e.full;
        };
    }

    private void sortFarToNear(List<Entry> visible, List<BlockMesher.Mesh> meshes, Matrix4f rotation) {
        int n = visible.size();
        Integer[] idx = new Integer[n];
        float[] depth = new float[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
            long key = visible.get(i).key;
            Vector4f v = rotation.transform(new Vector4f(
                    (float) ((SectionPos.x(key) << 4) + 8 - s.camera.tx),
                    (float) ((SectionPos.y(key) << 4) + 8 - s.camera.ty),
                    (float) ((SectionPos.z(key) << 4) + 8 - s.camera.tz), 1.0F));
            depth[i] = v.z;
        }
        java.util.Arrays.sort(idx, (a, b) -> Float.compare(depth[a], depth[b]));
        List<Entry> v2 = new ArrayList<>(n);
        List<BlockMesher.Mesh> m2 = new ArrayList<>(n);
        for (int i : idx) {
            v2.add(visible.get(i));
            m2.add(meshes.get(i));
        }
        visible.clear();
        visible.addAll(v2);
        meshes.clear();
        meshes.addAll(m2);
    }

    /** 상자·표지판 등 블록 엔티티 모양 블록은 아이템 모델로 그때그때 그린다. */
    private void drawDynamic(List<BlockMesher.Mesh> meshes, Pass pass) {
        int count = 0;
        for (BlockMesher.Mesh m : meshes) count += m.dynamic.length;
        if (count == 0 || count > 3000) return;
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PoseStack pose = new PoseStack();
        for (BlockMesher.Mesh m : meshes) {
            for (long p : m.dynamic) {
                int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
                if (pass == Pass.LOWER && s.cutActive && s.isCut(x, y, z)) continue;
                BlockState state = s.visibleAt(x, y, z, false);
                pose.pushPose();
                pose.translate(x - s.camera.tx, y - s.camera.ty, z - s.camera.tz);
                if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                    pose.translate(0.5, 0.5, 0.5);
                    pose.mulPose(Axis.YP.rotationDegrees(-state.getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot()));
                    pose.translate(-0.5, -0.5, -0.5);
                }
                try {
                    mc.getBlockRenderer().renderSingleBlock(state, pose, buffers, LightTexture.FULL_BRIGHT,
                            OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
                } catch (Exception ignored) {
                }
                pose.popPose();
            }
        }
        buffers.endBatch();
        RenderSystem.enableDepthTest();
    }

    @Override
    public void close() {
        for (Entry e : entries.values()) e.close();
        entries.clear();
        dirty.clear();
        mesher.close();
        edger.close();
    }

    /** 섹션 안 칸을 차례로 (번호 i = y<<8 | z<<4 | x), 조건으로 거른다. */
    private static final class SectionIter implements Iterator<BlockPos> {
        private final int ox, oy, oz;
        private final IntPredicate filter;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private int next = 0;

        SectionIter(int ox, int oy, int oz, IntPredicate filter) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.filter = filter;
            advance();
        }

        private void advance() {
            if (filter == null) return;
            while (next < 4096 && !filter.test(next)) next++;
        }

        @Override
        public boolean hasNext() {
            return next < 4096;
        }

        @Override
        public BlockPos next() {
            if (next >= 4096) throw new NoSuchElementException();
            int i = next++;
            pos.set(ox + (i & 15), oy + (i >> 8), oz + ((i >> 4) & 15));
            advance();
            return pos;
        }
    }
}

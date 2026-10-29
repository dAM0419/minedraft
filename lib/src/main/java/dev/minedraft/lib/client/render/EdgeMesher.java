package dev.minedraft.lib.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Arrays;

/**
 * 편집창 블록 테두리 선을 섹션마다 굽는다. 눈밭·얼음처럼 무늬가 고른 곳에서도 칸이 세어지게.
 *
 * <ul>
 *   <li>꽉 찬 블록: 격자 모서리마다, 그 모서리를 둘러싼 네 칸이 "꽉 참/아님"으로 섞여 있으면 긋는다.
 *       (= 드러난 면의 경계. 같은 면에 이어진 블록 사이 경계도 포함.) 모서리마다 주인 섹션이 하나라 겹쳐 긋지 않는다.</li>
 *   <li>꽉 차지 않은 블록 (반블록·계단·눈 층·울타리 …): 바닐라 선택 테두리처럼 모양의 모서리를 긋는다.
 *       풀·꽃·횃불처럼 부딪히지 않는 장식은 뺀다 (눈 층은 넣는다).</li>
 * </ul>
 * 정점 좌표는 섹션 원점 기준. 색은 회색으로 굽고 진하기는 그릴 때 정한다.
 */
public final class EdgeMesher implements AutoCloseable {
    /** 칸 포함 여부 (전역 좌표). 잘린 위쪽만 그릴 때 등. */
    @FunctionalInterface
    public interface CellFilter {
        boolean test(int x, int y, int z);
    }

    /** 섹션이 그려지는가 (섹션 좌표). 영역 가장자리 모서리의 주인을 정할 때 쓴다. */
    @FunctionalInterface
    public interface SectionExists {
        boolean test(int sx, int sy, int sz);
    }

    private static final int SPAN = 18; // 섹션 ±1 칸
    private static final int COLOR = 0xFF8A8A8A;

    private final ByteBufferBuilder memory = new ByteBufferBuilder(1 << 16);
    /** 칸 상태: 0 = 아직 모름, 1 = 꽉 찬 블록, 2 = 아님. x, y, z 모두 -1..16. */
    private final byte[] cells = new byte[SPAN * SPAN * SPAN];
    private final BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
    private BlockGetter level;
    private CellFilter filter;
    private int ox, oy, oz;

    /** @return 선이 하나도 없으면 null */
    public VertexBuffer build(BlockGetter level, CellFilter filter, SectionExists exists, int ox, int oy, int oz) {
        this.level = level;
        this.filter = filter;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        Arrays.fill(cells, (byte) 0);
        BufferBuilder b = new BufferBuilder(memory, VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        int sx = ox >> 4, sy = oy >> 4, sz = oz >> 4;

        // 1. 꽉 찬 블록의 격자 모서리
        for (int py = 0; py <= 16; py++) {
            for (int pz = 0; pz <= 16; pz++) {
                for (int px = 0; px <= 16; px++) {
                    if ((px == 16 || py == 16 || pz == 16) && !owns(exists, sx, sy, sz, px, py, pz)) continue;
                    // X 방향 모서리: 둘레 칸 (px, py-1|py, pz-1|pz)
                    if (mixed(solid(px, py - 1, pz - 1), solid(px, py, pz - 1), solid(px, py - 1, pz), solid(px, py, pz))) {
                        line(b, px, py, pz, px + 1, py, pz);
                    }
                    // Y 방향: 둘레 칸 (px-1|px, py, pz-1|pz)
                    if (mixed(solid(px - 1, py, pz - 1), solid(px, py, pz - 1), solid(px - 1, py, pz), solid(px, py, pz))) {
                        line(b, px, py, pz, px, py + 1, pz);
                    }
                    // Z 방향: 둘레 칸 (px-1|px, py-1|py, pz)
                    if (mixed(solid(px - 1, py - 1, pz), solid(px, py - 1, pz), solid(px - 1, py, pz), solid(px, py, pz))) {
                        line(b, px, py, pz, px, py, pz + 1);
                    }
                }
            }
        }

        // 2. 꽉 차지 않은 블록의 모양 테두리
        for (int i = 0; i < 4096; i++) {
            int lx = i & 15, ly = i >> 8, lz = (i >> 4) & 15;
            if (solid(lx, ly, lz)) continue;
            int x = ox + lx, y = oy + ly, z = oz + lz;
            if (filter != null && !filter.test(x, y, z)) continue;
            mp.set(x, y, z);
            BlockState st = level.getBlockState(mp);
            if (st.isAir() || st.getRenderShape() == RenderShape.INVISIBLE) continue;
            try {
                if (st.getCollisionShape(level, mp).isEmpty() && !st.is(Blocks.SNOW)) continue;
                VoxelShape shape = st.getShape(level, mp);
                if (shape.isEmpty()) continue;
                final float fx = lx, fy = ly, fz = lz;
                shape.forAllEdges((x0, y0, z0, x1, y1, z1) -> line(b,
                        fx + (float) x0, fy + (float) y0, fz + (float) z0,
                        fx + (float) x1, fy + (float) y1, fz + (float) z1));
            } catch (Exception e) {
                // 모드 블록 모양 계산 실패: 테두리만 생략
            }
        }

        this.level = null;
        this.filter = null;
        MeshData data = b.build();
        if (data == null) return null;
        VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vb.bind();
        vb.upload(data);
        VertexBuffer.unbind();
        return vb;
    }

    /**
     * 섹션 끝(16) 격자점의 주인: 그 점이 속한 섹션이 그려지면 그 섹션, 아니면 이웃 중 정해진 순서로 처음 있는 섹션.
     * 영역 가장자리 모서리도 빠짐없이, 한 번만 긋기 위해.
     */
    private static boolean owns(SectionExists exists, int sx, int sy, int sz, int px, int py, int pz) {
        int cx = sx + (px >> 4), cy = sy + (py >> 4), cz = sz + (pz >> 4);
        for (int dy = 0; dy <= (py >> 4); dy++) {
            for (int dz = 0; dz <= (pz >> 4); dz++) {
                for (int dx = 0; dx <= (px >> 4); dx++) {
                    int tx = cx - dx, ty = cy - dy, tz = cz - dz;
                    if (tx == sx && ty == sy && tz == sz) return true;
                    if (exists.test(tx, ty, tz)) return false;
                }
            }
        }
        return true;
    }

    private static boolean mixed(boolean a, boolean b, boolean c, boolean d) {
        return !(a == b && b == c && c == d);
    }

    private boolean solid(int lx, int ly, int lz) {
        int i = ((ly + 1) * SPAN + (lz + 1)) * SPAN + (lx + 1);
        byte v = cells[i];
        if (v == 0) {
            v = isFullCube(ox + lx, oy + ly, oz + lz) ? (byte) 1 : (byte) 2;
            cells[i] = v;
        }
        return v == 1;
    }

    private boolean isFullCube(int x, int y, int z) {
        if (filter != null && !filter.test(x, y, z)) return false;
        mp.set(x, y, z);
        BlockState st = level.getBlockState(mp);
        if (st.isAir() || st.getRenderShape() == RenderShape.INVISIBLE) return false;
        try {
            VoxelShape shape = st.getShape(level, mp);
            return shape == Shapes.block() || Block.isShapeFullBlock(shape);
        } catch (Exception e) {
            return false;
        }
    }

    private static void line(BufferBuilder b, float x0, float y0, float z0, float x1, float y1, float z1) {
        float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len <= 0) return;
        dx /= len;
        dy /= len;
        dz /= len;
        b.addVertex(x0, y0, z0).setColor(COLOR).setNormal(dx, dy, dz);
        b.addVertex(x1, y1, z1).setColor(COLOR).setNormal(dx, dy, dz);
    }

    @Override
    public void close() {
        memory.close();
    }
}

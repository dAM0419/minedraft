package dev.designandbuild.client.editor;

import dev.designandbuild.client.ClientConfig;
import dev.minedraft.lib.util.Box;
import dev.designandbuild.core.DesignTag;
import dev.designandbuild.core.Requests;
import dev.designandbuild.core.Resolved;
import dev.designandbuild.core.TemplateData;
import dev.designandbuild.core.TerrainCell;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

/** 직접조작·배치 계산. 화면 그리기와 적용이 같은 계산을 쓴다. */
public final class ToolLogic {
    private ToolLogic() {}

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    public static final int GHOST_LIMIT = 4096;

    // ------------------------------------------------------------------
    // 직접조작: 범위
    // ------------------------------------------------------------------

    /** 커서 블록·면·중심 모드·크기로 정한 조작 범위. */
    public static Box region(EditorSession s, EditorSession.Hit hit) {
        BlockPos p = hit.pos();
        Direction f = hit.face();
        int[] size = {s.sizeX, s.sizeY, s.sizeZ};
        int[] pc = {p.getX(), p.getY(), p.getZ()};
        int[] min = new int[3];
        for (int a = 0; a < 3; a++) {
            Direction.Axis axis = Direction.Axis.values()[a];
            int n = Math.max(1, size[a]);
            int center = (n - 1) / 2;
            if (s.anchor == EditorSession.Anchor.CENTER || f.getAxis() != axis) {
                min[a] = pc[a] - center;
            } else {
                boolean positive = f.getAxisDirection() == Direction.AxisDirection.POSITIVE;
                if (s.anchor == EditorSession.Anchor.OUTSIDE) {
                    min[a] = positive ? pc[a] + 1 : pc[a] - n;
                } else {
                    min[a] = positive ? pc[a] - n + 1 : pc[a];
                }
            }
        }
        // 반블록 빈 절반 쪽 면을 같은 반블록으로 가리켰다: 그 칸 자체가 대상 (바닐라처럼 겹반블록)
        if (s.anchor == EditorSession.Anchor.OUTSIDE && size[0] == 1 && size[1] == 1 && size[2] == 1
                && s.op != EditorSession.Op.ERASE && slabFacesEmptyHalf(s, p, f)) {
            return new Box(pc[0], pc[1], pc[2], pc[0], pc[1], pc[2]);
        }
        return new Box(min[0], min[1], min[2], min[0] + size[0] - 1, min[1] + size[1] - 1, min[2] + size[2] - 1);
    }

    /** 가리킨 블록이 홑 반블록이고, 가리킨 면이 빈 절반 쪽이며, 재료가 같은 반블록인가. */
    private static boolean slabFacesEmptyHalf(EditorSession s, BlockPos p, Direction f) {
        BlockState cur = s.blockAt(p.getX(), p.getY(), p.getZ(), false);
        if (!cur.hasProperty(BlockStateProperties.SLAB_TYPE)) return false;
        SlabType t = cur.getValue(BlockStateProperties.SLAB_TYPE);
        boolean inward = (t == SlabType.BOTTOM && f == Direction.UP) || (t == SlabType.TOP && f == Direction.DOWN);
        if (!inward) return false;
        return s.materials[0].getItem() instanceof net.minecraft.world.item.BlockItem bi && bi.getBlock() == cur.getBlock();
    }

    /** 홑 반블록 칸에 같은 반블록을 놓으면 겹반블록. */
    @Nullable
    static BlockState slabMerge(BlockState cur, BlockState placed) {
        if (cur.getBlock() != placed.getBlock() || !cur.hasProperty(BlockStateProperties.SLAB_TYPE)) return null;
        if (cur.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) return null;
        BlockState d = placed.setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
        if (d.hasProperty(BlockStateProperties.WATERLOGGED)) d = d.setValue(BlockStateProperties.WATERLOGGED, false);
        return d;
    }

    public static boolean inShape(EditorSession s, Box b, int x, int y, int z) {
        if (s.shape == EditorSession.Shape.BOX) return true;
        double cx = (b.minX() + b.maxX() + 1) / 2.0, cy = (b.minY() + b.maxY() + 1) / 2.0, cz = (b.minZ() + b.maxZ() + 1) / 2.0;
        double rx = b.sizeX() / 2.0, ry = b.sizeY() / 2.0, rz = b.sizeZ() / 2.0;
        double dx = (x + 0.5 - cx) / rx, dy = (y + 0.5 - cy) / ry, dz = (z + 0.5 - cz) / rz;
        if (s.shape == EditorSession.Shape.SPHERE) return dx * dx + dy * dy + dz * dz <= 1.0001;
        return switch (s.cylinderAxis) {
            case X -> dy * dy + dz * dz <= 1.0001;
            case Y -> dx * dx + dz * dz <= 1.0001;
            case Z -> dx * dx + dy * dy <= 1.0001;
        };
    }

    /** 마지막 targets 계산에서 빠진 칸: 다른 구조물 소속 / 채우기인데 이미 찬 칸. 커서 칩 안내용. */
    public static int skippedForeign, skippedOccupied;

    /** 범위 안, 영역 안의 칸 (조작 종류·보호 규칙 적용). */
    public static long[] targets(EditorSession s, Box b) {
        LongArrayList out = new LongArrayList();
        boolean structureLayer = structureLayer(s);
        skippedForeign = 0;
        skippedOccupied = 0;
        long volume = b.volume();
        if (volume > 4_000_000L) return new long[0];
        net.minecraft.world.level.block.Block slabMat = null;
        java.util.Set<net.minecraft.world.level.block.Block> replaceFilter = s.op == EditorSession.Op.REPLACE ? s.replaceFilter() : null;
        if (s.op == EditorSession.Op.FILL) {
            for (ItemStack m : s.materials) {
                if (m.getItem() instanceof net.minecraft.world.item.BlockItem bi
                        && bi.getBlock().defaultBlockState().hasProperty(BlockStateProperties.SLAB_TYPE)) {
                    slabMat = bi.getBlock();
                    break;
                }
            }
        }
        for (int y = b.minY(); y <= b.maxY(); y++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                for (int x = b.minX(); x <= b.maxX(); x++) {
                    if (!s.model.isInside(x, y, z) || !inShape(s, b, x, y, z)) continue;
                    long p = BlockPos.asLong(x, y, z);
                    if (!s.editable(p, structureLayer)) {
                        skippedForeign++;
                        continue;
                    }
                    BlockState cur = s.blockAt(x, y, z, false);
                    if (s.op == EditorSession.Op.FILL && !(cur.isAir() || cur.canBeReplaced())) {
                        // 홑 반블록의 빈 절반은 같은 반블록으로 채울 수 있다
                        boolean halfEmpty = slabMat != null && cur.getBlock() == slabMat
                                && cur.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE;
                        if (!halfEmpty) {
                            skippedOccupied++;
                            continue;
                        }
                    }
                    if (s.op == EditorSession.Op.REPLACE && !EditorSession.replaceable(cur, replaceFilter)) continue;
                    // 지형 지우기: 이미 비어 있고 설계도 없는 칸은 건너뛴다 (쓸모없는 철거 기록이 쌓이지 않게)
                    if (s.op == EditorSession.Op.ERASE && !structureLayer && cur.isAir() && s.model.terrainAt(p) == null) continue;
                    out.add(p);
                }
            }
        }
        return out.toLongArray();
    }

    /** 이 조작이 구조물에 쓰는가: 구조물이 선택돼 있으면 태그와 무관하게 그 구조물, 아니면 구조물 태그일 때 새 구조물. */
    public static boolean structureLayer(EditorSession s) {
        if (s.selected != 0) return true;
        return s.op != EditorSession.Op.ERASE && s.tag == DesignTag.STRUCTURE;
    }

    // ------------------------------------------------------------------
    // 직접조작: 재료
    // ------------------------------------------------------------------

    public static final class Materials {
        final BlockState[] states;
        final int[] weights;
        final int total;

        Materials(BlockState[] states, int[] weights) {
            this.states = states;
            this.weights = weights;
            int t = 0;
            for (int w : weights) t += w;
            this.total = t;
        }

        BlockState pick(Random rng) {
            if (states.length == 1 || total <= 0) return states[0];
            int r = rng.nextInt(total);
            for (int i = 0; i < states.length; i++) {
                r -= weights[i];
                if (r < 0) return states[i];
            }
            return states[states.length - 1];
        }
    }

    @Nullable
    public static Materials materials(EditorSession s) {
        java.util.List<BlockState> states = new java.util.ArrayList<>();
        java.util.List<Integer> weights = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            BlockState st = PlacementStates.stateFor(s.materials[i], s.lookH, s.lookV, s.face, s.upperHalf);
            if (st == null) continue;
            states.add(st);
            weights.add(Math.max(1, s.weights[i]));
        }
        if (states.isEmpty()) return null;
        int[] w = new int[weights.size()];
        for (int i = 0; i < w.length; i++) w[i] = weights.get(i);
        return new Materials(states.toArray(new BlockState[0]), w);
    }

    /** 미리보기·적용 공용: 칸 → 놓일 상태. random 은 미리보기에서 고정 씨앗. */
    public static Long2ObjectLinkedOpenHashMap<BlockState> compute(EditorSession s, long[] targets, @Nullable Materials mats,
                                                                    Random rng, boolean applyPercent) {
        Long2ObjectLinkedOpenHashMap<BlockState> cells = new Long2ObjectLinkedOpenHashMap<>();
        boolean erase = s.op == EditorSession.Op.ERASE;
        for (long p : targets) {
            if (!erase && applyPercent && s.percent < 100 && rng.nextInt(100) >= s.percent) continue;
            if (erase) {
                cells.put(p, AIR);
            } else if (mats != null) {
                BlockState cur = s.blockAt(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), false);
                BlockState placed = mats.pick(rng);
                BlockState merged = slabMerge(cur, placed);
                if (merged != null) {
                    cells.put(p, merged);
                } else if (s.op == EditorSession.Op.FILL && !(cur.isAir() || cur.canBeReplaced())) {
                    // 반블록 칸인데 이번에 뽑힌 재료가 다른 블록: 채우기는 건드리지 않는다
                    continue;
                } else {
                    cells.put(p, PlacementStates.waterlog(placed, cur));
                }
            }
        }
        if (!erase && !cells.isEmpty()) {
            PlacementStates.expandMultiBlocks(s, cells);
            PlacementStates.updateConnections(s, cells, structureLayer(s));
        }
        return cells;
    }

    /** 직접조작 적용. 너무 크면 false (한 번 더 눌러야 함은 화면이 처리). */
    public static void apply(EditorSession s, EditorSession.Hit hit, int group) {
        Box b = region(s, hit);
        long[] targets = targets(s, b);
        if (targets.length == 0) return;
        Materials mats = null;
        if (s.op != EditorSession.Op.ERASE) {
            mats = materials(s);
            if (mats == null) {
                s.toast(Component.translatable("designandbuild.msg.pick_material"));
                return;
            }
        }
        rememberTargets(targets);
        Long2ObjectLinkedOpenHashMap<BlockState> cells = compute(s, targets, mats, new Random(), true);
        if (cells.isEmpty()) return;
        send(s, cells, group);
    }

    /** 계산된 칸을 요청으로 보낸다. */
    public static void send(EditorSession s, Long2ObjectLinkedOpenHashMap<BlockState> cells, int group) {
        long[] pos = cells.keySet().toLongArray();
        BlockState[] states = new BlockState[pos.length];
        for (int i = 0; i < pos.length; i++) states[i] = cells.get(pos[i]);
        s.exemptFromCut(pos, states); // 단면을 켠 뒤 놓은 칸은 잘린 쪽이어도 보이게
        if (structureLayer(s)) {
            if (s.selected == 0) {
                if (s.isAwaitingCreate()) s.queueForCreated(group, pos, states);
                else s.sendCreating(new Requests.StructCells(group, 0, s.newStructureName(), pos, states));
            } else {
                s.send(new Requests.StructCells(group, s.selected, "", pos, states));
            }
        } else {
            DesignTag[] tags = new DesignTag[pos.length];
            for (int i = 0; i < pos.length; i++) {
                if (states[i].isAir()) {
                    tags[i] = DesignTag.NONE;
                } else {
                    TerrainCell existing = s.model.terrainAt(pos[i]);
                    boolean placed = s.op != EditorSession.Op.ERASE;
                    tags[i] = placed && existing != null && existing.isRealBlock() && !isTarget(s, pos[i])
                            ? existing.tag() : (s.tag == DesignTag.PATH ? DesignTag.PATH : DesignTag.FLOOR);
                }
            }
            s.send(new Requests.Terrain(group, pos, states, tags));
        }
    }

    /** 이번 조작 범위에 든 칸인가 (이웃 연결 갱신으로 딸려 온 칸과 구별). */
    private static boolean isTarget(EditorSession s, long pos) {
        return lastTargets != null && java.util.Arrays.binarySearch(lastTargets, pos) >= 0;
    }

    private static long[] lastTargets;

    /** 조작 범위 칸 목록을 기억해 둔다 (태그 판정용, 정렬됨). */
    public static void rememberTargets(long[] targets) {
        long[] copy = targets.clone();
        java.util.Arrays.sort(copy);
        lastTargets = copy;
    }

    // ------------------------------------------------------------------
    // 배치
    // ------------------------------------------------------------------

    /** 회전·반전한 조각 (상대 좌표). */
    public record Transformed(TemplateData template, int rotation, boolean mirror, int sx, int sy, int sz,
                              int[] xs, int[] ys, int[] zs, BlockState[] states, Direction mount) {}

    public static Transformed transform(TemplateData t, int rotation, boolean mirror) {
        int n = t.count();
        int[] xs = new int[n], ys = new int[n], zs = new int[n];
        BlockState[] states = new BlockState[n];
        int steps = Math.floorMod(rotation, 4);
        int fsx = steps % 2 == 0 ? t.sizeX : t.sizeZ;
        int fsz = steps % 2 == 0 ? t.sizeZ : t.sizeX;
        for (int i = 0; i < n; i++) {
            int x = t.xs[i], z = t.zs[i];
            int sx = t.sizeX, sz = t.sizeZ;
            BlockState st = t.states[i];
            if (mirror) {
                x = sx - 1 - x;
                st = st.mirror(Mirror.FRONT_BACK);
            }
            for (int k = 0; k < steps; k++) {
                int nx = sz - 1 - z;
                int nz = x;
                x = nx;
                z = nz;
                int tmp = sx;
                sx = sz;
                sz = tmp;
                st = st.rotate(Rotation.CLOCKWISE_90);
            }
            xs[i] = x;
            ys[i] = t.ys[i];
            zs[i] = z;
            states[i] = st;
        }
        Direction mount = t.mount;
        if (mirror) mount = Mirror.FRONT_BACK.mirror(mount);
        for (int k = 0; k < steps; k++) mount = Rotation.CLOCKWISE_90.rotate(mount);
        return new Transformed(t, steps, mirror, fsx, t.sizeY, fsz, xs, ys, zs, states, mount);
    }

    /**
     * @param kept 배치 방식이 설치가 아닐 때 실제로 쓰는 칸 번호 (조각의 공기 아닌 칸 중 조건에 맞는 것). 설치면 null = 전부.
     */
    public record Placement(Transformed shape, int ox, int oy, int oz, @Nullable String error,
                            long[] pos, BlockState[] states, long[] conflicts, long[] floating, int replaced,
                            @Nullable int[] kept) {
        public boolean valid() {
            return error == null;
        }
    }

    /**
     * 커서 면에 조각을 놓을 자리. 아랫면 설치는 윗면에만, 옆면 설치는 옆면에만 (회전 자동 고정),
     * 윗면 설치는 아랫면을 가리킬 수 없어 놓을 수 없다.
     */
    public static Placement placement(EditorSession s, TemplateData t, EditorSession.Hit hit) {
        java.util.Set<net.minecraft.world.level.block.Block> replaceFilter = s.placeOp == EditorSession.Op.REPLACE ? s.replaceFilter() : null;
        int rot = s.placeRotation;
        Direction face = hit.face();
        String error = null;
        if (t.mount == Direction.UP) {
            error = "designandbuild.msg.mount_up";
        } else if (t.mount.getAxis().isHorizontal()) {
            if (!face.getAxis().isHorizontal()) {
                error = "designandbuild.msg.mount_mismatch";
            } else {
                Direction base = s.placeMirror ? Mirror.FRONT_BACK.mirror(t.mount) : t.mount;
                for (int k = 0; k < 4; k++) {
                    Direction d = base;
                    for (int j = 0; j < k; j++) d = Rotation.CLOCKWISE_90.rotate(d);
                    if (d == face.getOpposite()) {
                        rot = k;
                        break;
                    }
                }
            }
        } else if (face != Direction.UP) {
            error = "designandbuild.msg.mount_mismatch";
        }
        Transformed tr = cachedTransform(t, rot, s.placeMirror);
        BlockPos p = hit.pos();
        int ox, oy, oz;
        if (tr.mount().getAxis().isHorizontal() && face.getAxis().isHorizontal()) {
            oy = p.getY() - (tr.sy() - 1) / 2;
            if (face.getAxis() == Direction.Axis.X) {
                int in = s.placeInto ? 1 : 0;
                ox = face == Direction.EAST ? p.getX() + 1 - in : p.getX() - tr.sx() + in;
                oz = p.getZ() - (tr.sz() - 1) / 2;
            } else {
                int in = s.placeInto ? 1 : 0;
                oz = face == Direction.SOUTH ? p.getZ() + 1 - in : p.getZ() - tr.sz() + in;
                ox = p.getX() - (tr.sx() - 1) / 2;
            }
        } else {
            ox = p.getX() - (tr.sx() - 1) / 2;
            oz = p.getZ() - (tr.sz() - 1) / 2;
            oy = p.getY() + (s.placeInto ? 0 : 1);
        }
        int n = tr.states().length;
        int replaced = 0;
        EditorSession.Op op = s.placeOp;
        boolean install = op == EditorSession.Op.INSTALL;
        // 설치가 아니면 조각을 "붓"처럼 쓴다: 조각의 공기 아닌 칸 자리에 채우기·대체·지우기
        it.unimi.dsi.fastutil.ints.IntArrayList kept = install ? null : new it.unimi.dsi.fastutil.ints.IntArrayList();
        boolean eraseIntoStructure = s.selected != 0;
        long[] pos = new long[n];
        LongArrayList conflicts = new LongArrayList();
        LongArrayList floating = new LongArrayList();
        boolean outside = false;
        for (int i = 0; i < n; i++) {
            int x = ox + tr.xs()[i], y = oy + tr.ys()[i], z = oz + tr.zs()[i];
            pos[i] = BlockPos.asLong(x, y, z);
            if (!s.model.isInside(x, y, z)) {
                outside = true;
                continue;
            }
            BlockState st = tr.states()[i];
            if (st.isAir()) continue;
            if (!install) {
                BlockState cur = s.blockAt(x, y, z, false);
                boolean ok = switch (op) {
                    case FILL -> (cur.isAir() || cur.canBeReplaced()) && s.editable(pos[i], true);
                    case REPLACE -> EditorSession.replaceable(cur, replaceFilter) && s.editable(pos[i], true);
                    default -> !cur.isAir() && s.editable(pos[i], eraseIntoStructure);   // 지우기
                };
                if (!ok) continue;
                kept.add(i);
                if (op == EditorSession.Op.ERASE) continue;   // 지우기는 겹침·받침 표시가 필요 없다
            }
            Resolved r = s.model.resolved(pos[i]);
            if (r != null && r.owner() != 0 && !r.isAir() && r.state() != st) conflicts.add(pos[i]);
            // 겹쳐 놓기: 기존 바닥·길 설계 칸을 이 구조물이 대체한다
            if (r != null && r.owner() == 0 && !r.isAir() && r.state() != st) replaced++;
            if (tr.ys()[i] == 0 && tr.mount() == Direction.DOWN) {
                BlockState below = s.blockAt(x, y - 1, z, false);
                if (below.isAir() || below.canBeReplaced()) floating.add(BlockPos.asLong(x, y - 1, z));
            }
        }
        if (error == null && outside) error = "designandbuild.msg.outside_area";
        if (error == null && kept != null && kept.isEmpty()) error = "designandbuild.msg.place_nothing";
        return new Placement(tr, ox, oy, oz, error, pos, tr.states(), conflicts.toLongArray(), floating.toLongArray(), replaced,
                kept == null ? null : kept.toIntArray());
    }

    private static Transformed lastTransform;

    public static Transformed cachedTransform(TemplateData t, int rot, boolean mirror) {
        Transformed c = lastTransform;
        if (c != null && c.template() == t && c.rotation() == Math.floorMod(rot, 4) && c.mirror() == mirror) return c;
        lastTransform = transform(t, rot, mirror);
        return lastTransform;
    }

    public static void commitPlacement(EditorSession s, Placement p, String source, String name) {
        if (!p.valid()) {
            s.toast(Component.translatable(p.error()));
            return;
        }
        long[] pos = p.pos();
        BlockState[] states = p.states();
        if (p.kept() != null) {
            int[] k = p.kept();
            pos = new long[k.length];
            states = new BlockState[k.length];
            for (int i = 0; i < k.length; i++) {
                pos[i] = p.pos()[k[i]];
                states[i] = s.placeOp == EditorSession.Op.ERASE ? AIR : p.states()[k[i]];
            }
        }
        ClientConfig.touchRecent(source);
        if (s.placeOp == EditorSession.Op.ERASE) {
            // 조각 모양대로 지운다: 구조물을 선택했으면 그 구조물에서, 아니면 지형 층에서 (직접조작 지우기와 같음)
            int group = dev.designandbuild.client.ClientStore.nextGroup();
            if (s.selected != 0) {
                s.send(new Requests.StructCells(group, s.selected, "", pos, states));
            } else {
                DesignTag[] tags = new DesignTag[pos.length];
                java.util.Arrays.fill(tags, DesignTag.NONE);
                s.send(new Requests.Terrain(group, pos, states, tags));
            }
            return;
        }
        s.exemptFromCut(pos, states); // 단면을 켠 뒤 놓은 조각은 잘린 쪽이어도 보이게
        // 배치한 구조물은 자동으로 선택하지 않는다 (이어서 지우기를 하면 그 구조물의 공기 지정이 되어 버리므로)
        s.send(new Requests.StructCreate(0, name, source, p.shape().mount(),
                rotationOf(p.shape().rotation()), p.shape().mirror() ? Mirror.FRONT_BACK : Mirror.NONE,
                pos, states));
    }

    public static Rotation rotationOf(int steps) {
        return switch (Math.floorMod(steps, 4)) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

}

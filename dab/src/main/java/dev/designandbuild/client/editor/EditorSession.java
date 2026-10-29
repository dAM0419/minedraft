package dev.designandbuild.client.editor;

import dev.minedraft.lib.client.editor.Snapshot;
import dev.minedraft.lib.client.editor.EditorColors;
import dev.minedraft.lib.client.editor.EditorCamera;
import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.area.AreaOp;
import dev.minedraft.lib.area.AreaOps;
import dev.minedraft.lib.client.ClientAreas;
import dev.minedraft.lib.client.ClientEditor;
import dev.minedraft.lib.edit.EditRequest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import dev.designandbuild.client.library.LibraryEntry;
import dev.designandbuild.core.BlueprintModel;
import dev.minedraft.lib.util.Box;
import dev.designandbuild.core.DesignTag;
import dev.designandbuild.core.ModelOps;
import dev.designandbuild.core.Requests;
import dev.designandbuild.core.Resolved;
import dev.designandbuild.core.StructureInstance;
import dev.designandbuild.net.Msg;
import dev.designandbuild.net.Net;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 열려 있는 편집창 하나의 상태. 화면(EditorScreen)은 이 상태를 그리고 입력을 넘긴다. */
public final class EditorSession implements ClientEditor {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public enum Tool { SELECT, DIRECT, PLACE }

    public enum Op { INSTALL, FILL, REPLACE, ERASE }

    public enum Shape { BOX, CYLINDER, SPHERE }

    public enum Anchor { OUTSIDE, ON, CENTER }

    public BlueprintModel model;
    public final BlockPos tablePos;
    public final Snapshot snapshot = new Snapshot();
    public final EditorCamera camera = new EditorCamera();
    public final EditorWorld world = new EditorWorld(this, false);
    public final EditorWorld cutWorld = new EditorWorld(this, true);
    @Nullable
    public EditorRenderer renderer;
    public boolean ready;
    public int renderMinY;
    private boolean cameraPlaced;

    // ---- 도구 ----
    public Tool tool = Tool.DIRECT;
    public Op op = Op.INSTALL;
    public Shape shape = Shape.BOX;
    public Direction.Axis cylinderAxis = Direction.Axis.Y;
    public Anchor anchor = Anchor.OUTSIDE;
    public int sizeX = 1, sizeY = 1, sizeZ = 1;
    public boolean ratioLock;
    public int percent = 100;
    public final ItemStack[] materials = {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};
    /** 대체 대상 (직접조작·배치 공통). 모두 비어 있으면 모든 블록을 대체, 아니면 이 블록들만. */
    public final ItemStack[] replaceTargets = {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};

    /** 대체 대상 블록 (비어 있으면 null = 모든 블록). */
    @Nullable
    public java.util.Set<net.minecraft.world.level.block.Block> replaceFilter() {
        java.util.Set<net.minecraft.world.level.block.Block> set = null;
        for (ItemStack st : replaceTargets) {
            net.minecraft.world.level.block.Block b = blockOf(st);
            if (b == null) continue;
            if (set == null) set = new java.util.HashSet<>(4);
            set.add(b);
        }
        return set;
    }

    /** 대체할 칸인가: 비어 있지 않고, 대상을 정했으면 그 블록. */
    public static boolean replaceable(BlockState cur, @Nullable java.util.Set<net.minecraft.world.level.block.Block> filter) {
        return !cur.isAir() && (filter == null || filter.contains(cur.getBlock()));
    }

    /** 대상 칸의 아이템이 가리키는 블록 (블록 아이템, 액체 양동이). */
    @Nullable
    public static net.minecraft.world.level.block.Block blockOf(ItemStack st) {
        if (st.isEmpty()) return null;
        if (st.getItem() instanceof net.minecraft.world.item.BlockItem bi) return bi.getBlock();
        if (st.getItem() instanceof net.minecraft.world.item.BucketItem) {
            return net.neoforged.neoforge.fluids.FluidUtil.getFluidContained(st)
                    .map(f -> f.getFluid().defaultFluidState().createLegacyBlock().getBlock()).orElse(null);
        }
        return null;
    }
    public final int[] weights = {1, 1, 1};
    public DesignTag tag = DesignTag.FLOOR;
    /** 설치하는 가상 플레이어가 바라보는 수평 방향 / 위(1)·수평(0)·아래(-1). */
    public Direction lookH = Direction.NORTH;
    public int lookV = 0;
    /** 클릭할 면 (바닐라 getClickedFace) 과 그 면의 위 절반 여부. */
    public Direction face = Direction.UP;
    public boolean upperHalf = false;
    public int paletteIndex = -1;

    // ---- 선택·배치 ----
    public int selected;
    @Nullable
    public LibraryEntry placing;
    public int placeRotation;
    public boolean placeMirror;
    /** 구조물 배치 기준: false = 블록 밖에(기본), true = 가리킨 블록 층에 겹쳐 */
    public boolean placeInto;
    /** 구조물 배치 방식: 설치(기본, 조각 그대로) / 채우기 / 대체 / 지우기 (조각의 공기 아닌 칸에 대해). */
    public Op placeOp = Op.INSTALL;

    // ---- 단면 ----
    /** 단면 보기 전체 켜기·끄기. */
    public boolean cutActive;
    /** 축별 단면 (X·Y·Z 를 동시에 켤 수 있다, 순서는 Axis.ordinal). Y는 위쪽을, X·Z는 카메라 쪽을 숨긴다. */
    public final boolean[] cutOn = new boolean[3];
    /** 축별 단면 평면이 지나는 칸 좌표. 이 줄은 보이는 쪽에 남는다. */
    public final int[] cutAt = new int[3];
    /** 지금 조작하는 축 (Shift+휠, Ctrl+클릭, Shift+L). */
    public Direction.Axis cutAxis = Direction.Axis.Y;
    public boolean bubble;
    /** 이 면만 보기 (반대쪽도 숨김). 기본은 이 면까지. 켜진 모든 축에 같이 쓴다. */
    public boolean cutOnlyLayer;
    public int cutStamp;

    // ---- 통신·공동 편집 ----
    public int pending;
    private int awaitingCreateSeq = -1;
    public final Map<UUID, String> editors = new LinkedHashMap<>();
    public final Map<UUID, RemoteCursor> cursors = new HashMap<>();
    public final List<Flash> flashes = new ArrayList<>();
    public final List<Toast> toasts = new ArrayList<>();

    public record RemoteCursor(UUID player, boolean has, long pos, Box region, int selected, long time) {}

    public record Flash(long pos, int color, long time) {}

    public record Toast(Component text, long time, int count) {}

    public record Hit(BlockPos pos, Direction face, Vec3 location) {}

    public EditorSession(BlueprintModel model, BlockPos tablePos) {
        this.model = model;
        this.tablePos = tablePos;
        attachListener();
    }

    private final java.util.function.LongConsumer blockListener = pos -> {
        if (renderer != null) renderer.markBlock(pos);
    };

    private void attachListener() {
        model.addListener(blockListener);
    }

    @Override
    public void dispose() {
        model.removeListener(blockListener);
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
    }

    // ------------------------------------------------------------------
    // 가상 월드
    // ------------------------------------------------------------------

    public BlockState blockAt(int x, int y, int z, boolean hideCut) {
        if (y < renderMinY || y >= model.maxY) return AIR;
        if (!model.isInsideColumn(x, z)) return AIR;
        if (hideCut && cutActive && isCut(x, y, z)) return AIR;
        Resolved r = model.resolved(x, y, z);
        if (r != null) return r.state();
        return snapshot.get(x, y, z);
    }

    /**
     * 화면에 보이는 블록 (그리기·커서용). 구조물 편집 중이면(구조물 선택 + 직접조작) 그 구조물 블록만 보인다.
     * 설치·채우기 같은 판정은 이것이 아니라 {@link #blockAt} 을 쓴다 (숨겨도 지형은 그대로 있다).
     */
    public BlockState visibleAt(int x, int y, int z, boolean hideCut) {
        BlockState st = blockAt(x, y, z, hideCut);
        int iso = isolated();
        if (iso == 0 || st.isAir()) return st;
        Resolved r = model.resolved(x, y, z);
        return r != null && r.owner() == iso ? st : AIR;
    }

    /** 구조물 편집 중이면 그 구조물 ID (나머지를 숨긴다), 아니면 0. */
    public int isolated() {
        return selected != 0 && tool == Tool.DIRECT && model.structure(selected) != null ? selected : 0;
    }

    private int lastIsolated;

    /** 구조물 편집에 들어가기 전의 카메라 목표점 (나오면 되돌린다). */
    @Nullable
    private double[] savedCameraTarget;

    /**
     * 구조물 편집에 들어가거나 나오면 화면 전체를 다시 굽는다. 매 프레임 부른다.
     * 들어가면 카메라 목표점(회전 중심)을 그 구조물 가운데로 옮기고, 나오면 원래 자리로 되돌린다.
     */
    public void checkIsolationChanged() {
        int iso = isolated();
        if (iso == lastIsolated) return;
        lastIsolated = iso;
        if (renderer != null) renderer.markAll();
        StructureInstance st = iso != 0 ? model.structure(iso) : null;
        if (st != null && st.bounds() != null) {
            if (savedCameraTarget == null) savedCameraTarget = new double[]{camera.tx, camera.ty, camera.tz};
            Box b = st.bounds();
            camera.tx = (b.minX() + b.maxX() + 1) / 2.0;
            camera.ty = (b.minY() + b.maxY() + 1) / 2.0;
            camera.tz = (b.minZ() + b.maxZ() + 1) / 2.0;
        } else if (iso == 0 && savedCameraTarget != null) {
            camera.tx = savedCameraTarget[0];
            camera.ty = savedCameraTarget[1];
            camera.tz = savedCameraTarget[2];
            savedCameraTarget = null;
        }
    }

    /** 설계를 뺀 원래 월드 (스냅샷). */
    public BlockState worldAt(int x, int y, int z) {
        return snapshot.get(x, y, z);
    }

    /** 직전에 정한 X·Z 숨김 방향 (카메라가 단면 축과 수직이라 정할 수 없을 때 유지). */
    private int lastCutSignX = 1, lastCutSignZ = -1;

    /**
     * 숨기는 쪽: 축의 + 방향이면 1, - 방향이면 -1. Y는 언제나 위, X·Z는 카메라가 있는 쪽.
     * 정면으로 볼 때처럼 카메라가 그 축과 수직이면 어느 쪽인지 알 수 없으므로 직전 값을 쓴다.
     */
    public int cutSign(Direction.Axis axis) {
        if (axis == Direction.Axis.Y) return 1;
        org.joml.Vector3f c = camera.toCamera();
        int sign;
        if (axis == Direction.Axis.X) {
            if (Math.abs(c.x) > 0.05F) lastCutSignX = c.x > 0 ? 1 : -1;
            sign = lastCutSignX;
        } else {
            if (Math.abs(c.z) > 0.05F) lastCutSignZ = c.z > 0 ? 1 : -1;
            sign = lastCutSignZ;
        }
        cutSigns[axis.ordinal()] = sign;
        return sign;
    }

    /**
     * 칸 판정({@link #isCut})용 숨김 방향 (축 순서 X, Y, Z). isCut 은 메시를 구울 때 칸마다 불리므로
     * 카메라 계산을 하지 않고 이 값을 쓴다. {@link #checkCutChanged} 가 매 프레임 새로 정한다.
     */
    private final int[] cutSigns = {1, 1, -1};

    private static final Direction.Axis[] AXES = Direction.Axis.values();

    /** 단면에 숨는 칸인가. 단면을 켠 뒤 직접 놓은 칸(단면 예외)은 숨기지 않는다. */
    public boolean isCut(int x, int y, int z) {
        return isCut(x, y, z, true);
    }

    /** withExemptions = false 면 단면 예외를 무시한 원래 판정 (예외 표시용). */
    public boolean isCut(int x, int y, int z, boolean withExemptions) {
        if (!cutActive) return false;
        boolean hidden = false;
        for (Direction.Axis ax : AXES) {
            int i = ax.ordinal();
            if (!cutOn[i]) continue;
            int c = ax == Direction.Axis.X ? x : ax == Direction.Axis.Y ? y : z;
            int d = (c - cutAt[i]) * cutSigns[i];
            if (d > 0 || (d < 0 && cutOnlyLayer)) {
                hidden = true;
                break;
            }
        }
        if (!hidden) return false;
        Box scope = cutScope();
        if (scope != null && !scope.contains(x, y, z)) return false;
        return !withExemptions || !exempt(x, y, z);
    }

    // ---- 단면 예외 ----
    // 단면을 켠 뒤 이 편집자가 직접조작·배치로 놓은 칸은 잘린 쪽에 있어도 보인다 (잘라 낸 방 안을 꾸밀 수 있게).
    // 칸마다 놓은 블록을 기억하고, 지금 설계의 그 칸이 같은 블록일 때만 예외다: 되돌리기나 다른 사람의 변경으로
    // 블록이 바뀌면 자동으로 풀린다. 단면을 끄면 비운다.

    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<net.minecraft.world.level.block.Block> cutExempt =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    /** 구역(SectionPos)별 예외 칸 수 (통째로 잘린 구역도 예외 칸은 그리게). */
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap exemptBySection = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

    /** 놓으려고 보낸 칸을 단면 예외로 기억한다 (단면이 켜져 있을 때만, 공기 칸 제외). */
    public void exemptFromCut(long[] pos, BlockState[] states) {
        if (!cutActive) return;
        for (int i = 0; i < pos.length; i++) {
            if (states[i] == null || states[i].isAir()) continue;
            long p = pos[i];
            if (cutExempt.put(p, states[i].getBlock()) == null) exemptBySection.addTo(sectionOf(p), 1);
        }
    }

    /** 이 구역에 단면 예외 칸이 있는가. */
    public boolean hasCutExempt(long section) {
        return !exemptBySection.isEmpty() && exemptBySection.get(section) > 0;
    }

    private boolean exempt(int x, int y, int z) {
        if (cutExempt.isEmpty()) return false;
        long p = BlockPos.asLong(x, y, z);
        net.minecraft.world.level.block.Block b = cutExempt.get(p);
        if (b == null) return false;
        Resolved r = model.resolved(p);
        return r != null && !r.isAir() && r.state().getBlock() == b;
    }

    /** 단면 예외로 보이고 있는 칸 (표시용, 최대 limit 개). */
    public void forEachShownExempt(int limit, java.util.function.LongConsumer out) {
        if (!cutActive || cutExempt.isEmpty()) return;
        int n = 0;
        for (long p : cutExempt.keySet()) {
            int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
            if (!exempt(x, y, z) || !isCut(x, y, z, false)) continue;
            out.accept(p);
            if (++n >= limit) return;
        }
    }

    private static long sectionOf(long p) {
        return net.minecraft.core.SectionPos.asLong(BlockPos.getX(p) >> 4, BlockPos.getY(p) >> 4, BlockPos.getZ(p) >> 4);
    }

    /** 단면 범위: 선택 구조물 볼륨, 없으면 영역 전체 (그려지는 높이 안). */
    public Box cutBounds() {
        Box scope = cutScope();
        if (scope != null) return scope;
        Box a = model.areaBounds();
        return new Box(a.minX(), renderMinY, a.minZ(), a.maxX(), model.maxY - 1, a.maxZ());
    }

    /** 숨겨지는 칸 범위들 (켜진 축마다 숨기는 쪽, "이 면만"이면 반대쪽도). 겹쳐도 된다. */
    public List<Box> cutRegions() {
        List<Box> out = new ArrayList<>(6);
        if (!cutActive) return out;
        Box scope = cutBounds();
        int[] min = {scope.minX(), scope.minY(), scope.minZ()};
        int[] max = {scope.maxX(), scope.maxY(), scope.maxZ()};
        for (Direction.Axis ax : Direction.Axis.values()) {
            int a = ax.ordinal();
            if (!cutOn[a]) continue;
            int sign = cutSign(ax), pos = cutAt[a];
            addSlab(out, min, max, a, sign > 0 ? pos + 1 : min[a], sign > 0 ? max[a] : pos - 1);
            if (cutOnlyLayer) addSlab(out, min, max, a, sign > 0 ? min[a] : pos + 1, sign > 0 ? pos - 1 : max[a]);
        }
        return out;
    }

    private static void addSlab(List<Box> out, int[] min, int[] max, int axis, int from, int to) {
        int lo = Math.max(from, min[axis]), hi = Math.min(to, max[axis]);
        if (lo > hi) return;
        int[] a = min.clone(), b = max.clone();
        a[axis] = lo;
        b[axis] = hi;
        out.add(new Box(a[0], a[1], a[2], b[0], b[1], b[2]));
    }

    /** 축 방향 범위 [최소, 최대] (슬라이더용). Y는 실제 블록이 있는 높이까지만. */
    public int[] cutRange(Direction.Axis axis) {
        StructureInstance st = selected != 0 ? model.structure(selected) : null;
        if (st != null && st.bounds() != null) {
            Box b = st.bounds();
            return switch (axis) {
                case X -> new int[]{b.minX(), b.maxX()};
                case Y -> new int[]{b.minY(), b.maxY()};
                case Z -> new int[]{b.minZ(), b.maxZ()};
            };
        }
        Box a = model.areaBounds();
        return switch (axis) {
            case X -> new int[]{a.minX(), a.maxX()};
            case Y -> new int[]{renderMinY, Math.max(renderMinY + 1, topY())};
            case Z -> new int[]{a.minZ(), a.maxZ()};
        };
    }

    /** 영역 안 가장 높은 블록 (층 슬라이더 범위). */
    public int topY() {
        if (topYRevision == model.revision() && topYCache != Integer.MIN_VALUE) return topYCache;
        int top = renderMinY;
        LongIterator it = model.chunks().iterator();
        while (it.hasNext()) {
            long c = it.nextLong();
            int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
            for (int x = 0; x < 16; x += 2) {
                for (int z = 0; z < 16; z += 2) {
                    top = Math.max(top, snapshot.surface((cx << 4) + x, (cz << 4) + z));
                }
            }
        }
        for (long p : model.resolvedMap().keySet()) top = Math.max(top, BlockPos.getY(p));
        topYCache = Math.min(model.maxY - 1, top + 2);
        topYRevision = model.revision();
        return topYCache;
    }

    private int topYCache = Integer.MIN_VALUE;
    private long topYRevision = -1;

    /** 자르기 범위: 선택된 구조물이 있으면 그 볼륨, 없으면 영역 전체. */
    @Nullable
    public Box cutScope() {
        if (selected != 0) {
            StructureInstance s = model.structure(selected);
            if (s != null && s.bounds() != null) return s.bounds();
        }
        return null;
    }

    /** 자르기 설정이 바뀌었으면 경계 섹션을 다시 굽게 한다. 매 프레임 부른다. */
    public void checkCutChanged() {
        if (!cutActive && !cutExempt.isEmpty()) {
            cutExempt.clear();
            exemptBySection.clear();
        }
        List<Object> key = cutActive
                ? java.util.Arrays.asList(selected, bubble, cutOnlyLayer, cutOn[0], cutOn[1], cutOn[2], cutAt[0], cutAt[1], cutAt[2],
                        cutSign(Direction.Axis.X), cutSign(Direction.Axis.Z))
                : null;
        if (!java.util.Objects.equals(key, lastCutKey)) {
            lastCutKey = key;
            cutStamp++;
        }
    }

    @Nullable
    private List<Object> lastCutKey;

    /** L: 단면 보기 켜기·끄기. 처음 켤 때 켜진 축이 없으면 Y를 켠다. */
    public void toggleCut() {
        cutActive = !cutActive;
        if (cutActive && !cutOn[0] && !cutOn[1] && !cutOn[2]) {
            cutAxis = Direction.Axis.Y;
            cutOn[Direction.Axis.Y.ordinal()] = true;
            resetCutPos(Direction.Axis.Y);
        }
    }

    /** 축 하나를 켜거나 끈다. 켜면 그 축을 조작 축으로 하고 처음 위치로. */
    public void toggleCutAxis(Direction.Axis axis) {
        int i = axis.ordinal();
        cutOn[i] = !cutOn[i];
        cutActive = true;
        if (cutOn[i]) {
            cutAxis = axis;
            resetCutPos(axis);
        } else if (cutAxis == axis) {
            for (Direction.Axis a : new Direction.Axis[]{Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z}) {
                if (cutOn[a.ordinal()]) {
                    cutAxis = a;
                    break;
                }
            }
        }
    }

    /** 축을 켜거나 끄지 않고 조작 축만 바꾼다 (켜져 있을 때). */
    public void focusCutAxis(Direction.Axis axis) {
        if (cutOn[axis.ordinal()]) cutAxis = axis;
    }

    /** 처음 위치: 선택 구조물이 있으면 Y는 바닥·X·Z는 가운데, 없으면 화면 중심. */
    private void resetCutPos(Direction.Axis axis) {
        StructureInstance s = selected != 0 ? model.structure(selected) : null;
        int v;
        if (s != null && s.bounds() != null) {
            Box b = s.bounds();
            v = switch (axis) {
                case X -> (b.minX() + b.maxX()) / 2;
                case Y -> b.minY();
                case Z -> (b.minZ() + b.maxZ()) / 2;
            };
        } else {
            v = switch (axis) {
                case X -> Mth.floor(camera.tx);
                case Y -> Mth.floor(camera.ty);
                case Z -> Mth.floor(camera.tz);
            };
        }
        cutAt[axis.ordinal()] = v;
    }

    /** Shift+L: 조작 축을 Y → X → Z 로 바꾼다. 꺼져 있던 축이면 켠다. */
    public void cycleCutAxis() {
        Direction.Axis next = switch (cutAxis) {
            case Y -> Direction.Axis.X;
            case X -> Direction.Axis.Z;
            case Z -> Direction.Axis.Y;
        };
        cutActive = true;
        if (!cutOn[next.ordinal()]) {
            cutOn[next.ordinal()] = true;
            resetCutPos(next);
        }
        cutAxis = next;
    }

    /** Ctrl+클릭: 조작 축에서 그 블록을 지나는 평면으로 자른다. 그 블록의 줄은 보이는 쪽에 남는다. */
    public void setCutAt(BlockPos p) {
        cutActive = true;
        cutOn[cutAxis.ordinal()] = true;
        cutAt[cutAxis.ordinal()] = switch (cutAxis) {
            case X -> p.getX();
            case Y -> p.getY();
            case Z -> p.getZ();
        };
    }

    /** 조작 축에서 한 칸 이동. +면 숨긴 쪽으로 (더 드러남), -면 반대로. */
    public void moveCut(int delta) {
        moveCut(cutAxis, delta);
    }

    public void moveCut(Direction.Axis axis, int delta) {
        if (!cutActive || !cutOn[axis.ordinal()]) return;
        int[] r = cutRange(axis);
        int i = axis.ordinal();
        cutAt[i] = Mth.clamp(cutAt[i] + delta * cutSign(axis), r[0] - 1, r[1] + 1);
    }

    // ------------------------------------------------------------------
    // 스냅샷 수신
    // ------------------------------------------------------------------

    @Override
    public void onSnapshot(FriendlyByteBuf buf, RegistryAccess access) {
        onSnapshotChunk(snapshot.read(buf, access));
    }

    public void onSnapshotChunk(long chunk) {
        if (ready && renderer != null) {
            computeRenderMinY();
            renderer.markChunkArea(chunk);
        }
    }

    @Override
    public void onReady() {
        ready = true;
        computeRenderMinY();
        if (!cameraPlaced) placeCamera();
        if (renderer == null) renderer = new EditorRenderer(this);
        renderer.markAll();
    }

    private void computeRenderMinY() {
        int minSurface = Integer.MAX_VALUE;
        LongIterator it = model.chunks().iterator();
        while (it.hasNext()) {
            long c = it.nextLong();
            int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
            for (int x = 0; x < 16; x += 3) {
                for (int z = 0; z < 16; z += 3) {
                    int s = snapshot.surface((cx << 4) + x, (cz << 4) + z);
                    if (s != Integer.MIN_VALUE) minSurface = Math.min(minSurface, s);
                }
            }
        }
        if (minSurface == Integer.MAX_VALUE) minSurface = 60;
        int min = minSurface - 12;
        for (long p : model.resolvedMap().keySet()) min = Math.min(min, BlockPos.getY(p) - 2);
        int newMin = Math.max(model.minY, min);
        if (renderer != null && newMin != renderMinY) {
            renderMinY = newMin;
            renderer.markAll();
        } else {
            renderMinY = newMin;
        }
    }

    private void placeCamera() {
        cameraPlaced = true;
        int bx = (model.baseX << 4) + 8, bz = (model.baseZ << 4) + 8;
        int s = snapshot.surface(bx, bz);
        camera.tx = bx;
        camera.tz = bz;
        camera.ty = s == Integer.MIN_VALUE ? 64 : s + 1;
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        camera.scale = Mth.clamp(w * 0.55F / 70.0F, 2.0F, 24.0F);
    }

    // ------------------------------------------------------------------
    // 커서 (시선 → 블록)
    // ------------------------------------------------------------------

    @Nullable
    public Hit pick(double mx, double my) {
        EditorCamera.Ray ray = camera.ray(mx, my);
        Box a = model.areaBounds();
        double[] range = slab(ray, a.minX(), renderMinY, a.minZ(), a.maxX() + 1, model.maxY, a.maxZ() + 1);
        if (range == null) return null;
        Vec3 o = ray.origin(), d = ray.dir();
        double t0 = Math.max(0, range[0] - 0.01), t1 = range[1] + 0.01;
        Vec3 start = o.add(d.scale(t0));
        Vec3 end = o.add(d.scale(t1));
        int x = Mth.floor(start.x), y = Mth.floor(start.y), z = Mth.floor(start.z);
        int stepX = d.x > 0 ? 1 : -1, stepY = d.y > 0 ? 1 : -1, stepZ = d.z > 0 ? 1 : -1;
        double tdx = d.x == 0 ? Double.MAX_VALUE : Math.abs(1 / d.x);
        double tdy = d.y == 0 ? Double.MAX_VALUE : Math.abs(1 / d.y);
        double tdz = d.z == 0 ? Double.MAX_VALUE : Math.abs(1 / d.z);
        double tmx = d.x == 0 ? Double.MAX_VALUE : (d.x > 0 ? (x + 1 - start.x) : (start.x - x)) * tdx;
        double tmy = d.y == 0 ? Double.MAX_VALUE : (d.y > 0 ? (y + 1 - start.y) : (start.y - y)) * tdy;
        double tmz = d.z == 0 ? Double.MAX_VALUE : (d.z > 0 ? (z + 1 - start.z) : (start.z - z)) * tdz;
        double max = t1 - t0;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int guard = 0; guard < 20000; guard++) {
            BlockState st = visibleAt(x, y, z, true);
            if (!st.isAir()) {
                mp.set(x, y, z);
                VoxelShape shape = st.getShape(cutWorld, mp);
                if (shape.isEmpty() && !st.getFluidState().isEmpty()) shape = Shapes.block();
                if (!shape.isEmpty()) {
                    BlockHitResult r = shape.clip(start, end, mp);
                    if (r != null) return new Hit(mp.immutable(), r.getDirection(), r.getLocation());
                }
            }
            double travelled;
            if (tmx < tmy && tmx < tmz) {
                x += stepX;
                travelled = tmx;
                tmx += tdx;
            } else if (tmy < tmz) {
                y += stepY;
                travelled = tmy;
                tmy += tdy;
            } else {
                z += stepZ;
                travelled = tmz;
                tmz += tdz;
            }
            if (travelled > max) break;
        }
        return null;
    }

    @Nullable
    private static double[] slab(EditorCamera.Ray ray, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double tmin = -1e9, tmax = 1e9;
        double[] o = {ray.origin().x, ray.origin().y, ray.origin().z};
        double[] d = {ray.dir().x, ray.dir().y, ray.dir().z};
        double[] lo = {minX, minY, minZ}, hi = {maxX, maxY, maxZ};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-12) {
                if (o[i] < lo[i] || o[i] > hi[i]) return null;
            } else {
                double a = (lo[i] - o[i]) / d[i], b = (hi[i] - o[i]) / d[i];
                tmin = Math.max(tmin, Math.min(a, b));
                tmax = Math.min(tmax, Math.max(a, b));
            }
        }
        return tmax < tmin ? null : new double[]{tmin, tmax};
    }

    // ------------------------------------------------------------------
    // 서버로 보내기
    // ------------------------------------------------------------------

    public int send(EditRequest req) {
        pending++;
        return ClientAreas.send(model.area, req);
    }

    /** 새 구조물을 만드는 요청: 응답이 오면 그 구조물을 선택한다. */
    public void sendCreating(EditRequest req) {
        awaitingCreateSeq = send(req);
    }

    /** 새 구조물 응답을 기다리는 중인가 (그동안 같은 드래그의 칸은 모아 두었다가 그 구조물로 보낸다). */
    public boolean isAwaitingCreate() {
        return awaitingCreateSeq != -1;
    }

    private final List<Requests.StructCells> waitingForCreate = new ArrayList<>();

    public void queueForCreated(int group, long[] pos, BlockState[] states) {
        waitingForCreate.add(new Requests.StructCells(group, 0, "", pos, states));
    }

    private void flushWaiting(int createdId) {
        List<Requests.StructCells> list = new ArrayList<>(waitingForCreate);
        waitingForCreate.clear();
        for (Requests.StructCells q : list) send(new Requests.StructCells(q.group(), createdId, "", q.pos(), q.states()));
    }

    @Override
    public void onApplied(UUID actor, int seq, List<AreaOp> ops) {
        Minecraft mc = Minecraft.getInstance();
        boolean mine = mc.player != null && actor.equals(mc.player.getUUID()) && seq != 0;
        if (mine) {
            pending = Math.max(0, pending - 1);
            if (seq == awaitingCreateSeq) {
                awaitingCreateSeq = -1;
                int created = 0;
                for (AreaOp op : ops) {
                    if (op instanceof ModelOps.StructCreate c) created = c.id();
                }
                if (created != 0) {
                    selected = created;
                    flushWaiting(created);
                } else {
                    waitingForCreate.clear();
                }
            }
        } else {
            int color = EditorColors.editorColor(actor);
            long now = System.currentTimeMillis();
            for (AreaOp op : ops) {
                long[] pos = switch (op) {
                    case ModelOps.Terrain t -> t.pos();
                    case ModelOps.StructCells t -> t.pos();
                    case ModelOps.StructCreate t -> t.pos();
                    case ModelOps.StructReplace t -> t.pos();
                    default -> new long[0];
                };
                for (long p : pos) {
                    if (flashes.size() > 6000) break;
                    flashes.add(new Flash(p, color, now));
                }
            }
        }
        int lowest = Integer.MAX_VALUE;
        for (AreaOp op : ops) {
            if (op instanceof AreaOps.ChunkAdd || op instanceof AreaOps.ChunkRemove) {
                long c = op instanceof AreaOps.ChunkAdd a ? a.chunk() : ((AreaOps.ChunkRemove) op).chunk();
                if (op instanceof AreaOps.ChunkRemove) snapshot.remove(c);
                if (renderer != null) renderer.markChunkArea(c);
            }
            long[] pos = switch (op) {
                case ModelOps.Terrain t -> t.pos();
                case ModelOps.StructCells t -> t.pos();
                case ModelOps.StructCreate t -> t.pos();
                case ModelOps.StructReplace t -> t.pos();
                default -> new long[0];
            };
            for (long p : pos) lowest = Math.min(lowest, BlockPos.getY(p));
        }
        // 설계가 지금 그리는 바닥보다 깊이 내려가면 그릴 범위를 넓힌다
        if (ready && lowest < renderMinY + 2) computeRenderMinY();
        if (selected != 0 && model.structure(selected) == null) selected = 0;
    }

    @Override
    public void onRejected(int seq, String key) {
        pending = Math.max(0, pending - 1);
        if (seq == awaitingCreateSeq) {
            awaitingCreateSeq = -1;
            // 만들기가 거절됐으면 모아 둔 칸의 첫 묶음으로 다시 만든다
            if (!waitingForCreate.isEmpty()) {
                Requests.StructCells first = waitingForCreate.remove(0);
                sendCreating(new Requests.StructCells(first.group(), 0, newStructureName(), first.pos(), first.states()));
            }
        }
        if (!key.isEmpty()) toast(Component.translatable(key));
    }

    /** 서버가 도면 전체를 다시 보냄 (순서가 어긋났을 때). */
    @Override
    public void onAreaReplaced(Area area) {
        replaceModel(BlueprintModel.of(area));
    }

    public void replaceModel(BlueprintModel newModel) {
        model.removeListener(blockListener);
        model = newModel;
        attachListener();
        if (selected != 0 && model.structure(selected) == null) selected = 0;
        if (renderer != null) renderer.markAll();
    }

    @Override
    public void toast(Component text) {
        long now = System.currentTimeMillis();
        if (!toasts.isEmpty()) {
            Toast last = toasts.get(toasts.size() - 1);
            if (last.text().getString().equals(text.getString()) && now - last.time() < 3000) {
                toasts.set(toasts.size() - 1, new Toast(text, now, last.count() + 1));
                return;
            }
        }
        toasts.add(new Toast(text, now, 1));
        while (toasts.size() > 3) toasts.remove(0);
    }

    public void tickEffects() {
        long now = System.currentTimeMillis();
        flashes.removeIf(f -> now - f.time() > 500);
        toasts.removeIf(t -> now - t.time() > 3000);
        cursors.values().removeIf(c -> now - c.time() > 4000);
    }

    // ------------------------------------------------------------------

    /** 이 칸을 지금 바꿀 수 있는가 (다른 구조물 칸은 그 구조물을 선택해야). */
    public boolean editable(long pos, boolean structureTag) {
        Resolved r = model.resolved(pos);
        if (r == null || r.owner() == 0) return true;
        return structureTag && r.owner() == selected;
    }

    /** 커서가 놓인 블록을 가진 구조물들 (건축 순서대로). */
    public int[] structuresAround(BlockPos pos) {
        List<StructureInstance> list = model.orderedStructures();
        IntOpenHashSet set = new IntOpenHashSet();
        List<Integer> out = new ArrayList<>();
        for (StructureInstance s : list) {
            Box b = s.bounds();
            if (b != null && b.contains(pos.getX(), pos.getY(), pos.getZ()) && set.add(s.id)) out.add(s.id);
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    public String newStructureName() {
        return Component.translatable("designandbuild.structure.default", model.nextStructureId).getString();
    }

    // ------------------------------------------------------------------
    // Lib 편집창 연결 (ClientEditor)
    // ------------------------------------------------------------------

    @Override
    public Area area() {
        return model.area;
    }

    @Override
    public void onEditors(List<UUID> ids, List<String> names) {
        editors.clear();
        for (int i = 0; i < ids.size(); i++) editors.put(ids.get(i), names.get(i));
        cursors.keySet().removeIf(u -> !editors.containsKey(u));
    }

    @Override
    public void onCursor(UUID from, byte[] data) {
        FriendlyByteBuf b = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data));
        boolean has = b.readBoolean();
        long pos = b.readLong();
        Box region = new Box(b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readInt());
        int sel = b.readVarInt();
        cursors.put(from, new RemoteCursor(from, has, pos, region, sel, System.currentTimeMillis()));
    }

    @Override
    public boolean isShowing() {
        return Minecraft.getInstance().screen instanceof EditorScreen;
    }

    @Override
    public void closeScreen() {
        if (isShowing()) Minecraft.getInstance().setScreen(null);
    }
}

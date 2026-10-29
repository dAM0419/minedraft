package dev.designandbuild.client.editor;

import dev.minedraft.lib.client.editor.Snapshot;
import dev.minedraft.lib.client.editor.EditorColors;
import dev.minedraft.lib.client.editor.EditorCamera;
import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.area.AreaOp;
import dev.minedraft.lib.area.AreaOps;
import dev.minedraft.lib.client.ClientAreas;
import dev.minedraft.lib.client.editor.AreaEditorSession;
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

/**
 * DaB 편집창 상태: Lib 편집창 틀(카메라·단면·공동 편집) 위에 도구(직접조작·배치·선택), 재료·방향, 구조물 선택을 더한다.
 */
public final class EditorSession extends AreaEditorSession {

    public enum Tool { SELECT, DIRECT, PLACE }


    public enum Op { INSTALL, FILL, REPLACE, ERASE }


    public enum Shape { BOX, CYLINDER, SPHERE }


    public enum Anchor { OUTSIDE, ON, CENTER }


    public BlueprintModel model;

    public final BlockPos tablePos;


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

    @Nullable
    public LibraryEntry placing;

    public int placeRotation;

    public boolean placeMirror;

    /** 구조물 배치 기준: false = 블록 밖에(기본), true = 가리킨 블록 층에 겹쳐 */
    public boolean placeInto;

    /** 구조물 배치 방식: 설치(기본, 조각 그대로) / 채우기 / 대체 / 지우기 (조각의 공기 아닌 칸에 대해). */
    public Op placeOp = Op.INSTALL;

    private int awaitingCreateSeq = -1;


    public EditorSession(BlueprintModel model, BlockPos tablePos) {
        super(model.area, tablePos);
        this.model = model;
        this.tablePos = tablePos;
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
    // Lib 편집창 틀에 넘기는 DaB 설계
    // ------------------------------------------------------------------

    @Override
    @Nullable
    protected BlockState designAt(long pos) {
        Resolved r = model.resolved(pos);
        return r == null ? null : r.state();
    }

    /** 구조물 편집 중(구조물 선택 + 직접조작)이면 그 구조물 블록만 보인다. */
    @Override
    protected boolean shown(int x, int y, int z) {
        int iso = isolated();
        if (iso == 0) return true;
        Resolved r = model.resolved(x, y, z);
        return r != null && r.owner() == iso;
    }

    /** 선택한 구조물의 볼륨 (단면 범위·슬라이더). */
    @Override
    @Nullable
    public Box focusBounds() {
        if (selected == 0) return null;
        StructureInstance s = model.structure(selected);
        return s == null ? null : s.bounds();
    }

    @Override
    protected int lowestDesignY() {
        int min = Integer.MAX_VALUE;
        for (long p : model.resolvedMap().keySet()) min = Math.min(min, BlockPos.getY(p));
        return min;
    }

    @Override
    protected int highestDesignY() {
        int max = Integer.MIN_VALUE;
        for (long p : model.resolvedMap().keySet()) max = Math.max(max, BlockPos.getY(p));
        return max;
    }

    @Override
    protected long[] changedPositions(AreaOp op) {
        return switch (op) {
            case ModelOps.Terrain t -> t.pos();
            case ModelOps.StructCells t -> t.pos();
            case ModelOps.StructCreate t -> t.pos();
            case ModelOps.StructReplace t -> t.pos();
            default -> new long[0];
        };
    }

    @Override
    protected void afterApplied(UUID actor, int seq, List<AreaOp> ops, boolean mine) {
        if (mine && seq == awaitingCreateSeq) {
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
        if (selected != 0 && model.structure(selected) == null) selected = 0;
    }

    @Override
    protected void afterRejected(int seq) {
        if (seq == awaitingCreateSeq) {
            awaitingCreateSeq = -1;
            // 만들기가 거절됐으면 모아 둔 칸의 첫 묶음으로 다시 만든다
            if (!waitingForCreate.isEmpty()) {
                Requests.StructCells first = waitingForCreate.remove(0);
                sendCreating(new Requests.StructCells(first.group(), 0, newStructureName(), first.pos(), first.states()));
            }
        }
    }

    /** 서버가 도면 전체를 다시 보냄 (순서가 어긋났을 때). */
    @Override
    protected void onAreaSwapped(Area newArea) {
        model = BlueprintModel.of(newArea);
        if (selected != 0 && model.structure(selected) == null) selected = 0;
    }
}

package dev.designandbuild.client.projection;

import dev.designandbuild.client.ClientConfig;
import dev.designandbuild.core.BlueprintModel;
import dev.designandbuild.core.DesignTag;
import dev.designandbuild.core.Resolved;
import dev.designandbuild.core.StateRules;
import dev.designandbuild.core.StructureInstance;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 도면 하나의 투영 상태: 설계 칸마다 지금 월드와 비교한 결과, 단계·층별 남은 개수, 현재 단계와 층.
 *
 * 단계: 0 = 철거, 1 = 바닥, 2 = 길, 3.. = 구조물(순서대로).
 * 철거는 위층부터, 나머지는 아래층부터 진행한다. 비교는 클라이언트가 가진 월드 블록으로 조금씩 굴려 가며 한다.
 */
public final class Projection {
    public static final byte UNKNOWN = 0, DONE = 1, PLACE = 2, WRONG = 3, DEMOLISH = 4, FILL = 5, CONVERT = 6, WAIT = 7, INVALID = 8;
    public static final int STAGE_DEMOLISH = 0, STAGE_FLOOR = 1, STAGE_PATH = 2, STAGE_STRUCTURES = 3;
    public static final int NO_LAYER = Integer.MIN_VALUE;

    public BlueprintModel model;
    private long builtRevision = Long.MIN_VALUE;

    // ---- 칸 ----
    long[] pos = new long[0];
    BlockState[] target = new BlockState[0];
    int[] stage = new int[0];
    byte[] status = new byte[0];
    int n;
    private Long2ObjectOpenHashMap<IntArrayList> bySection = new Long2ObjectOpenHashMap<>();
    /** 위치 → 칸 번호 (건설망치 조준, 유령 블록 이웃 조회). */
    private Long2IntOpenHashMap index = new Long2IntOpenHashMap();
    private long[] sectionKeys = new long[0];
    private boolean sectionKeysStale;

    /** 구역(SectionPos) 안의 칸 번호들. */
    IntArrayList cellsIn(long section) {
        return bySection.get(section);
    }

    long[] sections() {
        if (sectionKeysStale) {
            sectionKeys = bySection.keySet().toLongArray();
            sectionKeysStale = false;
        }
        return sectionKeys;
    }

    /** 이 위치에 지금 보이는 "놓을" 유령 블록 (없으면 null). 유령끼리 맞닿은 면을 지우는 데 쓴다. */
    @org.jetbrains.annotations.Nullable
    BlockState ghostAt(long p) {
        int i = index.get(p);
        return i >= 0 && (status[i] == PLACE || status[i] == INVALID) && inView(i) ? target[i] : null;
    }

    // ---- 도면 변경 추적 (증분 반영) ----
    private final LongOpenHashSet changed = new LongOpenHashSet();
    private boolean changedOverflow;
    @org.jetbrains.annotations.Nullable
    private BlueprintModel listening;
    private final java.util.function.LongConsumer changeListener = p -> {
        if (changedOverflow) return;
        changed.add(p);
        if (changed.size() > 200_000) {
            changedOverflow = true;
            changed.clear();
        }
    };

    private void listenTo(BlueprintModel m) {
        if (listening == m) return;
        if (listening != null) listening.removeListener(changeListener);
        m.addListener(changeListener);
        listening = m;
        changed.clear();
        changedOverflow = false;
    }

    /** 투영을 버릴 때: 도면 변경 알림을 그만 받는다. */
    void detach() {
        if (listening != null) listening.removeListener(changeListener);
        listening = null;
    }

    // ---- 단계 ----
    int stageCount = 3;
    int[] structIds = new int[0];
    String[] structNames = new String[0];
    int[] total = new int[3];
    /** 남은 칸 = 확인했는데 아직 안 맞는 칸. 시야 밖(불러오지 않은 청크) 칸은 unknownBy 로 따로 센다. */
    int[] remaining = new int[3];
    int[] unknownBy = new int[3];
    Int2IntOpenHashMap[] layerRemaining = new Int2IntOpenHashMap[0];
    int demolishCount;
    final Int2IntOpenHashMap demolishLayer = new Int2IntOpenHashMap();
    int unknownCount;

    // ---- 평가 ----
    private int cursor;
    boolean firstPassDone;
    /** 평가용 위치 (클라이언트 틱 스레드에서만 쓴다). */
    private final BlockPos.MutableBlockPos evalPos = new BlockPos.MutableBlockPos(), nearPos = new BlockPos.MutableBlockPos();

    // ---- 진행 ----
    int cur;
    int curLayer = NO_LAYER;
    boolean manualLayer;
    private boolean initialized;
    private boolean[] prevComplete = new boolean[0];
    private int prevLayerRemaining = -1;
    /** 그림을 다시 만들어야 하는 구역 (allDirty 면 전부). */
    final LongOpenHashSet dirtySections = new LongOpenHashSet();
    boolean allDirty = true;
    /** 마지막 단계 완료 알림 (HUD·소리용). */
    int events;
    public static final int EVENT_LAYER = 1, EVENT_STAGE = 2, EVENT_ALL = 4;

    Projection(BlueprintModel model) {
        this.model = model;
        listenTo(model);
        refreshViewMode();
    }

    public boolean onlyLayer() {
        return viewMode == dev.designandbuild.core.Stages.LAYER_ONLY;
    }

    private int viewMode;

    /** 이 도면의 층 표시 방식 (core.Stages.LAYER_ONLY / BELOW / ALL). 칸마다 묻는 값이라 설정에서 읽어 둔다. */
    public int viewMode() {
        return viewMode;
    }

    /** 설정의 층 표시 방식을 다시 읽는다 (도면 메뉴에서 바꿨을 때). */
    void refreshViewMode() {
        String id = model.id.toString();
        if (ClientConfig.get().layerAll.contains(id)) {
            viewMode = dev.designandbuild.core.Stages.LAYER_ALL;
        } else {
            viewMode = Boolean.TRUE.equals(ClientConfig.get().layerBelow.get(id))
                    ? dev.designandbuild.core.Stages.LAYER_BELOW : dev.designandbuild.core.Stages.LAYER_ONLY;
        }
    }

    /** 층 표시 이름 키 (HUD 용). */
    public String viewModeKey() {
        return switch (viewMode()) {
            case dev.designandbuild.core.Stages.LAYER_ALL -> "designandbuild.projection.mode.all";
            case dev.designandbuild.core.Stages.LAYER_BELOW -> "designandbuild.projection.mode.below";
            default -> "designandbuild.projection.mode.only";
        };
    }

    // ------------------------------------------------------------------
    // 칸 목록 만들기
    // ------------------------------------------------------------------

    boolean needsRebuild() {
        return model.revision() != builtRevision;
    }

    /** 서버가 도면 전체를 다시 보냈다. */
    void replaceModel(BlueprintModel m) {
        model = m;
        builtRevision = Long.MIN_VALUE;
        listenTo(m);
        refreshViewMode();
    }

    /** 바뀐 칸만 고칠 수 있는가: 처음이 아니고, 단계 목록(구조물 순서)이 그대로이고, 바뀐 칸이 많지 않다. */
    private boolean canPatch() {
        if (builtRevision == Long.MIN_VALUE || changedOverflow) return false;
        if (changed.size() > Math.max(4096, n / 4)) return false;
        List<StructureInstance> ordered = model.orderedStructures();
        if (ordered.size() != structIds.length) return false;
        for (int i = 0; i < structIds.length; i++) if (ordered.get(i).id != structIds[i]) return false;
        return true;
    }

    void rebuild(ClientLevel level) {
        if (canPatch()) {
            patch(level);
            return;
        }
        changed.clear();
        changedOverflow = false;
        builtRevision = model.revision();
        int keepStructure = cur >= STAGE_STRUCTURES && cur - STAGE_STRUCTURES < structIds.length
                ? structIds[cur - STAGE_STRUCTURES] : 0;
        int keepStage = cur;

        // 단계 목록
        List<StructureInstance> ordered = model.orderedStructures();
        structIds = new int[ordered.size()];
        structNames = new String[ordered.size()];
        Int2IntOpenHashMap stageOfStructure = stageMap(ordered);
        for (int i = 0; i < ordered.size(); i++) {
            structIds[i] = ordered.get(i).id;
            structNames[i] = ordered.get(i).name;
        }
        stageCount = STAGE_STRUCTURES + structIds.length;

        // 이전 결과를 이어받을 수 있게
        Long2IntOpenHashMap old = new Long2IntOpenHashMap(n);
        old.defaultReturnValue(-1);
        for (int i = 0; i < n; i++) old.put(pos[i], i);
        BlockState[] oldTarget = target;
        byte[] oldStatus = status;

        Long2ObjectMap<Resolved> map = model.resolvedMap();
        int size = map.size();
        long[] np = new long[size];
        BlockState[] nt = new BlockState[size];
        int[] ns = new int[size];
        byte[] nst = new byte[size];
        Long2ObjectOpenHashMap<IntArrayList> sections = new Long2ObjectOpenHashMap<>();
        int k = 0;
        for (Long2ObjectMap.Entry<Resolved> e : map.long2ObjectEntrySet()) {
            long p = e.getLongKey();
            Resolved r = e.getValue();
            np[k] = p;
            nt[k] = r.state();
            ns[k] = stageFor(r, stageOfStructure);
            int oi = old.get(p);
            nst[k] = oi >= 0 && oldTarget[oi] == r.state() ? oldStatus[oi] : UNKNOWN;
            sections.computeIfAbsent(sectionOf(p), q -> new IntArrayList()).add(k);
            k++;
        }
        Long2IntOpenHashMap idx = new Long2IntOpenHashMap(size);
        idx.defaultReturnValue(-1);
        for (int i = 0; i < size; i++) idx.put(np[i], i);
        index = idx;
        pos = np;
        target = nt;
        stage = ns;
        status = nst;
        n = size;
        // 새로 생긴 칸은 바로 비교한다 (많지 않으면). 그래야 진행률이 흔들리지 않는다
        int unknown = 0;
        for (int i = 0; i < n; i++) if (status[i] == UNKNOWN) unknown++;
        if (unknown <= 200_000) {
            for (int i = 0; i < n; i++) if (status[i] == UNKNOWN) status[i] = evaluate(level, i);
            firstPassDone = true;
        } else {
            firstPassDone = false;
        }
        bySection = sections;
        sectionKeysStale = true;
        recount();
        if (cursor >= n) cursor = 0;

        // 보던 단계 유지
        if (keepStructure != 0 && stageOfStructure.containsKey(keepStructure)) {
            cur = stageOfStructure.get(keepStructure);
        } else if (keepStage >= STAGE_STRUCTURES) {
            cur = Math.min(keepStage, stageCount);
        }
        prevComplete = new boolean[stageCount];
        for (int s = 0; s < stageCount; s++) prevComplete[s] = isComplete(s);
        allDirty = true;
    }

    // ------------------------------------------------------------------
    // 증분 반영 (다른 사람이 편집 중일 때 전체를 다시 만들지 않는다)
    // ------------------------------------------------------------------

    /** 구조물 ID → 단계 번호 (없으면 -1). */
    private static Int2IntOpenHashMap stageMap(List<StructureInstance> ordered) {
        Int2IntOpenHashMap m = new Int2IntOpenHashMap();
        m.defaultReturnValue(-1);
        for (int i = 0; i < ordered.size(); i++) m.put(ordered.get(i).id, STAGE_STRUCTURES + i);
        return m;
    }

    private static int stageFor(Resolved r, Int2IntOpenHashMap stageOf) {
        if (r.isAir()) return STAGE_DEMOLISH;
        if (r.tag() == DesignTag.PATH) return STAGE_PATH;
        if (r.tag() == DesignTag.STRUCTURE && r.owner() != 0) {
            int s = stageOf.get(r.owner());
            if (s >= 0) return s;
        }
        return STAGE_FLOOR;
    }

    private void patch(ClientLevel level) {
        builtRevision = model.revision();
        List<StructureInstance> ordered = model.orderedStructures();
        Int2IntOpenHashMap stageOf = stageMap(ordered);
        for (int i = 0; i < ordered.size(); i++) structNames[i] = ordered.get(i).name;
        it.unimi.dsi.fastutil.longs.LongIterator it = changed.iterator();
        while (it.hasNext()) {
            long p = it.nextLong();
            Resolved r = model.resolved(p);
            int i = index.get(p);
            if (r == null) {
                if (i >= 0) removeCell(i);
                continue;
            }
            int st = stageFor(r, stageOf);
            if (i < 0) {
                addCell(level, p, r.state(), st);
            } else if (target[i] != r.state() || stage[i] != st) {
                countCell(i, -1);
                target[i] = r.state();
                stage[i] = st;
                status[i] = evaluate(level, i);
                countCell(i, 1);
                dirtyAround(p);
            }
        }
        changed.clear();
    }

    private void addCell(ClientLevel level, long p, BlockState t, int st) {
        if (n == pos.length) {
            int cap = Math.max(64, n + (n >> 1));
            pos = java.util.Arrays.copyOf(pos, cap);
            target = java.util.Arrays.copyOf(target, cap);
            stage = java.util.Arrays.copyOf(stage, cap);
            status = java.util.Arrays.copyOf(status, cap);
        }
        int i = n++;
        pos[i] = p;
        target[i] = t;
        stage[i] = st;
        status[i] = evaluate(level, i);
        index.put(p, i);
        long sec = sectionOf(p);
        IntArrayList l = bySection.get(sec);
        if (l == null) {
            l = new IntArrayList();
            bySection.put(sec, l);
            sectionKeysStale = true;
        }
        l.add(i);
        countCell(i, 1);
        dirtyAround(p);
    }

    /** 칸을 빼고 마지막 칸을 그 자리로 옮긴다. */
    private void removeCell(int i) {
        long p = pos[i];
        countCell(i, -1);
        dirtyAround(p);
        long sec = sectionOf(p);
        IntArrayList l = bySection.get(sec);
        if (l != null) {
            l.rem(i);
            if (l.isEmpty()) {
                bySection.remove(sec);
                sectionKeysStale = true;
            }
        }
        index.remove(p);
        int last = n - 1;
        if (i != last) {
            long lp = pos[last];
            pos[i] = lp;
            target[i] = target[last];
            stage[i] = stage[last];
            status[i] = status[last];
            index.put(lp, i);
            IntArrayList ll = bySection.get(sectionOf(lp));
            if (ll != null) {
                int k = ll.indexOf(last);
                if (k >= 0) ll.set(k, i);
            }
        }
        target[last] = null;
        n--;
        if (cursor >= n) cursor = 0;
    }

    /**
     * 설치 단계에서 이 칸이 속하는 층. 받침이 위에 있는 블록(매단 랜턴 등)은 받침 층에서 놓는다
     * — 아래층부터 지으면 받침이 아직 없어 놓을 수 없고, 그 칸 때문에 층이 끝나지 않기 때문이다.
     */
    private int layerY(int i) {
        return dev.designandbuild.core.Stages.buildLayerY(target[i], BlockPos.getY(pos[i]));
    }

    /** 칸 하나를 개수에 더하거나(1) 뺀다(-1). {@link #recount} 와 같은 규칙. */
    private void countCell(int i, int d) {
        int s = stage[i];
        int y = BlockPos.getY(pos[i]);
        byte st = status[i];
        if (s != STAGE_DEMOLISH) {
            total[s] += d;
            if (st == UNKNOWN) {
                unknownBy[s] += d;
            } else if (st != DONE) {
                remaining[s] += d;
                int ly = layerY(i);
                layerRemaining[s].addTo(ly, d);
                if (layerRemaining[s].get(ly) <= 0) layerRemaining[s].remove(ly);
            }
        }
        if (st == DEMOLISH || st == FILL) {
            demolishCount += d;
            demolishLayer.addTo(y, d);
            if (demolishLayer.get(y) <= 0) demolishLayer.remove(y);
        }
        if (st == UNKNOWN) unknownCount += d;
    }

    private static long sectionOf(long p) {
        return SectionPos.asLong(BlockPos.getX(p) >> 4, BlockPos.getY(p) >> 4, BlockPos.getZ(p) >> 4);
    }

    /** 이 칸의 구역과, 경계면에 닿아 있으면 이웃 구역도 다시 그린다 (유령끼리 맞닿은 면이 바뀌므로). */
    private void dirtyAround(long p) {
        int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        dirtySections.add(SectionPos.asLong(sx, sy, sz));
        int lx = x & 15, ly = y & 15, lz = z & 15;
        if (lx == 0) dirtySections.add(SectionPos.asLong(sx - 1, sy, sz));
        if (lx == 15) dirtySections.add(SectionPos.asLong(sx + 1, sy, sz));
        if (ly == 0) dirtySections.add(SectionPos.asLong(sx, sy - 1, sz));
        if (ly == 15) dirtySections.add(SectionPos.asLong(sx, sy + 1, sz));
        if (lz == 0) dirtySections.add(SectionPos.asLong(sx, sy, sz - 1));
        if (lz == 15) dirtySections.add(SectionPos.asLong(sx, sy, sz + 1));
    }

    /**
     * 층만 바뀌었을 때 다시 그릴 구역: 보이는 칸이 달라지는 높이(2칸 블록 때문에 ±1)에 걸친 구역만.
     * 단계 전체 보기면 층과 무관하므로 없다.
     */
    private void dirtyLayerChange(int oldLayer, int newLayer) {
        if (oldLayer == newLayer) return;
        if (viewMode == dev.designandbuild.core.Stages.LAYER_ALL) return;
        if (oldLayer == NO_LAYER || newLayer == NO_LAYER) {
            allDirty = true;
            return;
        }
        int lo, hi;
        boolean only = onlyLayer();
        long[] keys = sections();
        for (long sec : keys) {
            int y0 = SectionPos.y(sec) << 4, y1 = y0 + 15;
            boolean hit;
            if (only) {
                hit = (oldLayer + 1 >= y0 && oldLayer - 1 <= y1) || (newLayer + 1 >= y0 && newLayer - 1 <= y1);
            } else {
                lo = Math.min(oldLayer, newLayer) - 1;
                hi = Math.max(oldLayer, newLayer) + 1;
                hit = hi >= y0 && lo <= y1;
            }
            if (hit) dirtySections.add(sec);
        }
    }

    private void recount() {
        total = new int[stageCount];
        remaining = new int[stageCount];
        unknownBy = new int[stageCount];
        layerRemaining = new Int2IntOpenHashMap[stageCount];
        for (int s = 0; s < stageCount; s++) layerRemaining[s] = new Int2IntOpenHashMap();
        demolishLayer.clear();
        demolishCount = 0;
        unknownCount = 0;
        for (int i = 0; i < n; i++) countCell(i, 1);
    }

    // ------------------------------------------------------------------
    // 평가
    // ------------------------------------------------------------------

    private byte evaluate(ClientLevel level, int i) {
        long p = pos[i];
        int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
        if (!level.hasChunk(x >> 4, z >> 4)) return UNKNOWN;
        BlockState world = level.getBlockState(evalPos.set(x, y, z));
        BlockState t = target[i];
        if (StateRules.matches(world, t)) return DONE;
        if (StateRules.needsDemolish(level, world, t)) return StateRules.isFillTarget(world) ? FILL : DEMOLISH;
        if (t.isAir()) return DONE;
        if (StateRules.sameBlockWrongState(world, t)) return WRONG;
        if (StateRules.conversion(level, world, t) != null) return CONVERT;
        int sup = support(level, i, evalPos.immutable()); // 버팀 검사에는 고정 위치를 넘긴다 (블록이 위치를 붙잡아 둘 수 있다)
        return sup == 1 ? WAIT : sup == 2 ? INVALID : PLACE;
    }

    /**
     * 받침 상태: 0 = 버틴다, 1 = 받침이 될 이웃이 아직 안 지은 설계 칸이라 기다린다 (투영에 안 보이고 남은 칸으로는 센다),
     * 2 = 받침이 생길 수 없다 (이웃이 설계 칸이 아니거나 지어도 못 받침) → 놓을 수 없는 칸 (보라 외곽선).
     */
    private int support(ClientLevel level, int i, BlockPos p) {
        BlockState t = target[i];
        try {
            if (t.canSurvive(level, p)) return 0;
        } catch (Exception e) {
            return 0;
        }
        BlockPos.MutableBlockPos q = nearPos;
        for (net.minecraft.core.Direction d : DIRECTIONS) {
            q.setWithOffset(p, d);
            int j = index.get(q.asLong());
            if (j < 0 || target[j].isAir()) continue;
            if (!StateRules.matches(level.getBlockState(q), target[j])) return 1;
        }
        return 2;
    }

    private static final net.minecraft.core.Direction[] DIRECTIONS = net.minecraft.core.Direction.values();

    private void setStatus(int i, byte ns) {
        byte old = status[i];
        if (old == ns) return;
        status[i] = ns;
        int s = stage[i];
        int y = BlockPos.getY(pos[i]);
        if (s != STAGE_DEMOLISH) {
            if (old == UNKNOWN) unknownBy[s]--;
            if (ns == UNKNOWN) unknownBy[s]++;
            boolean was = old != DONE && old != UNKNOWN, is = ns != DONE && ns != UNKNOWN;
            if (was != is) {
                int d = is ? 1 : -1;
                remaining[s] += d;
                int ly = layerY(i);
                layerRemaining[s].addTo(ly, d);
                if (layerRemaining[s].get(ly) <= 0) layerRemaining[s].remove(ly);
            }
        }
        boolean wasDem = old == DEMOLISH || old == FILL, isDem = ns == DEMOLISH || ns == FILL;
        if (wasDem != isDem) {
            int d = isDem ? 1 : -1;
            demolishCount += d;
            demolishLayer.addTo(y, d);
            if (demolishLayer.get(y) <= 0) demolishLayer.remove(y);
        }
        if (old == UNKNOWN) unknownCount--;
        if (ns == UNKNOWN) unknownCount++;
        if (s == cur || (cur == STAGE_DEMOLISH && (wasDem || isDem))) dirtyAround(pos[i]);
    }

    /** 칸 일부를 다시 비교한다. */
    void step(ClientLevel level, int budget) {
        if (n == 0) {
            firstPassDone = true;
            return;
        }
        int count = Math.min(budget, n);
        for (int k = 0; k < count; k++) {
            setStatus(cursor, evaluate(level, cursor));
            if (++cursor >= n) {
                cursor = 0;
                firstPassDone = true;
            }
        }
    }

    /** 플레이어 주변 구역은 자주 다시 본다 (방금 놓은 블록이 바로 반영되게). */
    void stepNear(ClientLevel level, BlockPos center, int radiusSections) {
        int sx = center.getX() >> 4, sy = center.getY() >> 4, sz = center.getZ() >> 4;
        for (int dx = -radiusSections; dx <= radiusSections; dx++) {
            for (int dy = -radiusSections; dy <= radiusSections; dy++) {
                for (int dz = -radiusSections; dz <= radiusSections; dz++) {
                    IntArrayList cells = bySection.get(SectionPos.asLong(sx + dx, sy + dy, sz + dz));
                    if (cells == null) continue;
                    for (int k = 0; k < cells.size(); k++) {
                        int i = cells.getInt(k);
                        setStatus(i, evaluate(level, i));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 단계와 층
    // ------------------------------------------------------------------

    /**
     * 확인된 칸 기준으로 끝났는가. 시야 밖(불러오지 않은 청크) 칸은 판정에서 빼고 {@link #unknown} 으로 따로 보여 준다.
     * 넓은 영역에서 먼 쪽 칸 때문에 단계가 영영 안 끝나는 일을 막는다.
     */
    public boolean isComplete(int s) {
        if (s == STAGE_DEMOLISH) return demolishCount == 0 && firstPassDone;
        if (s < 0 || s >= stageCount) return true;
        return remaining[s] == 0;
    }

    /** 이 단계에서 아직 확인하지 못한 칸 (시야 밖). 철거는 전체 설계 칸 중 미확인 수. */
    public int unknown(int s) {
        if (s == STAGE_DEMOLISH) return unknownCount;
        if (s < 0 || s >= stageCount) return 0;
        return unknownBy[s];
    }

    /** 확인된 칸 기준 진행률 (%). 미확인 칸은 아직 안 된 것으로 친다. */
    public int percent(int s) {
        if (s <= STAGE_DEMOLISH || s >= stageCount || total[s] == 0) return 100;
        return (int) Math.floor(100.0 * (total[s] - remaining[s] - unknownBy[s]) / total[s]);
    }

    public boolean allComplete() {
        for (int s = 0; s < stageCount; s++) if (!isComplete(s)) return false;
        return true;
    }

    private int nextIncomplete(int from) {
        for (int k = 1; k <= stageCount; k++) {
            int s = (from + k) % stageCount;
            if (!isComplete(s)) return s;
        }
        return stageCount;
    }

    private int firstIncomplete() {
        for (int s = 0; s < stageCount; s++) if (!isComplete(s)) return s;
        return stageCount;
    }

    public int autoLayer(int s) {
        if (s == STAGE_DEMOLISH) {
            int best = NO_LAYER;
            for (int y : demolishLayer.keySet()) if (demolishLayer.get(y) > 0 && (best == NO_LAYER || y > best)) best = y;
            return best;
        }
        if (s < 0 || s >= stageCount) return NO_LAYER;
        int best = NO_LAYER;
        Int2IntOpenHashMap m = layerRemaining[s];
        for (int y : m.keySet()) if (m.get(y) > 0 && (best == NO_LAYER || y < best)) best = y;
        return best;
    }

    public int layerRemaining(int s, int y) {
        if (y == NO_LAYER) return 0;
        if (s == STAGE_DEMOLISH) return demolishLayer.get(y);
        if (s < 0 || s >= stageCount) return 0;
        return layerRemaining[s].get(y);
    }

    /** 매 틱: 완료를 감지해 다음 단계·층으로 넘긴다. */
    void updateNavigation() {
        events = 0;
        if (!firstPassDone) return;
        if (!initialized) {
            initialized = true;
            cur = firstIncomplete();
            curLayer = autoLayer(cur);
            prevComplete = new boolean[stageCount];
            for (int s = 0; s < stageCount; s++) prevComplete[s] = isComplete(s);
            prevLayerRemaining = layerRemaining(cur, curLayer);
            allDirty = true;
            return;
        }
        if (prevComplete.length != stageCount) {
            prevComplete = new boolean[stageCount];
            for (int s = 0; s < stageCount; s++) prevComplete[s] = isComplete(s);
        }
        int before = cur;
        if (cur < stageCount) {
            boolean now = isComplete(cur);
            if (now && !prevComplete[cur]) {
                cur = nextIncomplete(cur);
                events |= cur >= stageCount && unknownCount == 0 ? EVENT_ALL : EVENT_STAGE;
            }
        } else if (!allComplete()) {
            cur = firstIncomplete();
        }
        for (int s = 0; s < stageCount; s++) prevComplete[s] = isComplete(s);
        if (cur != before) {
            manualLayer = false;
            curLayer = autoLayer(cur);
            prevLayerRemaining = layerRemaining(cur, curLayer);
            allDirty = true;
            return;
        }
        int rem = layerRemaining(cur, curLayer);
        if (manualLayer) {
            if (prevLayerRemaining > 0 && rem == 0) {
                manualLayer = false;
                events |= EVENT_LAYER;
            }
        }
        if (!manualLayer) {
            int auto = autoLayer(cur);
            if (auto != curLayer) {
                if (curLayer != NO_LAYER && prevLayerRemaining > 0 && rem == 0 && auto != NO_LAYER) events |= EVENT_LAYER;
                dirtyLayerChange(curLayer, auto);
                curLayer = auto;
            }
        }
        prevLayerRemaining = layerRemaining(cur, curLayer);
    }

    void moveStage(int d) {
        if (stageCount == 0) return;
        int base = cur >= stageCount ? (d > 0 ? -1 : stageCount) : cur;
        cur = Math.max(0, Math.min(stageCount - 1, base + d));
        manualLayer = false;
        curLayer = autoLayer(cur);
        prevLayerRemaining = layerRemaining(cur, curLayer);
        allDirty = true;
    }

    /**
     * 도면 메뉴에서 단계를 골라 바로 이동 (이 클라이언트에서만). 끝난 단계로 돌아가도 튕겨 나가지 않는다:
     * 자동 넘김은 보고 있는 단계가 "방금 끝나는 순간"에만 일어난다.
     */
    public void jumpToStage(int s) {
        if (stageCount == 0) return;
        cur = Math.max(0, Math.min(stageCount - 1, s));
        manualLayer = false;
        curLayer = autoLayer(cur);
        prevLayerRemaining = layerRemaining(cur, curLayer);
        allDirty = true;
    }

    /** 처음부터 보아 아직 안 끝난 첫 단계로. */
    public void jumpToFirstIncomplete() {
        int s = firstIncomplete();
        if (s >= stageCount) {
            cur = stageCount;
            allDirty = true;
        } else {
            jumpToStage(s);
        }
    }

    void moveLayer(int d, int playerY) {
        int before = curLayer;
        if (curLayer == NO_LAYER) curLayer = playerY;
        curLayer += d;
        manualLayer = true;
        prevLayerRemaining = layerRemaining(cur, curLayer);
        dirtyLayerChange(before, curLayer);
    }

    // ------------------------------------------------------------------
    // 표시할 칸
    // ------------------------------------------------------------------

    /** 지금 단계·층 보기 기준으로 이 칸을 그리는가. */
    boolean inView(int i) {
        if (cur >= stageCount || curLayer == NO_LAYER) return false;
        byte st = status[i];
        if (st == DONE || st == UNKNOWN || st == WAIT) return false;
        boolean dem = st == DEMOLISH || st == FILL;
        if (cur == STAGE_DEMOLISH) {
            if (!dem) return false;
        } else if (stage[i] != cur) {
            return false;
        }
        if (viewMode() == dev.designandbuild.core.Stages.LAYER_ALL) return true; // 단계 전체: 층 조건 없음
        boolean topDown = cur == STAGE_DEMOLISH;
        int y = topDown ? BlockPos.getY(pos[i]) : layerY(i);
        boolean only = onlyLayer();
        if (layerOk(y, only, topDown)) return true;
        // 2칸 블록은 반쪽이 이 층에 걸리면 함께 보인다
        BlockState t = target[i];
        if (t.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            boolean upper = t.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
            return layerOk(upper ? y - 1 : y + 1, only, topDown);
        }
        return false;
    }

    private boolean layerOk(int y, boolean only, boolean topDown) {
        if (only) return y == curLayer;
        return topDown ? y >= curLayer : y <= curLayer;
    }

    // ------------------------------------------------------------------
    // 재료
    // ------------------------------------------------------------------

    /**
     * 재료 목록 한 줄. tool 이 0 이상이면 가공 줄: item 은 도구 그림, count 는 사용 횟수.
     */
    public record Material(Item item, int count, int hint, int tool) {
        public boolean isTool() {
            return tool >= 0;
        }
    }

    /**
     * 현재 단계(구조물 단계는 현재 구조물)에서 남은 재료. layerOnly 면 현재 층만.
     * 이미 블록이 있는 칸(방향만 틀림)은 재료가 들지 않는다. 가공할 칸은 재료 대신 도구 줄(사용 횟수)로 센다.
     * 칸마다 환산하지 않고 상태별로 센 뒤 상태마다 한 번 환산한다.
     */
    List<Material> materials(ClientLevel level, boolean layerOnly) {
        List<Material> out = new ArrayList<>();
        if (cur <= STAGE_DEMOLISH || cur >= stageCount) return out;
        it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<BlockState> byState = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<>();
        int[] toolUses = new int[StateRules.TOOL_COUNT];
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int i = 0; i < n; i++) {
            if (stage[i] != cur) continue;
            byte st = status[i];
            if (st == DONE || st == WRONG) continue;
            if (layerOnly && layerY(i) != curLayer) continue;
            if (st == CONVERT) {
                StateRules.Conversion c = StateRules.conversion(level, level.getBlockState(mp.set(pos[i])), target[i]);
                if (c != null) for (int t = 0; t < toolUses.length; t++) toolUses[t] += c.uses()[t];
                continue;
            }
            byState.addTo(target[i], 1);
        }
        for (int t = 0; t < toolUses.length; t++) {
            if (toolUses[t] > 0) out.add(new Material(StateRules.toolIcon(t), toolUses[t], StateRules.HINT_NONE, t));
        }
        Object2IntLinkedOpenHashMap<Item> counts = new Object2IntLinkedOpenHashMap<>();
        Map<Item, Integer> hints = new HashMap<>();
        for (var e : byState.object2IntEntrySet()) {
            int times = e.getIntValue();
            Item[] first = new Item[1];
            int hint = StateRules.requirement(e.getKey(), (item, c) -> {
                if (first[0] == null) first[0] = item;
                counts.addTo(item, c * times);
            });
            if (hint != StateRules.HINT_NONE && first[0] != null) hints.put(first[0], hint);
        }
        for (var e : counts.object2IntEntrySet()) {
            if (e.getKey() == net.minecraft.world.item.Items.AIR) continue;
            out.add(new Material(e.getKey(), e.getIntValue(), hints.getOrDefault(e.getKey(), StateRules.HINT_NONE), -1));
        }
        // 도구 줄이 먼저, 그다음 많이 드는 재료 순
        out.sort((a, b) -> a.isTool() != b.isTool() ? (a.isTool() ? -1 : 1) : b.count() - a.count());
        return out;
    }

    // ------------------------------------------------------------------
    // HUD 용 값
    // ------------------------------------------------------------------

    public int stageCount() {
        return stageCount;
    }

    public int currentStage() {
        return cur;
    }

    public int currentLayer() {
        return curLayer;
    }

    public int total(int s) {
        return s > 0 && s < stageCount ? total[s] : 0;
    }

    public int remaining(int s) {
        return s == STAGE_DEMOLISH ? demolishCount : s < stageCount ? remaining[s] : 0;
    }

    public int completedStructures() {
        int c = 0;
        for (int s = STAGE_STRUCTURES; s < stageCount; s++) if (isComplete(s)) c++;
        return c;
    }

    public String structureName(int s) {
        int i = s - STAGE_STRUCTURES;
        return i >= 0 && i < structNames.length ? structNames[i] : "";
    }

    /** 현재 구조물의 층 범위 (최저 Y, 층 수). 구조물 단계가 아니면 null. */
    public int[] structureFloors() {
        if (cur < STAGE_STRUCTURES || cur >= stageCount) return null;
        StructureInstance st = model.structure(structIds[cur - STAGE_STRUCTURES]);
        if (st == null || st.bounds() == null) return null;
        return new int[]{st.bounds().minY(), st.bounds().sizeY()};
    }

    public boolean isReady() {
        return firstPassDone;
    }

    // ------------------------------------------------------------------
    // 서버에 알리는 보기 상태 · 건설망치 조준
    // ------------------------------------------------------------------

    /** 지금 보는 단계의 (종류, 구조물 ID). 다 끝났으면 종류 NONE. */
    public int[] currentStageKey() {
        return stageKey(cur);
    }

    public int[] stageKey(int s) {
        if (s < 0 || s >= stageCount) return new int[]{dev.designandbuild.core.Stages.NONE, 0};
        if (s < STAGE_STRUCTURES) return new int[]{s, 0};
        return new int[]{dev.designandbuild.core.Stages.STRUCTURE, structIds[s - STAGE_STRUCTURES]};
    }

    /** 층 보기 방식 (core.Stages.LAYER_*). */
    public int layerMode() {
        if (curLayer == NO_LAYER || cur >= stageCount) return dev.designandbuild.core.Stages.LAYER_NONE;
        return viewMode();
    }

    /** 망치로 겨눌 수 있는 상태: 놓을 칸, 그리고 이유를 알려 줄 칸(놓을 수 없음·방향 틀림·가공). */
    private static boolean aimable(byte st) {
        return st == PLACE || st == INVALID || st == WRONG || st == CONVERT;
    }

    /**
     * 시선이 처음 닿는 망치 대상 투영 칸. 실제 블록(부딪히는 것)에 먼저 막히면 없음.
     * 철거 단계에서는 놓을 칸이 없으니 언제나 없음.
     */
    @org.jetbrains.annotations.Nullable
    public BlockPos pickPlaceable(ClientLevel level, net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 dir, double reach) {
        if (cur <= STAGE_DEMOLISH || cur >= stageCount || n == 0) return null;
        int x = net.minecraft.util.Mth.floor(from.x), y = net.minecraft.util.Mth.floor(from.y), z = net.minecraft.util.Mth.floor(from.z);
        int sx = dir.x > 0 ? 1 : -1, sy = dir.y > 0 ? 1 : -1, sz = dir.z > 0 ? 1 : -1;
        double tdx = dir.x == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.x);
        double tdy = dir.y == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.y);
        double tdz = dir.z == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.z);
        double tmx = dir.x == 0 ? Double.MAX_VALUE : (dir.x > 0 ? (x + 1 - from.x) : (from.x - x)) * tdx;
        double tmy = dir.y == 0 ? Double.MAX_VALUE : (dir.y > 0 ? (y + 1 - from.y) : (from.y - y)) * tdy;
        double tmz = dir.z == 0 ? Double.MAX_VALUE : (dir.z > 0 ? (z + 1 - from.z) : (from.z - z)) * tdz;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        double t = 0;
        for (int guard = 0; guard < 64 && t <= reach; guard++) {
            long p = BlockPos.asLong(x, y, z);
            int i = index.get(p);
            if (i >= 0 && aimable(status[i]) && inView(i)) return BlockPos.of(p);
            mp.set(x, y, z);
            BlockState w = level.getBlockState(mp);
            if (guard > 0 && !w.isAir() && !w.canBeReplaced() && !w.getCollisionShape(level, mp).isEmpty()) return null;
            if (tmx < tmy && tmx < tmz) {
                x += sx;
                t = tmx;
                tmx += tdx;
            } else if (tmy < tmz) {
                y += sy;
                t = tmy;
                tmy += tdy;
            } else {
                z += sz;
                t = tmz;
                tmz += tdz;
            }
        }
        return null;
    }
}

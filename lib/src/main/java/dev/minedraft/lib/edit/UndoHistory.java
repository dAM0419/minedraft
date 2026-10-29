package dev.minedraft.lib.edit;

import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.area.AreaOp;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 플레이어 한 명이 영역 하나에 한 편집 기록 (되돌리기·다시 하기). 기록 한 칸은 한 영역(domain)의 변경만 담는다. */
public final class UndoHistory {
    private static final int LIMIT = 100;
    private static final Map<ResourceLocation, UndoInverter> INVERTERS = new HashMap<>();

    public static void registerInverter(ResourceLocation domain, UndoInverter inverter) {
        if (INVERTERS.putIfAbsent(domain, inverter) != null) throw new IllegalStateException("Duplicate undo domain " + domain);
    }

    private final Deque<Entry> undo = new ArrayDeque<>();
    private final Deque<Entry> redo = new ArrayDeque<>();

    public record Entry(int group, ResourceLocation domain, List<UndoChange> changes) {}

    /** 적용을 마친 기록을 쌓는다 (영역별로 나눠서). 같은 group 의 직전 기록이면 이어 붙인다. */
    public void push(Area area, int group, List<UndoChange> changes) {
        if (changes.isEmpty()) return;
        Map<ResourceLocation, List<UndoChange>> byDomain = new java.util.LinkedHashMap<>();
        for (UndoChange c : changes) byDomain.computeIfAbsent(c.domain(), k -> new ArrayList<>()).add(c);
        redo.clear();
        for (Map.Entry<ResourceLocation, List<UndoChange>> e : byDomain.entrySet()) {
            UndoInverter inv = INVERTERS.get(e.getKey());
            if (inv != null) inv.finish(area, e.getValue());
            Entry last = undo.peekLast();
            if (last != null && group != 0 && last.group() == group && last.domain().equals(e.getKey())) {
                last.changes().addAll(e.getValue());
                continue;
            }
            undo.addLast(new Entry(group, e.getKey(), new ArrayList<>(e.getValue())));
        }
        while (undo.size() > LIMIT) undo.removeFirst();
    }

    /** 되돌리기. 적용할 연산을 out 에 담는다. 되돌릴 게 없으면 false. */
    public boolean undo(Area area, List<AreaOp> out) {
        return step(area, undo, redo, out);
    }

    public boolean redo(Area area, List<AreaOp> out) {
        return step(area, redo, undo, out);
    }

    private static boolean step(Area area, Deque<Entry> from, Deque<Entry> to, List<AreaOp> out) {
        Entry e = from.pollLast();
        if (e == null) return false;
        UndoInverter inv = INVERTERS.get(e.domain());
        if (inv == null) return true;
        List<UndoChange> inverse = inv.invert(area, e.changes(), out);
        if (!inverse.isEmpty()) to.addLast(new Entry(0, e.domain(), new ArrayList<>(inverse)));
        return true;
    }
}

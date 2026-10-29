package dev.minedraft.lib.client.editor.ui;

import dev.minedraft.lib.edit.EditRequests;

import com.mojang.math.Axis;
import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.client.editor.AreaEditorScreen;
import dev.minedraft.lib.client.editor.AreaEditorSession;
import dev.minedraft.lib.client.ui.Icons;
import dev.minedraft.lib.client.ui.Ui;
import dev.minedraft.lib.util.Box;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 범위 탭: 위에서 본 청크 지도. 편집창 시점과 같은 방향으로 돌아가 있어 (Q·E 로 함께 돈다) 지도 위쪽 = 화면 위쪽.
 *
 * 청크를 눌러 고르고 (끌면 여러 개), 아래 "추가"·"삭제" 버튼으로 한꺼번에 반영한다.
 * 추가는 바로, 삭제는 고른 청크 중 수정된 것(설계 칸·구조물·작업 중인 편집자)이 있을 때만 한 번 더 묻는다.
 * 지도 이동은 우클릭 끌기.
 */
public final class RangeTab implements PanelTab {
    private static final int CELL = 11;
    private static final int COLOR_IN = 0x8C2E8C84;        // 포함·수정 안 됨
    private static final int COLOR_MODIFIED = 0xE0D9962F;  // 포함·수정됨
    private static final int COLOR_CANDIDATE = 0x70FFFFFF; // 붙일 수 있음 (테두리)
    private static final int COLOR_EMPTY = 0x12FFFFFF;

    private final AreaEditorScreen screen;
    private final LongOpenHashSet selected = new LongOpenHashSet();
    /** 지도 중심 (청크 좌표, 실수). 처음엔 영역 가운데. */
    private double centerX = Double.NaN, centerZ;
    /** 지도 회전 (라디안): 북쪽이 위인 지도를 이만큼 돌리면 화면 위쪽 = 시선 방향. */
    private float angle;

    // 입력 상태
    private boolean painting, paintSelect, panning, moved;
    private double pressX, pressY, lastX, lastY;
    private long lastPainted = Long.MIN_VALUE;

    // 배치
    private int px, py, pw, ph, mapX, mapY, mapW, mapH, addX, removeX, clearX, buttonY, addW, removeW, clearW;

    // 수정된 청크 캐시
    private final LongOpenHashSet modified = new LongOpenHashSet();
    private long modifiedRevision = Long.MIN_VALUE;

    public RangeTab(AreaEditorScreen screen) {
        this.screen = screen;
    }

    private AreaEditorSession s() {
        return screen.session();
    }

    @Override
    public String title() {
        return "minedraftlib.panel.range";
    }

    @Override
    public int icon() {
        return Icons.RANGE;
    }

    // ------------------------------------------------------------------
    // 좌표 변환
    // ------------------------------------------------------------------

    /** 시선 방향(지면에 투영)을 화면 위로 보내는 회전. 시점 회전 애니메이션도 따라간다. */
    private void updateAngle() {
        Matrix4f r = s().camera.rotationMatrix();
        // 지면 벡터 (dx, dz) 의 화면 위쪽 성분 = m01*dx + m21*dz → 시선 방향 (fx, fz)
        double fx = r.m01(), fz = r.m21();
        if (fx * fx + fz * fz < 1.0E-6) return;
        // 북쪽 위 지도(화면 x = 동, 화면 y = 남)에서 (fx, fz) 방향을 화면 위(0, -1)로
        angle = (float) (-Math.PI / 2 - Math.atan2(fz, fx));
    }

    private double mapCX() {
        return mapX + mapW / 2.0;
    }

    private double mapCY() {
        return mapY + mapH / 2.0;
    }

    /** 화면 → 청크 좌표 (실수). */
    private double[] toChunk(double mx, double my) {
        double dx = mx - mapCX(), dy = my - mapCY();
        double c = Math.cos(-angle), sn = Math.sin(-angle);
        double ux = dx * c - dy * sn, uy = dx * sn + dy * c;
        return new double[]{centerX + ux / CELL, centerZ + uy / CELL};
    }

    /** 청크 좌표 (실수) → 화면. */
    private double[] toScreen(double cx, double cz) {
        double ux = (cx - centerX) * CELL, uy = (cz - centerZ) * CELL;
        double c = Math.cos(angle), sn = Math.sin(angle);
        return new double[]{mapCX() + ux * c - uy * sn, mapCY() + ux * sn + uy * c};
    }

    private long chunkAt(double mx, double my) {
        if (!Ui.inside(mx, my, mapX + 1, mapY + 1, mapW - 2, mapH - 2)) return Long.MIN_VALUE;
        double[] c = toChunk(mx, my);
        return ChunkPos.asLong((int) Math.floor(c[0]), (int) Math.floor(c[1]));
    }

    // ------------------------------------------------------------------
    // 그리기
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        px = x;
        py = y;
        pw = w;
        ph = h;
        AreaEditorSession s = s();
        Area m = s.area;
        refreshModified(m);
        pruneSelection(m);
        updateAngle();
        Component count = Component.translatable("minedraftlib.range.count", m.chunks().size(), m.type.maxChunks());
        Ui.text(g, count, x + 6, y + 5, m.chunks().size() >= m.type.maxChunks() ? Ui.WARN : Ui.TEXT);

        mapX = x + 4;
        mapY = y + 18;
        mapW = w - 8;
        mapH = Math.max(40, Math.min(mapW, h - 96));
        if (Double.isNaN(centerX)) recenter();
        g.fill(mapX, mapY, mapX + mapW, mapY + mapH, 0xFF0B0E12);
        Ui.border(g, mapX, mapY, mapW, mapH, Ui.EDGE);
        long hover = painting || panning ? Long.MIN_VALUE : chunkAt(mx, my);

        g.enableScissor(mapX + 1, mapY + 1, mapX + mapW - 1, mapY + mapH - 1);
        // 지도 안 청크 범위 (돌아가 있으니 넉넉히)
        double radius = Math.hypot(mapW, mapH) / 2.0 / CELL + 1;
        int x0 = (int) Math.floor(centerX - radius), x1 = (int) Math.ceil(centerX + radius);
        int z0 = (int) Math.floor(centerZ - radius), z1 = (int) Math.ceil(centerZ + radius);
        g.pose().pushPose();
        g.pose().translate(mapCX(), mapCY(), 0);
        g.pose().mulPose(Axis.ZP.rotation(angle));
        for (int cz = z0; cz <= z1; cz++) {
            for (int cx = x0; cx <= x1; cx++) {
                long c = ChunkPos.asLong(cx, cz);
                int sx = (int) Math.round((cx - centerX) * CELL), sy = (int) Math.round((cz - centerZ) * CELL);
                boolean in = m.chunks().contains(c);
                if (in) {
                    g.fill(sx, sy, sx + CELL - 1, sy + CELL - 1, modified.contains(c) ? COLOR_MODIFIED : COLOR_IN);
                } else if (touchesArea(m, c)) {
                    Ui.border(g, sx, sy, CELL - 1, CELL - 1, COLOR_CANDIDATE);
                } else {
                    g.fill(sx, sy, sx + CELL - 1, sy + CELL - 1, COLOR_EMPTY);
                }
                if (selected.contains(c)) {
                    Ui.border(g, sx - 1, sy - 1, CELL + 1, CELL + 1, 0xFFFFFFFF);
                    Ui.border(g, sx, sy, CELL - 1, CELL - 1, 0xFFFFFFFF);
                } else if (c == hover) {
                    Ui.border(g, sx - 1, sy - 1, CELL + 1, CELL + 1, 0xA0FFFFFF);
                }
            }
        }
        // 카메라 위치
        double camCX = s.camera.tx / 16.0, camCZ = s.camera.tz / 16.0;
        int kx = (int) Math.round((camCX - centerX) * CELL), kz = (int) Math.round((camCZ - centerZ) * CELL);
        g.fill(kx - 1, kz - 1, kx + 2, kz + 2, Ui.WARN);
        g.pose().popPose();
        // 기준 청크 표시 (돌리지 않음)
        long base = m.baseChunk();
        double[] bp = toScreen(ChunkPos.getX(base) + 0.5, ChunkPos.getZ(base) + 0.5);
        Ui.icon(g, screen.titleIcon(), (int) Math.round(bp[0]) - 8, (int) Math.round(bp[1]) - 8, 0xFFFFFFFF);
        // 북쪽 표시: 지도 가장자리, 실제 북쪽 방향
        double nx = Math.sin(angle) * (Math.min(mapW, mapH) / 2.0 - 7), ny = -Math.cos(angle) * (Math.min(mapW, mapH) / 2.0 - 7);
        Component north = Component.translatable("minedraftlib.compass.letter.north");
        int nw = Ui.font().width(north);
        int lx = (int) Math.round(mapCX() + nx) - nw / 2, ly = (int) Math.round(mapCY() + ny) - 4;
        g.fill(lx - 2, ly - 2, lx + nw + 2, ly + 10, 0xC00B0E12);
        Ui.text(g, north, lx, ly, Ui.DEMOLISH);
        g.disableScissor();

        if (hover != Long.MIN_VALUE) tooltip(m, hover);

        // 범례 (2열)
        int legY = mapY + mapH + 5;
        int col2 = x + w / 2 + 2;
        legend(g, x + 6, legY, COLOR_IN, false, "minedraftlib.range.legend.in");
        legend(g, col2, legY, COLOR_MODIFIED, false, "minedraftlib.range.legend.modified");
        legend(g, x + 6, legY + 12, COLOR_CANDIDATE, true, "minedraftlib.range.legend.add");
        legend(g, col2, legY + 12, 0xFFFFFFFF, true, "minedraftlib.range.legend.selected");

        // 버튼
        int[] counts = counts(m);
        buttonY = legY + 28;
        Component add = Component.translatable("minedraftlib.range.add_button", counts[0]);
        Component remove = Component.translatable("minedraftlib.range.remove_button", counts[1]);
        Component clear = Component.translatable("minedraftlib.range.clear_selection");
        addW = Ui.font().width(add) + 12;
        removeW = Ui.font().width(remove) + 12;
        clearW = Ui.font().width(clear) + 12;
        addX = x + 6;
        removeX = addX + addW + 4;
        clearX = removeX + removeW + 4;
        String addError = counts[0] == 0 ? null : m.checkChunkChange(addSet(m), new LongOpenHashSet());
        String removeError = counts[1] == 0 ? null : m.checkChunkChange(new LongOpenHashSet(), removeSet(m));
        button(g, addX, addW, add, counts[0] > 0 && addError == null, false, mx, my);
        button(g, removeX, removeW, remove, counts[1] > 0 && removeError == null, true, mx, my);
        if (clearX + clearW <= x + w - 4) button(g, clearX, clearW, clear, !selected.isEmpty(), false, mx, my);
        if (Ui.inside(mx, my, addX, buttonY, addW, 16) && addError != null) {
            screen.tooltip = List.of(Component.translatable(addError));
        } else if (Ui.inside(mx, my, removeX, buttonY, removeW, 16) && removeError != null) {
            screen.tooltip = List.of(Component.translatable(removeError));
        }
        int hintY = buttonY + 21;
        if (hintY + 9 <= y + h) {
            for (var line : Ui.wrap(Component.translatable("minedraftlib.range.hint"), w - 12)) {
                if (hintY + 9 > y + h) break;
                g.drawString(Ui.font(), line, x + 6, hintY, Ui.DIM, false);
                hintY += 10;
            }
        }
    }

    private void tooltip(Area m, long c) {
        Component state;
        if (c == m.baseChunk()) state = Component.translatable("minedraftlib.range.base");
        else if (m.chunks().contains(c)) state = Component.translatable(modified.contains(c) ? "minedraftlib.range.state.modified" : "minedraftlib.range.state.in");
        else if (touchesArea(m, c)) state = Component.translatable("minedraftlib.range.state.candidate");
        else state = Component.translatable("minedraftlib.range.state.out");
        screen.tooltip = List.of(Component.translatable("minedraftlib.range.chunk", ChunkPos.getX(c), ChunkPos.getZ(c)), state);
    }

    private static void legend(GuiGraphics g, int x, int y, int color, boolean outline, String key) {
        if (outline) Ui.border(g, x, y, 8, 8, color);
        else g.fill(x, y, x + 8, y + 8, color);
        Ui.text(g, Component.translatable(key), x + 12, y, Ui.DIM);
    }

    private void button(GuiGraphics g, int bx, int bw, Component label, boolean enabled, boolean danger, int mx, int my) {
        boolean hover = enabled && Ui.inside(mx, my, bx, buttonY, bw, 16);
        int bg = !enabled ? 0xFF1A1F26 : danger ? (hover ? 0xFF9A3030 : 0xFF7A2626) : (hover ? 0xFF25877E : 0xFF1E6F68);
        g.fill(bx, buttonY, bx + bw, buttonY + 16, bg);
        Ui.border(g, bx, buttonY, bw, 16, !enabled ? Ui.EDGE : danger ? Ui.DEMOLISH : Ui.ACCENT);
        Ui.text(g, label, bx + 6, buttonY + 4, enabled ? Ui.TEXT : Ui.DISABLED);
    }

    // ------------------------------------------------------------------
    // 상태
    // ------------------------------------------------------------------

    private static boolean touchesArea(Area m, long c) {
        int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
        return m.chunks().contains(ChunkPos.asLong(cx + 1, cz)) || m.chunks().contains(ChunkPos.asLong(cx - 1, cz))
                || m.chunks().contains(ChunkPos.asLong(cx, cz + 1)) || m.chunks().contains(ChunkPos.asLong(cx, cz - 1));
    }

    /** 어느 층이든 내용이 있는 청크 (영역 판이 바뀔 때만 다시 센다). */
    private void refreshModified(Area m) {
        if (modifiedRevision == m.revision()) return;
        modifiedRevision = m.revision();
        modified.clear();
        for (var l : m.layers()) l.collectUsedChunks(modified);
    }

    /** 다른 편집자가 먼저 반영해 의미가 없어진 선택은 그대로 둬도 되지만, 기준 청크는 고를 수 없다. */
    private void pruneSelection(Area m) {
        selected.remove(m.baseChunk());
    }

    private LongOpenHashSet addSet(Area m) {
        LongOpenHashSet out = new LongOpenHashSet();
        LongIterator it = selected.iterator();
        while (it.hasNext()) {
            long c = it.nextLong();
            if (!m.chunks().contains(c)) out.add(c);
        }
        return out;
    }

    private LongOpenHashSet removeSet(Area m) {
        LongOpenHashSet out = new LongOpenHashSet();
        LongIterator it = selected.iterator();
        while (it.hasNext()) {
            long c = it.nextLong();
            if (m.chunks().contains(c)) out.add(c);
        }
        return out;
    }

    private int[] counts(Area m) {
        int add = 0, remove = 0;
        LongIterator it = selected.iterator();
        while (it.hasNext()) {
            if (m.chunks().contains(it.nextLong())) remove++;
            else add++;
        }
        return new int[]{add, remove};
    }

    private void recenter() {
        Box a = s().area.areaBounds();
        centerX = (a.minX() + a.maxX() + 1) / 32.0;
        centerZ = (a.minZ() + a.maxZ() + 1) / 32.0;
    }

    // ------------------------------------------------------------------
    // 입력
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        Area m = s().area;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && Ui.inside(mx, my, addX, buttonY, addW, 16)) {
            applyAdd(m);
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && Ui.inside(mx, my, removeX, buttonY, removeW, 16)) {
            applyRemove(m);
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && Ui.inside(mx, my, clearX, buttonY, clearW, 16)) {
            selected.clear();
            return true;
        }
        if (!Ui.inside(mx, my, mapX, mapY, mapW, mapH)) return true;
        pressX = lastX = mx;
        pressY = lastY = my;
        moved = false;
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            panning = true;
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            long c = chunkAt(mx, my);
            if (c == Long.MIN_VALUE) return true;
            if (c == m.baseChunk()) {
                s().toast(Component.translatable("minedraftlib.range.base"));
                return true;
            }
            painting = true;
            paintSelect = !selected.contains(c);
            paint(c, m);
        }
        return true;
    }

    private void paint(long c, Area m) {
        if (c == Long.MIN_VALUE || c == lastPainted || c == m.baseChunk()) return;
        lastPainted = c;
        if (paintSelect) selected.add(c);
        else selected.remove(c);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (painting) {
            paint(chunkAt(mx, my), s().area);
            return true;
        }
        if (panning) {
            if (Math.abs(mx - pressX) + Math.abs(my - pressY) > 3) moved = true;
            // 화면에서 끈 만큼 지도를 옮긴다 (돌아간 지도이므로 청크 좌표로 되돌려 계산)
            double[] a = toChunk(lastX, lastY), b = toChunk(mx, my);
            centerX -= b[0] - a[0];
            centerZ -= b[1] - a[1];
            lastX = mx;
            lastY = my;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        boolean was = painting || panning;
        painting = false;
        panning = false;
        lastPainted = Long.MIN_VALUE;
        return was;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        return true;
    }

    // ------------------------------------------------------------------
    // 반영
    // ------------------------------------------------------------------

    /** 추가: 확인 없이 바로. */
    private void applyAdd(Area m) {
        LongOpenHashSet add = addSet(m);
        if (add.isEmpty()) return;
        String error = m.checkChunkChange(add, new LongOpenHashSet());
        if (error != null) {
            s().toast(Component.translatable(error));
            return;
        }
        s().send(new EditRequests.ChunkChange(add.toLongArray(), new long[0]));
        selected.removeAll(add);
    }

    /** 삭제: 고른 청크 중 수정된 것이 있으면 무엇이 사라지는지 보여 주고 한 번 더 묻는다. */
    private void applyRemove(Area m) {
        LongOpenHashSet remove = removeSet(m);
        if (remove.isEmpty()) return;
        String error = m.checkChunkChange(new LongOpenHashSet(), remove);
        if (error != null) {
            s().toast(Component.translatable(error));
            return;
        }
        Runnable send = () -> {
            s().send(new EditRequests.ChunkChange(new long[0], remove.toLongArray()));
            selected.removeAll(remove);
        };
        // 무엇이 사라지는지: 모드가 적는 내용 + 그 청크에서 작업 중인 편집자
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("minedraftlib.range.remove.count", remove.size()));
        boolean modified = s().describeChunkRemoval(remove, lines);
        List<String> working = new ArrayList<>();
        for (Map.Entry<UUID, AreaEditorSession.RemoteCursor> e : s().cursors.entrySet()) {
            AreaEditorSession.RemoteCursor c = e.getValue();
            if (!c.has()) continue;
            long chunk = ChunkPos.asLong(BlockPos.getX(c.pos()) >> 4, BlockPos.getZ(c.pos()) >> 4);
            if (remove.contains(chunk)) {
                String name = s().editors.getOrDefault(e.getKey(), "?");
                if (!working.contains(name)) working.add(name);
            }
        }
        if (!working.isEmpty()) lines.add(Component.translatable("minedraftlib.range.remove.working", String.join(", ", working)));
        if (!modified && working.isEmpty()) {
            send.run();
            return;
        }
        screen.openModal(new ConfirmDialog(screen, Component.translatable("minedraftlib.range.remove.title"), lines,
                Component.translatable("minedraftlib.range.remove.warning"), Component.translatable("minedraftlib.range.remove.confirm"),
                true, send));
    }
}

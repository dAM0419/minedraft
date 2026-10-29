package dev.designandbuild.client.editor.ui;

import dev.minedraft.lib.client.editor.ui.Modal;
import dev.designandbuild.client.editor.EditorScreen;
import dev.designandbuild.client.editor.EditorSession;
import dev.minedraft.lib.client.ui.Ui;
import dev.designandbuild.core.StructureInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 확인 창들. */
public final class Dialogs {
    private Dialogs() {}

    /** 일반 확인 창. danger 면 Enter 로는 확인되지 않고, 취소가 기본 선택이다. */
    public static final class Confirm extends Modal<EditorScreen> {
        private final Component title;
        private final List<Component> lines;
        @Nullable
        private final Component warning;
        private final Component confirm;
        private final boolean danger;
        private final Runnable onConfirm;
        private int cancelX, confirmX, buttonsY;

        public Confirm(EditorScreen screen, Component title, List<Component> lines, @Nullable Component warning,
                       Component confirm, boolean danger, Runnable onConfirm) {
            super(screen, 260, 0);
            this.title = title;
            this.lines = lines;
            this.warning = warning;
            this.confirm = confirm;
            this.danger = danger;
            this.onConfirm = onConfirm;
        }

        private List<FormattedCharSequence> wrapped(Component c) {
            return Ui.wrap(c, w - 20);
        }

        @Override
        protected void layout() {
            int lh = 0;
            if (warning != null) lh += wrapped(warning).size() * 10 + 6;
            for (Component c : lines) lh += wrapped(c).size() * 10;
            h = 22 + lh + 30;
            super.layout();
        }

        @Override
        protected void renderContent(GuiGraphics g, int mx, int my, float pt) {
            Ui.text(g, title, x + 10, y + 8, Ui.TEXT);
            g.fill(x + 1, y + 20, x + w - 1, y + 21, Ui.EDGE);
            int ty = y + 26;
            if (warning != null) {
                List<FormattedCharSequence> wl = wrapped(warning);
                g.fill(x + 6, ty - 3, x + w - 6, ty + wl.size() * 10 + 1, 0x40E04848);
                g.fill(x + 6, ty - 3, x + 8, ty + wl.size() * 10 + 1, Ui.DEMOLISH);
                for (FormattedCharSequence s : wl) {
                    g.drawString(Ui.font(), s, x + 12, ty, 0xFFFF8A8A, false);
                    ty += 10;
                }
                ty += 6;
            }
            for (Component c : lines) {
                for (FormattedCharSequence s : wrapped(c)) {
                    g.drawString(Ui.font(), s, x + 10, ty, Ui.DIM, false);
                    ty += 10;
                }
            }
            buttonsY = y + h - 24;
            Component cancel = Component.translatable("designandbuild.dialog.cancel");
            confirmX = x + w - 10 - buttonWidth(confirm);
            cancelX = confirmX - 6 - buttonWidth(cancel);
            button(g, cancelX, buttonsY, cancel, danger, false, mx, my);
            button(g, confirmX, buttonsY, confirm, !danger, danger, mx, my);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (Ui.inside(mx, my, confirmX, buttonsY, buttonWidth(confirm), 16)) {
                close();
                onConfirm.run();
            } else if (Ui.inside(mx, my, cancelX, buttonsY, buttonWidth(Component.translatable("designandbuild.dialog.cancel")), 16)) {
                close();
            }
            return true;
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                close();
            } else if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && !danger) {
                close();
                onConfirm.run();
            }
            return true;
        }
    }

    /** 청크 여러 개에 기록된 설계 칸 수·걸친 구조물·작업 중인 편집자. 수정된 청크가 없으면 칸 수 0, 목록 비어 있음. */
    public record ChunkImpact(int cells, List<StructureInstance> structures, List<String> working) {
        public boolean modified() {
            return cells > 0 || !structures.isEmpty() || !working.isEmpty();
        }
    }

    public static ChunkImpact impactOf(EditorSession s, LongCollection chunks) {
        int cells = 0;
        List<StructureInstance> touched = new ArrayList<>();
        LongIterator it = chunks.iterator();
        while (it.hasNext()) {
            List<StructureInstance> t = new ArrayList<>();
            cells += s.model.designCellsInChunk(it.nextLong(), t);
            for (StructureInstance st : t) if (!touched.contains(st)) touched.add(st);
        }
        List<String> working = new ArrayList<>();
        for (Map.Entry<UUID, EditorSession.RemoteCursor> e : s.cursors.entrySet()) {
            EditorSession.RemoteCursor c = e.getValue();
            if (!c.has()) continue;
            long chunk = ChunkPos.asLong(BlockPos.getX(c.pos()) >> 4, BlockPos.getZ(c.pos()) >> 4);
            if (chunks.contains(chunk)) {
                String name = s.editors.getOrDefault(e.getKey(), "?");
                if (!working.contains(name)) working.add(name);
            }
        }
        return new ChunkImpact(cells, touched, working);
    }

    /** 수정된 청크가 섞인 제거: 무엇이 사라지는지 보여 주고 한 번 더 묻는다. */
    public static Modal confirmRemoveChunks(EditorScreen screen, int count, ChunkImpact impact, Runnable onConfirm) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("designandbuild.range.remove.count", count));
        lines.add(Component.translatable("designandbuild.range.remove.cells", impact.cells()));
        if (!impact.structures().isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (StructureInstance st : impact.structures()) {
                if (b.length() > 0) b.append(", ");
                b.append(st.name);
            }
            lines.add(Component.translatable("designandbuild.range.remove.structures", b.toString()));
        }
        if (!impact.working().isEmpty()) {
            lines.add(Component.translatable("designandbuild.range.remove.working", String.join(", ", impact.working())));
        }
        return new Confirm(screen, Component.translatable("designandbuild.range.remove.title"), lines,
                Component.translatable("designandbuild.range.remove.warning"), Component.translatable("designandbuild.range.remove.confirm"), true,
                onConfirm);
    }
}

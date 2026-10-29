package dev.designandbuild.client.editor.ui;

import dev.minedraft.lib.client.editor.ui.Modal;
import dev.designandbuild.client.ClientConfig;
import dev.designandbuild.client.editor.EditorScreen;
import dev.designandbuild.client.library.ClientLibrary;
import dev.minedraft.lib.client.ui.Icons;
import dev.minedraft.lib.client.ui.Ui;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 구조물 폴더 등록 창. 등록한 폴더 안의 .nbt 가 내 라이브러리에 나온다 (하위 폴더 포함).
 * 이 목록은 내 클라이언트 설정에만 저장된다.
 */
public final class FolderDialog extends Modal<EditorScreen> {
    private static final int ROW = 18, MAX_ROWS = 6;

    private final EditBox path;
    private final Map<Path, Integer> counts = new HashMap<>();
    private int inputCount = Integer.MIN_VALUE;
    private String inputCountFor = "";
    private int listY, inputY, addX, addW, closeX, buttonsY;
    private int scroll;

    public FolderDialog(EditorScreen screen) {
        super(screen, 340, 0);
        this.path = newBox(260, Component.translatable("designandbuild.folder.path.hint"));
        recount();
    }

    private void recount() {
        counts.clear();
        for (Path p : ClientConfig.allFolders()) counts.put(p, ClientLibrary.countNbt(p));
    }

    private List<Path> folders() {
        return ClientConfig.allFolders();
    }

    @Override
    protected void layout() {
        int rows = Math.min(MAX_ROWS, folders().size());
        h = 22 + 24 + rows * ROW + 8 + 16 + 14 + 32;
        super.layout();
    }

    @Override
    protected void renderContent(GuiGraphics g, int mx, int my, float pt) {
        Ui.text(g, Component.translatable("designandbuild.folder.title"), x + 10, y + 8, Ui.TEXT);
        g.fill(x + 1, y + 20, x + w - 1, y + 21, Ui.EDGE);
        int ty = y + 26;
        for (var line : Ui.wrap(Component.translatable("designandbuild.folder.text"), w - 20)) {
            g.drawString(Ui.font(), line, x + 10, ty, Ui.DIM, false);
            ty += 10;
        }
        listY = y + 22 + 24;
        List<Path> list = folders();
        int rows = Math.min(MAX_ROWS, list.size());
        scroll = Math.max(0, Math.min(scroll, list.size() - rows));
        for (int r = 0; r < rows; r++) {
            int i = r + scroll;
            Path p = list.get(i);
            int ry = listY + r * ROW;
            boolean hover = Ui.inside(mx, my, x + 8, ry, w - 16, ROW - 2);
            g.fill(x + 8, ry, x + w - 8, ry + ROW - 2, hover ? 0x28FFFFFF : 0x14FFFFFF);
            boolean exists = Files.isDirectory(p);
            Ui.icon(g, Icons.FOLDER, x + 10, ry, exists ? Ui.TEXT : Ui.DEMOLISH);
            int count = counts.getOrDefault(p, -1);
            String countText = count < 0 ? Component.translatable("designandbuild.folder.missing").getString()
                    : Component.translatable("designandbuild.folder.count", count).getString();
            int cw = Ui.font().width(countText);
            int textW = w - 16 - 22 - cw - 8 - 40;
            String label = (i == 0 ? Component.translatable("designandbuild.folder.default").getString() + " · " : "") + p;
            Ui.text(g, Ui.clip(label, textW), x + 28, ry + 4, exists ? Ui.TEXT : Ui.DIM);
            int bx = x + w - 8 - 36;
            Ui.text(g, countText, bx - 6 - cw, ry + 4, count < 0 ? Ui.DEMOLISH : Ui.DIM);
            boolean ho = Ui.inside(mx, my, bx, ry, 16, 16);
            Ui.iconButton(g, bx, ry, 16, 16, Icons.EYE, false, ho, exists);
            if (ho) screen.tooltip = List.of(Component.translatable("designandbuild.folder.open"));
            if (i > 0) {
                boolean hd = Ui.inside(mx, my, bx + 18, ry, 16, 16);
                Ui.iconButton(g, bx + 18, ry, 16, 16, Icons.X_SMALL, false, hd, true);
                if (hd) screen.tooltip = List.of(Component.translatable("designandbuild.folder.remove"));
            }
            if (hover && !ho && Ui.font().width(label) > textW) screen.tooltip = List.of(Component.literal(p.toString()));
        }

        // 새 폴더
        inputY = listY + rows * ROW + 8;
        Component add = Component.translatable("designandbuild.folder.add");
        addW = buttonWidth(add);
        addX = x + w - 10 - addW;
        drawBox(g, path, x + 10, inputY + 1, addX - x - 16, mx, my);
        String v = path.getValue().trim();
        Path candidate = parse(v);
        if (!v.equals(inputCountFor)) {
            inputCountFor = v;
            inputCount = candidate == null ? Integer.MIN_VALUE : ClientLibrary.countNbt(candidate);
        }
        boolean valid = candidate != null && inputCount >= 0 && !folders().contains(candidate);
        button(g, addX, inputY, add, valid, false, mx, my);
        int hy = inputY + 19;
        if (!v.isEmpty()) {
            Component status;
            int color;
            if (candidate == null || inputCount < 0) {
                status = Component.translatable("designandbuild.folder.not_folder");
                color = Ui.DEMOLISH;
            } else if (folders().contains(candidate)) {
                status = Component.translatable("designandbuild.folder.already");
                color = Ui.WARN;
            } else {
                status = Component.translatable("designandbuild.folder.found", inputCount);
                color = Ui.GOOD;
            }
            Ui.text(g, status, x + 12, hy, color);
        }

        buttonsY = y + h - 24;
        Component close = Component.translatable("designandbuild.dialog.close");
        closeX = x + w - 10 - buttonWidth(close);
        button(g, closeX, buttonsY, close, true, false, mx, my);
    }

    private static Path parse(String v) {
        if (v.isEmpty()) return null;
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
        try {
            return Path.of(v).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private void addFolder() {
        Path candidate = parse(path.getValue().trim());
        if (candidate == null || !Files.isDirectory(candidate) || folders().contains(candidate)) return;
        ClientConfig.get().folders.add(candidate.toString());
        ClientConfig.save();
        path.setValue("");
        recount();
        ClientLibrary.rescanLocal();
        screen.session().toast(Component.translatable("designandbuild.folder.added", counts.getOrDefault(candidate, 0)));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        layout();
        if (!Ui.inside(mx, my, x, y, w, h)) return true;
        if (clickBoxes(mx, my, button)) return true;
        List<Path> list = folders();
        int rows = Math.min(MAX_ROWS, list.size());
        for (int r = 0; r < rows; r++) {
            int i = r + scroll;
            int ry = listY + r * ROW;
            int bx = x + w - 8 - 36;
            Path p = list.get(i);
            if (Ui.inside(mx, my, bx, ry, 16, 16)) {
                if (Files.isDirectory(p)) Util.getPlatform().openFile(p.toFile());
                return true;
            }
            if (i > 0 && Ui.inside(mx, my, bx + 18, ry, 16, 16)) {
                ClientConfig.get().folders.removeIf(sv -> {
                    try {
                        return Path.of(sv).toAbsolutePath().normalize().equals(p) || Path.of(sv).equals(p);
                    } catch (Exception e) {
                        return false;
                    }
                });
                ClientConfig.save();
                recount();
                ClientLibrary.rescanLocal();
                return true;
            }
        }
        if (Ui.inside(mx, my, addX, inputY, addW, 16)) {
            addFolder();
            return true;
        }
        if (Ui.inside(mx, my, closeX, buttonsY, buttonWidth(Component.translatable("designandbuild.dialog.close")), 16)) {
            close();
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll = Math.max(0, scroll - (int) Math.signum(delta));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && path.isFocused()) {
            addFolder();
            return true;
        }
        if (focusedBox() == null && key != GLFW.GLFW_KEY_ENTER) focusBox(path);
        keyToBox(key, scan, mods);
        return true;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (focusedBox() == null) focusBox(path);
        charToBox(c, mods);
        return true;
    }

    /** 경로 붙여넣기를 쉽게: 창이 열리면 바로 입력칸에. */
    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        if (focusedBox() == null && path.getValue().isEmpty()) focusBox(path);
        super.render(g, mx, my, pt);
    }
}

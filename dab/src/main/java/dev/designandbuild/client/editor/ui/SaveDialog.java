package dev.designandbuild.client.editor.ui;

import dev.designandbuild.client.ClientConfig;
import dev.designandbuild.client.editor.EditorScreen;
import dev.designandbuild.client.library.ClientLibrary;
import dev.designandbuild.client.library.LibraryEntry;
import dev.designandbuild.client.ui.Icons;
import dev.designandbuild.client.ui.Ui;
import dev.designandbuild.core.Box;
import dev.designandbuild.core.StructureInstance;
import dev.designandbuild.core.TemplateParser;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 구조물 저장 창: 이름, 저장 폴더와 파일 이름, 설치면, 태그(출처·종류·지형, 고르거나 새로 추가).
 * 저장하면 내 폴더에 .nbt 로 남고 바로 내 라이브러리에 나온다 (다른 플레이어에게는 보이지 않는다).
 */
public final class SaveDialog extends Modal {
    private static final Direction[] MOUNTS = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private static final int LABEL_W = 56;

    private final int structureId;
    private final EditBox name;
    private final EditBox fileName;
    private final EditBox newTag;
    private boolean fileNameEdited;
    private String lastAutoName = "";
    private final List<Path> folders;
    private int folderIndex;
    private Direction mount;
    private final Map<String, List<String>> tags = new LinkedHashMap<>();
    /** 새 태그를 입력 중인 축 (-1 = 없음). */
    private int addingAxis = -1;
    private final Map<String, int[]> chipRects = new LinkedHashMap<>();
    private int folderX, folderY, folderW;
    private int cancelX, saveX, saveAsX = Integer.MIN_VALUE, buttonsY;
    private final int[] mountX = new int[6];
    private int mountY;
    private final int[] plusX = new int[3];
    private final int[] rowY = new int[3];
    @Nullable
    private Component error;

    public SaveDialog(EditorScreen screen, int structureId) {
        super(screen, 320, 0);
        this.structureId = structureId;
        StructureInstance st = screen.session().model.structure(structureId);
        String initial = st == null ? "structure" : st.name;
        this.mount = st == null ? Direction.DOWN : st.mount;
        this.name = newBox(64, Component.translatable("designandbuild.save.name.hint"));
        name.setValue(initial);
        this.fileName = newBox(96, Component.translatable("designandbuild.save.file.hint"));
        this.newTag = newBox(32, Component.translatable("designandbuild.save.tag.hint"));
        newTag.visible = false;
        lastAutoName = fileSafe(initial);
        fileName.setValue(lastAutoName);
        name.setResponder(v -> {
            if (!fileNameEdited) {
                lastAutoName = fileSafe(v);
                fileName.setValue(lastAutoName);
            }
        });
        fileName.setResponder(v -> {
            if (!v.equals(lastAutoName)) fileNameEdited = true;
        });
        this.folders = ClientConfig.allFolders();
        for (String axis : TemplateParser.TAG_AXES) tags.put(axis, new ArrayList<>());
        // 라이브러리에서 온 구조물이면 종류·지형 태그를 이어받는다
        if (st != null && !st.source.isEmpty()) {
            for (LibraryEntry e : ClientLibrary.all()) {
                if (!e.id().equals(st.source)) continue;
                for (String t : e.tags()) {
                    int c = t.indexOf(':');
                    if (c < 0) continue;
                    String axis = t.substring(0, c);
                    if ("source".equals(axis)) continue;
                    List<String> l = tags.get(axis);
                    if (l != null && !l.contains(t.substring(c + 1))) l.add(t.substring(c + 1));
                }
                break;
            }
        }
        focusBox(name);
    }

    private static String fileSafe(String s) {
        String clean = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return clean.isEmpty() ? "structure" : clean;
    }

    private Path targetFile() {
        String f = fileSafe(fileName.getValue());
        if (!f.toLowerCase(java.util.Locale.ROOT).endsWith(".nbt")) f = f + ".nbt";
        return folders.get(folderIndex).resolve(f);
    }

    @Override
    protected void layout() {
        h = 26 + 18 * 3 + 20 + 3 * 16 + 8 + 14 + 30;
        if (error != null) h += 12;
        super.layout();
    }

    @Override
    protected void renderContent(GuiGraphics g, int mx, int my, float pt) {
        StructureInstance st = screen.session().model.structure(structureId);
        Ui.text(g, Component.translatable("designandbuild.save.title"), x + 10, y + 8, Ui.TEXT);
        g.fill(x + 1, y + 20, x + w - 1, y + 21, Ui.EDGE);
        int fx = x + 10 + LABEL_W, fw = w - 20 - LABEL_W;
        int ry = y + 28;

        // 이름
        Ui.text(g, Component.translatable("designandbuild.save.name"), x + 10, ry + 3, Ui.DIM);
        drawBox(g, name, fx, ry, fw, mx, my);
        ry += 18;

        // 폴더
        Ui.text(g, Component.translatable("designandbuild.save.folder"), x + 10, ry + 3, Ui.DIM);
        folderX = fx;
        folderY = ry;
        folderW = fw;
        boolean hf = Ui.inside(mx, my, fx, ry, fw, 14);
        g.fill(fx, ry, fx + fw, ry + 14, hf ? 0xFF262E38 : 0xFF161B22);
        Ui.border(g, fx, ry, fw, 14, Ui.EDGE);
        Ui.icon(g, Icons.FOLDER, fx + 1, ry - 1, Ui.DIM);
        Path folder = folders.get(folderIndex);
        String folderText = folderIndex == 0 ? Component.translatable("designandbuild.folder.default").getString() + " · " + folder : folder.toString();
        Ui.text(g, Ui.clip(folderText, fw - 36), fx + 18, ry + 3, Ui.TEXT);
        if (folders.size() > 1) Ui.icon(g, Icons.CHEVRON_RIGHT, fx + fw - 16, ry - 1, Ui.DIM);
        if (hf) screen.tooltip = List.of(Component.literal(folder.toString()), Component.translatable("designandbuild.save.folder.tip"));
        ry += 18;

        // 파일
        Ui.text(g, Component.translatable("designandbuild.save.file"), x + 10, ry + 3, Ui.DIM);
        drawBox(g, fileName, fx, ry, fw - 26, mx, my);
        Ui.text(g, ".nbt", fx + fw - 22, ry + 3, Ui.DIM);
        ry += 18;
        boolean exists = Files.exists(targetFile());
        if (exists) {
            Ui.icon(g, Icons.WARN, fx - 2, ry - 4, Ui.WARN);
            Ui.text(g, Component.translatable("designandbuild.save.exists"), fx + 14, ry, Ui.WARN);
        }
        ry += 14;

        // 설치면
        Ui.text(g, Component.translatable("designandbuild.save.mount"), x + 10, ry + 3, Ui.DIM);
        mountY = ry;
        int mxPos = fx;
        for (int i = 0; i < MOUNTS.length; i++) {
            Component label = Ui.dirName(MOUNTS[i]);
            int bw = Ui.font().width(label) + 8;
            mountX[i] = mxPos;
            boolean on = mount == MOUNTS[i];
            boolean hover = Ui.inside(mx, my, mxPos, ry, bw, 14);
            g.fill(mxPos, ry, mxPos + bw, ry + 14, on ? 0x602EC4B6 : hover ? 0x40FFFFFF : 0x20FFFFFF);
            if (on) Ui.border(g, mxPos, ry, bw, 14, Ui.ACCENT);
            Ui.text(g, label, mxPos + 4, ry + 3, on ? Ui.TEXT : Ui.DIM);
            mxPos += bw + 2;
        }
        if (Ui.inside(mx, my, x + 10, ry, LABEL_W, 14)) screen.tooltip = List.of(Component.translatable("designandbuild.save.mount.tip"));
        ry += 20;

        // 태그 3줄
        chipRects.clear();
        Map<String, List<String>> known = ClientLibrary.knownTags();
        for (int axis = 0; axis < 3; axis++) {
            String ax = TemplateParser.TAG_AXES[axis];
            rowY[axis] = ry;
            Ui.text(g, Component.translatable("designandbuild.tagaxis." + ax), x + 10, ry + 2, Ui.DIM);
            int cx = fx;
            int limit = x + w - 10 - 16;
            g.enableScissor(fx, ry, limit, ry + 12);
            List<String> chosen = tags.get(ax);
            for (String v : chosen) {
                Component label = LibraryTab.tagName(ax + ":" + v);
                int cw = Ui.font().width(label) + 6;
                boolean hover = Ui.inside(mx, my, cx, ry, cw, 12) && mx < limit;
                g.fill(cx, ry, cx + cw, ry + 12, hover ? 0x80E04848 : 0x602EC4B6);
                Ui.border(g, cx, ry, cw, 12, hover ? Ui.DEMOLISH : Ui.ACCENT);
                Ui.text(g, label, cx + 3, ry + 2, Ui.TEXT);
                chipRects.put(axis + "|1|" + v, new int[]{cx, ry, cw, 12});
                if (hover) screen.tooltip = List.of(Component.translatable("designandbuild.save.tag.remove"));
                cx += cw + 2;
            }
            if (addingAxis == axis) {
                newTag.visible = true;
                g.disableScissor();
                drawBox(g, newTag, cx, ry - 1, Math.max(60, limit - cx), mx, my);
                g.enableScissor(fx, ry, limit, ry + 12);
            } else {
                for (String v : known.getOrDefault(ax, List.of())) {
                    if (chosen.contains(v) || ("source".equals(ax) && "player".equals(v))) continue;
                    Component label = LibraryTab.tagName(ax + ":" + v);
                    int cw = Ui.font().width(label) + 6;
                    if (cx + cw > limit) break;
                    boolean hover = Ui.inside(mx, my, cx, ry, cw, 12);
                    g.fill(cx, ry, cx + cw, ry + 12, hover ? 0x40FFFFFF : 0x14FFFFFF);
                    Ui.text(g, label, cx + 3, ry + 2, hover ? Ui.TEXT : Ui.DISABLED);
                    chipRects.put(axis + "|0|" + v, new int[]{cx, ry, cw, 12});
                    cx += cw + 2;
                }
            }
            g.disableScissor();
            plusX[axis] = x + w - 10 - 14;
            boolean hp = Ui.inside(mx, my, plusX[axis], ry - 1, 14, 14);
            Ui.iconButton(g, plusX[axis] - 1, ry - 2, 16, 16, Icons.PLUS, addingAxis == axis, hp, true);
            if (hp) screen.tooltip = List.of(Component.translatable("designandbuild.save.tag.add"));
            ry += 16;
        }
        ry += 4;

        // 요약
        if (st != null) {
            int real = 0, air = 0;
            for (BlockState bs : st.blocks().values()) {
                if (bs.isAir()) air++;
                else real++;
            }
            Box b = st.bounds();
            String size = b == null ? "-" : b.sizeX() + "×" + b.sizeY() + "×" + b.sizeZ();
            Ui.text(g, Component.translatable("designandbuild.save.summary", size, real, air), x + 10, ry, Ui.DIM);
        } else {
            Ui.text(g, Component.translatable("designandbuild.save.gone"), x + 10, ry, Ui.DEMOLISH);
        }
        ry += 12;
        if (error != null) {
            Ui.text(g, error, x + 10, ry, Ui.DEMOLISH);
        }

        // 버튼
        buttonsY = y + h - 24;
        Component cancel = Component.translatable("designandbuild.dialog.cancel");
        Component save = Component.translatable(exists ? "designandbuild.save.overwrite" : "designandbuild.save.confirm");
        saveX = x + w - 10 - buttonWidth(save);
        int left = saveX;
        if (exists) {
            // 같은 이름이 있으면: 덮어쓰기 / 다른 이름으로 저장
            Component saveAs = Component.translatable("designandbuild.save.save_as");
            saveAsX = saveX - 6 - buttonWidth(saveAs);
            button(g, saveAsX, buttonsY, saveAs, true, false, mx, my);
            left = saveAsX;
        } else {
            saveAsX = Integer.MIN_VALUE;
        }
        cancelX = left - 6 - buttonWidth(cancel);
        button(g, cancelX, buttonsY, cancel, false, false, mx, my);
        button(g, saveX, buttonsY, save, !exists, exists, mx, my);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        layout();
        if (!Ui.inside(mx, my, x, y, w, h)) return true;
        if (addingAxis >= 0 && !newTag.isMouseOver(mx, my)) commitNewTag();
        if (clickBoxes(mx, my, button)) return true;
        if (Ui.inside(mx, my, folderX, folderY, folderW, 14)) {
            int n = folders.size();
            folderIndex = button == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? (folderIndex + n - 1) % n : (folderIndex + 1) % n;
            return true;
        }
        for (int i = 0; i < MOUNTS.length; i++) {
            int bw = Ui.font().width(Ui.dirName(MOUNTS[i])) + 8;
            if (Ui.inside(mx, my, mountX[i], mountY, bw, 14)) {
                mount = MOUNTS[i];
                return true;
            }
        }
        for (int axis = 0; axis < 3; axis++) {
            if (Ui.inside(mx, my, plusX[axis] - 1, rowY[axis] - 2, 16, 16)) {
                addingAxis = addingAxis == axis ? -1 : axis;
                newTag.setValue("");
                newTag.visible = addingAxis >= 0;
                focusBox(addingAxis >= 0 ? newTag : null);
                return true;
            }
        }
        for (Map.Entry<String, int[]> e : chipRects.entrySet()) {
            int[] r = e.getValue();
            if (!Ui.inside(mx, my, r[0], r[1], r[2], r[3])) continue;
            String[] parts = e.getKey().split("\\|", 3);
            List<String> l = tags.get(TemplateParser.TAG_AXES[Integer.parseInt(parts[0])]);
            if ("1".equals(parts[1])) l.remove(parts[2]);
            else if (!l.contains(parts[2])) l.add(parts[2]);
            return true;
        }
        if (Ui.inside(mx, my, cancelX, buttonsY, buttonWidth(Component.translatable("designandbuild.dialog.cancel")), 16)) {
            close();
            return true;
        }
        Component save = Component.translatable(Files.exists(targetFile()) ? "designandbuild.save.overwrite" : "designandbuild.save.confirm");
        if (Ui.inside(mx, my, saveX, buttonsY, buttonWidth(save), 16)) {
            save();
            return true;
        }
        if (saveAsX != Integer.MIN_VALUE
                && Ui.inside(mx, my, saveAsX, buttonsY, buttonWidth(Component.translatable("designandbuild.save.save_as")), 16)) {
            String base = fileSafe(fileName.getValue());
            if (base.toLowerCase(java.util.Locale.ROOT).endsWith(".nbt")) base = base.substring(0, base.length() - 4);
            for (int k = 2; k < 1000; k++) {
                fileName.setValue(base + " (" + k + ")");
                if (!Files.exists(targetFile())) break;
            }
            fileNameEdited = true;
            save();
            return true;
        }
        return true;
    }

    private void commitNewTag() {
        String v = TemplateParser.clean(newTag.getValue());
        if (addingAxis >= 0 && !v.isEmpty()) {
            List<String> l = tags.get(TemplateParser.TAG_AXES[addingAxis]);
            if (!l.contains(v)) l.add(v);
        }
        addingAxis = -1;
        newTag.setValue("");
        newTag.visible = false;
        if (newTag.isFocused()) focusBox(null);
    }

    private void save() {
        StructureInstance st = screen.session().model.structure(structureId);
        if (st == null || st.bounds() == null) {
            error = Component.translatable("designandbuild.save.gone");
            return;
        }
        String n = name.getValue().trim();
        if (n.isEmpty()) {
            error = Component.translatable("designandbuild.save.need_name");
            focusBox(name);
            return;
        }
        Box b = st.bounds();
        Long2ObjectMap<BlockState> blocks = st.blocks();
        int count = blocks.size();
        int[] xs = new int[count], ys = new int[count], zs = new int[count];
        BlockState[] states = new BlockState[count];
        int i = 0;
        for (Long2ObjectMap.Entry<BlockState> e : blocks.long2ObjectEntrySet()) {
            long p = e.getLongKey();
            xs[i] = BlockPos.getX(p) - b.minX();
            ys[i] = BlockPos.getY(p) - b.minY();
            zs[i] = BlockPos.getZ(p) - b.minZ();
            states[i] = e.getValue();
            i++;
        }
        Path file = targetFile();
        Path saved = ClientLibrary.save(file, n, mount, tags, b.sizeX(), b.sizeY(), b.sizeZ(), xs, ys, zs, states);
        if (saved == null) {
            error = Component.translatable("designandbuild.save.failed");
            return;
        }
        screen.session().toast(Component.translatable("designandbuild.save.done", n));
        close();
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        EditBox f = focusedBox();
        if (f == newTag && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            commitNewTag();
            return true;
        }
        if (f == newTag && key == GLFW.GLFW_KEY_ESCAPE) {
            addingAxis = -1;
            newTag.visible = false;
            focusBox(null);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            focusBox(f == name ? fileName : name);
            return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && !Files.exists(targetFile())) {
            save();
            return true;
        }
        keyToBox(key, scan, mods);
        return true;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        charToBox(c, mods);
        return true;
    }
}

package dev.designandbuild.client.editor.ui;

import dev.designandbuild.client.ClientConfig;
import dev.designandbuild.client.editor.EditorScreen;
import dev.designandbuild.client.editor.EditorSession;
import dev.designandbuild.client.ui.Icons;
import dev.designandbuild.client.ui.Ui;
import dev.designandbuild.compat.JeiState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 블록 선택기. JEI 가 있으면 왼쪽에 작은 안내 패널만 띄우고 오른쪽 JEI 목록에서 고르게 한다.
 * 없으면 크리에이티브 인벤토리처럼 탭 + 검색 격자를 띄운다.
 * 설치할 수 있는 것(블록 아이템, 액체 양동이)만 보인다.
 */
public final class BlockPicker extends Modal {
    public enum Kind { PALETTE, MATERIAL, REPLACE_TARGET }

    public record Target(Kind kind, int index) {}

    private static final int CELL = 18;
    private static final int JEI_PANEL_W = 150, JEI_PANEL_H = 150;
    /** JEI 목록이 차지할 오른쪽 폭. */
    public static final int JEI_LIST_W = 176;

    private final Target target;
    private boolean jeiMode;
    private final EditBox search;
    /** 0 = 전체, 1 = 최근, 2.. = 크리에이티브 탭. */
    private int tab;
    private final List<TabInfo> tabs = new ArrayList<>();
    private List<ItemStack> all = List.of();
    private List<ItemStack> shown = List.of();
    private String filterKey = "";
    private int scroll;
    private int tabScroll;
    private int gridX, gridY, cols, rows;
    private int jeiSwitchX, jeiSwitchY, jeiSwitchW;

    private record TabInfo(Component name, ItemStack icon, List<ItemStack> items) {}

    public BlockPicker(EditorScreen screen, Target target) {
        super(screen, 0, 0);
        this.target = target;
        this.jeiMode = JeiState.available();
        this.search = new EditBox(Ui.font(), 0, 0, 100, 12, Component.empty());
        search.setBordered(false);
        search.setMaxLength(64);
        search.setTextColor(Ui.TEXT);
        search.setHint(Component.translatable("designandbuild.picker.search"));
        search.setFocused(true);
        buildTabs();
    }

    // ------------------------------------------------------------------
    // 목록
    // ------------------------------------------------------------------

    /** 설계에 놓을 수 있는 아이템인가. */
    public static boolean placeable(ItemStack st) {
        if (st.isEmpty()) return false;
        Item it = st.getItem();
        if (it instanceof BlockItem bi) return !bi.getBlock().defaultBlockState().isAir();
        if (it instanceof BucketItem) {
            return net.neoforged.neoforge.fluids.FluidUtil.getFluidContained(st).map(f -> !f.isEmpty()).orElse(false);
        }
        return false;
    }

    private void buildTabs() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Set<Item> seen = new LinkedHashSet<>();
        List<ItemStack> everything = new ArrayList<>();
        if (player != null && mc.level != null) {
            boolean op = player.canUseGameMasterBlocks() && mc.options.operatorItemsTab().get();
            try {
                CreativeModeTabs.tryRebuildTabContents(player.connection.enabledFeatures(), op, mc.level.registryAccess());
            } catch (Exception ignored) {
            }
            for (CreativeModeTab t : CreativeModeTabs.tabs()) {
                if (t.getType() != CreativeModeTab.Type.CATEGORY) continue;
                List<ItemStack> items = new ArrayList<>();
                Set<Item> inTab = new LinkedHashSet<>();
                boolean any = false;
                for (ItemStack st : t.getDisplayItems()) {
                    if (!inTab.add(st.getItem())) continue;
                    ItemStack one = new ItemStack(st.getItem());
                    // 블록이 없는 아이템도 탭에는 보이되 회색으로 (고를 수 없음)
                    items.add(one);
                    if (placeable(one)) {
                        any = true;
                        if (seen.add(st.getItem())) everything.add(one);
                    }
                }
                if (any) tabs.add(new TabInfo(t.getDisplayName(), t.getIconItem(), items));
            }
        }
        // 어느 탭에도 없는 블록도 검색에 걸리게
        for (Item item : BuiltInRegistries.ITEM) {
            if (seen.contains(item)) continue;
            ItemStack st = new ItemStack(item);
            if (placeable(st)) {
                seen.add(item);
                everything.add(st);
            }
        }
        all = everything;
        tab = 0;
    }

    private List<ItemStack> recent() {
        List<ItemStack> out = new ArrayList<>();
        for (String id : ClientConfig.get().recentBlocks) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) continue;
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item != Items.AIR) out.add(new ItemStack(item));
        }
        return out;
    }

    private void refilter() {
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        String key = q + "|" + tab;
        if (key.equals(filterKey)) return;
        filterKey = key;
        scroll = 0;
        List<ItemStack> base = tab == 0 ? all : tab == 1 ? recent() : tabs.get(tab - 2).items();
        if (q.isEmpty()) {
            shown = base;
            return;
        }
        // 검색은 언제나 전체에서 (탭에 갇히지 않게)
        if (tab != 1) base = all;
        List<ItemStack> out = new ArrayList<>();
        boolean byMod = q.startsWith("@");
        String mq = byMod ? q.substring(1) : q;
        for (ItemStack st : base) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(st.getItem());
            if (byMod) {
                if (id.getNamespace().contains(mq)) out.add(st);
            } else if (st.getHoverName().getString().toLowerCase(Locale.ROOT).contains(q) || id.getPath().contains(q)) {
                out.add(st);
            }
        }
        shown = out;
    }

    // ------------------------------------------------------------------
    // 배치
    // ------------------------------------------------------------------

    @Override
    protected void layout() {
        if (jeiMode) {
            // JEI 목록은 오른쪽(평소 우측 패널 자리)에, 안내 패널은 그 바로 왼쪽 뷰포트 위에
            w = JEI_PANEL_W;
            h = JEI_PANEL_H;
            x = jeiPanelLeft(screen.width);
            y = EditorScreen.TOP + 26;
            return;
        }
        int maxCols = Math.max(6, (screen.width - 60) / CELL);
        cols = Math.min(14, maxCols);
        w = cols * CELL + 20;
        int maxRows = Math.max(4, (screen.height - 40 - 70) / CELL);
        rows = Math.min(9, maxRows);
        h = 64 + rows * CELL + 10;
        super.layout();
        gridX = x + 10;
        gridY = y + 62;
    }

    /** JEI 에 알려 줄 "화면 본체" 영역 (x, y, w, h). JEI 는 그 오른쪽에 목록을 띄운다. JEI 모드가 아니면 null. */
    @Nullable
    public int[] jeiArea() {
        if (!jeiMode) return null;
        return new int[]{0, 0, screen.width - JEI_LIST_W, screen.height};
    }

    /** JEI 모드 안내 패널의 왼쪽 끝 (시점 큐브 등이 그 왼쪽으로 비켜 선다). */
    public static int jeiPanelLeft(int screenWidth) {
        return screenWidth - JEI_LIST_W - JEI_PANEL_W - 6;
    }

    public boolean isJeiMode() {
        return jeiMode;
    }

    @Override
    protected boolean dims() {
        return !jeiMode;
    }

    /** JEI 모드에서는 우측 패널과 층 슬라이더를 숨겨 JEI 목록 자리를 비운다. */
    public boolean hidesEditorUi() {
        return jeiMode;
    }

    private Component targetName() {
        return switch (target.kind()) {
            case PALETTE -> Component.translatable("designandbuild.picker.for_palette", target.index() + 1);
            case MATERIAL -> Component.translatable("designandbuild.picker.for_material", target.index() + 1);
            case REPLACE_TARGET -> Component.translatable("designandbuild.picker.for_target", target.index() + 1);
        };
    }

    // ------------------------------------------------------------------
    // 그리기
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        if (jeiMode) {
            layout();
            // JEI 목록이 이 위에 그려지므로 깊이를 올리지 않는다. 목록 자리만 어둡게 깐다
            g.fill(screen.width - JEI_LIST_W, EditorScreen.TOP, screen.width, screen.height, 0xF00D1117);
            g.fill(screen.width - JEI_LIST_W, EditorScreen.TOP, screen.width - JEI_LIST_W + 1, screen.height, Ui.EDGE);
            Ui.solidPanel(g, x, y, w, h);
            renderContent(g, mx, my, pt);
            return;
        }
        super.render(g, mx, my, pt);
    }

    @Override
    protected void renderContent(GuiGraphics g, int mx, int my, float pt) {
        if (jeiMode) {
            renderJei(g, mx, my);
            return;
        }
        refilter();
        Ui.text(g, Component.translatable("designandbuild.picker.title"), x + 10, y + 8, Ui.TEXT);
        Component tn = targetName();
        Ui.text(g, tn, x + w - 10 - Ui.font().width(tn), y + 8, Ui.DIM);

        // 검색
        int sw = w - 20;
        g.fill(x + 10, y + 20, x + 10 + sw, y + 34, 0xFF0B0E12);
        Ui.border(g, x + 10, y + 20, sw, 14, search.isFocused() ? Ui.ACCENT : Ui.EDGE);
        Ui.icon(g, Icons.SEARCH, x + 10 + sw - 16, y + 19, Ui.DIM);
        search.setX(x + 14);
        search.setY(y + 23);
        search.setWidth(sw - 22);
        search.render(g, mx, my, pt);

        // 탭
        int ty = y + 38;
        int tx = x + 10 - tabScroll;
        g.enableScissor(x + 10, ty, x + w - 10, ty + 20);
        int count = 2 + tabs.size();
        for (int i = 0; i < count; i++) {
            int bx = tx + i * 22;
            boolean on = tab == i;
            boolean hover = Ui.inside(mx, my, bx, ty, 20, 20) && mx >= x + 10 && mx < x + w - 10;
            g.fill(bx, ty, bx + 20, ty + 20, on ? 0x402EC4B6 : hover ? 0x30FFFFFF : 0x18FFFFFF);
            if (on) g.fill(bx + 2, ty + 18, bx + 18, ty + 19, Ui.ACCENT);
            if (i == 0) {
                Ui.icon(g, Icons.CUBE, bx + 2, ty + 2, on ? Ui.ACCENT : Ui.TEXT);
            } else if (i == 1) {
                Ui.icon(g, Icons.STAR, bx + 2, ty + 2, on ? Ui.ACCENT : Ui.TEXT);
            } else {
                g.renderItem(tabs.get(i - 2).icon(), bx + 2, ty + 2);
            }
            if (hover) {
                Component name = i == 0 ? Component.translatable("designandbuild.picker.all")
                        : i == 1 ? Component.translatable("designandbuild.picker.recent") : tabs.get(i - 2).name();
                screen.tooltip = List.of(name);
            }
        }
        g.disableScissor();

        // 격자
        g.fill(gridX - 1, gridY - 1, gridX + cols * CELL + 1, gridY + rows * CELL + 1, 0xFF0B0E12);
        int first = scroll * cols;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int i = first + r * cols + c;
                int sx = gridX + c * CELL, sy = gridY + r * CELL;
                g.fill(sx, sy, sx + CELL - 1, sy + CELL - 1, 0xFF161B22);
                if (i >= shown.size()) continue;
                ItemStack st = shown.get(i);
                boolean ok = placeable(st);
                boolean hover = Ui.inside(mx, my, sx, sy, CELL - 1, CELL - 1);
                if (hover && ok) g.fill(sx, sy, sx + CELL - 1, sy + CELL - 1, 0x50FFFFFF);
                g.renderItem(st, sx + 1, sy + 1);
                if (!ok) {
                    g.pose().pushPose();
                    g.pose().translate(0, 0, 200);
                    g.fill(sx, sy, sx + CELL - 1, sy + CELL - 1, 0xB0161B22);
                    g.pose().popPose();
                }
                if (hover) {
                    screen.tooltip = ok
                            ? List.of(st.getHoverName(), Component.literal(BuiltInRegistries.ITEM.getKey(st.getItem()).toString())
                            .withStyle(s -> s.withColor(Ui.DIM & 0xFFFFFF)))
                            : List.of(st.getHoverName(), Component.translatable("designandbuild.picker.not_block")
                            .withStyle(s -> s.withColor(Ui.DIM & 0xFFFFFF)));
                }
            }
        }
        // 스크롤 막대
        int totalRows = Mth.positiveCeilDiv(shown.size(), cols);
        if (totalRows > rows) {
            int trackH = rows * CELL;
            int barH = Math.max(10, trackH * rows / totalRows);
            int barY = gridY + (trackH - barH) * scroll / Math.max(1, totalRows - rows);
            g.fill(x + w - 7, gridY, x + w - 5, gridY + trackH, 0x40FFFFFF);
            g.fill(x + w - 7, barY, x + w - 5, barY + barH, 0xFFB8C2CC);
        }
        if (shown.isEmpty()) {
            Ui.centered(g, Component.translatable(tab == 1 && search.getValue().isBlank() ? "designandbuild.picker.no_recent" : "designandbuild.picker.none"),
                    gridX + cols * CELL / 2, gridY + rows * CELL / 2 - 4, Ui.DIM);
        }
        Component hint = Component.translatable("designandbuild.picker.hint");
        Ui.text(g, Ui.clip(hint.getString(), w - 20), x + 10, y + h - 9 - 1, Ui.DISABLED);
    }

    private void renderJei(GuiGraphics g, int mx, int my) {
        Ui.text(g, Component.translatable("designandbuild.picker.title"), x + 8, y + 8, Ui.TEXT);
        Ui.text(g, targetName(), x + 8, y + 20, Ui.DIM);
        g.fill(x + 1, y + 31, x + w - 1, y + 32, Ui.EDGE);
        int ty = y + 38;
        Component text = JeiState.listDisplayed() ? Component.translatable("designandbuild.picker.jei")
                : Component.translatable("designandbuild.picker.jei_hidden");
        for (var line : Ui.wrap(text, w - 16)) {
            g.drawString(Ui.font(), line, x + 8, ty, Ui.TEXT, false);
            ty += 10;
        }
        // 최근
        ty += 4;
        Ui.text(g, Component.translatable("designandbuild.picker.recent"), x + 8, ty, Ui.DIM);
        ty += 11;
        List<ItemStack> rec = recent();
        int perRow = (w - 16) / CELL;
        for (int i = 0; i < Math.min(rec.size(), perRow * 2); i++) {
            int sx = x + 8 + (i % perRow) * CELL, sy = ty + (i / perRow) * CELL;
            boolean hover = Ui.inside(mx, my, sx, sy, CELL - 1, CELL - 1);
            g.fill(sx, sy, sx + CELL - 1, sy + CELL - 1, hover ? 0x50FFFFFF : 0xFF161B22);
            g.renderItem(rec.get(i), sx + 1, sy + 1);
            if (hover) screen.tooltip = List.of(rec.get(i).getHoverName());
        }
        // 기본 선택기로
        Component sw = Component.translatable("designandbuild.picker.builtin");
        jeiSwitchW = Ui.font().width(sw) + 8;
        jeiSwitchX = x + 8;
        jeiSwitchY = y + h - 18;
        boolean hs = Ui.inside(mx, my, jeiSwitchX, jeiSwitchY, jeiSwitchW, 12);
        Ui.text(g, sw, jeiSwitchX + 4, jeiSwitchY + 2, hs ? Ui.ACCENT : Ui.DIM);
        g.fill(jeiSwitchX + 4, jeiSwitchY + 11, jeiSwitchX + jeiSwitchW - 4, jeiSwitchY + 12, hs ? Ui.ACCENT : Ui.EDGE);
    }

    // ------------------------------------------------------------------
    // 입력
    // ------------------------------------------------------------------

    /** 블록을 골랐다. */
    public void accept(ItemStack picked) {
        if (!placeable(picked)) {
            screen.session().toast(Component.translatable("designandbuild.picker.not_block"));
            return;
        }
        ItemStack st = new ItemStack(picked.getItem());
        EditorSession s = screen.session();
        if (target.kind() == Kind.PALETTE) {
            PaletteBar.setSlot(target.index(), st);
            boolean noMaterial = s.materials[0].isEmpty() && s.materials[1].isEmpty() && s.materials[2].isEmpty();
            if (noMaterial) {
                s.paletteIndex = target.index();
                s.materials[0] = st.copy();
            }
        } else if (target.kind() == Kind.REPLACE_TARGET) {
            s.replaceTargets[target.index()] = st;
        } else {
            int i = target.index();
            s.materials[i] = st;
            if (s.weights[i] <= 0) s.weights[i] = 1;
        }
        String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
        List<String> rec = ClientConfig.get().recentBlocks;
        rec.remove(id);
        rec.add(0, id);
        while (rec.size() > 28) rec.remove(rec.size() - 1);
        ClientConfig.save();
        close();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        layout();
        if (jeiMode) {
            if (Ui.inside(mx, my, jeiSwitchX, jeiSwitchY, jeiSwitchW, 12)) {
                jeiMode = false;
                filterKey = "";
                return true;
            }
            int ty = y + 38 + Ui.wrap(Component.translatable(JeiState.listDisplayed() ? "designandbuild.picker.jei" : "designandbuild.picker.jei_hidden"), w - 16).size() * 10 + 4 + 11;
            List<ItemStack> rec = recent();
            int perRow = (w - 16) / CELL;
            for (int i = 0; i < Math.min(rec.size(), perRow * 2); i++) {
                int sx = x + 8 + (i % perRow) * CELL, sy = ty + (i / perRow) * CELL;
                if (Ui.inside(mx, my, sx, sy, CELL - 1, CELL - 1)) {
                    accept(rec.get(i));
                    return true;
                }
            }
            if (!Ui.inside(mx, my, x, y, w, h) && mx < screen.width - JEI_LIST_W) close();
            return true;
        }
        if (!Ui.inside(mx, my, x, y, w, h)) {
            close();
            return true;
        }
        int sw = w - 20;
        search.setFocused(Ui.inside(mx, my, x + 10, y + 20, sw, 14));
        if (search.isFocused()) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) search.setValue("");
            search.mouseClicked(mx, my, button);
            return true;
        }
        int ty = y + 38;
        if (my >= ty && my < ty + 20 && mx >= x + 10 && mx < x + w - 10) {
            int i = (int) ((mx - (x + 10) + tabScroll) / 22);
            if (i >= 0 && i < 2 + tabs.size()) {
                tab = i;
                filterKey = "";
            }
            return true;
        }
        if (Ui.inside(mx, my, gridX, gridY, cols * CELL, rows * CELL)) {
            int c = (int) ((mx - gridX) / CELL), r = (int) ((my - gridY) / CELL);
            int i = (scroll + r) * cols + c;
            if (i >= 0 && i < shown.size() && placeable(shown.get(i))) accept(shown.get(i));
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (jeiMode) return true;
        layout();
        int ty = y + 38;
        if (my >= ty && my < ty + 20) {
            int maxScroll = Math.max(0, (2 + tabs.size()) * 22 - (w - 20));
            tabScroll = Mth.clamp(tabScroll - (int) (delta * 22), 0, maxScroll);
            return true;
        }
        int totalRows = Mth.positiveCeilDiv(shown.size(), Math.max(1, cols));
        scroll = Mth.clamp(scroll - (int) Math.signum(delta), 0, Math.max(0, totalRows - rows));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (jeiMode) return true;
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            refilter();
            for (ItemStack st : shown) {
                if (placeable(st)) {
                    accept(st);
                    break;
                }
            }
            return true;
        }
        if (!search.isFocused()) search.setFocused(true);
        search.keyPressed(key, scan, mods);
        return true;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (jeiMode) return true;
        if (tab >= 2) tab = 0; // 검색은 전체에서 (크리에이티브 검색 탭처럼)
        if (!search.isFocused()) search.setFocused(true);
        search.charTyped(c, mods);
        return true;
    }
}

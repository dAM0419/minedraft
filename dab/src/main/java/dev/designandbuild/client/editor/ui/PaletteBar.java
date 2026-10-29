package dev.designandbuild.client.editor.ui;

import dev.designandbuild.client.ClientConfig;
import dev.designandbuild.client.editor.EditorScreen;
import dev.designandbuild.client.editor.EditorSession;
import dev.minedraft.lib.client.ui.Ui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 팔레트: 자주 쓰는 블록 20칸 (플레이어별 저장). 핫바처럼 Ctrl+휠로 고른다.
 * 클릭 = 재료를 그 블록 하나로, Shift+클릭 = 섞기 칸에 추가, 빈 칸 클릭 = 블록 선택기,
 * 칸끼리 끌어 순서 바꾸기, 바 밖으로 끌어내면 비우기.
 */
public final class PaletteBar extends UiPart {
    public static final int SLOTS = 20, SLOT = 18;
    private int dragFrom = -1;
    private boolean dragged;

    public PaletteBar(EditorScreen screen) {
        super(screen);
    }

    public static int preferredWidth() {
        return 14 + SLOTS * SLOT + 4;
    }

    @Override
    public boolean visible() {
        return s().tool == EditorSession.Tool.DIRECT && s().op != EditorSession.Op.ERASE;
    }

    private boolean collapsed() {
        return ClientConfig.get().paletteCollapsed;
    }

    @Override
    public boolean contains(double mx, double my) {
        if (!visible()) return false;
        if (collapsed()) return Ui.inside(mx, my, x, y + h - 10, 14, 10);
        return super.contains(mx, my);
    }

    public static ItemStack stackAt(int i) {
        List<String> p = ClientConfig.get().palette;
        if (i < 0 || i >= p.size() || p.get(i).isEmpty()) return ItemStack.EMPTY;
        ResourceLocation rl = ResourceLocation.tryParse(p.get(i));
        if (rl == null) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.get(rl);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    public static void setSlot(int i, ItemStack stack) {
        List<String> p = ClientConfig.get().palette;
        while (p.size() < SLOTS) p.add("");
        p.set(i, stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        ClientConfig.save();
    }

    public void addToPalette(ItemStack stack) {
        int target = -1;
        for (int i = 0; i < SLOTS; i++) {
            if (stackAt(i).isEmpty()) {
                target = i;
                break;
            }
        }
        if (target < 0) target = Math.max(0, s().paletteIndex);
        setSlot(target, stack);
        s().toast(Component.translatable("designandbuild.palette.added", stack.getHoverName()));
    }

    /** Ctrl+휠: 채워진 칸만 돌아가며 고른다. */
    public void cycle(int dir) {
        EditorSession s = s();
        for (int k = 1; k <= SLOTS; k++) {
            int i = Math.floorMod(s.paletteIndex + dir * k, SLOTS);
            ItemStack st = stackAt(i);
            if (!st.isEmpty()) {
                choose(i, false);
                return;
            }
        }
    }

    private void choose(int i, boolean addToMix) {
        EditorSession s = s();
        ItemStack st = stackAt(i);
        if (st.isEmpty()) return;
        s.paletteIndex = i;
        if (addToMix) {
            for (int k = 0; k < 3; k++) {
                if (s.materials[k].isEmpty()) {
                    s.materials[k] = st.copy();
                    s.weights[k] = 1;
                    return;
                }
            }
            s.toast(Component.translatable("designandbuild.palette.mix_full"));
        } else {
            s.materials[0] = st.copy();
            s.materials[1] = ItemStack.EMPTY;
            s.materials[2] = ItemStack.EMPTY;
        }
    }

    private int slotX(int i) {
        return x + 14 + i * SLOT;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        if (collapsed()) {
            int cy = y + h - 10;
            g.fill(x, cy, x + 14, cy + 10, Ui.PANEL);
            Ui.border(g, x, cy, 14, 10, Ui.EDGE);
            Ui.text(g, "▴", x + 4, cy + 1, Ui.DIM);
            if (Ui.inside(mx, my, x, cy, 14, 10)) screen.tooltip = List.of(Component.translatable("designandbuild.palette.expand"));
            return;
        }
        Ui.panel(g, x, y, w, h);
        boolean hc = Ui.inside(mx, my, x + 1, y + 1, 12, h - 2);
        if (hc) g.fill(x + 1, y + 1, x + 13, y + h - 1, Ui.HOVER);
        Ui.text(g, "▾", x + 4, y + 6, Ui.DIM);
        if (hc) screen.tooltip = List.of(Component.translatable("designandbuild.palette.collapse"));
        EditorSession s = s();
        for (int i = 0; i < SLOTS; i++) {
            int sx = slotX(i), sy = y + 1;
            if (sx + SLOT > x + w) break;
            boolean hover = Ui.inside(mx, my, sx, sy, SLOT, SLOT);
            g.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, hover ? 0xFF262E38 : 0xFF161B22);
            if (i == s.paletteIndex) Ui.border(g, sx, sy, SLOT, SLOT, Ui.ACCENT);
            ItemStack st = stackAt(i);
            if (!st.isEmpty() && !(dragged && i == dragFrom)) {
                g.renderItem(st, sx + 1, sy + 1);
            }
            if (hover) {
                screen.tooltip = st.isEmpty() ? List.of(Component.translatable("designandbuild.palette.empty"))
                        : List.of(st.getHoverName(), Component.translatable("designandbuild.palette.tip").withStyle(x -> x.withColor(Ui.DIM & 0xFFFFFF)));
            }
        }
        if (dragged && dragFrom >= 0) {
            ItemStack st = stackAt(dragFrom);
            if (!st.isEmpty()) g.renderItem(st, mx - 8, my - 8);
        }
    }

    private int slotAt(double mx, double my) {
        for (int i = 0; i < SLOTS; i++) {
            if (Ui.inside(mx, my, slotX(i), y + 1, SLOT, SLOT)) return i;
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (collapsed()) {
            ClientConfig.get().paletteCollapsed = false;
            return true;
        }
        if (Ui.inside(mx, my, x + 1, y + 1, 12, h - 2)) {
            ClientConfig.get().paletteCollapsed = true;
            return true;
        }
        int i = slotAt(mx, my);
        if (i < 0) return true;
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            setSlot(i, ItemStack.EMPTY);
            return true;
        }
        if (stackAt(i).isEmpty()) {
            screen.openPicker(new BlockPicker.Target(BlockPicker.Kind.PALETTE, i));
            return true;
        }
        dragFrom = i;
        dragged = false;
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragFrom < 0) return false;
        dragged = true;
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragFrom < 0) return false;
        int from = dragFrom;
        dragFrom = -1;
        if (!dragged) {
            choose(from, net.minecraft.client.gui.screens.Screen.hasShiftDown());
            return true;
        }
        dragged = false;
        int to = slotAt(mx, my);
        if (to < 0) {
            if (!super.contains(mx, my)) setSlot(from, ItemStack.EMPTY);
            return true;
        }
        if (to != from) {
            ItemStack a = stackAt(from), b = stackAt(to);
            setSlot(from, b);
            setSlot(to, a);
            if (s().paletteIndex == from) s().paletteIndex = to;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        cycle(delta > 0 ? -1 : 1);
        return true;
    }
}

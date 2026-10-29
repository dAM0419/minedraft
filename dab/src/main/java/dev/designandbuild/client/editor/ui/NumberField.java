package dev.designandbuild.client.editor.ui;

import dev.designandbuild.client.ui.Ui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** 숫자 칸: 직접 입력, 휠로 ±1 (Shift ±10). 입력을 마치면(엔터·초점 이동) 값을 반영한다. */
public final class NumberField {
    public final EditBox box;
    private final int min, max;
    private final IntSupplier getter;
    private final IntConsumer setter;

    public NumberField(int min, int max, IntSupplier getter, IntConsumer setter) {
        this.min = min;
        this.max = max;
        this.getter = getter;
        this.setter = setter;
        this.box = new EditBox(Ui.font(), 0, 0, 20, 12, Component.empty());
        box.setMaxLength(4);
        box.setBordered(false);
        box.setTextColor(Ui.TEXT);
        box.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        box.setValue(Integer.toString(getter.getAsInt()));
    }

    public void layout(int x, int y, int w) {
        box.setX(x + 2);
        box.setY(y + 2);
        box.setWidth(w - 3);
        this.x = x;
        this.y = y;
        this.w = w;
    }

    private int x, y, w;

    public boolean contains(double mx, double my) {
        return Ui.inside(mx, my, x, y, w, 12);
    }

    public void render(GuiGraphics g, int mx, int my, float pt) {
        if (!box.isFocused()) {
            String v = Integer.toString(getter.getAsInt());
            if (!v.equals(box.getValue())) box.setValue(v);
        }
        g.fill(x, y, x + w, y + 12, box.isFocused() ? 0xFF0B0E12 : 0xFF161B22);
        Ui.border(g, x, y, w, 12, box.isFocused() ? Ui.ACCENT : (contains(mx, my) ? 0xFF5A6573 : Ui.EDGE));
        box.render(g, mx, my, pt);
    }

    public void commit() {
        String v = box.getValue();
        int value = v.isEmpty() ? getter.getAsInt() : Mth.clamp(parse(v), min, max);
        setter.accept(value);
        box.setValue(Integer.toString(getter.getAsInt()));
    }

    public void scroll(double delta, boolean big) {
        int step = big ? 10 : 1;
        setter.accept(Mth.clamp(getter.getAsInt() + (delta > 0 ? step : -step), min, max));
        box.setValue(Integer.toString(getter.getAsInt()));
    }

    private static int parse(String v) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

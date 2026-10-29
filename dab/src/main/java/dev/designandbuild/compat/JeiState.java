package dev.designandbuild.compat;

import net.minecraft.world.item.ItemStack;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * JEI 와 주고받는 값. JEI 클래스를 직접 참조하지 않으므로 JEI 가 없어도 안전하다.
 * 값은 JEI 플러그인이 런타임을 받을 때 채운다.
 */
public final class JeiState {
    private JeiState() {}

    private static volatile boolean available;
    /** 마우스 아래 JEI 목록(또는 즐겨찾기)의 아이템. 없으면 빈 스택. */
    public static volatile Supplier<ItemStack> underMouse = () -> ItemStack.EMPTY;
    /** JEI 검색창이 키보드 입력을 받는 중인가. */
    public static volatile BooleanSupplier keyboardFocus = () -> false;
    /** JEI 목록이 켜져 있는가 (Ctrl+O 로 끌 수 있다). */
    public static volatile BooleanSupplier listDisplayed = () -> false;

    public static void setAvailable(boolean value) {
        available = value;
        if (!value) {
            underMouse = () -> ItemStack.EMPTY;
            keyboardFocus = () -> false;
            listDisplayed = () -> false;
        }
    }

    public static boolean available() {
        return available;
    }

    public static ItemStack underMouse() {
        try {
            ItemStack st = underMouse.get();
            return st == null ? ItemStack.EMPTY : st;
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    public static boolean keyboardFocus() {
        try {
            return keyboardFocus.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean listDisplayed() {
        try {
            return listDisplayed.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }
}

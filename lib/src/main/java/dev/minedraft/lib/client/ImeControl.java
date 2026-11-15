package dev.minedraft.lib.client;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.system.SharedLibrary;

import java.nio.IntBuffer;

/**
 * 윈도우 입력기(IME)를 영문으로 돌린다.
 * 한국어 입력기가 한글 상태면 입력기가 거른 키(VK_PROCESSKEY)를 GLFW 가 버려서 W·A·S·D 같은 글자 키가 게임에 오지 않는다.
 * 편집창은 입력칸을 쓰지 않는 동안 이 키로 움직이므로, 그동안 영문으로 둔다. 윈도우가 아니거나 실패하면 아무것도 하지 않는다.
 */
public final class ImeControl {
    private static final int IME_CMODE_NATIVE = 0x0001; // 한글(모국어) 입력

    private static boolean ready, failed;
    private static long getContext, releaseContext, getConversion, setConversion;

    private ImeControl() {
    }

    private static boolean init() {
        if (ready) return true;
        if (failed) return false;
        try {
            if (Platform.get() != Platform.WINDOWS) {
                failed = true;
                return false;
            }
            SharedLibrary imm = Library.loadNative(ImeControl.class, "org.lwjgl", "imm32");
            getContext = imm.getFunctionAddress("ImmGetContext");
            releaseContext = imm.getFunctionAddress("ImmReleaseContext");
            getConversion = imm.getFunctionAddress("ImmGetConversionStatus");
            setConversion = imm.getFunctionAddress("ImmSetConversionStatus");
            if (getContext == 0 || releaseContext == 0 || getConversion == 0 || setConversion == 0) {
                failed = true;
                return false;
            }
            ready = true;
            return true;
        } catch (Throwable t) {
            failed = true;
            return false;
        }
    }

    /** 한글 입력 상태면 영문으로 돌린다. 매 프레임 불러도 된다 (바꿀 게 없으면 상태만 읽는다). */
    public static void forceAlphanumeric() {
        if (!init()) return;
        try {
            long hwnd = GLFWNativeWin32.glfwGetWin32Window(Minecraft.getInstance().getWindow().getWindow());
            if (hwnd == 0) return;
            long himc = JNI.callPP(hwnd, getContext);
            if (himc == 0) return;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer conv = stack.mallocInt(1), sent = stack.mallocInt(1);
                if (JNI.callPPPI(himc, org.lwjgl.system.MemoryUtil.memAddress(conv), org.lwjgl.system.MemoryUtil.memAddress(sent), getConversion) != 0
                        && (conv.get(0) & IME_CMODE_NATIVE) != 0) {
                    JNI.callPI(himc, conv.get(0) & ~IME_CMODE_NATIVE, sent.get(0), setConversion);
                }
            } finally {
                JNI.callPPI(hwnd, himc, releaseContext);
            }
        } catch (Throwable t) {
            failed = true;
            ready = false;
        }
    }
}

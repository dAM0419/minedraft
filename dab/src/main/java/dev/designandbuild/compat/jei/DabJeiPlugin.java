package dev.designandbuild.compat.jei;

import dev.designandbuild.DesignAndBuild;
import dev.designandbuild.client.editor.EditorScreen;
import dev.minedraft.lib.compat.JeiState;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * JEI 연동: 편집창의 블록 선택기가 열려 있을 때만 JEI 목록을 오른쪽에 띄운다.
 * 목록에서 아이템을 누르면 (레시피 대신) 그 블록을 고른다. 이 처리는 ClientEvents 의 마우스 이벤트에서 한다.
 */
@JeiPlugin
public final class DabJeiPlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return DesignAndBuild.rl("jei");
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiScreenHandler(EditorScreen.class, screen -> {
            int[] a = screen.jeiArea();
            if (a == null) return null;
            return new Props(a[0], a[1], a[2], a[3], screen.width, screen.height);
        });
        registration.addGlobalGuiHandler(new IGlobalGuiHandler() {
            @Override
            public Collection<Rect2i> getGuiExtraAreas() {
                Screen s = Minecraft.getInstance().screen;
                if (!(s instanceof EditorScreen es)) return List.of();
                List<Rect2i> out = new ArrayList<>();
                for (int[] r : es.jeiExclusions()) out.add(new Rect2i(r[0], r[1], r[2], r[3]));
                return out;
            }
        });
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        JeiState.setAvailable(true);
        JeiState.underMouse = () -> {
            ItemStack st = runtime.getIngredientListOverlay().getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
            if (st == null) st = runtime.getBookmarkOverlay().getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
            return st == null ? ItemStack.EMPTY : st;
        };
        JeiState.keyboardFocus = () -> runtime.getIngredientListOverlay().hasKeyboardFocus();
        JeiState.listDisplayed = () -> runtime.getIngredientListOverlay().isListDisplayed();
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiState.setAvailable(false);
    }

    private record Props(int guiLeft, int guiTop, int guiXSize, int guiYSize, int screenWidth, int screenHeight)
            implements IGuiProperties {
        @Override
        public Class<? extends Screen> screenClass() {
            return EditorScreen.class;
        }
    }
}

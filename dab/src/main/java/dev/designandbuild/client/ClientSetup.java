package dev.designandbuild.client;

import dev.designandbuild.DesignAndBuild;
import dev.designandbuild.Registration;
import dev.designandbuild.client.editor.EditorScreen;
import dev.designandbuild.client.editor.ui.BlockPicker;
import dev.designandbuild.client.library.ClientLibrary;
import dev.designandbuild.client.projection.ProjectionHud;
import dev.designandbuild.client.projection.ProjectionManager;
import dev.designandbuild.client.projection.ProjectionRenderer;
import dev.designandbuild.client.table.BlueprintMenuScreen;
import dev.designandbuild.client.table.BlueprintTableScreen;
import dev.minedraft.lib.compat.JeiState;
import dev.designandbuild.item.BlueprintItem;
import dev.designandbuild.net.Net;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** 클라이언트 쪽 등록과 이벤트. */
public final class ClientSetup {
    private ClientSetup() {}

    public static void init(IEventBus modBus) {
        ClientConfig.load();
        Net.setClientReceiver(ClientNet::receive);
        BlueprintItem.clientMenuOpener = BlueprintMenuScreen::open;
        // Lib 이 DaB 도면 영역의 편집창을 열 때: 편집 상태를 만들고 화면을 띄운다
        dev.minedraft.lib.client.ClientAreas.registerEditor(dev.designandbuild.core.BlueprintModel.AREA_TYPE, (area, anchor) -> {
            dev.designandbuild.client.editor.EditorSession session =
                    new dev.designandbuild.client.editor.EditorSession(dev.designandbuild.core.BlueprintModel.of(area), anchor);
            net.minecraft.client.Minecraft.getInstance().setScreen(new EditorScreen(session));
            return session;
        });
        // 사본이 통째로 바뀌면 투영도 새 사본으로
        dev.minedraft.lib.client.ClientAreas.onReplaced(area -> {
            dev.designandbuild.core.BlueprintModel m = dev.designandbuild.core.BlueprintModel.ofNullable(area);
            if (m != null) ProjectionManager.onModelReplaced(m);
        });

        modBus.addListener(ClientSetup::clientSetup);
        modBus.addListener(ClientSetup::registerScreens);
        modBus.addListener(Keys::register);
        modBus.addListener(ClientSetup::registerLayers);

        NeoForge.EVENT_BUS.addListener(ClientSetup::clientTick);
        NeoForge.EVENT_BUS.addListener(ProjectionRenderer::render);
        NeoForge.EVENT_BUS.addListener(ClientSetup::loggingOut);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ClientSetup::keyPressed);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ClientSetup::keyReleased);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ClientSetup::charTyped);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ClientSetup::mousePressed);
        NeoForge.EVENT_BUS.addListener(ClientSetup::useKey);
    }

    /**
     * 건설망치: 우클릭할 때 겨눈 투영 칸이 있으면 서버에 설치를 요청하고 바닐라 동작은 막는다.
     * 투영 칸이 없으면 바닐라대로 둔다 (망치 아이템 사용은 행동만 소비하므로 보조손 도면 메뉴는 열리지 않는다).
     */
    private static void useKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || !mc.player.getMainHandItem().is(Registration.HAMMER.get())) return;
        net.minecraft.core.BlockPos target = ProjectionManager.hammerTarget;
        if (target == null) return;
        boolean bulk = mc.player.isShiftKeyDown();
        Net.toServer(dev.designandbuild.net.Msg.C_HAMMER, b -> {
            b.writeBlockPos(target);
            b.writeBoolean(bulk);
        });
        event.setSwingHand(true);
        event.setCanceled(true);
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(Registration.BLUEPRINT.get(), DesignAndBuild.rl("bound"),
                (stack, level, entity, seed) -> BlueprintItem.isBound(stack) ? 1.0F : 0.0F));
    }

    private static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(Registration.TABLE_MENU.get(), BlueprintTableScreen::new);
    }

    private static void registerLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(DesignAndBuild.rl("projection"), ProjectionHud::render);
    }

    private static void clientTick(ClientTickEvent.Post event) {
        ProjectionManager.tick();
    }

    private static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientLibrary.reset();
        ProjectionManager.reset();
        Net.resetClient();
        ClientNet.tableInfo = null;
    }

    // ------------------------------------------------------------------
    // 편집창이 열려 있는 동안 다른 키 막기 (Esc·F2·F11 은 바닐라가 먼저 처리하거나 편집창이 받는다)
    // ------------------------------------------------------------------

    /**
     * JEI 선택 모드에서는 JEI 가 키를 받게 둔다 (검색 입력, Ctrl+O 등).
     * 단 아이템 위에 마우스가 있으면 막는다: R·U 같은 키가 레시피 화면을 열어 편집창이 닫히지 않게.
     */
    private static boolean letJeiType(EditorScreen screen) {
        BlockPicker picker = screen.picker();
        if (picker == null || !picker.isJeiMode()) return false;
        return JeiState.keyboardFocus() || JeiState.underMouse().isEmpty();
    }

    private static void keyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!(event.getScreen() instanceof EditorScreen screen) || letJeiType(screen)) return;
        event.setCanceled(true);
        screen.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers());
    }

    private static void keyReleased(ScreenEvent.KeyReleased.Pre event) {
        if (!(event.getScreen() instanceof EditorScreen screen) || letJeiType(screen)) return;
        event.setCanceled(true);
        screen.keyReleased(event.getKeyCode(), event.getScanCode(), event.getModifiers());
    }

    private static void charTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (!(event.getScreen() instanceof EditorScreen screen) || letJeiType(screen)) return;
        event.setCanceled(true);
        screen.charTyped(event.getCodePoint(), event.getModifiers());
    }

    /** JEI 목록의 아이템을 누르면 레시피 화면 대신 블록 선택. */
    private static void mousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof EditorScreen screen)) return;
        BlockPicker picker = screen.picker();
        if (picker == null || !picker.isJeiMode()) return;
        ItemStack under = JeiState.underMouse();
        if (under.isEmpty()) return;
        event.setCanceled(true);
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) picker.accept(under);
    }
}

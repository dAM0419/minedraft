package dev.minedraft.lib.client.editor;

import com.mojang.blaze3d.platform.InputConstants;
import dev.minedraft.lib.client.ClientAreas;
import dev.minedraft.lib.client.LibConfig;
import dev.minedraft.lib.client.editor.ui.HelpOverlay;
import dev.minedraft.lib.client.editor.ui.LayerSlider;
import dev.minedraft.lib.client.editor.ui.Modal;
import dev.minedraft.lib.client.editor.ui.SidePanel;
import dev.minedraft.lib.client.editor.ui.ToolBar;
import dev.minedraft.lib.client.editor.ui.ToolButton;
import dev.minedraft.lib.client.editor.ui.TopBar;
import dev.minedraft.lib.client.editor.ui.ViewCube;
import dev.minedraft.lib.client.editor.ui.UiPart;
import dev.minedraft.lib.client.ui.Icons;
import dev.minedraft.lib.client.ui.Ui;
import dev.minedraft.lib.edit.EditRequests;
import dev.minedraft.lib.net.Channel;
import dev.minedraft.lib.net.LibNet;
import dev.minedraft.lib.util.Box;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 영역 편집창의 틀. 월드가 화면의 대부분을 차지하고, UI 조각({@link UiPart})이 가장자리에 붙는다.
 * <p>
 * 여기서 하는 일: 불러오기 화면, 카메라 조작(우클릭 끌기·휠·이동 키·Q/E), 단면(L, Shift+L, Ctrl+클릭, Shift+휠),
 * 투시(X), 테두리(G), 도움말(H), 되돌리기(Ctrl+Z/Y), 창(모달)·입력칸 포커스·툴팁, 커서 아래 칸 찾기와 다른 편집자에게 커서 알리기.
 * <p>
 * <pre>
 * 상단 바: 영역·편집자 정보     좌측 툴바: 도구 (모드가 등록)
 * 하단 바: 모드가 정함 (없어도 됨) 우측 창: 탭 (모드가 등록, 범위 탭은 Lib 제공)
 * 뷰포트: 나머지 전부 (단면 슬라이더·시점 큐브, 모드가 얹는 것)
 * </pre>
 * 모드가 할 일: 생성자에서 도구·탭 등록과 {@link #assembleParts}, {@link #renderViewport} 로 월드 그리기, 도구 입력은 {@code viewport*} 훅.
 */
public abstract class AreaEditorScreen extends Screen {
    public static final int TOP = 16, LEFT = 22;

    private final AreaEditorSession base;
    public final TopBar topBar;
    public final ToolBar toolBar;
    public final SidePanel sidePanel;
    public final LayerSlider layerSlider;
    public final ViewCube viewCube;
    /** 아래쪽 바 (없으면 null). 높이는 {@link UiPart#requiredHeight}. */
    @Nullable
    protected UiPart<?> bottomBar;
    /** 그리는 순서 = 아래에서 위로 (누르기는 반대 순서로 위에 있는 것부터). */
    protected final List<UiPart<?>> parts = new ArrayList<>();
    /** 왼쪽 툴바 위쪽의 도구 단추 (모드가 생성자에서 채운다). */
    public final List<ToolButton> tools = new ArrayList<>();
    /** 왼쪽 툴바 아래쪽, 테두리와 도움말 사이의 보기 단추 (모드가 채운다). */
    public final List<ToolButton> toggles = new ArrayList<>();
    /** 오른쪽 창의 탭 (모드가 채운다. 범위 탭은 {@link dev.minedraft.lib.client.editor.ui.RangeTab}). */
    public final List<dev.minedraft.lib.client.editor.ui.PanelTab> panelTabs = new ArrayList<>();

    @Nullable
    public Modal<?> modal;
    @Nullable
    private EditBox focused;
    @Nullable
    private Runnable focusCommit;
    @Nullable
    public List<Component> tooltip;
    public boolean showHelp;

    // ---- 배치 (layout 이 채운다) ----
    public int bottomH = 22;
    public int vpX, vpY, vpW, vpH;
    public boolean panelAutoCollapsed;

    // ---- 상호작용 ----
    @Nullable
    public AreaEditorSession.Hit hover;
    private double mouseX, mouseY;
    private boolean rightDown, rightDragged;
    private double rightStartX, rightStartY, lastPanX, lastPanY;
    private long lastCursorSend;
    private String lastCursorKey = "";
    private long lastPanNanos;

    protected AreaEditorScreen(Component title, AreaEditorSession session) {
        super(title);
        this.base = session;
        this.topBar = new TopBar(this);
        this.toolBar = new ToolBar(this);
        this.sidePanel = new SidePanel(this);
        this.layerSlider = new LayerSlider(this);
        this.viewCube = new ViewCube(this);
    }

    /**
     * 조각을 그리는 순서대로 모은다 (누르기는 반대 순서로 위에 있는 것부터).
     * 뷰포트 위에 얹는 것(overlays) &lt; 단면 슬라이더 · 시점 큐브 &lt; 오른쪽 창 &lt; 가장자리 바 (도구 · 아래쪽 · 상단).
     */
    protected final void assembleParts(@Nullable UiPart<?> bottom, UiPart<?>... overlays) {
        parts.clear();
        bottomBar = bottom;
        parts.addAll(List.of(overlays));
        parts.add(layerSlider);
        parts.add(viewCube);
        parts.add(sidePanel);
        parts.add(toolBar);
        if (bottom != null) parts.add(bottom);
        parts.add(topBar);
    }

    public AreaEditorSession session() {
        return base;
    }

    @Override
    public boolean isPauseScreen() {
        // 싱글: 서버 틱을 멈춘다. 단 서버가 청크 스냅샷을 틱마다 나눠 보내므로, 받을 청크가 남아 있는 동안은 멈추지 않는다
        // (편집창을 열 때, 청크를 추가했을 때).
        return base.ready && !awaitingSnapshots();
    }

    private boolean awaitingSnapshots() {
        LongIterator it = base.area.chunks().iterator();
        while (it.hasNext()) if (!base.snapshot.has(it.nextLong())) return true;
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    protected void init() {
        layout();
    }

    @Override
    public void removed() {
        super.removed();
        onRemoved();
        ClientAreas.editorClosed(base);
        LibConfig.save();
    }

    /** 편집창이 닫힐 때 (자원 정리, 설정 저장). */
    protected void onRemoved() {
    }

    // ------------------------------------------------------------------
    // 배치
    // ------------------------------------------------------------------

    /** 조각 배치와 뷰포트(vpX..vpH) 계산. 그릴 때마다 부른다. 카메라에 화면 크기도 알린다. */
    public void layout() {
        int W = width, H = height;
        boolean userCollapsed = LibConfig.get().panelCollapsed;
        int panelW = SidePanel.WIDTH;
        panelAutoCollapsed = (W - LEFT - panelW) < W * 0.6;
        boolean collapsed = userCollapsed || panelAutoCollapsed;
        int right = collapsed ? SidePanel.STRIP : panelW;
        bottomH = bottomBar == null ? 0 : bottomBar.requiredHeight(W - LEFT);
        topBar.setBounds(0, 0, W, TOP);
        toolBar.setBounds(0, TOP, LEFT, H - TOP);
        if (bottomBar != null) bottomBar.setBounds(LEFT, H - bottomH, W - LEFT, bottomH);
        sidePanel.setBounds(W - right, TOP, right, H - TOP - bottomH);
        sidePanel.setCollapsed(collapsed);
        vpX = LEFT;
        vpY = TOP;
        vpW = W - LEFT - SidePanel.STRIP;
        if (!collapsed) vpW = W - LEFT - panelW;
        vpH = H - TOP - bottomH;
        int floor = layoutOverlays();
        layerSlider.setBounds(vpX + vpW - 30, vpY + 26, 26, floor - vpY - 34);
        // 시점 큐브: 뷰포트 오른쪽 위, 층 슬라이더 왼쪽
        int cubeRight = viewCubeRight(vpX + vpW - 34);
        viewCube.setBounds(cubeRight - 64, vpY + 4, 64, 84);
        base.camera.setViewport(W, H);
    }

    /** 뷰포트 위에 얹는 모드 조각 배치 (vp* 가 정해진 뒤). 돌려주는 값: 뷰포트 안 떠 있는 조각들이 내려올 수 있는 아래 끝. */
    protected int layoutOverlays() {
        return vpY + vpH;
    }

    /** 시점 큐브 오른쪽 끝 (오른쪽에 다른 것이 열려 있으면 비켜 선다). */
    protected int viewCubeRight(int preferred) {
        return preferred;
    }

    public boolean inViewport(double mx, double my) {
        if (!Ui.inside(mx, my, vpX, vpY, vpW, vpH)) return false;
        for (UiPart<?> p : parts) if (blocksViewport(p) && p.contains(mx, my)) return false;
        return true;
    }

    /** 이 조각이 뷰포트 위를 가리는지 (그 자리는 월드를 누를 수 없다). */
    protected boolean blocksViewport(UiPart<?> p) {
        return p != topBar;
    }

    // ------------------------------------------------------------------
    // 그리기
    // ------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        tooltip = null;
        layout();
        g.fillGradient(0, 0, width, height, Ui.BG_TOP, Ui.BG_BOTTOM);
        if (!base.ready || base.renderer == null) {
            renderLoading(g);
            return;
        }
        base.tickEffects();
        base.checkCutChanged();
        beforeFrame();
        keyboardPan();
        base.renderer.update(6_000_000L);
        afterMeshing();
        updateHover(mx, my);
        sendCursor();

        renderViewport(g, mx, my, pt);

        for (UiPart<?> p : parts) {
            if (!p.visible() || partHidden(p)) continue;
            p.render(g, mx, my, pt);
        }
        if (showHelp && helpAllowed()) renderHelp(g);
        renderAboveParts(g, mx, my, pt);
        if (modal != null) modal.render(g, mx, my, pt);
        renderTop(g, mx, my, pt);
        if (tooltip != null && !tooltip.isEmpty()) {
            // 창(모달)은 z 300 위에 그리고 그 안의 아이템은 더 앞에 그려지므로, 툴팁을 그보다 앞으로 꺼낸다
            g.pose().pushPose();
            g.pose().translate(0, 0, 600);
            g.renderComponentTooltip(font, tooltip, mx, my);
            g.pose().popPose();
        }
    }

    private void renderLoading(GuiGraphics g) {
        int expected = Math.max(1, base.area.chunks().size());
        int got = base.snapshot.chunkCount();
        Component text = Component.translatable("minedraftlib.editor.loading", got, expected);
        Ui.centered(g, text, width / 2, height / 2 - 4, Ui.TEXT);
        int bw = 160, bx = width / 2 - bw / 2, by = height / 2 + 10;
        g.fill(bx, by, bx + bw, by + 3, 0xFF263040);
        g.fill(bx, by, bx + (int) (bw * Math.min(1, got / (float) expected)), by + 3, Ui.ACCENT);
    }

    /** 매 프레임, 메싱 전 (단면 바뀜 확인 뒤). */
    protected void beforeFrame() {
    }

    /** 매 프레임, 메싱 뒤 · 커서 갱신 전. */
    protected void afterMeshing() {
    }

    /** 월드와 그 위의 표시 (조각 아래). */
    protected abstract void renderViewport(GuiGraphics g, int mx, int my, float pt);

    /** 이번 프레임에 이 조각을 숨길지 (예: 블록 선택 중 오른쪽 창). */
    protected boolean partHidden(UiPart<?> p) {
        return false;
    }

    protected boolean helpAllowed() {
        return true;
    }

    /** 도움말 (H). */
    protected void renderHelp(GuiGraphics g) {
        HelpOverlay.render(this, g);
    }

    /** 도움말 영역 {x, y, w, h}. 그 안을 누르면 닫힌다. 없으면 null. */
    @Nullable
    protected int[] helpBounds() {
        return HelpOverlay.bounds(this);
    }

    /**
     * 도움말 표의 묶음: 묶음마다 첫 줄은 {제목 키}, 나머지는 {단축키, 설명 키}.
     * 단축키 글자는 '+' 로 잇고, 점이 든 조각은 문구 키로 번역한다 ("Ctrl+minedraftlib.key.lmb"). "@move" 는 게임 설정의 이동 키.
     */
    public String[][][] helpGroups() {
        return new String[][][]{helpView(), helpGeneral()};
    }

    /** 보기 묶음. extra 는 테두리(G) 줄 앞에 끼운다. */
    protected static String[][] helpView(String[]... extra) {
        List<String[]> rows = new ArrayList<>(List.of(new String[][]{{"minedraftlib.help.view"},
                {"minedraftlib.key.rmb_drag", "minedraftlib.help.pan"}, {"@move", "minedraftlib.help.pan_keys"},
                {"minedraftlib.key.wheel", "minedraftlib.help.zoom"}, {"Q / E", "minedraftlib.help.rotate_view"},
                {"L", "minedraftlib.layer.toggle"}, {"Shift+L", "minedraftlib.help.cut_axis"},
                {"Ctrl+minedraftlib.key.lmb", "minedraftlib.help.cut_at"}, {"Shift+minedraftlib.key.wheel", "minedraftlib.help.layer_move"},
                {"X", "minedraftlib.layer.bubble"}}));
        rows.addAll(List.of(extra));
        rows.add(new String[]{"G", "minedraftlib.help.outlines"});
        rows.add(new String[]{"Tab", "minedraftlib.help.panel"});
        return rows.toArray(new String[0][]);
    }

    protected static String[][] helpGeneral() {
        return new String[][]{{"minedraftlib.help.general"}, {"Ctrl+Z", "minedraftlib.undo"}, {"Ctrl+Y", "minedraftlib.redo"},
                {"H", "minedraftlib.help.toggle"}, {"Esc", "minedraftlib.help.escape"}};
    }

    /** 도움말 표의 아래 끝 (뷰포트 아래쪽에 떠 있는 것이 있으면 그 위). */
    public int helpBottom() {
        return vpY + vpH;
    }

    /** 상단 바 왼쪽, 영역 이름 앞의 아이콘. */
    public int titleIcon() {
        return Icons.BLUEPRINT;
    }

    /** 조각 위 · 창(모달) 아래에 그릴 것 (떠 있는 작은 창 등). */
    protected void renderAboveParts(GuiGraphics g, int mx, int my, float pt) {
    }

    /** 창(모달) 위 · 툴팁 아래에 그릴 것 (끌고 있는 것 등). */
    protected void renderTop(GuiGraphics g, int mx, int my, float pt) {
    }

    // ------------------------------------------------------------------
    // 커서
    // ------------------------------------------------------------------

    private void updateHover(int mx, int my) {
        this.mouseX = mx;
        this.mouseY = my;
        if (modal != null || !inViewport(mx, my)) {
            hover = null;
            return;
        }
        hover = computeHover(mx, my);
    }

    /** 뷰포트 안 커서 아래 칸. 기본은 보이는 칸 고르기. 끌기 중인 도구는 바꿀 수 있다 (지금 값을 두려면 {@link #hover} 를 돌려준다). */
    @Nullable
    protected AreaEditorSession.Hit computeHover(int mx, int my) {
        return base.pick(mx, my);
    }

    /** 다른 편집자에게 보일 커서 범위 (없으면 커서 칸 하나). */
    @Nullable
    protected Box cursorRegion(AreaEditorSession.Hit h) {
        return null;
    }

    private void sendCursor() {
        long now = System.currentTimeMillis();
        if (now - lastCursorSend < 100) return;
        Box region = hover == null ? null : cursorRegion(hover);
        String key = (hover == null ? "-" : hover.pos().asLong() + ":" + hover.face()) + "|" + region + "|" + base.selected;
        if (key.equals(lastCursorKey)) return;
        lastCursorKey = key;
        lastCursorSend = now;
        AreaEditorSession.Hit h = hover;
        Box r = region;
        UUID id = base.area.id;
        int sel = base.selected;
        LibNet.CHANNEL.toServer(LibNet.C_CURSOR, buf -> {
            buf.writeUUID(id);
            buf.writeByteArray(Channel.encode(b -> {
                b.writeBoolean(h != null);
                b.writeLong(h == null ? 0 : h.pos().asLong());
                Box rr = r == null ? (h == null ? new Box(0, 0, 0, 0, 0, 0)
                        : new Box(h.pos().getX(), h.pos().getY(), h.pos().getZ(), h.pos().getX(), h.pos().getY(), h.pos().getZ())) : r;
                b.writeInt(rr.minX());
                b.writeInt(rr.minY());
                b.writeInt(rr.minZ());
                b.writeInt(rr.maxX());
                b.writeInt(rr.maxY());
                b.writeInt(rr.maxZ());
                b.writeVarInt(sel);
            }));
        });
    }

    // ------------------------------------------------------------------
    // 입력: 마우스
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!base.ready) return true;
        if (modal != null) {
            modal.mouseClicked(mx, my, button);
            return true;
        }
        if (showHelp) {
            int[] r = helpBounds();
            if (r != null && Ui.inside(mx, my, r[0], r[1], r[2], r[3])) {
                showHelp = false;
                return true;
            }
        }
        if (focused != null && !focused.isMouseOver(mx, my)) unfocus(true);
        sidePanel.closeOverlayIfOutside(mx, my);
        if (popupClicked(mx, my, button)) return true;
        for (int i = parts.size() - 1; i >= 0; i--) {
            UiPart<?> p = parts.get(i);
            if (p.contains(mx, my) && p.mouseClicked(mx, my, button)) return true;
        }
        if (!inViewport(mx, my)) return true;
        AreaEditorSession.Hit h = base.pick(mx, my);
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            rightDown = true;
            rightDragged = false;
            rightStartX = lastPanX = mx;
            rightStartY = lastPanY = my;
            return true;
        }
        viewportClicked(mx, my, button, h);
        return true;
    }

    /** 조각보다 먼저 누르기를 받을 것 (떠 있는 작은 창 등). 받았으면 true. */
    protected boolean popupClicked(double mx, double my, int button) {
        return false;
    }

    /** 뷰포트를 누름 (우클릭 제외: 우클릭 끌기는 화면 이동). 기본: Ctrl+좌클릭은 단면 위치. */
    protected void viewportClicked(double mx, double my, int button, @Nullable AreaEditorSession.Hit h) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) cutClick(h);
    }

    /** Ctrl+클릭으로 단면을 그 칸에 둔다. 처리했으면 true. */
    protected boolean cutClick(@Nullable AreaEditorSession.Hit h) {
        if (!hasControlDown() || !base.cutActive) return false;
        if (h != null) base.setCutAt(h.pos());
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (modal != null) {
            modal.mouseReleased(mx, my, button);
            return true;
        }
        if (popupReleased(mx, my, button)) return true;
        for (UiPart<?> p : parts) if (p.mouseReleased(mx, my, button)) return true;
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && rightDown) {
            rightDown = false;
            if (!rightDragged) rightClicked(mx, my);
            return true;
        }
        viewportReleased(mx, my, button);
        return true;
    }

    protected boolean popupReleased(double mx, double my, int button) {
        return false;
    }

    /** 뷰포트에서 끌지 않고 우클릭만 했다. */
    protected void rightClicked(double mx, double my) {
    }

    protected void viewportReleased(double mx, double my, int button) {
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (modal != null) {
            modal.mouseDragged(mx, my, button, dx, dy);
            return true;
        }
        if (popupDragged(mx, my, button, dx, dy)) return true;
        for (UiPart<?> p : parts) if (p.mouseDragged(mx, my, button, dx, dy)) return true;
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && rightDown) {
            if (Math.abs(mx - rightStartX) + Math.abs(my - rightStartY) > 3) rightDragged = true;
            if (rightDragged) {
                base.camera.pan(lastPanX, lastPanY, mx, my);
                lastPanX = mx;
                lastPanY = my;
            }
            return true;
        }
        viewportDragged(mx, my, button);
        return true;
    }

    protected boolean popupDragged(double mx, double my, int button, double dx, double dy) {
        return false;
    }

    protected void viewportDragged(double mx, double my, int button) {
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (modal != null) {
            modal.mouseScrolled(mx, my, scrollY);
            return true;
        }
        if (popupScrolled(mx, my)) return true;
        for (int i = parts.size() - 1; i >= 0; i--) {
            UiPart<?> p = parts.get(i);
            if (p.contains(mx, my) && p.mouseScrolled(mx, my, scrollY)) return true;
        }
        if (!inViewport(mx, my)) return true;
        viewportScrolled(mx, my, scrollY);
        return true;
    }

    /** 떠 있는 작은 창 위의 휠 (받았으면 true). */
    protected boolean popupScrolled(double mx, double my) {
        return false;
    }

    /** 뷰포트 위의 휠. 기본: Shift = 단면 옮기기, 아니면 확대·축소. */
    protected void viewportScrolled(double mx, double my, double scrollY) {
        if (hasShiftDown()) {
            if (base.cutActive) base.moveCut(scrollY > 0 ? 1 : -1);
        } else {
            base.camera.zoom(mx, my, scrollY);
        }
    }

    // ------------------------------------------------------------------
    // 입력: 키보드
    // ------------------------------------------------------------------

    public void focus(EditBox box, @Nullable Runnable onCommit) {
        if (focused != null && focused != box) unfocus(true);
        focused = box;
        focusCommit = onCommit;
        box.setFocused(true);
    }

    public void unfocus(boolean commit) {
        if (focused == null) return;
        EditBox box = focused;
        Runnable c = focusCommit;
        focused = null;
        focusCommit = null;
        box.setFocused(false);
        if (commit && c != null) c.run();
    }

    @Nullable
    public EditBox focusedBox() {
        return focused;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (focused != null) return focused.charTyped(c, mods);
        if (modal != null) return modal.charTyped(c, mods);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (!base.ready) {
            if (key == GLFW.GLFW_KEY_ESCAPE) onClose();
            return true;
        }
        if (focused != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                unfocus(false);
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_TAB) {
                unfocus(true);
                return true;
            }
            focused.keyPressed(key, scan, mods);
            return true;
        }
        if (modal != null) {
            modal.keyPressed(key, scan, mods);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            escape();
            return true;
        }
        if (popupKey(key)) return true;
        boolean ctrl = hasControlDown();
        if (ctrl && key == GLFW.GLFW_KEY_Z) {
            base.send(hasShiftDown() ? new EditRequests.Redo() : new EditRequests.Undo());
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_Y) {
            base.send(new EditRequests.Redo());
            return true;
        }
        if (editorKey(key)) return true;
        switch (key) {
            case GLFW.GLFW_KEY_Q -> base.camera.rotate(-1);
            case GLFW.GLFW_KEY_E -> base.camera.rotate(1);
            case GLFW.GLFW_KEY_TAB -> {
                LibConfig.get().panelCollapsed = !LibConfig.get().panelCollapsed;
                if (panelAutoCollapsed) sidePanel.toggleOverlay();
            }
            case GLFW.GLFW_KEY_G -> toggleOutlines();
            case GLFW.GLFW_KEY_H -> showHelp = !showHelp;
            case GLFW.GLFW_KEY_L -> {
                if (hasShiftDown()) base.cycleCutAxis();
                else base.toggleCut();
            }
            case GLFW.GLFW_KEY_X -> base.bubble = !base.bubble;
            default -> {
            }
        }
        return true;
    }

    /** 떠 있는 작은 창의 키 (Ctrl 단축키보다 먼저). 받았으면 true. */
    protected boolean popupKey(int key) {
        return false;
    }

    /** 모드의 단축키 (도구 등). 받았으면 true. 기본 키(Q E Tab G H L X)보다 먼저 본다. */
    protected boolean editorKey(int key) {
        return false;
    }

    /** Esc: 기본은 도움말 닫기, 아니면 편집창 닫기. */
    protected void escape() {
        if (showHelp) showHelp = false;
        else onClose();
    }

    // ------------------------------------------------------------------
    // 다른 조각에서 쓰는 동작
    // ------------------------------------------------------------------

    /** 카메라를 이 점으로 옮긴다. */
    public void focusCamera(double x, double y, double z) {
        base.camera.tx = x;
        base.camera.ty = y;
        base.camera.tz = z;
    }

    public void openModal(@Nullable Modal<?> m) {
        unfocus(false);
        this.modal = m;
    }

    public double mouseX() {
        return mouseX;
    }

    public double mouseY() {
        return mouseY;
    }

    public boolean isRightDragging() {
        return rightDown && rightDragged;
    }

    /** 블록 테두리 켜고 끄기 (설정에 남는다). */
    public void toggleOutlines() {
        LibConfig.get().blockOutlines = !LibConfig.get().blockOutlines;
        LibConfig.save();
    }

    /**
     * 이동 키(게임 설정의 앞·뒤·왼쪽·오른쪽, 기본 WASD)로 화면 이동. 화면 기준이라 W 는 늘 화면 위쪽.
     * 글자 입력 중이거나 창(모달)이 떠 있을 때, Ctrl 을 누르고 있을 때는 움직이지 않는다. Shift 는 빠르게.
     */
    private void keyboardPan() {
        long now = System.nanoTime();
        float dt = Math.min(0.1F, (now - lastPanNanos) / 1.0E9F);
        lastPanNanos = now;
        if (focused != null || modal != null || hasControlDown() || minecraft == null) return;
        var o = minecraft.options;
        int fx = 0, fy = 0;
        if (held(o.keyUp)) fy++;
        if (held(o.keyDown)) fy--;
        if (held(o.keyLeft)) fx++;
        if (held(o.keyRight)) fx--;
        if (fx == 0 && fy == 0) return;
        // 화면 픽셀 기준 속도: 확대 정도와 무관하게 화면 위에서 같은 빠르기로 움직인다
        float speed = 420.0F * (hasShiftDown() ? 2.5F : 1.0F) * dt;
        double len = Math.sqrt(fx * fx + fy * fy);
        double cx = vpX + vpW / 2.0, cy = vpY + vpH / 2.0;
        base.camera.pan(cx, cy, cx + fx / len * speed, cy + fy / len * speed);
    }

    private boolean held(KeyMapping key) {
        InputConstants.Key k = key.getKey();
        if (k.getType() != InputConstants.Type.KEYSYM) return false;
        return InputConstants.isKeyDown(minecraft.getWindow().getWindow(), k.getValue());
    }
}

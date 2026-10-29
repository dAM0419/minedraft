package dev.designandbuild.client.editor;

import dev.minedraft.lib.client.LibConfig;



import dev.minedraft.lib.client.ClientAreas;
import dev.minedraft.lib.client.editor.AreaEditorScreen;
import dev.minedraft.lib.client.editor.AreaEditorSession;
import dev.minedraft.lib.client.editor.ui.ToolButton;
import dev.minedraft.lib.client.editor.ui.UiPart;
import dev.minedraft.lib.client.ui.Icons;

import dev.designandbuild.client.ClientConfig;
import dev.designandbuild.client.editor.ui.BlockPicker;
import dev.minedraft.lib.client.editor.ui.LayerSlider;
import dev.minedraft.lib.client.editor.ui.Modal;
import dev.designandbuild.client.editor.ui.OptionBar;
import dev.designandbuild.client.editor.ui.OrientationPopup;
import dev.designandbuild.client.editor.ui.PaletteBar;
import dev.minedraft.lib.client.editor.ui.SidePanel;
import dev.minedraft.lib.client.editor.ui.ToolBar;
import dev.minedraft.lib.client.editor.ui.TopBar;
import dev.minedraft.lib.client.editor.ui.ToolButton;
import dev.minedraft.lib.client.editor.ui.UiPart;
import dev.minedraft.lib.client.ui.Icons;
import dev.designandbuild.client.library.ClientLibrary;
import dev.designandbuild.client.library.LibraryEntry;
import dev.designandbuild.client.ui.Thumbnails;
import dev.minedraft.lib.client.ui.Ui;
import dev.minedraft.lib.util.Box;
import dev.designandbuild.core.Requests;
import dev.designandbuild.core.StructureInstance;
import dev.designandbuild.core.TemplateData;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 편집창. 월드가 화면의 대부분을 차지하고, UI 는 네 가장자리에 얇게 붙는다.
 * <pre>
 * 상단 바: 도면·세션 정보     좌측 툴바: 무엇을 할지
 * 하단 옵션 바: 어떻게 할지    우측 패널: 라이브러리·순서·범위 (접을 수 있음)
 * 뷰포트: 나머지 전부 (커서 칩·층 슬라이더·팔레트만 얹힘)
 * </pre>
 */
public final class EditorScreen extends AreaEditorScreen {
    public static final int TOP = 16, LEFT = 22, PALETTE_H = 20;

    private final EditorSession s;

    public final TopBar topBar;
    public final ToolBar toolBar;
    public final OptionBar optionBar;
    public final PaletteBar paletteBar;
    public final SidePanel sidePanel;
    public final dev.designandbuild.client.editor.ui.OrderTab orderTab;
    public final LayerSlider layerSlider;
    public final dev.minedraft.lib.client.editor.ui.ViewCube viewCube;
    /** 이번 클릭으로 설치 미리보기 창이 닫혔다 (옵션 바 버튼이 다시 열지 않게). */
    public boolean popupJustClosed;

    @Nullable
    public OrientationPopup orientationPopup;
    public boolean tagView;

    // ---- 상호작용 ----
    private boolean stroking;
    private int strokeGroup;
    private final LongOpenHashSet strokeDone = new LongOpenHashSet();
    /** 연속 칠하기의 기준: 첫 클릭 칸과 면, 고정 평면 축 (내려다보면 Y, 정면이면 X·Z). */
    private BlockPos strokePos = BlockPos.ZERO;
    private Direction strokeFace = Direction.UP;
    private Direction.Axis strokeAxis = Direction.Axis.Y;
    /** 드래그를 시작한 마우스 위치와 첫 칸. 여기서 거의 움직이지 않았으면 첫 칸만 쓴다 (한 번 누름 = 한 번 적용). */
    private double strokeStartX, strokeStartY;
    private boolean strokeMoved;
    @Nullable
    private EditorSession.Hit strokeFirst;
    private long armedKey = Long.MIN_VALUE;
    public boolean moving;
    public int moveDX, moveDY, moveDZ;
    @Nullable
    private Vec3 moveStart;
    private double movePlane;
    private Direction.Axis moveAxis = Direction.Axis.Y;
    @Nullable
    public LibraryEntry libraryDrag;
    private int[] cycleIds = new int[0];
    private int cycleIndex;

    // ---- 미리보기 캐시 ----
    public final Preview preview = new Preview();

    public EditorScreen(EditorSession session) {
        super(Component.translatable("designandbuild.editor.title"), session);
        this.s = session;
        this.topBar = new TopBar(this);
        this.toolBar = new ToolBar(this);
        this.optionBar = new OptionBar(this);
        this.paletteBar = new PaletteBar(this);
        this.sidePanel = new SidePanel(this);
        this.orderTab = new dev.designandbuild.client.editor.ui.OrderTab(this);
        panelTabs.add(new dev.designandbuild.client.editor.ui.LibraryTab(this));
        panelTabs.add(orderTab);
        panelTabs.add(new dev.minedraft.lib.client.editor.ui.RangeTab(this));
        this.layerSlider = new LayerSlider(this);
        this.viewCube = new dev.minedraft.lib.client.editor.ui.ViewCube(this);
        // 그리는 순서 = 아래에서 위로 (누르기는 반대 순서로 위에 있는 것부터).
        // 뷰포트 위 도구(팔레트·단면 슬라이더·시점 큐브) < 오른쪽 창 < 가장자리 바 (옵션·도구·상단)
        tools.add(new ToolButton(Icons.SELECT, "V", "designandbuild.tool.select", "designandbuild.tool.select.tip",
                () -> s.tool == EditorSession.Tool.SELECT, () -> setTool(EditorSession.Tool.SELECT)));
        tools.add(new ToolButton(Icons.DIRECT, "B", "designandbuild.tool.direct", "designandbuild.tool.direct.tip",
                () -> s.tool == EditorSession.Tool.DIRECT, () -> setTool(EditorSession.Tool.DIRECT)));
        tools.add(new ToolButton(Icons.PLACE, "P", "designandbuild.tool.place", "designandbuild.tool.place.tip",
                () -> s.tool == EditorSession.Tool.PLACE, () -> setTool(EditorSession.Tool.PLACE)));
        toggles.add(new ToolButton(Icons.TAGS, null, "designandbuild.editor.tag_view", null, () -> tagView, () -> tagView = !tagView));
        parts.add(paletteBar);
        parts.add(layerSlider);
        parts.add(viewCube);
        parts.add(sidePanel);
        parts.add(toolBar);
        parts.add(optionBar);
        parts.add(topBar);
    }

    @Override
    public EditorSession session() {
        return s;
    }

    @Override
    protected void init() {
        ClientLibrary.refresh();
        super.init();
    }

    @Override
    protected void onRemoved() {
        preview.close();
        ViewportRenderer.release();
        ClientConfig.save();
    }

    // ------------------------------------------------------------------
    // 배치
    // ------------------------------------------------------------------

    @Override
    public void layout() {
        int W = width, H = height;
        boolean userCollapsed = LibConfig.get().panelCollapsed;
        int panelW = SidePanel.WIDTH;
        panelAutoCollapsed = (W - LEFT - panelW) < W * 0.6;
        boolean collapsed = userCollapsed || panelAutoCollapsed;
        int right = collapsed ? SidePanel.STRIP : panelW;
        bottomH = optionBar.requiredHeight(W - LEFT);
        topBar.setBounds(0, 0, W, TOP);
        toolBar.setBounds(0, TOP, LEFT, H - TOP);
        optionBar.setBounds(LEFT, H - bottomH, W - LEFT, bottomH);
        sidePanel.setBounds(W - right, TOP, right, H - TOP - bottomH);
        sidePanel.setCollapsed(collapsed);
        vpX = LEFT;
        vpY = TOP;
        vpW = W - LEFT - SidePanel.STRIP;
        if (!collapsed) vpW = W - LEFT - panelW;
        vpH = H - TOP - bottomH;
        int palW = PaletteBar.preferredWidth();
        int palX = vpX + Math.max(0, (vpW - palW) / 2);
        int palY = H - bottomH - PALETTE_H - 2;
        paletteBar.setBounds(palX, palY, Math.min(palW, vpW), PALETTE_H);
        layerSlider.setBounds(vpX + vpW - 30, vpY + 26, 26, palY - vpY - 34);
        // 시점 큐브: 뷰포트 오른쪽 위, 층 슬라이더 왼쪽
        int cubeRight = vpX + vpW - 34;
        // JEI 로 블록을 고르는 중이면 오른쪽에 JEI 목록과 안내 패널이 열리므로 그 왼쪽으로 비켜 선다
        if (jeiPicking()) cubeRight = Math.min(cubeRight, BlockPicker.jeiPanelLeft(W) - 6);
        viewCube.setBounds(cubeRight - 64, vpY + 4, 64, 84);
        s.camera.setViewport(W, H);
    }

    private boolean jeiPicking() {
        return modal instanceof BlockPicker bp && bp.hidesEditorUi();
    }

    public int paletteTop() {
        return ClientConfig.get().paletteCollapsed ? height - bottomH - 10 : paletteBar.y;
    }

    @Override
    protected boolean blocksViewport(UiPart<?> p) {
        return p != topBar;
    }

    // ------------------------------------------------------------------
    // 그리기
    // ------------------------------------------------------------------

    @Override
    protected void beforeFrame() {
        s.checkIsolationChanged();
    }

    @Override
    protected void afterMeshing() {
        Thumbnails.frame();
    }

    @Override
    protected void renderViewport(GuiGraphics g, int mx, int my, float pt) {
        ViewportRenderer.render(this, g, mx, my, pt);
        ViewportRenderer.render2D(this, g, mx, my);
    }

    @Override
    protected boolean partHidden(UiPart<?> p) {
        return jeiPicking() && (p == sidePanel || p == layerSlider);
    }

    @Override
    protected boolean helpAllowed() {
        return !jeiPicking();
    }

    @Override
    public String[][][] helpGroups() {
        return new String[][][]{
                {{"designandbuild.help.tools"}, {"V", "designandbuild.tool.select"}, {"B", "designandbuild.tool.direct"},
                        {"P", "designandbuild.tool.place"}, {"1 2 3 4", "designandbuild.help.ops"}},
                helpView(new String[]{"T", "designandbuild.help.tag_view"}),
                {{"designandbuild.help.direct"}, {"minedraftlib.key.lmb", "designandbuild.help.apply"},
                        {"minedraftlib.key.lmb_drag", "designandbuild.help.paint"},
                        {"Alt+minedraftlib.key.wheel", "designandbuild.help.size"}, {"Ctrl+minedraftlib.key.wheel", "designandbuild.help.palette"},
                        {"minedraftlib.key.mmb", "designandbuild.help.eyedropper"},
                        {"Shift+minedraftlib.key.mmb", "designandbuild.help.eyedropper_palette"}},
                {{"designandbuild.help.structures"}, {"Alt+minedraftlib.key.lmb", "designandbuild.help.alt_select"},
                        {"R", "designandbuild.help.rotate"}, {"F", "designandbuild.help.mirror"},
                        {"PgUp / PgDn", "designandbuild.help.updown"}, {"Del", "designandbuild.select.delete"},
                        {"minedraftlib.key.rmb", "designandbuild.help.cancel_place"}},
                helpGeneral()};
    }

    @Override
    public int helpBottom() {
        return paletteTop();
    }

    @Override
    protected void renderAboveParts(GuiGraphics g, int mx, int my, float pt) {
        if (orientationPopup != null) orientationPopup.render(g, mx, my, pt);
    }

    @Override
    protected void renderTop(GuiGraphics g, int mx, int my, float pt) {
        if (libraryDrag != null) {
            TemplateData t = ClientLibrary.template(libraryDrag);
            if (t == null || !Thumbnails.draw(g, t, mx - 16, my - 16, 32, 32, 45, 400)) {
                Ui.icon(g, dev.minedraft.lib.client.ui.Icons.PLACE, mx - 8, my - 8, Ui.TEXT);
            }
        }
    }

    // ------------------------------------------------------------------
    // 커서와 미리보기
    // ------------------------------------------------------------------

    @Override
    @Nullable
    protected AreaEditorSession.Hit computeHover(int mx, int my) {
        if (moving) return hover;
        if (stroking) {
            if (!strokeMoved && Math.abs(mx - strokeStartX) <= 2 && Math.abs(my - strokeStartY) <= 2) return strokeFirst;
            strokeMoved = true;
            EditorSession.Hit h = planeHit(mx, my);
            if (h != null) strokeAt(h);
            return h;
        }
        return s.pick(mx, my);
    }

    /**
     * 드래그 평면의 축: 누른 면이 카메라 쪽을 충분히 향하면 그 면의 평면(벽면을 누르면 벽면을 따라 칠한다).
     * 면이 카메라와 거의 나란하면(정면 시점에서 윗면 등) 평면 위 위치가 불안정하므로 화면을 마주 보는 평면.
     * 누른 면의 평면을 쓰면 첫 프레임의 커서 칸이 누른 칸과 같아, 한 번 누름이 두 번 적용되지 않는다.
     */
    private Direction.Axis strokeAxisFor(Direction face) {
        org.joml.Vector3f c = s.camera.toCamera();
        float facing = Math.abs(face.getStepX() * c.x + face.getStepY() * c.y + face.getStepZ() * c.z);
        return facing >= 0.3F ? face.getAxis() : s.camera.planeAxis();
    }

    /** 드래그 중: 첫 클릭 높이의 평면에 고정한 커서. */
    @Nullable
    private EditorSession.Hit planeHit(double mx, double my) {
        Direction.Axis ax = strokeAxis;
        int c = strokePos.get(ax);
        // 첫 클릭 면이 이 축을 향하면 그 면 위, 아니면 칸 가운데를 지나는 평면
        double plane = strokeFace.getAxis() == ax
                ? c + (strokeFace.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0) : c + 0.5;
        Vec3 p = s.camera.planePoint(mx, my, ax, plane);
        if (p == null) return null;
        BlockPos pos = switch (ax) {
            case X -> new BlockPos(c, Mth.floor(p.y), Mth.floor(p.z));
            case Y -> new BlockPos(Mth.floor(p.x), c, Mth.floor(p.z));
            case Z -> new BlockPos(Mth.floor(p.x), Mth.floor(p.y), c);
        };
        return new EditorSession.Hit(pos, strokeFace, p);
    }

    private void strokeAt(EditorSession.Hit h) {
        long key = h.pos().asLong();
        if (!strokeDone.add(key)) return;
        ToolLogic.apply(s, h, strokeGroup);
    }

    @Override
    @Nullable
    protected Box cursorRegion(AreaEditorSession.Hit h) {
        return s.tool == EditorSession.Tool.DIRECT ? ToolLogic.region(s, h) : null;
    }

    /** 직접조작 미리보기 캐시. */
    public final class Preview {
        /** 미리보기를 만든 입력 (같으면 다시 계산하지 않는다). 해시 충돌이 없게 값 목록 자체로 비교한다. */
        @Nullable
        private List<Object> key;
        @Nullable
        private List<Object> placementKey;
        @Nullable
        public Box region;
        public long[] targets = new long[0];
        /** 빠진 칸 수: 다른 구조물 소속 / 채우기인데 이미 찬 칸. */
        public int skippedForeign, skippedOccupied;
        @Nullable
        public dev.minedraft.lib.client.render.BlockMesher.Mesh ghost;
        public int ghostOx, ghostOy, ghostOz;
        @Nullable
        public ToolLogic.Placement placement;
        @Nullable
        public dev.minedraft.lib.client.render.BlockMesher.Mesh placementGhost;
        @Nullable
        private ToolLogic.Transformed placementShape;
        private final dev.minedraft.lib.client.render.BlockMesher mesher = new dev.minedraft.lib.client.render.BlockMesher();

        public void update() {
            EditorSession.Hit h = hover;
            if (h == null || s.tool != EditorSession.Tool.DIRECT || stroking) {
                if (s.tool != EditorSession.Tool.DIRECT || h == null) {
                    region = null;
                    targets = new long[0];
                    closeGhost();
                    key = null;
                }
                return;
            }
            List<Object> k = java.util.Arrays.asList(h.pos().asLong(), h.face(), s.op, s.shape, s.cylinderAxis, s.anchor,
                    s.sizeX, s.sizeY, s.sizeZ, s.tag, s.selected, s.lookH, s.lookV, s.face, s.upperHalf, materialKey(),
                    s.model.revision(), s.cutStamp);
            if (k.equals(key)) return;
            key = k;
            region = ToolLogic.region(s, h);
            targets = ToolLogic.targets(s, region);
            skippedForeign = ToolLogic.skippedForeign;
            skippedOccupied = ToolLogic.skippedOccupied;
            closeGhost();
            if (targets.length > 0 && targets.length <= ToolLogic.GHOST_LIMIT && s.op != EditorSession.Op.ERASE) {
                ToolLogic.Materials mats = ToolLogic.materials(s);
                if (mats != null) {
                    var cells = ToolLogic.compute(s, targets, mats, new java.util.Random(12345L), false);
                    ghostOx = region.minX();
                    ghostOy = region.minY();
                    ghostOz = region.minZ();
                    List<BlockPos> list = new ArrayList<>();
                    for (long p : cells.keySet()) list.add(BlockPos.of(p));
                    ghost = mesher.build(new dev.minedraft.lib.client.render.MapWorld(cells, s.model.minY, s.model.maxY - s.model.minY),
                            list.iterator(), ghostOx, ghostOy, ghostOz, 0.6F, true);
                }
            }
        }

        public void updatePlacement() {
            EditorSession.Hit h = hover;
            if (s.tool != EditorSession.Tool.PLACE || s.placing == null || h == null) {
                placement = null;
                placementKey = null;
                return;
            }
            TemplateData t = ClientLibrary.template(s.placing);
            if (t == null) {
                placement = null;
                placementKey = null;
                return;
            }
            // 커서·회전·기준·도면이 그대로면 다시 계산하지 않는다 (큰 조각은 칸 수만큼 조회가 든다)
            List<Object> k = java.util.Arrays.asList(t, h.pos().asLong(), h.face(), s.placeRotation, s.placeMirror,
                    s.placeInto, s.placeOp, s.model.revision(), materialKey());
            if (placement != null && k.equals(placementKey)) return;
            placementKey = k;
            placement = ToolLogic.placement(s, t, h);
            // 설치는 조각 모양이 같으면 유령을 다시 쓰고, 채우기·대체는 놓일 칸이 자리마다 달라 매번 다시 굽는다. 지우기는 붉은 상자로 표시
            boolean filtered = placement.kept() != null;
            if (filtered || placementShape != placement.shape()) {
                placementShape = filtered ? null : placement.shape();
                if (placementGhost != null) placementGhost.close();
                placementGhost = null;
                ToolLogic.Transformed tr = placement.shape();
                int[] kept = placement.kept();
                boolean erase = s.placeOp == EditorSession.Op.ERASE;
                if (!erase && tr.states().length <= 60_000) {
                    it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockState> map = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
                    List<BlockPos> list = new ArrayList<>();
                    int count = kept != null ? kept.length : tr.states().length;
                    for (int j = 0; j < count; j++) {
                        int i = kept != null ? kept[j] : j;
                        if (tr.states()[i].isAir()) continue;
                        BlockPos p = new BlockPos(tr.xs()[i], tr.ys()[i], tr.zs()[i]);
                        map.put(p.asLong(), tr.states()[i]);
                        list.add(p);
                    }
                    placementGhost = mesher.build(new dev.minedraft.lib.client.render.MapWorld(map, -64, 512), list.iterator(),
                            0, 0, 0, 0.55F, true);
                }
            }
        }

        private void closeGhost() {
            if (ghost != null) ghost.close();
            ghost = null;
        }

        void close() {
            closeGhost();
            if (placementGhost != null) placementGhost.close();
            placementGhost = null;
            placementShape = null;
            mesher.close();
        }
    }

    private int materialKey() {
        int h = 1;
        for (int i = 0; i < 3; i++) {
            h = h * 31 + ItemStack.hashItemAndComponents(s.materials[i]);
            h = h * 31 + s.weights[i];
            h = h * 31 + ItemStack.hashItemAndComponents(s.replaceTargets[i]);
        }
        return h * 31 + s.percent;
    }

    // ------------------------------------------------------------------
    // 입력: 마우스
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        popupJustClosed = false;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    protected boolean popupClicked(double mx, double my, int button) {
        sidePanel.closeOverlayIfOutside(mx, my);
        if (orientationPopup != null) {
            if (orientationPopup.contains(mx, my)) {
                orientationPopup.mouseClicked(mx, my, button);
                return true;
            }
            orientationPopup = null;
            if (!optionBar.contains(mx, my)) return true;
            // 옵션 바의 설치 미리보기 버튼을 다시 누른 것이면 닫기만 한다 (버튼이 곧바로 다시 열지 않게)
            popupJustClosed = true;
        }
        return false;
    }

    @Override
    protected void viewportClicked(double mx, double my, int button, @Nullable AreaEditorSession.Hit h) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            if (h != null) eyedropper(h);
            return;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        if (hasAltDown()) {
            altSelect(h);
            return;
        }
        if (cutClick(h)) return;
        switch (s.tool) {
            case DIRECT -> {
                if (h == null) {
                    s.toast(Component.translatable("designandbuild.msg.no_target"));
                    return;
                }
                if (!hasMaterialOrErase()) {
                    s.toast(Component.translatable("designandbuild.msg.pick_material"));
                    return;
                }
                Box r = ToolLogic.region(s, h);
                long[] targets = ToolLogic.targets(s, r);
                if (targets.length == 0) return;
                long key = Objects.hash(r, s.op, s.tag, s.shape);
                if (targets.length > ClientConfig.get().largeOpThreshold && armedKey != key) {
                    armedKey = key;
                    s.toast(Component.translatable("designandbuild.msg.large_op_confirm", targets.length));
                    return;
                }
                armedKey = Long.MIN_VALUE;
                stroking = true;
                strokeGroup = ClientAreas.nextGroup();
                strokeDone.clear();
                strokePos = h.pos();
                strokeFace = h.face();
                strokeAxis = strokeAxisFor(h.face());
                strokeStartX = mx;
                strokeStartY = my;
                strokeMoved = false;
                strokeFirst = h;
                strokeAt(h);
            }
            case PLACE -> {
                if (s.placing == null) {
                    s.toast(Component.translatable("designandbuild.msg.pick_structure"));
                    return;
                }
                preview.updatePlacement();
                if (preview.placement != null) {
                    ToolLogic.commitPlacement(s, preview.placement, s.placing.id(), s.placing.name());
                }
            }
            case SELECT -> {
                if (h == null) {
                    s.selected = 0;
                    return;
                }
                int[] ids = s.structuresAround(h.pos());
                if (ids.length == 0) {
                    s.selected = 0;
                    cycleIds = ids;
                    return;
                }
                int idx = -1;
                for (int i = 0; i < ids.length; i++) if (ids[i] == s.selected) idx = i;
                cycleIds = ids;
                if (idx >= 0) {
                    // 선택된 볼륨을 눌렀다: 끌면 이동, 그냥 떼면 겹친 다음 구조물로
                    cycleIndex = idx;
                    beginMove(h);
                    return;
                }
                cycleIndex = 0;
                s.selected = ids[0];
            }
        }
    }

    /** Alt+클릭: 어느 도구에서든 커서 아래 구조물을 선택한다. 칸 주인을 먼저, 같은 자리를 다시 누르면 겹친 구조물을 순환. */
    private void altSelect(@Nullable AreaEditorSession.Hit h) {
        if (h == null) {
            s.toast(Component.translatable("designandbuild.msg.no_target"));
            return;
        }
        int[] ids = s.structuresAround(h.pos());
        dev.designandbuild.core.Resolved r = s.model.resolved(h.pos().asLong());
        int owner = r == null ? 0 : r.owner();
        if (ids.length == 0 && owner == 0) {
            s.toast(Component.translatable("designandbuild.msg.no_structure_here"));
            return;
        }
        int idx = -1;
        for (int i = 0; i < ids.length; i++) if (ids[i] == s.selected) idx = i;
        if (idx >= 0 && ids.length > 1) {
            s.selected = ids[(idx + 1) % ids.length];
        } else if (idx < 0) {
            s.selected = owner != 0 ? owner : ids[0];
        }
    }

    private boolean hasMaterialOrErase() {
        if (s.op == EditorSession.Op.ERASE) return true;
        for (ItemStack st : s.materials) if (!st.isEmpty()) return true;
        return false;
    }

    private void beginMove(EditorSession.Hit h) {
        moving = true;
        moveDX = moveDY = moveDZ = 0;
        moveAxis = s.camera.planeAxis();
        int c = h.pos().get(moveAxis);
        movePlane = h.face().getAxis() == moveAxis
                ? c + (h.face().getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0) : c + 0.5;
        moveStart = s.camera.planePoint(mouseX(), mouseY(), moveAxis, movePlane);
    }

    /** 1~4: 배치 도구면 배치 방식, 아니면 직접조작 방식. */
    private void setOp(EditorSession.Op op) {
        if (s.tool == EditorSession.Tool.PLACE) s.placeOp = op;
        else s.op = op;
    }

    private void eyedropper(EditorSession.Hit h) {
        BlockState st = s.blockAt(h.pos().getX(), h.pos().getY(), h.pos().getZ(), true);
        ItemStack stack = new ItemStack(st.getBlock().asItem());
        if (stack.isEmpty()) {
            if (!st.getFluidState().isEmpty()) stack = new ItemStack(st.getFluidState().getType().getBucket());
            if (stack.isEmpty()) return;
        }
        if (hasShiftDown()) {
            paletteBar.addToPalette(stack);
            return;
        }
        s.materials[0] = stack;
        s.materials[1] = ItemStack.EMPTY;
        s.materials[2] = ItemStack.EMPTY;
        copyOrientationFrom(st);
    }

    /** 스포이드: 집은 블록의 방향 값을 삼면도 입력으로 되돌린다. */
    private void copyOrientationFrom(BlockState st) {
        if (st.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            s.lookH = st.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
            s.lookV = 0;
        }
        if (st.hasProperty(BlockStateProperties.AXIS)) {
            s.face = Direction.fromAxisAndDirection(st.getValue(BlockStateProperties.AXIS), Direction.AxisDirection.POSITIVE);
        }
        if (st.hasProperty(BlockStateProperties.HALF)) {
            s.face = st.getValue(BlockStateProperties.HALF) == Half.TOP ? Direction.DOWN : Direction.UP;
        }
        if (st.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            s.face = st.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.TOP ? Direction.DOWN : Direction.UP;
        }
        if (st.hasProperty(BlockStateProperties.HANGING)) {
            // 매단 랜턴을 집으면 밑면에 붙이기로
            s.face = st.getValue(BlockStateProperties.HANGING) ? Direction.DOWN : Direction.UP;
            s.lookV = 0;
        }
        if (st.hasProperty(BlockStateProperties.FACING)) {
            Direction d = st.getValue(BlockStateProperties.FACING);
            if (d.getAxis().isVertical()) {
                s.lookV = d == Direction.UP ? -1 : 1;
            } else {
                s.lookH = d.getOpposite();
                s.lookV = 0;
            }
        }
    }

    @Override
    protected boolean popupReleased(double mx, double my, int button) {
        return orientationPopup != null && orientationPopup.mouseReleased(mx, my, button);
    }

    @Override
    protected void rightClicked(double mx, double my) {
        if (s.tool == EditorSession.Tool.PLACE && s.placing != null) s.placing = null;
    }

    @Override
    protected void viewportReleased(double mx, double my, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        if (stroking) {
            stroking = false;
            strokeDone.clear();
        }
        if (moving) {
            moving = false;
            if (moveDX != 0 || moveDY != 0 || moveDZ != 0) {
                s.send(new Requests.StructMove(s.selected, moveDX, moveDY, moveDZ, 0, false));
            } else if (cycleIds.length > 1) {
                cycleIndex = (cycleIndex + 1) % cycleIds.length;
                s.selected = cycleIds[cycleIndex];
            }
            moveDX = moveDY = moveDZ = 0;
        }
        if (libraryDrag != null) {
            LibraryEntry e = libraryDrag;
            libraryDrag = null;
            if (inViewport(mx, my)) {
                selectForPlacement(e);
                EditorSession.Hit h = s.pick(mx, my);
                TemplateData t = ClientLibrary.template(e);
                if (h != null && t != null) {
                    ToolLogic.Placement p = ToolLogic.placement(s, t, h);
                    ToolLogic.commitPlacement(s, p, e.id(), e.name());
                }
            }
        }
    }

    @Override
    protected boolean popupDragged(double mx, double my, int button, double dx, double dy) {
        return orientationPopup != null && orientationPopup.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    protected void viewportDragged(double mx, double my, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && moving && moveStart != null) {
            Vec3 now = s.camera.planePoint(mx, my, moveAxis, movePlane);
            if (now != null) {
                // 평면 안의 두 축으로 옮긴다 (정면에서 보면 위아래로도). 나머지 축은 PgUp/PgDn 과 같이 따로
                if (moveAxis != Direction.Axis.X) moveDX = (int) Math.round(now.x - moveStart.x);
                if (moveAxis != Direction.Axis.Y) moveDY = (int) Math.round(now.y - moveStart.y);
                if (moveAxis != Direction.Axis.Z) moveDZ = (int) Math.round(now.z - moveStart.z);
            }
        }
    }

    @Override
    protected boolean popupScrolled(double mx, double my) {
        return orientationPopup != null && orientationPopup.contains(mx, my);
    }

    @Override
    protected void viewportScrolled(double mx, double my, double scrollY) {
        if (hasAltDown()) {
            int d = scrollY > 0 ? 1 : -1;
            s.sizeX = Mth.clamp(s.sizeX + d, 1, 128);
            s.sizeY = Mth.clamp(s.sizeY + d, 1, 128);
            s.sizeZ = Mth.clamp(s.sizeZ + d, 1, 128);
        } else if (!hasShiftDown() && hasControlDown()) {
            paletteBar.cycle(scrollY > 0 ? -1 : 1);
        } else {
            super.viewportScrolled(mx, my, scrollY);
        }
    }

    // ------------------------------------------------------------------
    // 입력: 키보드
    // ------------------------------------------------------------------

    @Override
    protected boolean popupKey(int key) {
        if (orientationPopup != null && key == GLFW.GLFW_KEY_R) {
            orientationPopup.rotate();
            return true;
        }
        if (orientationPopup != null && key == GLFW.GLFW_KEY_F) {
            orientationPopup.flipHalf();
            return true;
        }
        return false;
    }

    @Override
    protected boolean editorKey(int key) {
        switch (key) {
            case GLFW.GLFW_KEY_V -> setTool(EditorSession.Tool.SELECT);
            case GLFW.GLFW_KEY_B -> setTool(EditorSession.Tool.DIRECT);
            case GLFW.GLFW_KEY_P -> setTool(EditorSession.Tool.PLACE);
            case GLFW.GLFW_KEY_1 -> setOp(EditorSession.Op.INSTALL);
            case GLFW.GLFW_KEY_2 -> setOp(EditorSession.Op.FILL);
            case GLFW.GLFW_KEY_3 -> setOp(EditorSession.Op.REPLACE);
            case GLFW.GLFW_KEY_4 -> setOp(EditorSession.Op.ERASE);
            case GLFW.GLFW_KEY_R -> rotateKey();
            case GLFW.GLFW_KEY_F -> mirrorKey();
            case GLFW.GLFW_KEY_T -> tagView = !tagView;
            case GLFW.GLFW_KEY_PAGE_UP -> moveSelected(0, 1, 0);
            case GLFW.GLFW_KEY_PAGE_DOWN -> moveSelected(0, -1, 0);
            case GLFW.GLFW_KEY_DELETE -> {
                if (s.selected != 0) s.send(new Requests.StructDelete(s.selected));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void togglePanelOverlay() {
        sidePanel.toggleOverlay();
    }

    @Override
    protected void escape() {
        if (orientationPopup != null) {
            orientationPopup = null;
        } else if (showHelp) {
            showHelp = false;
        } else if (s.tool == EditorSession.Tool.PLACE && s.placing != null) {
            s.placing = null;
        } else if (s.selected != 0) {
            s.selected = 0;
        } else {
            onClose();
        }
    }

    public void setTool(EditorSession.Tool tool) {
        s.tool = tool;
        stroking = false;
        moving = false;
    }

    private void rotateKey() {
        if (s.tool == EditorSession.Tool.PLACE && s.placing != null) {
            s.placeRotation = (s.placeRotation + 1) & 3;
        } else if (s.selected != 0) {
            s.send(new Requests.StructMove(s.selected, 0, 0, 0, 1, false));
        }
    }

    private void mirrorKey() {
        if (s.tool == EditorSession.Tool.PLACE && s.placing != null) {
            s.placeMirror = !s.placeMirror;
        } else if (s.selected != 0) {
            s.send(new Requests.StructMove(s.selected, 0, 0, 0, 0, true));
        }
    }

    private void moveSelected(int dx, int dy, int dz) {
        if (s.selected != 0) s.send(new Requests.StructMove(s.selected, dx, dy, dz, 0, false));
    }

    // ------------------------------------------------------------------
    // 다른 조각에서 쓰는 동작
    // ------------------------------------------------------------------

    public void selectForPlacement(LibraryEntry e) {
        s.placing = e;
        s.placeRotation = 0;
        s.placeMirror = false;
        setTool(EditorSession.Tool.PLACE);
        ClientLibrary.template(e);
    }

    public void focusStructure(int id) {
        StructureInstance st = s.model.structure(id);
        if (st == null || st.bounds() == null) return;
        Box b = st.bounds();
        focusCamera((b.minX() + b.maxX() + 1) / 2.0, b.minY(), (b.minZ() + b.maxZ() + 1) / 2.0);
    }

    public void openPicker(BlockPicker.Target target) {
        openModal(new BlockPicker(this, target));
    }

    /** 열려 있는 블록 선택기 (없으면 null). */
    @Nullable
    public BlockPicker picker() {
        return modal instanceof BlockPicker bp ? bp : null;
    }

    /** JEI 에 알려 줄 패널 영역. JEI 선택 모드가 아니면 null. */
    @Nullable
    public int[] jeiArea() {
        BlockPicker bp = picker();
        return bp == null ? null : bp.jeiArea();
    }

    /** JEI 가 피해야 할 자리 (상단 바·옵션 바·팔레트). JEI 선택 모드일 때만. */
    public List<int[]> jeiExclusions() {
        if (jeiArea() == null) return List.of();
        List<int[]> out = new ArrayList<>();
        out.add(new int[]{0, 0, width, TOP});
        out.add(new int[]{optionBar.x, optionBar.y, optionBar.w, optionBar.h});
        if (paletteBar.visible()) out.add(new int[]{paletteBar.x, paletteBar.y, paletteBar.w, paletteBar.h});
        return out;
    }

    public boolean isStroking() {
        return stroking;
    }

    public long armedKey() {
        return armedKey;
    }
}

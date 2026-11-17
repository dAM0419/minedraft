package dev.civilizeandgovern.client;

import dev.civilizeandgovern.city.CityPermission;
import dev.civilizeandgovern.net.CityView;
import dev.civilizeandgovern.net.Msg;
import dev.civilizeandgovern.net.Net;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 도시 패널 (명세 01-architecture.md 11절). 1단계: 개요, 구역 편집, 발행, 강제 로딩.
 * 탭 구조(주민, 시장, 요청 …)와 의회 편집은 뒤 판에서 더한다.
 */
public final class CityPanelScreen extends Screen {
    private CityView view;
    private Button forceLoad, dissolve;

    public CityPanelScreen(CityView view) {
        super(Component.literal(view.name()));
        this.view = view;
    }

    public int cityId() {
        return view.id();
    }

    public void update(CityView next) {
        this.view = next;
        refresh();
    }

    @Override
    protected void init() {
        int x = width / 2 - 130, y = height / 2 + 10;
        addRenderableWidget(Button.builder(Component.translatable("civilizeandgovern.panel.edit_zones"),
                b -> Net.toServer(Msg.C_OPEN_EDITOR, buf -> buf.writeVarInt(view.id()))).bounds(x, y, 260, 20).build());
        forceLoad = addRenderableWidget(Button.builder(Component.empty(), b -> Net.toServer(Msg.C_FORCE_LOAD, buf -> {
            buf.writeVarInt(view.id());
            buf.writeBoolean(!view.forceLoad());
        })).bounds(x, y + 26, 260, 20).build());
        dissolve = addRenderableWidget(Button.builder(Component.empty(), b -> Net.toServer(Msg.C_ALLOW_DISSOLVE, buf -> {
            buf.writeVarInt(view.id());
            buf.writeBoolean(!view.allowDissolve());
        })).bounds(x, y + 52, 260, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x + 80, y + 82, 100, 20).build());
        refresh();
    }

    private void refresh() {
        if (forceLoad == null) return;
        forceLoad.active = view.can(CityPermission.FORCE_LOAD);
        forceLoad.setMessage(Component.translatable(view.forceLoad()
                ? "civilizeandgovern.panel.force_load_on" : "civilizeandgovern.panel.force_load_off"));
        // 해체 허용은 시장만 (COUNCIL 권한은 시장만 가진다)
        dissolve.visible = view.can(CityPermission.COUNCIL);
        dissolve.setMessage(Component.translatable(view.allowDissolve()
                ? "civilizeandgovern.panel.dissolve_on" : "civilizeandgovern.panel.dissolve_off"));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = width / 2 - 130, y = height / 2 - 112;
        g.drawCenteredString(font, title, width / 2, y, 0xFFFFFF);
        y += 16;
        line(g, x, y, "civilizeandgovern.panel.mayor", view.founderName());
        line(g, x, y + 11, "civilizeandgovern.panel.day", view.cityDay());
        line(g, x, y + 22, "civilizeandgovern.panel.level", view.level(), view.population());
        line(g, x, y + 33, "civilizeandgovern.panel.chunks", view.chunks(), view.maxChunks());
        line(g, x, y + 44, "civilizeandgovern.panel.treasury", view.treasury(), view.currency());
        line(g, x, y + 55, "civilizeandgovern.panel.issued", view.issuedTotal(), view.currency());
        line(g, x, y + 66, "civilizeandgovern.panel.price_anchor", String.format("%.2f", view.priceAnchor()));
        g.drawString(font, Component.translatable("civilizeandgovern.panel.issue_hint"), x, y + 88, 0xA0A0A0);
        g.drawString(font, Component.translatable(view.active() ? "civilizeandgovern.panel.active" : "civilizeandgovern.panel.inactive"),
                x, y + 77, view.active() ? 0x80E080 : 0xE0A060);
    }

    private void line(GuiGraphics g, int x, int y, String key, Object... args) {
        g.drawString(font, Component.translatable(key, args), x, y, 0xE0E0E0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

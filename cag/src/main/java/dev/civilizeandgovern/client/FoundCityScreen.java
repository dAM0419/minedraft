package dev.civilizeandgovern.client;

import dev.civilizeandgovern.net.Msg;
import dev.civilizeandgovern.net.Net;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** 도시 세우기: 도시 이름과 화폐 이름을 정한다 (명세 09-blocks.md 1절). */
public final class FoundCityScreen extends Screen {
    private final BlockPos plaza;
    private EditBox name, currency;
    private Button found;

    public FoundCityScreen(BlockPos plaza) {
        super(Component.translatable("civilizeandgovern.found.title"));
        this.plaza = plaza;
    }

    @Override
    protected void init() {
        int x = width / 2 - 100, y = height / 2 - 40;
        name = new EditBox(font, x, y, 200, 20, Component.translatable("civilizeandgovern.found.name"));
        name.setMaxLength(32);
        name.setResponder(s -> refresh());
        currency = new EditBox(font, x, y + 44, 200, 20, Component.translatable("civilizeandgovern.found.currency"));
        currency.setMaxLength(16);
        currency.setResponder(s -> refresh());
        addRenderableWidget(name);
        addRenderableWidget(currency);
        found = addRenderableWidget(Button.builder(Component.translatable("civilizeandgovern.found.confirm"), b -> submit())
                .bounds(x, y + 76, 98, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(x + 102, y + 76, 98, 20).build());
        setInitialFocus(name);
        refresh();
    }

    private void refresh() {
        if (found != null) found.active = !name.getValue().isBlank() && !currency.getValue().isBlank();
    }

    private void submit() {
        String n = name.getValue().strip(), c = currency.getValue().strip();
        if (n.isEmpty() || c.isEmpty()) return;
        Net.toServer(Msg.C_FOUND, buf -> {
            buf.writeBlockPos(plaza);
            buf.writeUtf(n);
            buf.writeUtf(c);
        });
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = width / 2 - 100, y = height / 2 - 40;
        g.drawCenteredString(font, title, width / 2, y - 30, 0xFFFFFF);
        g.drawString(font, Component.translatable("civilizeandgovern.found.name"), x, y - 11, 0xA0A0A0);
        g.drawString(font, Component.translatable("civilizeandgovern.found.currency"), x, y + 33, 0xA0A0A0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

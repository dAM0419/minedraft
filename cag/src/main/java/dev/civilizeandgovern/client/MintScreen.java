package dev.civilizeandgovern.client;

import dev.civilizeandgovern.net.MintView;
import dev.civilizeandgovern.net.Msg;
import dev.civilizeandgovern.net.Net;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** 주조대 화면: 넣어 둔 구리 주괴를 녹여 화폐를 발행한다 (D-56). */
public final class MintScreen extends Screen {
    private MintView view;
    private EditBox amount;
    private Button mint, all, take;

    public MintScreen(MintView view) {
        super(Component.translatable("civilizeandgovern.mint.title"));
        this.view = view;
    }

    public BlockPos pos() {
        return view.pos();
    }

    public void update(MintView next) {
        this.view = next;
        refresh();
    }

    @Override
    protected void init() {
        int x = width / 2 - 110, y = height / 2 + 4;
        amount = new EditBox(font, x, y, 106, 20, Component.translatable("civilizeandgovern.mint.amount"));
        amount.setMaxLength(5);
        amount.setFilter(s -> s.matches("\\d*"));
        amount.setResponder(s -> refresh());
        addRenderableWidget(amount);
        all = addRenderableWidget(Button.builder(Component.translatable("civilizeandgovern.mint.all"),
                b -> amount.setValue(Integer.toString(view.copper()))).bounds(x + 110, y, 40, 20).build());
        mint = addRenderableWidget(Button.builder(Component.translatable("civilizeandgovern.mint.confirm"), b -> {
            int n = parse();
            if (n > 0) {
                Net.toServer(Msg.C_MINT, buf -> {
                    buf.writeBlockPos(view.pos());
                    buf.writeVarInt(n);
                });
                amount.setValue("");
            }
        }).bounds(x + 154, y, 66, 20).build());
        take = addRenderableWidget(Button.builder(Component.translatable("civilizeandgovern.mint.take"),
                b -> Net.toServer(Msg.C_MINT_TAKE, buf -> buf.writeBlockPos(view.pos()))).bounds(x, y + 26, 106, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x + 114, y + 26, 106, 20).build());
        setInitialFocus(amount);
        refresh();
    }

    private int parse() {
        try {
            return amount.getValue().isEmpty() ? 0 : Integer.parseInt(amount.getValue());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void refresh() {
        if (mint == null) return;
        int n = parse();
        mint.active = view.canIssue() && n > 0 && n <= view.copper();
        all.active = view.canIssue() && view.copper() > 0;
        amount.setEditable(view.canIssue());
        take.active = view.copper() > 0;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = width / 2 - 110, y = height / 2 - 76;
        g.drawCenteredString(font, Component.translatable("civilizeandgovern.mint.header", view.cityName()), width / 2, y, 0xFFFFFF);
        y += 18;
        g.drawString(font, Component.translatable("civilizeandgovern.mint.copper", view.copper()), x, y, 0xE0E0E0);
        g.drawString(font, Component.translatable("civilizeandgovern.mint.rate", view.rate(), view.currency()), x, y + 11, 0xE0E0E0);
        g.drawString(font, Component.translatable("civilizeandgovern.panel.treasury", view.treasury(), view.currency()), x, y + 22, 0xE0E0E0);
        g.drawString(font, Component.translatable("civilizeandgovern.panel.issued", view.issuedTotal(), view.currency()), x, y + 33, 0xE0E0E0);
        int n = parse();
        Component hint = !view.canIssue() ? Component.translatable("civilizeandgovern.mint.no_permission")
                : n > view.copper() ? Component.translatable("civilizeandgovern.mint.not_enough")
                : n > 0 ? Component.translatable("civilizeandgovern.mint.result", (long) n * view.rate(), view.currency())
                : Component.translatable("civilizeandgovern.mint.hint");
        g.drawString(font, hint, x, y + 50, n > view.copper() || !view.canIssue() ? 0xE0A060 : 0xA0A0A0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

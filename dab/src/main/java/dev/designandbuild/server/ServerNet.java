package dev.designandbuild.server;

import dev.designandbuild.Registration;
import dev.designandbuild.block.BlueprintTableBlockEntity;
import dev.designandbuild.core.BlueprintModel;
import dev.designandbuild.core.TemplateData;
import dev.designandbuild.item.BlueprintRef;
import dev.designandbuild.net.Msg;
import dev.designandbuild.net.Net;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** 서버가 받은 메시지 처리. 서버 주 스레드에서 불린다. */
public final class ServerNet {
    private ServerNet() {}

    private static final double REACH_SQR = 8 * 8;

    public static void receive(ServerPlayer player, dev.minedraft.lib.net.Channel.Frame frame) {
        BlueprintManager manager = BlueprintManager.get();
        if (manager == null) return;
        FriendlyByteBuf buf = frame.buf();
        switch (frame.kind()) {
            case Msg.C_OPEN_EDITOR -> openEditor(manager, player, buf.readBlockPos());
            case Msg.C_START_DESIGN -> startDesign(manager, player, buf.readBlockPos(), buf.readUtf(64));
            case Msg.C_LIBRARY_REQ -> {
                var list = StructureLibrary.entries(manager.server());
                Net.toPlayer(player, Msg.S_LIBRARY, b -> StructureLibrary.writeList(b, list));
            }
            case Msg.C_TEMPLATE_REQ -> {
                String id = buf.readUtf(512);
                TemplateData data = StructureLibrary.load(manager.server(), id);
                Net.toPlayer(player, Msg.S_TEMPLATE, b -> {
                    b.writeUtf(id);
                    b.writeBoolean(data != null);
                    if (data != null) data.write(b);
                });
            }
            case Msg.C_TABLE_INFO_REQ -> sendTableInfo(manager, player, buf.readBlockPos());
            case Msg.C_TABLE_RENAME -> {
                BlockPos pos = buf.readBlockPos();
                String name = buf.readUtf(64);
                BlueprintTableBlockEntity table = reachableTable(player, pos);
                if (table == null) return;
                BlueprintRef ref = table.blueprint().get(Registration.BLUEPRINT_REF.get());
                if (ref == null) return;
                BlueprintModel m = manager.model(ref.id());
                if (m != null) {
                    manager.renameFromTable(player, m, name);
                    table.syncBlueprintName(m);
                    sendTableInfo(manager, player, pos);
                }
            }
            case Msg.C_VIEW_STATE -> {
                if (!buf.readBoolean()) {
                    BuildService.setView(player, null);
                } else {
                    UUID bp = buf.readUUID();
                    int kind = buf.readVarInt();
                    int sid = buf.readVarInt();
                    int mode = buf.readByte();
                    int y = buf.readInt();
                    boolean topDown = buf.readBoolean();
                    BuildService.setView(player, new BuildService.View(bp, kind, sid, mode, y, topDown));
                }
            }
            case Msg.C_INSTANT_APPLY -> {
                UUID bp = buf.readUUID();
                int kind = buf.readVarInt();
                int sid = buf.readVarInt();
                BuildService.instantApply(player, bp, kind, sid);
            }
            case Msg.C_INSTANT_UNDO -> BuildService.instantUndo(player, buf.readUUID());
            case Msg.C_INSTANT_QUERY -> BuildService.sendState(player, buf.readUUID());
            case Msg.C_HAMMER -> {
                BlockPos pos = buf.readBlockPos();
                boolean bulk = buf.readBoolean();
                BuildService.hammer(player, pos, bulk);
            }
            default -> {
            }
        }
    }

    @Nullable
    private static BlueprintTableBlockEntity reachableTable(ServerPlayer player, BlockPos pos) {
        BlueprintTableBlockEntity table = BlueprintTableBlockEntity.at(player.level(), pos);
        if (table == null) return null;
        if (player.distanceToSqr(Vec3.atCenterOf(table.getBlockPos())) > REACH_SQR) return null;
        return table;
    }

    private static void openEditor(BlueprintManager manager, ServerPlayer player, BlockPos pos) {
        BlueprintTableBlockEntity table = reachableTable(player, pos);
        if (table == null) {
            BlueprintManager.toast(player, "designandbuild.msg.too_far");
            return;
        }
        ItemStack stack = table.blueprint();
        if (stack.isEmpty()) {
            BlueprintManager.toast(player, "designandbuild.msg.no_blueprint");
            return;
        }
        BlueprintRef ref = stack.get(Registration.BLUEPRINT_REF.get());
        if (ref == null) {
            BlueprintManager.toast(player, "designandbuild.msg.not_started");
            return;
        }
        BlueprintModel m = manager.model(ref.id());
        if (m == null) {
            BlueprintManager.toast(player, "designandbuild.msg.blueprint_missing");
            return;
        }
        player.closeContainer();
        manager.openEditor(player, m, table.getBlockPos());
    }

    private static void startDesign(BlueprintManager manager, ServerPlayer player, BlockPos pos, String name) {
        BlueprintTableBlockEntity table = reachableTable(player, pos);
        if (table == null) {
            BlueprintManager.toast(player, "designandbuild.msg.too_far");
            return;
        }
        ItemStack stack = table.blueprint();
        if (stack.isEmpty()) {
            BlueprintManager.toast(player, "designandbuild.msg.no_blueprint");
            return;
        }
        if (stack.get(Registration.BLUEPRINT_REF.get()) != null) {
            openEditor(manager, player, pos);
            return;
        }
        BlueprintModel m = manager.create(player.serverLevel(), table.getBlockPos(), name.isBlank() ? "Blueprint" : name);
        ItemStack bound = stack.copyWithCount(1);
        bound.set(Registration.BLUEPRINT_REF.get(), new BlueprintRef(m.id, m.name(), m.baseX, m.baseZ,
                m.dimension.location().toString()));
        table.setBlueprint(bound);
        player.closeContainer();
        manager.openEditor(player, m, table.getBlockPos());
    }

    private static void sendTableInfo(BlueprintManager manager, ServerPlayer player, BlockPos pos) {
        BlueprintTableBlockEntity table = BlueprintTableBlockEntity.at(player.level(), pos);
        if (table == null) return;
        ItemStack stack = table.blueprint();
        BlueprintRef ref = stack.get(Registration.BLUEPRINT_REF.get());
        BlueprintModel m = ref == null ? null : manager.model(ref.id());
        Net.toPlayer(player, Msg.S_TABLE_INFO, buf -> {
            buf.writeBlockPos(table.getBlockPos());
            int state = stack.isEmpty() ? 0 : ref == null ? 1 : m == null ? 3 : 2;
            buf.writeByte(state);
            if (m != null) {
                buf.writeUtf(m.name());
                buf.writeVarInt(m.baseX);
                buf.writeVarInt(m.baseZ);
                buf.writeVarInt(m.chunks().size());
                buf.writeVarInt(m.structureCount());
                buf.writeVarInt(manager.progress(m));
                buf.writeVarInt(manager.editorCount(m.id));
            }
        });
    }
}

package dev.designandbuild.client.projection;

import dev.minedraft.lib.client.ClientAreas;

import dev.designandbuild.Registration;
import dev.designandbuild.client.ClientConfig;
import dev.designandbuild.client.Keys;
import dev.designandbuild.core.BlueprintModel;
import dev.designandbuild.item.BlueprintRef;
import dev.designandbuild.net.Msg;
import dev.designandbuild.net.Net;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 도면을 손에 들었을 때의 투영. 주손 도면을 먼저 보고, 없으면 보조손 도면.
 * 들면 서버에 구독을 걸어 설계 변경을 받고, 놓으면 구독을 푼다.
 */
public final class ProjectionManager {
    private ProjectionManager() {}

    @Nullable
    private static UUID subscribed;
    @Nullable
    private static Projection active;
    /** 최근에 든 도면의 투영 (다시 들면 비교를 처음부터 하지 않게). 오래된 것은 버리고 도면 변경 알림도 끊는다. */
    private static final int CACHE_MAX = 3;
    private static final Map<UUID, Projection> CACHE = new java.util.LinkedHashMap<>(8, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Projection> eldest) {
            if (size() <= CACHE_MAX) return false;
            if (eldest.getValue() == active) return false;
            eldest.getValue().detach();
            return true;
        }
    };
    private static int tick;
    private static int rebuildCooldown;
    private static int materialsAge = 999;

    /** 재료 목록 표시: 0 = 접힘, 1 = 펼침(현재 층), 2 = 펼침(현재 단계 전체). */
    public static int materialsMode;
    static List<Projection.Material> layerMaterials = List.of();
    static List<Projection.Material> stageMaterials = List.of();

    @Nullable
    public static Projection active() {
        return active;
    }

    public static List<Projection.Material> layerMaterials() {
        return layerMaterials;
    }

    public static List<Projection.Material> stageMaterials() {
        return stageMaterials;
    }

    /** 건설망치가 지금 겨누는 투영 칸 (없으면 null). 틱마다 다시 구한다. */
    @Nullable
    public static net.minecraft.core.BlockPos hammerTarget;
    /** 겨눈 칸에 망치로 놓을 수 없으면 그 이유 (조준점 아래에 띄운다). 놓을 수 있으면 null. */
    @Nullable
    public static net.minecraft.network.chat.Component hammerHint;
    /** 서버에 마지막으로 보낸 보기 상태 (같으면 다시 안 보냄). sentViewValid = false 면 아직 안 보냄. */
    private static boolean sentViewValid, sentViewOn;
    @Nullable
    private static UUID sentViewId;
    private static final int[] sentViewKey = new int[4];
    /** 조준 이유 문구를 만든 판정 (같은 판정이면 문구를 다시 만들지 않는다). */
    @Nullable
    private static dev.designandbuild.core.PlaceCheck.Result hintFor;

    /** 도면별 즉시 반영 상태 (서버가 알려 줌). */
    public record InstantState(boolean allowed, boolean running, boolean hasUndo, int undoKind, int undoStructure) {}

    public static final Map<UUID, InstantState> INSTANT = new HashMap<>();

    public static void reset() {
        hammerTarget = null;
        hammerHint = null;
        hintFor = null;
        sentViewValid = false;
        INSTANT.clear();
        subscribed = null;
        active = null;
        for (Projection p : CACHE.values()) p.detach();
        CACHE.clear();
        ProjectionRenderer.clear();
        layerMaterials = List.of();
        stageMaterials = List.of();
    }

    public static void onModelReplaced(BlueprintModel m) {
        Projection p = CACHE.get(m.id);
        if (p != null) p.replaceModel(m);
        materialsAge = 999;
    }

    /** 도면 메뉴에서 층 보기 방식을 바꿨을 때. */
    public static void markAllDirty() {
        if (active != null) {
            active.refreshViewMode();
            active.allDirty = true;
        }
    }

    @Nullable
    private static BlueprintRef heldRef(LocalPlayer player, ClientLevel level) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (!stack.is(Registration.BLUEPRINT.get())) continue;
            BlueprintRef ref = stack.get(Registration.BLUEPRINT_REF.get());
            if (ref == null) continue;
            if (!ref.dimension().equals(level.dimension().location().toString())) continue;
            return ref;
        }
        return null;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        tick++;
        if (player == null || level == null) {
            if (subscribed != null || active != null) reset();
            drainKeys();
            return;
        }
        BlueprintRef ref = heldRef(player, level);
        UUID held = ref == null || ClientConfig.get().projectionOff.contains(ref.id().toString()) ? null : ref.id();
        if (!Objects.equals(held, subscribed)) {
            if (subscribed != null) {
                UUID old = subscribed;
                ClientAreas.unsubscribe(old);
            }
            subscribed = held;
            active = null;
            ProjectionRenderer.clear();
            if (held != null) {
                ClientAreas.subscribe(held);
            }
        }
        if (subscribed == null) {
            hammerTarget = null;
            hammerHint = null;
            sendView(null);
            drainKeys();
            return;
        }
        if (active == null) {
            BlueprintModel m = BlueprintModel.ofNullable(ClientAreas.get(subscribed));
            if (m == null) {
                drainKeys();
                return;
            }
            active = CACHE.computeIfAbsent(subscribed, id -> new Projection(m));
            if (active.model != m) active.replaceModel(m);
            materialsAge = 999;
        }
        Projection p = active;
        if (rebuildCooldown > 0) rebuildCooldown--;
        if (p.needsRebuild() && rebuildCooldown <= 0) {
            p.rebuild(level);
            rebuildCooldown = 10;
            materialsAge = 999;
        }
        // 전체 칸은 약 1초에 한 바퀴 다시 본다 (주변 칸은 stepNear 가 더 자주)
        p.step(level, p.isReady() ? Math.max(512, Math.min(25_000, (p.n + 19) / 20)) : 200_000);
        if (tick % 4 == 0) p.stepNear(level, player.blockPosition(), 1);
        int stageBefore = p.currentStage();
        int layerBefore = p.currentLayer();
        p.updateNavigation();
        handleKeys(p, player);
        if (p.currentStage() != stageBefore || p.currentLayer() != layerBefore) materialsAge = 999;
        announce(p, mc);
        sendView(p);
        hammerTarget = player.getMainHandItem().is(Registration.HAMMER.get())
                ? p.pickPlaceable(level, player.getEyePosition(), player.getViewVector(1.0F), player.blockInteractionRange())
                : null;
        updateHammerHint(p, level);
        ProjectionRenderer.update(p);
        if (++materialsAge >= 10) {
            materialsAge = 0;
            // 지금 보이는 목록 하나만 센다 (펼침 방식을 바꾸면 바로 다시 센다)
            if (materialsMode == 2) {
                stageMaterials = p.materials(level, false);
            } else {
                layerMaterials = p.materials(level, true);
            }
        }
    }

    /** 서버와 같은 판정으로 겨눈 칸을 미리 본다 (재료·보호 구역은 서버만 안다). */
    private static void updateHammerHint(Projection p, ClientLevel level) {
        if (hammerTarget == null) {
            hammerHint = null;
            hintFor = null;
            return;
        }
        int[] k = p.currentStageKey();
        dev.designandbuild.core.PlaceCheck.Result r = dev.designandbuild.core.PlaceCheck.check(p.model, level, hammerTarget, k[0], k[1],
                p.layerMode(), p.currentLayer(), p.currentStage() == Projection.STAGE_DEMOLISH);
        if (r.ok()) {
            hammerHint = null;
            hintFor = null;
        } else if (!r.equals(hintFor)) {
            hintFor = r;
            hammerHint = HammerText.describe(r);
        }
    }

    /** 보는 단계·층이 바뀌면 서버에 알린다 (건설망치 판정용). */
    private static void sendView(@Nullable Projection p) {
        boolean on = p != null && p.isReady();
        int[] k = on ? p.currentStageKey() : null;
        int mode = on ? p.layerMode() : 0, y = on ? p.currentLayer() : 0;
        UUID id = on ? p.model.id : null;
        if (!sentViewValid) {
            sentViewValid = true;
            if (!on) { // 처음부터 꺼져 있으면 알릴 것이 없다
                sentViewOn = false;
                return;
            }
        } else if (on == sentViewOn && (!on || (id.equals(sentViewId) && k[0] == sentViewKey[0] && k[1] == sentViewKey[1]
                && mode == sentViewKey[2] && y == sentViewKey[3]))) {
            return;
        }
        sentViewOn = on;
        if (!on) {
            Net.toServer(Msg.C_VIEW_STATE, b -> b.writeBoolean(false));
            return;
        }
        sentViewId = id;
        sentViewKey[0] = k[0];
        sentViewKey[1] = k[1];
        sentViewKey[2] = mode;
        sentViewKey[3] = y;
        boolean topDown = p.currentStage() == Projection.STAGE_DEMOLISH;
        Net.toServer(Msg.C_VIEW_STATE, b -> {
            b.writeBoolean(true);
            b.writeUUID(id);
            b.writeVarInt(k[0]);
            b.writeVarInt(k[1]);
            b.writeByte(mode);
            b.writeInt(y);
            b.writeBoolean(topDown);
        });
    }

    private static void drainKeys() {
        while (Keys.LAYER_UP.consumeClick()) ;
        while (Keys.LAYER_DOWN.consumeClick()) ;
        while (Keys.STAGE_PREV.consumeClick()) ;
        while (Keys.STAGE_NEXT.consumeClick()) ;
        while (Keys.MATERIALS.consumeClick()) ;
    }

    private static void handleKeys(Projection p, LocalPlayer player) {
        while (Keys.LAYER_UP.consumeClick()) p.moveLayer(1, player.getBlockY());
        while (Keys.LAYER_DOWN.consumeClick()) p.moveLayer(-1, player.getBlockY());
        while (Keys.STAGE_PREV.consumeClick()) p.moveStage(-1);
        while (Keys.STAGE_NEXT.consumeClick()) p.moveStage(1);
        while (Keys.MATERIALS.consumeClick()) {
            materialsMode = (materialsMode + 1) % 3;
            materialsAge = 999;
        }
    }

    private static void announce(Projection p, Minecraft mc) {
        int ev = p.events;
        if (ev == 0 || mc.player == null) return;
        if ((ev & Projection.EVENT_ALL) != 0) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0F, 0.6F));
            mc.player.displayClientMessage(Component.translatable("designandbuild.projection.all_done", p.model.name()), true);
        } else if ((ev & Projection.EVENT_STAGE) != 0) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.6F, 0.4F));
            mc.player.displayClientMessage(Component.translatable("designandbuild.projection.stage_done", stageName(p, p.currentStage())), true);
        } else if ((ev & Projection.EVENT_LAYER) != 0) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.3F, 0.35F));
        }
    }

    public static Component stageName(Projection p, int s) {
        return switch (s) {
            case Projection.STAGE_DEMOLISH -> Component.translatable("designandbuild.stage.demolish");
            case Projection.STAGE_FLOOR -> Component.translatable("designandbuild.stage.floor");
            case Projection.STAGE_PATH -> Component.translatable("designandbuild.stage.path");
            default -> s >= p.stageCount() ? Component.translatable("designandbuild.stage.done")
                    : Component.literal(p.structureName(s));
        };
    }
}

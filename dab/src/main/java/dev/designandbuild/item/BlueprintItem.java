package dev.designandbuild.item;

import dev.designandbuild.Registration;
import dev.designandbuild.core.BlueprintModel;
import dev.designandbuild.server.BlueprintManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.function.Consumer;

/**
 * DaB 도면. 빈 도면은 테이블에 올려 "설계 시작"으로 영역이 정해진다.
 * 설계가 정해진 도면을 손(주손·보조손)에 들면 월드에 투영된다. 허공에 우클릭하면 투영 메뉴.
 */
public class BlueprintItem extends Item {
    /** 클라이언트가 넣는다: 허공 우클릭 시 투영 메뉴 열기. */
    public static Consumer<ItemStack> clientMenuOpener = stack -> {};

    public BlueprintItem(Properties properties) {
        super(properties);
    }

    public static boolean isBound(ItemStack stack) {
        return stack.getItem() instanceof BlueprintItem && stack.get(Registration.BLUEPRINT_REF.get()) != null;
    }

    public static boolean isEmptyBlueprint(ItemStack stack) {
        return stack.getItem() instanceof BlueprintItem && stack.get(Registration.BLUEPRINT_REF.get()) == null;
    }

    @Override
    public Component getName(ItemStack stack) {
        BlueprintRef ref = stack.get(Registration.BLUEPRINT_REF.get());
        if (ref == null) return Component.translatable("item.designandbuild.blueprint.empty");
        return Component.translatable("item.designandbuild.blueprint.named", ref.name());
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        BlueprintRef ref = stack.get(Registration.BLUEPRINT_REF.get());
        if (ref == null) {
            lines.add(Component.translatable("item.designandbuild.blueprint.empty.tip").withStyle(ChatFormatting.GRAY));
            return;
        }
        lines.add(Component.translatable("item.designandbuild.blueprint.base", ref.baseX(), ref.baseZ()).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("item.designandbuild.blueprint.tip").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!isBound(stack)) return InteractionResultHolder.pass(stack);
        if (level.isClientSide) clientMenuOpener.accept(stack);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** 이름이 바뀌었으면 사본에도 반영 (서버, 2초마다). */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide || entity.tickCount % 40 != 0) return;
        BlueprintRef ref = stack.get(Registration.BLUEPRINT_REF.get());
        BlueprintManager manager = BlueprintManager.get();
        if (ref == null || manager == null) return;
        BlueprintModel m = manager.model(ref.id());
        if (m != null && !m.name.equals(ref.name())) stack.set(Registration.BLUEPRINT_REF.get(), ref.withName(m.name));
    }
}

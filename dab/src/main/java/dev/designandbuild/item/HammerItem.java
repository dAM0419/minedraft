package dev.designandbuild.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 건설망치: 보조손에 도면을 들고 투영된 칸을 우클릭하면 인벤토리 블록으로 설계대로 설치한다. 웅크리면 이어진 칸을 한꺼번에.
 * 조준·요청은 클라이언트 입력 단계에서 처리한다(투영 칸은 실제로 비어 있어 바닐라 조준에 걸리지 않는다).
 * 아이템 자체의 사용은 행동을 소비해, 망치를 든 채 보조손 도면 메뉴가 열리지 않게 한다. 설치 전용이며 부수지 않는다.
 */
public class HammerItem extends Item {
    public HammerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return InteractionResult.PASS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("item.designandbuild.construction_hammer.tip").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("item.designandbuild.construction_hammer.tip2").withStyle(ChatFormatting.DARK_GRAY));
    }
}

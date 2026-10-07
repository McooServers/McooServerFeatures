package com.mcoo.core.gui;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 只读槽位：玩家拿不走、也放不进东西。
 *
 * <p>把界面上每个格子都换成这种槽位，是物品安全的第一道防线。
 * 原版箱子槽位默认是**完全开放**的：玩家可以拿起按钮、可以把东西塞进去。
 * 那等于凭空刷物品。
 *
 * <p>本类还刻意锁死了堆叠上限：即使将来因为某个 bug 有物品被塞进来，
 * 也不会在槽位里堆成一堆。
 */
public final class LockedSlot extends Slot {

    public LockedSlot(Container container, int index, int x, int y) {
        super(container, index, x, y);
    }

    /** 拿不走。 */
    @Override
    public boolean mayPickup(Player player) {
        return false;
    }

    /** 放不进、也拿不出 —— Shift+点击、拖拽、双击统统无效。 */
    @Override
    public boolean allowModification(Player player) {
        return false;
    }

    /** 每格最多 1 个。防止万一有物品漏进来时堆成一堆。 */
    @Override
    public int getMaxStackSize(ItemStack stack) {
        return 1;
    }
}

package com.mcoo.core.gui;

import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 我们自己的箱子菜单 —— 只做两件事：**把所有点击拦下来路由走**，以及**堵死物品流失**。
 *
 * <h2>为什么 MenuType 用的是原版的</h2>
 * 本类虽然继承了 {@code ChestMenu}，但传进去的 {@link MenuType} 是原版内置的
 * {@code GENERIC_9x1} ~ {@code GENERIC_9x6}。
 * <b>我们没有、也不会注册任何自定义 MenuType</b> ——
 * 自定义 MenuType 会让原版客户端认不出这个界面直接崩掉，
 * 而本项目是仅服务端 mod，客户端什么都没装。
 *
 * <p>客户端收到的是「打开一个 9x3 箱子」，它用原版箱子界面渲染；
 * 服务端这个子类对它完全不可见。<b>这是"仅服务端 mod 也能开 GUI"的全部秘密。</b>
 *
 * <h2>物品安全：三条流失路径全部堵死</h2>
 * 界面上的按钮是**真实物品堆**，所以每一条能让物品离开容器的路径都必须堵：
 * <ol>
 *   <li><b>点击 / 拖拽 / 双击</b> → 容器区的点击<b>根本不调</b> {@code super}，
 *       自己处理完就返回</li>
 *   <li><b>Shift+点击（quickMoveStack）</b> → 容器区直接返回空，
 *       玩家背包区照常放行</li>
 *   <li><b>槽位本身</b> → 全部换成 {@link LockedSlot}，
 *       {@code mayPickup} 与 {@code allowModification} 都是 false</li>
 * </ol>
 * 这三条任何一条破了，玩家就能白嫖按钮物品。
 *
 * <p><b>关掉界面不会掉物品</b>：已核对 26.2 的 {@code ChestMenu.removed()} 字节码，
 * 它只调 {@code container.stopOpen()}，不做掉落。但这里仍然会在关闭时
 * 清空容器，理由见 {@link #closed}。
 */
public final class McooChestMenu extends ChestMenu {

    /** 容器区（界面按钮）的槽位数量 = 行数 × 9。 */
    private final int guiSlotCount;

    /**
     * 界面内容容器。存下来是为了能<b>原地</b>改内容做刷新/换页 ——
     * 菜单构造时槽位就绑死在这个容器上了，换一个容器会导致
     * 槽位和客户端看到的内容对不上。
     */
    private final Container guiContainer;

    /**
     * 当前显示的是哪个界面。
     *
     * <p>做多级菜单时，{@link ChestGui#switchTo} 会直接把本菜单"改指向"另一个界面，
     * <b>不关闭也不重开</b>（见 {@link #retargetTo}）。
     * 所以点击处理不能是构造时定死的函数指针，必须跟着这个字段走。
     */
    private ChestGui view;

    /** 界面关闭时的回调（玩家、是否真的点过按钮）。 */
    private final GuiClosedCallback closedCallback;

    private boolean interacted;

    /** 界面关闭时的回调。 */
    @FunctionalInterface
    public interface GuiClosedCallback {
        void onClosed(Player player, boolean interacted);
    }

    public McooChestMenu(MenuType<?> type,
                         int syncId,
                         Inventory playerInventory,
                         Container container,
                         int rows,
                         ChestGui view,
                         GuiClosedCallback closedCallback) {
        super(type, syncId, playerInventory, container, rows);
        this.guiSlotCount = GuiLayout.size(rows);
        this.guiContainer = container;
        this.view = view;
        this.closedCallback = closedCallback;
        lockGuiSlots();
    }

    /** 界面的内容容器，供 {@link ChestGui#refresh} 原地改内容用。 */
    public Container guiContainer() {
        return guiContainer;
    }

    /**
     * 就地换成另一个界面：清空容器、按新界面重摆、广播给客户端。
     *
     * <p><b>为什么多级菜单要这么做，而不是"关掉再打开"</b>：
     * 关界面再开界面会让客户端在同一个 tick 里先收到关闭包、再收到打开包，
     * 中间屏幕被释放又重新抓取鼠标 —— 玩家会看到**光标被甩到屏幕中间**，
     * 还得重新挪回去才能点。这不是我们能在服务端设置的，是那次关闭/重开造成的。
     *
     * <p>只换内容不换屏幕，光标不会动，也没有关闭带来的开销。
     *
     * @return 换成功返回 true；槽位数对不上 / 摆界面失败返回 false
     */
    public boolean retargetTo(ChestGui newView, net.minecraft.server.level.ServerPlayer player) {
        if (newView == null || newView.slotCount() != guiSlotCount) {
            return false;
        }
        try {
            guiContainer.clearContent();
            newView.layoutInto(guiContainer, player);
            view = newView;
            broadcastChanges();
            return true;
        } catch (Throwable t) {
            System.err.println("[mcoo] 切换界面失败（界面保持原样）：" + t);
            t.printStackTrace();
            return false;
        }
    }

    /**
     * 把界面区的槽位全部换成只读槽位。
     *
     * <p>{@code slots} 是 {@code public final} 的 {@link NonNullList}，
     * 所以可以直接替换元素 —— 这是"不重新实现整个菜单"的关键。
     */
    private void lockGuiSlots() {
        for (int i = 0; i < guiSlotCount; i++) {
            Slot old = slots.get(i);
            slots.set(i, new LockedSlot(old.container, old.index, old.x, old.y));
        }
    }

    /**
     * 这个槽位是不是界面上的按钮（而不是玩家的背包）。
     */
    public boolean isGuiSlot(int slotId) {
        return slotId >= 0 && slotId < guiSlotCount;
    }

    public int guiSlotCount() {
        return guiSlotCount;
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player player) {
        if (!isGuiSlot(slotId)) {
            // 玩家自己的背包：完全放行，让原版处理
            super.clicked(slotId, button, input, player);
            return;
        }

        // 容器区：无论什么类型的点击都不交给原版。
        // 只有「拿起/放下」才算点击按钮，Shift、拖拽、双击一律忽略 ——
        // 它们在本界面里没有任何意义，放行就等于给刷物品开口子。
        if (input != ContainerInput.PICKUP && input != ContainerInput.PICKUP_ALL) {
            return;
        }

        ItemStack stack = slots.get(slotId).getItem();
        if (stack.isEmpty()) {
            return;
        }

        var action = GuiActionCodec.read(stack);
        if (action.isEmpty()) {
            if (GuiActionCodec.isFiller(stack)) {
                // 背景填充物：点了什么也不该发生，这是设计内的，直接安静返回。
                return;
            }
            // 既没有动作、也不是背景 —— 说明摆界面时漏了 encodeAction。
            // 这是真的代码 bug，记一笔；但绝不抛出去，
            // 玩家点一下不该导致界面崩掉。
            System.err.println("[mcoo] 界面格子 " + slotId
                    + " 里的物品既没有动作编码、也不是背景填充物（摆界面时漏了 encodeAction），已忽略");
            return;
        }

        interacted = true;
        try {
            String reply = view.handleClick(action.get(), player);
            sendReply(player, reply);
        } catch (Throwable t) {
            // 点击处理是功能自己的代码，它崩了绝不能连带界面崩掉。
            // 界面还在，物品也还在，只是这次点击没生效。
            System.err.println("[mcoo] 处理点击 " + action.get() + " 失败（界面不受影响）：" + t);
            t.printStackTrace();
            sendReply(player, "操作失败，功能暂时不可用");
        }
    }

    /**
     * 给玩家发回复。
     *
     * <p>玩家为 null 时直接跳过 —— 生产环境点界面的必然是真玩家，
     * 但自检（{@code GuiSelfCheck}）是在没有玩家的情况下模拟点击的。
     * 顺带这也符合本项目「通知失败绝不影响主流程」的原则。
     */
    private void sendReply(Player player, String text) {
        if (player == null || text == null || text.isEmpty()) {
            return;
        }
        try {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(text));
        } catch (Throwable t) {
            // 连"发消息失败"都不该影响点击流程本身
            System.err.println("[mcoo] 回复消息失败（已忽略）：" + t);
        }
    }

    /**
     * Shift+点击 / 快速移动。
     *
     * <p><b>这是最容易漏掉的一条物品流失路径</b>：原版 {@code ChestMenu}
     * 会把容器里的物品搬进玩家背包。界面按钮被搬走 = 白嫖。
     * 所以容器区一律返回空，玩家背包区照常放行。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int slotId) {
        if (isGuiSlot(slotId)) {
            return ItemStack.EMPTY;
        }
        return super.quickMoveStack(player, slotId);
    }

    @Override
    public void removed(Player player) {
        // 26.2 的 ChestMenu.removed 不会掉物品（已核对字节码），
        // 但这里仍然清空容器：界面关掉后按钮物品没有任何用途，
        // 留着只会在"万一将来有别的代码读到它"时变成惊喜。
        // 玩家背包区的槽位不在我们的容器里，不受影响。
        if (guiSlotCount > 0 && slots.get(0) != null) {
            for (int i = 0; i < guiSlotCount; i++) {
                slots.get(i).set(ItemStack.EMPTY);
            }
        }

        try {
            if (closedCallback != null) {
                closedCallback.onClosed(player, interacted);
            }
        } catch (Throwable t) {
            System.err.println("[mcoo] 界面关闭回调失败（不影响服务器）：" + t);
        }

        super.removed(player);
    }
}
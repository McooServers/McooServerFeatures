package com.mcoo.core.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

/**
 * 箱子界面框架 —— 所有面向玩家的界面都基于它。
 *
 * <p>功能只要实现几个方法就能有自己的界面：
 * <pre>{@code
 * public final class DailyGui extends ChestGui {
 *     @Override protected Component title() { return Component.literal("每日奖励"); }
 *     @Override protected int rows() { return 3; }
 *     @Override protected void layout(GuiCanvas canvas, ServerPlayer viewer) {
 *         canvas.fill(new ItemStack(Items.STAINED_GLASS_PANE.gray()));
 *         canvas.button(1, 4, "claim:day1", new ItemStack(Items.DIAMOND));
 *     }
 *     @Override protected String onClick(String action, ServerPlayer player) {
 *         return "领取成功";
 *     }
 * }
 * }</pre>
 *
 * <h2>为什么原版客户端能正常显示</h2>
 * 打开时传给客户端的是原版内置的 {@code MenuType.GENERIC_9xN}，
 * 客户端会用原版箱子界面渲染。<b>本项目不注册任何自定义 MenuType</b>，
 * 所以玩家不需要装任何东西 —— 见 {@link McooChestMenu} 的类注释。
 * <b>这一点已由真人进服实测确认</b>（vanilla 客户端正常渲染并可点击）。
 *
 * <h2>一条铁律：界面里的东西是"只读展示"，不是给玩家的物品</h2>
 * 按钮用的是真实物品堆，所以 {@link McooChestMenu} 把三条物品流失路径全堵死了。
 * 功能侧要做的只有一件事：<b>不要把贵重物品放进界面</b>。
 */
public abstract class ChestGui {

    /** 界面标题，显示在客户端界面上方。 */
    protected abstract Component title();

    /** 界面行数，1~6。 */
    protected abstract int rows();

    /**
     * 摆界面。只在打开时调用一次。
     *
     * @param canvas  画布，越界会当场报错
     * @param viewer  正在看这个界面的玩家；<b>自检场景下为 null</b>，
     *                所以功能不能假定它非空
     */
    protected abstract void layout(GuiCanvas canvas, ServerPlayer viewer);

    /**
     * 处理按钮点击。
     *
     * @param action  按钮的动作 id（{@code 动作名:参数}）
     * @param player  点击的人
     * @return 给玩家看的回复文字；返回 null 或空串表示不回复
     */
    protected abstract String onClick(String action, ServerPlayer player);

    /**
     * 界面被关闭时调用。默认什么都不做。
     *
     * <p>用来做"关掉界面后保存"、清掉每人状态这类事。注意玩家是可能直接断线的，
     * 所以关界面**不等于**玩家离开了服务器。
     */
    protected void onClosed(ServerPlayer player, boolean interacted) {
    }

    /**
     * 给这个界面挑一个原版 MenuType。
     *
     * <p>行数 1~6 对应原版的 {@code GENERIC_9x1} ~ {@code GENERIC_9x6}。
     * 行数非法时**立刻抛异常**——这是"界面定义写错了"，
     * 属于开发期就该暴露的错误，不该带病运行。
     */
    protected MenuType<?> menuType() {
        GuiLayout.validateRows(rows());
        return switch (rows()) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    /**
     * 造一个装界面内容的容器。
     *
     * <p>把「摆界面」和「打开界面」拆成两步有两个理由：
     * <ul>
     *   <li>摆界面失败时 {@link #open} 可以干净地拒绝这次开界面；</li>
     *   <li>自检（{@code GuiSelfCheck}）能在没有真玩家的情况下，
     *       用<b>真实的</b>摆界面流程验证布局和编码 ——
     *       而不是另外抄一份实现去测，那样测的是抄件不是原件。</li>
     * </ul>
     *
     * @throws IllegalArgumentException 行数非法
     * @throws RuntimeException        摆界面时功能自己的代码抛了异常（这里不兜底，由调用方决定）
     */
    public final SimpleContainer buildContent() {
        return build(null);
    }

    /** 同 {@link #buildContent()}，但会知道是哪个玩家在看。 */
    public final SimpleContainer buildContent(ServerPlayer viewer) {
        return build(viewer);
    }

    private SimpleContainer build(ServerPlayer viewer) {
        int rows = rows();
        GuiLayout.validateRows(rows);

        SimpleContainer container = new SimpleContainer(GuiLayout.size(rows));
        GuiCanvas canvas = new GuiCanvas(container, rows);
        // 刻意【不】把这里包一层"摆失败就返回 false"的兜底：
        // 那样自检就分不清"摆成功了"和"摆失败了但被吞掉"，
        // 拿到一个空容器还以为布局对了。
        layout(canvas, viewer);
        return container;
    }

    /**
     * 直接把内容摆进一个已有的容器（供就地刷新和换界面用）。
     *
     * <p>刻意不吞异常：调用方（{@link #refresh} / {@link McooChestMenu#retargetTo}）
     * 都需要知道"到底摆成功没有"，吞掉就分不清了。
     */
    void layoutInto(Container container, ServerPlayer viewer) {
        layout(new GuiCanvas(container, rows()), viewer);
    }

    /** 这个界面占多少格。用于判断能不能在同一个菜单里就地切换。 */
    int slotCount() {
        return GuiLayout.size(rows());
    }

    /**
     * 把界面重新摆一遍并<b>推给正在看的玩家</b>，不用关掉重开。
     *
     * <p><b>为什么需要它</b>：原版箱子界面不会自己刷新。
     * 容器里的东西变了，玩家眼睛里的东西不会跟着变 ——
     * 点完「领取」按钮，背包里的奖励到账了，但按钮还是原来那个样子，
     * 玩家会以为没生效。M4 的每日奖励一定会踩到这个。
     *
     * <p>做法是重新摆一遍界面，再让菜单把变化<b>广播</b>给客户端：
     * 菜单会逐格比对，把不一样的槽位发给玩家。
     * 界面不闪、不关、滚动位置也不丢。
     *
     * @return 刷新成功返回 true；玩家没在开这个界面、或摆界面失败返回 false
     */
    public final boolean refresh(ServerPlayer player) {
        if (player == null || !(player.containerMenu instanceof McooChestMenu menu)) {
            return false;
        }
        try {
            // 复用菜单里那个容器：换一个容器的话，槽位绑的还是旧的，
            // 客户端会看到一个界面对不上另一个界面的内容。
            Container container = menu.guiContainer();
            container.clearContent();
            layoutInto(container, player);
            menu.broadcastChanges();
            return true;
        } catch (Throwable t) {
            System.err.println("[mcoo] 刷新界面失败（" + getClass().getSimpleName()
                    + "），界面保持原样，玩家不受影响：" + t);
            t.printStackTrace();
            return false;
        }
    }

    /**
     * 从本 mod 的<b>另一个</b>界面切换到本界面。
     *
     * <p><b>优先就地切换</b>：如果玩家已经开着本 mod 的界面、且槽位数一致，
     * 就直接把这个菜单的内容换成新界面，<b>屏幕不关也不重开</b>。
     *
     * <p>这一点直接决定手感。关掉再开会让客户端在同一个 tick 里
     * 先收关闭包、再收打开包 —— 屏幕被释放又重新抓取鼠标，
     * 玩家的<b>光标会被甩到屏幕中间</b>，还得重新挪回去才能点。
     * 只换内容不换屏幕就不会有这个问题，也没有关闭带来的开销。
     *
     * <p>槽位数对不上（跨尺寸跳）时才退回"关掉再开"。
     *
     * @return 切换成功返回 true
     */
    public final boolean switchTo(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        if (player.containerMenu instanceof McooChestMenu menu && menu.retargetTo(this, player)) {
            return true;
        }
        try {
            if (player.containerMenu instanceof McooChestMenu) {
                // 跨尺寸切换只能关掉再开。
                // ServerPlayer.closeContainer() 会发关闭包并调用菜单的 removed()，
                // 所以关闭回调（清页码）和原版对"光标上物品"的处理都会正常走一遍。
                player.closeContainer();
            }
            return open(player);
        } catch (Throwable t) {
            System.err.println("[mcoo] 切换界面失败（" + getClass().getSimpleName()
                    + "），服务器不受影响：" + t);
            t.printStackTrace();
            return false;
        }
    }

    /**
     * 给玩家打开这个界面。
     *
     * <p><b>每人同时只允许开一个本 mod 的界面</b>：已经在界面里时直接返回 false，
     * 不重复开。这样玩家 spamming 指令也只会看到最后一个界面，
     * 不会出现"两个界面叠在一起、点哪个都不知道"的乱局。
     * 要在一个界面里跳到另一个界面（多级菜单），用 {@link #switchTo}。
     *
     * <p>这里刻意**不用静态集合记录谁开着界面** —— 那个状态在玩家断线、
     * 崩服、reload 时都会失准。直接问服务器"他现在是不是已经在本 mod 的界面里"，
     * 永远是真的。
     *
     * @return 成功打开返回 true；已有一个界面 / 摆界面失败返回 false
     */
    public final boolean open(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        try {
            if (player.containerMenu instanceof McooChestMenu) {
                player.sendSystemMessage(Component.literal("请先关闭当前界面"));
                return false;
            }

            int rows = rows();
            GuiLayout.validateRows(rows);

            // 容器先建好再交给菜单：菜单构造完就把槽位绑到这个容器上了，
            // 之后想换内容只能原地改（见 refresh）。
            SimpleContainer container = buildContent(player);

            SimpleMenuProvider provider = new SimpleMenuProvider(
                    (syncId, playerInventory, ignored) -> new McooChestMenu(
                            menuType(),
                            syncId,
                            playerInventory,
                            container,
                            rows,
                            this,
                            this::handleClosed),
                    title());

            player.openMenu(provider);
            return true;

        } catch (Throwable t) {
            // 整个开界面流程包死：功能写的界面代码崩了，
            // 最多是这次开不了界面，服务器和功能本身都不受影响。
            System.err.println("[mcoo] 打开界面失败（" + getClass().getSimpleName()
                    + "），服务器不受影响：" + t);
            t.printStackTrace();
            return false;
        }
    }

    /**
     * 点击路由的落点。菜单已经把点击拦下来了，这里只负责转交给功能。
     *
     * <p><b>注意 {@code player} 可能是 null</b>：自检是在没有真玩家的情况下
     * 模拟点击的，直接在这里把 null 拦掉的话，自检就<b>测不到真实路径</b>了
     * —— 它只能验证"菜单有没有把点击拦下来"，验证不了"拦下来之后有没有正确转交"。
     * 两条都得验，所以这里透传 null，由 {@code onClick} 自己去防
     * （和 {@code layout(canvas, viewer)} 的 viewer 可能是 null 是同一回事）。
     *
     * <p>再次包一层兜底：菜单那边已经包过一次，这里是给"功能返回回复文字"
     * 这段再加一道，避免功能在组装回复时炸掉把整个点击流程带走。
     */
    String handleClick(String action, net.minecraft.world.entity.player.Player rawPlayer) {
        ServerPlayer player = rawPlayer instanceof ServerPlayer sp ? sp : null;
        try {
            return onClick(action, player);
        } catch (Throwable t) {
            System.err.println("[mcoo] 功能处理点击失败（" + getClass().getSimpleName()
                    + "，动作 " + action + "）：" + t);
            t.printStackTrace();
            return "操作失败";
        }
    }

    private void handleClosed(net.minecraft.world.entity.player.Player rawPlayer, boolean interacted) {
        if (rawPlayer instanceof ServerPlayer player) {
            try {
                onClosed(player, interacted);
            } catch (Throwable t) {
                System.err.println("[mcoo] 界面关闭回调失败（" + getClass().getSimpleName()
                        + "）：" + t);
            }
        }
    }

    /** 供诊断用：界面类名。 */
    public final String guiName() {
        return getClass().getSimpleName();
    }
}
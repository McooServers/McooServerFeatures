package com.mcoo.core.gui;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * 可翻页的箱子界面 —— 固定用原版<b>大箱子</b>（6 行 54 格）。
 *
 * <p><b>为什么固定 6 行</b>：9x6 是原版支持的<b>最大</b>箱子尺寸，
 * 客户端肯定能正常渲染，不存在"客户端画不出来"的风险。
 * 想要更紧凑的界面，让功能自己在内容区留空就行 ——
 * 留空比"调小容器再让客户端想办法"安全得多。
 *
 * <h2>翻页按钮摆哪儿，由功能自己决定</h2>
 * 框架<b>不</b>预留任何一行、也不禁止功能碰哪一行。
 * 只需要摆按钮时用这几个动作 id，框架会接住点击：
 * <pre>{@code
 *   canvas.button(0, 0, PagedChestGui.ACTION_PREV, leftArrow);
 *   canvas.button(0, 8, PagedChestGui.ACTION_NEXT, rightArrow);
 * }</pre>
 *
 * <p>同一种动作可以摆任意多个格子 —— 例如把上一页摆成 3 格宽的箭头带，
 * 点哪一格都翻页，这完全没问题，框架不关心它摆了几格、在哪。
 *
 * <p>想要一个现成的底栏就调 {@link #drawDefaultBar}，不想要就自己画。
 * <b>框架不替功能决定版面</b>：版面是功能的一部分，
 * 把翻页栏钉死在某一行的做法，会让"顶部放导航"这类很常见的排版直接画不出来。
 *
 * <h2>页码是每人一份的</h2>
 * 两个人可以同时开着同一个界面停在不同的页上。
 * 状态存在按玩家 UUID 索引的表里，<b>界面关闭时立刻清掉</b>，
 * 所以玩家断线、崩服都不会留下脏数据。
 */
public abstract class PagedChestGui extends ChestGui {

    /** 固定 6 行（原版大箱子）。 */
    public static final int ROWS = 6;

    /** 一个界面最多有多少格。 */
    public static final int SLOTS = ROWS * GuiLayout.COLUMNS;

    /** 框架自己接住的翻页动作 id。功能不要用这几个前缀。 */
    public static final String ACTION_PREV = "mcoopage:prev";
    public static final String ACTION_NEXT = "mcoopage:next";
    /** 跳到指定页：{@code mcoopage:goto:3} */
    public static final String ACTION_GOTO_PREFIX = "mcoopage:goto:";

    /**
     * 每个玩家各自的当前页。key 是玩家 UUID。
     *
     * <p>用 {@code ConcurrentHashMap} 而不是普通 HashMap：
     * 界面刷新可能发生在网络线程处理点击的时刻，不加约束迟早出并发问题。
     *
     * <p><b>不会长期增长</b>：只有翻过页的玩家才会有一条记录，
     * 而关界面（{@link #onClosed}）就删掉。原版 {@code PlayerList.remove} 一定会调
     * 菜单的 {@code removed()}，玩家直接断线也算，所以不设留在表里的可能。
     */
    private final Map<UUID, Integer> currentPage = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    //  功能要实现的
    // ------------------------------------------------------------------

    /** 一共几页。至少 1 页；返回值不合法时按 1 页处理并记日志。 */
    protected abstract int pageCount();

    /**
     * 摆某一页的内容。<b>整块 54 格都能用</b>，框架不预留任何位置。
     *
     * @param page    页号，从 <b>0</b> 起
     * @param viewer  看这个界面的玩家；自检场景为 null
     */
    protected abstract void layoutPage(GuiCanvas canvas, int page, ServerPlayer viewer);

    /**
     * 功能自己的按钮点击。{@link #ACTION_PREV} / {@link #ACTION_NEXT} /
     * {@code mcoopage:goto:N} 会被框架先接住，不会走到这里。
     */
    protected abstract String onPageClick(String action, ServerPlayer player);

    // ------------------------------------------------------------------
    //  框架
    // ------------------------------------------------------------------

    @Override
    protected int rows() {
        return ROWS;
    }

    @Override
    protected void layout(GuiCanvas canvas, ServerPlayer viewer) {
        layoutPage(canvas, clampToRange(currentPageOf(viewer), safePageCount()), viewer);
    }

    @Override
    protected String onClick(String action, ServerPlayer player) {
        if (ACTION_PREV.equals(action)) {
            turnPage(player, -1);
            return null;
        }
        if (ACTION_NEXT.equals(action)) {
            turnPage(player, 1);
            return null;
        }
        if (action.startsWith(ACTION_GOTO_PREFIX)) {
            try {
                goToPage(player, Integer.parseInt(action.substring(ACTION_GOTO_PREFIX.length())));
            } catch (NumberFormatException ignored) {
                // 动作 id 是代码里写死的常量，解析不了说明写错了，
                // 但它已经由玩家点出来了，绝不能因此抛异常把界面搞崩。
                System.err.println("[mcoo] 跳页动作 id 解析失败：" + action);
            }
            return null;
        }
        return onPageClick(action, player);
    }

    /**
     * 翻页并<b>就地刷新</b>界面。
     *
     * <p>不用"关掉再打开"：那会让玩家眼前闪一下、滚动位置跳回顶部。
     *
     * @param delta 正数向后翻，负数向前翻。超界时按总数取模（循环翻页）
     * @return 真的翻了返回 true；没有玩家 / 只有一页 / 刷新失败返回 false
     */
    public final boolean turnPage(ServerPlayer player, int delta) {
        return goToPage(player, currentPageOf(player) + delta);
    }

    /**
     * 跳到指定页并就地刷新。页号超界时按总数取模（循环翻页）。
     *
     * @return 真的跳了返回 true；只有一页 / 刷新失败返回 false
     */
    public final boolean goToPage(ServerPlayer player, int page) {
        int total = safePageCount();
        if (player == null || total <= 1) {
            return false;
        }
        currentPage.put(player.getUUID(), Math.floorMod(page, total));
        if (!refresh(player)) {
            // 翻页失败：把页码退回去，别让玩家停在一个"界面根本没变"的页号上，
            // 下次点下一��会莫名连跳两页。
            currentPage.remove(player.getUUID());
            return false;
        }
        return true;
    }

    @Override
    protected void onClosed(ServerPlayer player, boolean interacted) {
        // 关界面就清掉页码：否则这张表会随着来来去去的玩家一直涨，
        // 而且玩家下次打开时会莫名其妙停在上次那一页。
        currentPage.remove(player.getUUID());
    }

    // ------------------------------------------------------------------
    //  给功能用的工具
    // ------------------------------------------------------------------

    /** 这个玩家当前在第几页（从 0 起）。 */
    public final int currentPageOf(ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        return currentPage.getOrDefault(player.getUUID(), 0);
    }

    /** 安全取页数：功能算错时不当场崩，按 1 页处理。 */
    public final int safePageCount() {
        int n;
        try {
            n = pageCount();
        } catch (Throwable t) {
            System.err.println("[mcoo] " + getClass().getSimpleName()
                    + " 的 pageCount() 抛异常了，按 1 页处理：" + t);
            return 1;
        }
        if (n < 1) {
            System.err.println("[mcoo] " + getClass().getSimpleName()
                    + " 的 pageCount() 返回了 " + n + "，至少要有 1 页，按 1 页处理");
            return 1;
        }
        return n;
    }

    /**
     * 画一个现成的底栏（上一页 / 页码 / 下一页）。
     *
     * <p><b>要不要用、摆哪、用几个，全看功能</b>。
     * 不调用它就完全没有框架自带的东西。
     *
     * @param row 底栏放在第几行
     */
    protected final void drawDefaultBar(GuiCanvas canvas, int row) {
        int total = safePageCount();
        if (total <= 1) {
            return;   // 只有一页时摆两个点不动的按钮只会让人以为坏了
        }
        int page = clampToRange(currentPageOf(null), total);
        ItemStack filler = new ItemStack(Items.STAINED_GLASS_PANE.gray());
        canvas.button(row, 0, ACTION_PREV, withText(filler.copy(), "« 上一页", null));
        canvas.button(row, 4, ACTION_GOTO_PREFIX + page,
                withText(filler.copy(), "第 " + (page + 1) + " / " + total + " 页", null));
        canvas.button(row, 8, ACTION_NEXT, withText(filler.copy(), "下一页 »", null));
    }

    private static int clampToRange(int page, int total) {
        if (page < 0) {
            return 0;
        }
        return page >= total ? total - 1 : page;
    }

    /** 给一个物品加上显示名和可选的副标题。 */
    protected static ItemStack withText(ItemStack stack, String name, String hint) {
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        if (hint != null) {
            stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(hint))));
        }
        return stack;
    }
}

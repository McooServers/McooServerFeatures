package com.mcoo.core.diag;

import com.mcoo.core.gui.ChestGui;
import com.mcoo.core.gui.GuiActionCodec;
import com.mcoo.core.gui.GuiCanvas;
import com.mcoo.core.gui.GuiLayout;
import com.mcoo.core.gui.McooChestMenu;
import com.mcoo.core.gui.PagedChestGui;
import net.minecraft.world.entity.player.Inventory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * GUI 框架自检 —— 在**真实服务器**里把整套流程跑一遍。
 *
 * <p><b>为什么这些验证不能放在单元测试里</b>：
 * 26.2 的物品是 {@code Holder.Reference}，它的数据组件要等游戏完整引导后才绑定。
 * 单元测试的 JVM 里 {@code Bootstrap.bootStrap()} 不会完成这一步，
 * 一碰 {@code new ItemStack(Items.DIAMOND)} 就是
 * {@code NullPointerException: Components not bound yet}（实测卡在这里）。
 * 真实服务器里这些全都天然就绪。
 *
 * <p>分工：
 * <ul>
 *   <li><b>单元测试</b>：纯逻辑（格子下标、行数校验、动作 id 拆分）</li>
 *   <li><b>本自检</b>：需要真 {@code ItemStack} / 真 {@code Menu} 的部分</li>
 * </ul>
 *
 * <p>本自检摆界面走的是<b>真实的 {@link ChestGui} 流程</b>（{@code buildContent()}），
 * 不是另抄一份实现 —— 抄一份去测，测的就不是原件了。
 *
 * <p><b>用法</b>：{@code .\tools\run-testserver.ps1 -JvmArgs '-Dmcoo.faultInject=gui'}
 */
public final class GuiSelfCheck {

    private final AtomicInteger pass = new AtomicInteger();
    private final AtomicInteger fail = new AtomicInteger();

    /** 供 {@link SelfCheck} 注册用的实例。 */
    public static GuiSelfCheck create() {
        return new GuiSelfCheck();
    }

    public void run() {
        say("===== GUI 框架自检开始 =====");
        try {
            检查编码往返();
            检查行数与菜单类型();
            检查摆界面();
            检查单行界面();
            检查菜单点击路由();
            检查物品拿不走();
            检查空格子吞不掉物品();
            检查分页();
        } catch (Throwable t) {
            fail("自检过程本身崩了", t);
        }
        say("===== GUI 框架自检结束：通过 " + pass.get() + " 项，失败 " + fail.get() + " 项 =====");
    }

    // ------------------------------------------------------------------
    //  一个真实走 ChestGui 流程的测试界面
    // ------------------------------------------------------------------

    private static final class TestGui extends ChestGui {
        private final int rows;
        private final String fillFirst;

        /**
         * @param rows 行数，<b>必须 ≥ 3</b>
         */
        TestGui(int rows, String fillFirst) {
            // 本夹具的 layout() 固定会摆第 1 行和第 2 行的按钮。
            // 这条限制在构造时就查，而不是等 layout() 跑到 canvas.button(2, 0, ...)
            // 才炸 —— 后者报的是「按钮摆到了界面外面」，读起来像是框架的锅，
            // 实际上只是夹具自己配错了行数。两种报错指向完全不同的结论，
            // 混在一起会让人去查错地方。
            if (rows < 3) {
                throw new IllegalArgumentException(
                        "TestGui 固定会摆第 1、2 行的按钮，所以 rows 至少要 3，实测 " + rows);
            }
            this.rows = rows;
            this.fillFirst = fillFirst;
        }

        @Override
        protected Component title() {
            return Component.literal("GUI 自检");
        }

        @Override
        protected int rows() {
            return rows;
        }

        @Override
        protected void layout(GuiCanvas canvas, net.minecraft.server.level.ServerPlayer viewer) {
            if ("先填".equals(fillFirst)) {
                canvas.fill(new ItemStack(Items.STONE));
            }
            canvas.button(1, 4, "claim:day1", new ItemStack(Items.DIAMOND));
            canvas.button(2, 0, "claim:day2", new ItemStack(Items.EMERALD));
            if ("后填".equals(fillFirst)) {
                canvas.fill(new ItemStack(Items.STONE));
            }
        }

        @Override
        protected String onClick(String action, net.minecraft.server.level.ServerPlayer player) {
            return "收到 " + action;
        }
    }

    // ------------------------------------------------------------------

    private void 检查编码往返() {
        try {
            ItemStack button = new ItemStack(Items.DIAMOND);
            GuiActionCodec.write(button, "claim:day1");

            var read = GuiActionCodec.read(button);
            if (read.isPresent() && read.get().equals("claim:day1")) {
                pass("动作编码往返：写入 claim:day1 能原样读回");
            } else {
                fail("动作编码往返：读回的是 " + read, null);
            }

            if (GuiActionCodec.read(new ItemStack(Items.EMERALD)).isEmpty()) {
                pass("普通物品不会被误认成按钮");
            } else {
                fail("普通物品被误认成按钮", null);
            }

            if (GuiActionCodec.read(ItemStack.EMPTY).isEmpty()) {
                pass("空槽不会被误认成按钮");
            } else {
                fail("空槽被误认成按钮", null);
            }
        } catch (Throwable t) {
            fail("动作编码往返", t);
        }
    }

    private void 检查摆界面() {
        try {
            // 两种顺序都要能保住按钮 —— 功能里"先铺背景"和"先摆按钮"都可能出现，
            // 不该让功能被迫记住顺序。
            SimpleContainer a = new TestGui(3, "先填").buildContent();
            if (GuiActionCodec.read(a.getItem(GuiLayout.index(1, 4)))
                    .map("claim:day1"::equals).orElse(false)) {
                pass("先铺背景再摆按钮：按钮没被覆盖");
            } else {
                fail("先铺背景时按钮被覆盖了", null);
            }

            SimpleContainer b = new TestGui(3, "后填").buildContent();
            if (GuiActionCodec.read(b.getItem(GuiLayout.index(1, 4)))
                    .map("claim:day1"::equals).orElse(false)) {
                pass("先摆按钮再铺背景：按钮也没被覆盖（顺序无关）");
            } else {
                fail("铺背景把已有按钮覆盖了", null);
            }

            // 摆到界面外必须当场报错
            try {
                new ChestGui() {
                    @Override protected Component title() { return Component.literal("x"); }
                    @Override protected int rows() { return 1; }
                    @Override protected void layout(GuiCanvas canvas, net.minecraft.server.level.ServerPlayer viewer) {
                        canvas.button(3, 0, "bad", new ItemStack(Items.DIAMOND));
                    }
                    @Override protected String onClick(
                            String a, net.minecraft.server.level.ServerPlayer p) { return null; }
                }.buildContent();
                fail("按钮摆到界面外却没有报错", null);
            } catch (IllegalArgumentException expected) {
                pass("按钮摆到界面外：当场报错，不会静默丢失");
            }

            // 摆界面时功能自己抛异常，必须原样冒出来（不能被吞掉）
            try {
                new ChestGui() {
                    @Override protected Component title() { return Component.literal("x"); }
                    @Override protected int rows() { return 1; }
                    @Override protected void layout(GuiCanvas canvas, net.minecraft.server.level.ServerPlayer viewer) {
                        throw new IllegalStateException("摆界面时故意炸");
                    }
                    @Override protected String onClick(
                            String a, net.minecraft.server.level.ServerPlayer p) { return null; }
                }.buildContent();
                fail("摆界面抛异常却被吞掉了", null);
            } catch (IllegalStateException expected) {
                pass("摆界面时功能抛异常：原样冒出来，由 open() 负责兜底");
            }
        } catch (Throwable t) {
            fail("摆界面", t);
        }
    }

    /**
     * 用一个空 {@code Inventory} 构造菜单。
     *
     * <p>{@code Inventory} 的构造只需要一个 {@code Player} 引用，
     * 而 {@code ChestMenu} 构造过程只读它的物品列表，不碰玩家本身，
     * 所以传 null 能把菜单建起来 —— 这样就能在没有真玩家的情况下测点击路由。
     */
    private void 检查菜单点击路由() {
        SimpleContainer container = new TestGui(3, "先填").buildContent();
        AtomicReference<String> lastAction = new AtomicReference<>("<没收到任何点击>");
        AtomicInteger clickCount = new AtomicInteger();
        AtomicInteger closedCalled = new AtomicInteger();

        McooChestMenu menu;
        try {
            Inventory fakeInventory = new Inventory(null, null);
            menu = new McooChestMenu(
                    MenuType.GENERIC_9x3, 1, fakeInventory, container, 3,
                    new CallbackGui((action, player) -> {
                        clickCount.incrementAndGet();
                        lastAction.set(action);
                        return "回复：" + action;
                    }),
                    (player, interacted) -> closedCalled.incrementAndGet());
        } catch (Throwable t) {
            fail("构造菜单（需要一个能传 null 玩家的 Inventory）", t);
            return;
        }

        if (menu.guiSlotCount() == 27) {
            pass("菜单用原版 MenuType 构造成功，槽位数 27");
        } else {
            fail("槽位数不对：" + menu.guiSlotCount(), null);
        }

        // 模拟玩家点击第一个按钮
        menu.clicked(GuiLayout.index(1, 4), 0, ContainerInput.PICKUP, null);
        if ("claim:day1".equals(lastAction.get()) && clickCount.get() == 1) {
            pass("点击按钮 → 动作正确路由到功能（claim:day1）");
        } else {
            fail("点击路由到了错误的地方：" + lastAction.get(), null);
        }

        // 点空格子（背景）：不崩、不路由
        int before = clickCount.get();
        menu.clicked(GuiLayout.index(0, 0), 0, ContainerInput.PICKUP, null);
        if (clickCount.get() == before) {
            pass("点背景格：不崩，也不误触发任何动作");
        } else {
            fail("点背景格却触发了动作", null);
        }

        // Shift+点击容器区：必须被拒绝
        ItemStack moved = menu.quickMoveStack(null, GuiLayout.index(1, 4));
        if (moved.isEmpty()) {
            pass("Shift+点击按钮：被拒绝，物品拿不走");
        } else {
            fail("Shift+点击把按钮物品搬走了：" + moved, null);
        }

        // 非 PICKUP 的点击类型全部忽略
        before = clickCount.get();
        menu.clicked(GuiLayout.index(1, 4), 0, ContainerInput.SWAP, null);
        menu.clicked(GuiLayout.index(1, 4), 0, ContainerInput.QUICK_MOVE, null);
        menu.clicked(GuiLayout.index(1, 4), 0, ContainerInput.QUICK_CRAFT, null);
        if (clickCount.get() == before) {
            pass("拖拽 / 快速合成 / Shift 点击：全部被忽略");
        } else {
            fail("非 PICKUP 的点击被当成了按钮点击", null);
        }

        // 关闭界面
        menu.removed(null);
        if (container.getItem(GuiLayout.index(1, 4)).isEmpty()) {
            pass("关闭界面后容器被清空（按钮不会留在原地）");
        } else {
            fail("关闭界面后容器没清空", null);
        }
        if (closedCalled.get() == 1) {
            pass("关闭回调被调用了 1 次");
        } else {
            fail("关闭回调被调用了 " + closedCalled.get() + " 次", null);
        }
    }

    // ------------------------------------------------------------------

    /**
     * 指定行数的空界面，用来验行数校验和 MenuType 映射。
     *
     * <p>{@link ChestGui#menuType()} 是 protected 且在别的包，
     * 所以自检不能直接调它 —— 只能由子类在自己的代码里调 {@code menuType()} 再暴露出来。
     */
    private static final class RowCountGui extends ChestGui {
        private final int rows;

        RowCountGui(int rows) {
            this.rows = rows;
        }

        @Override protected Component title() { return Component.literal("行数自检"); }
        @Override protected int rows() { return rows; }
        /** 故意什么都不摆 —— 这一项验的是行数，不是布局。 */
        @Override protected void layout(GuiCanvas canvas, net.minecraft.server.level.ServerPlayer viewer) { }
        @Override protected String onClick(String a, net.minecraft.server.level.ServerPlayer p) { return null; }

        MenuType<?> myMenuType() {
            return menuType();
        }
    }

    /** 一行的最小界面：只在第 0 行摆一个按钮。 */
    private static final class OneRowGui extends ChestGui {
        @Override protected Component title() { return Component.literal("单行自检"); }
        @Override protected int rows() { return 1; }
        @Override protected void layout(GuiCanvas canvas, net.minecraft.server.level.ServerPlayer viewer) {
            canvas.button(0, 4, "only:row1", new ItemStack(Items.DIAMOND));
        }
        @Override protected String onClick(String a, net.minecraft.server.level.ServerPlayer p) {
            return "收到 " + a;
        }

        MenuType<?> myMenuType() {
            return menuType();
        }
    }

    // ------------------------------------------------------------------

    /**
     * 行数 1~6 都要映射到对的原版 MenuType，0 和 7 必须当场报错。
     *
     * <p>这一段看着琐碎，但它守的是「仅服务端 mod 也能开 GUI」的全部依据：
     * MenuType 映射错了，客户端就会按错误的尺寸渲染这个界面。
     */
    private void 检查行数与菜单类型() {
        MenuType<?>[] expected = {
                MenuType.GENERIC_9x1, MenuType.GENERIC_9x2, MenuType.GENERIC_9x3,
                MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6,
        };
        boolean allMatched = true;
        for (int r = 1; r <= 6; r++) {
            MenuType<?> actual = new RowCountGui(r).myMenuType();
            if (actual != expected[r - 1]) {
                allMatched = false;
                System.out.println("[mcoo][gui自检] " + r + " 行映射成了 " + actual + "，应为 " + expected[r - 1]);
            }
        }
        if (allMatched) {
            pass("行数 1~6 全部映射到对应的原版 MenuType（客户端能按正确尺寸渲染）");
        } else {
            fail("行数到 MenuType 的映射有错（见上面逐行输出）", null);
        }

        for (int bad : new int[] {0, 7}) {
            try {
                new RowCountGui(bad).myMenuType();
                fail("行数 " + bad + " 非法却没有报错", null);
            } catch (IllegalArgumentException expected2) {
                pass("行数 " + bad + " 非法：当场报错，不会静默夹到合法值");
            } catch (Throwable t) {
                fail("行数 " + bad + " 非法时的报错类型不对：" + t, null);
            }
        }
    }

    /**
     * 1 行界面走通全流程。
     *
     * <p>最小的一种界面最容易出边界问题（9 槽、按钮下标 4 正好在正中间），
     * 而且它对应的 {@code GENERIC_9x1} 是所有行数里玩家最常用的形态。
     */
    private void 检查单行界面() {
        try {
            OneRowGui gui = new OneRowGui();
            SimpleContainer container = gui.buildContent();

            if (container.getContainerSize() == 9) {
                pass("1 行界面：容器正好 9 槽");
            } else {
                fail("1 行界面的容器是 " + container.getContainerSize() + " 槽，应为 9", null);
            }

            if (GuiActionCodec.read(container.getItem(4))
                    .map("only:row1"::equals).orElse(false)) {
                pass("1 行界面：按钮摆在中格（第 0 行第 4 列）");
            } else {
                fail("1 行界面的按钮没摆对位置", null);
            }

            if (gui.myMenuType() == MenuType.GENERIC_9x1) {
                pass("1 行界面：用原版 GENERIC_9x1 打开");
            } else {
                fail("1 行界面挑错了 MenuType：" + gui.myMenuType(), null);
            }

            AtomicReference<String> got = new AtomicReference<>("<没收到点击>");
            McooChestMenu menu = new McooChestMenu(
                    MenuType.GENERIC_9x1, 1, new Inventory(null, null), container, 1,
                    new CallbackGui((action, player) -> {
                        got.set(action);
                        return null;
                    }),
                    null);
            menu.clicked(4, 0, ContainerInput.PICKUP, null);
            if ("only:row1".equals(got.get())) {
                pass("1 行界面：点击按钮正确路由");
            } else {
                fail("1 行界面的点击路由到了 " + got.get(), null);
            }
        } catch (Throwable t) {
            fail("单行界面", t);
        }
    }

    private void 检查物品拿不走() {
        try {
            // 用 3 行而不是 1 行：TestGui 固定摆第 1、2 行的按钮。
            // 这一项验的是「槽位只读」，跟有几行无关。
            SimpleContainer container = new TestGui(3, "不填").buildContent();
            Inventory fakeInventory = new Inventory(null, null);
            McooChestMenu menu = new McooChestMenu(
                    MenuType.GENERIC_9x3, 1, fakeInventory, container, 3,
                    new CallbackGui((a, p) -> null), null);

            var slot = menu.slots.get(0);
            if (!slot.mayPickup(null) && !slot.allowModification(null)) {
                pass("界面槽位是只读的：拿不走也放不进");
            } else {
                fail("界面槽位可以被拿取/修改", null);
            }
        } catch (Throwable t) {
            fail("只读槽位", t);
        }
    }

    // ------------------------------------------------------------------

    /**
     * 一个什么都不摆、只把点击转给一个 lambda 的空壳界面。
     *
     * <p>菜单构造时需要一个 {@link ChestGui}（而不是一个函数指针），
     * 这样自检想验证"点击有没有正确路由"时，就不用给每种界面都写一个真版面。
     */
    private static final class CallbackGui extends ChestGui {
        private final java.util.function.BiFunction<String, ServerPlayer, String> handler;

        CallbackGui(java.util.function.BiFunction<String, ServerPlayer, String> handler) {
            this.handler = handler;
        }

        @Override protected Component title() { return Component.literal("回调壳"); }
        @Override protected int rows() { return 1; }
        @Override
        protected void layout(GuiCanvas canvas, ServerPlayer viewer) {
            // 由调用方自己往 container 里摆东西，这里什么都不做
        }
        @Override protected String onClick(String action, ServerPlayer player) {
            return handler.apply(action, player);
        }
    }

    // ------------------------------------------------------------------

    /** 分页测试界面：页数可调，翻页按钮自己摆在指定的行上。 */
    private static final class PagedTestGui extends PagedChestGui {
        private final int pages;
        private final int navRow;

        PagedTestGui(int pages, int navRow) {
            this.pages = pages;
            this.navRow = navRow;
        }

        @Override protected Component title() { return Component.literal("分页自检"); }

        @Override protected int pageCount() {
            return pages;
        }

        @Override
        protected void layoutPage(GuiCanvas canvas, int page,
                                  net.minecraft.server.level.ServerPlayer viewer) {
            canvas.button(2, 4, "page:item:" + page, new ItemStack(Items.PAPER));
            if (navRow >= 0) {
                canvas.button(navRow, 0, ACTION_PREV, new ItemStack(Items.ARROW));
                canvas.button(navRow, 8, ACTION_NEXT, new ItemStack(Items.ARROW));
            }
        }

        @Override
        protected String onPageClick(String action,
                                     net.minecraft.server.level.ServerPlayer player) {
            return action;
        }
    }

    // ------------------------------------------------------------------

    /**
     * 分页框架的结构性检查。
     *
     * <p><b>边界（必须诚实说明）</b>：这里验的是"版面和动作编码摆得对不对"，
     * <b>不是</b>"点下一页真的翻过去了" —— 那一步要真的玩家点，自动验不了。
     */
    private void 检查分页() {
        // 1. 固定 6 行 → 原版大箱子
        PagedTestGui gui = new PagedTestGui(3, 5);
        SimpleContainer container = gui.buildContent();
        if (container.getContainerSize() == 54) {
            pass("分页界面固定 6 行 = 54 格（对应原版大箱子）");
        } else {
            fail("分页界面的容器是 " + container.getContainerSize() + " 格，应为 54", null);
        }

        // 2. 框架【不】自带任何翻页按钮 —— 版面完全交给功能决定。
        //    这条很重要：一旦框架自作主张摆东西，功能想放顶部导航就画不出来。
        //    （夹具自己在第 2 行摆了个内容按钮，所以这里只查"有没有多出翻页按钮"，
        //      而不是查整格为空 —— 那样会把功能自己摆的东西也算成框架的锅。）
        SimpleContainer bare = new PagedTestGui(3, -1).buildContent();
        boolean strayed = false;
        for (int i = 0; i < 54; i++) {
            String act = GuiActionCodec.read(bare.getItem(i)).orElse("");
            if (act.equals(PagedChestGui.ACTION_PREV) || act.equals(PagedChestGui.ACTION_NEXT)
                    || act.startsWith(PagedChestGui.ACTION_GOTO_PREFIX)) {
                strayed = true;
                System.out.println("[mcoo][gui自检]   框架多摆了格子 " + i + "： " + act);
            }
        }
        if (!strayed) {
            pass("框架不自作主张摆翻页栏：没调 drawDefaultBar 就一个翻页按钮都没有");
        } else {
            fail("框架擅自摆了翻页按钮，功能无法控制版面", null);
        }

        // 3. 翻页按钮摆哪一行都行 —— 包括第 0 行（顶部导航）。
        //    这条对应"一格变三格的大箭头带"这种排版。
        for (int row : new int[] {0, 3, 5}) {
            SimpleContainer c = new PagedTestGui(3, row).buildContent();
            boolean ok = PagedChestGui.ACTION_PREV.equals(
                    GuiActionCodec.read(c.getItem(GuiLayout.index(row, 0))).orElse(""))
                    && PagedChestGui.ACTION_NEXT.equals(
                            GuiActionCodec.read(c.getItem(GuiLayout.index(row, 8))).orElse(""));
            if (ok) {
                pass("翻页按钮可以摆在第 " + row + " 行（0=顶部导航、5=底部工具栏都行）");
            } else {
                fail("翻页按钮摆在第 " + row + " 行时动作 id 不对", null);
            }
        }

        // 4. 真的摆第 6 行（越界）仍然要报错 —— 放宽的是"占哪行"，不是"能摆多大"
        try {
            new PagedTestGui(3, 6).buildContent();
            fail("翻页按钮摆到第 6 行（越界）却没有报错", null);
        } catch (IllegalArgumentException expected) {
            pass("摆到第 6 行（越界）当场报错：放宽的是位置，不是尺寸");
        } catch (Throwable t) {
            fail("越界时报的错类型不对：" + t, null);
        }

        // 5. pageCount() 返回非法值时不当场崩，按 1 页处理
        for (int bad : new int[] {0, -3}) {
            try {
                SimpleContainer c = new PagedTestGui(bad, 5).buildContent();
                if (c.getContainerSize() == 54 && new PagedTestGui(bad, 5).safePageCount() == 1) {
                    pass("pageCount() 返回 " + bad + "：不当场崩，退化成 1 页");
                } else {
                    fail("pageCount() 返回 " + bad + " 时没有正确退化", null);
                }
            } catch (Throwable t) {
                fail("pageCount() 返回 " + bad + " 时直接崩了：" + t, null);
            }
        }

        // 6. 没有玩家时翻页必须干净地返回 false，不能抛异常
        boolean turned = gui.turnPage(null, 1);
        if (!turned) {
            pass("没有玩家时 turnPage() 返回 false，不抛异常");
        } else {
            fail("没有玩家时 turnPage() 返回了 true", null);
        }

        // 7. 只有一页时翻页应当无效（否则会出现"点了没反应又像坏了"的情况）
        boolean singleTurn = new PagedTestGui(1, 5).goToPage(null, 1);
        if (!singleTurn) {
            pass("只有 1 页时翻页无效，不会出现点不动的死按钮");
        } else {
            fail("只有 1 页却允许翻页", null);
        }

        // 8. 刷新接口在"玩家没开界面"时必须干净地返回 false
        if (!gui.refresh(null)) {
            pass("玩家没开界面时 refresh() 返回 false，不抛异常");
        } else {
            fail("玩家没开界面时 refresh() 返回了 true", null);
        }
    }

    // ------------------------------------------------------------------

    // ------------------------------------------------------------------

    /**
     * <b>空格子会不会把玩家的物品吞掉</b>。
     *
     * <p>这条单独拿出来，是因为它和「有物品的格子拿不走」是<b>两回事</b>：
     * 前者防的是白嫖按钮，后者防的是<b>玩家丢东西</b>。
     * 后者后果严重得多，而且"空格子"这种最容易被排版时忽略。
     *
     * <p>实测方式：把物品放到光标上（模拟玩家从背包捡起东西），再点空格子 ——
     * 物品既不该进格子，也不该消失，更不该从光标上凭空不见。
     */
    private void 检查空格子吞不掉物品() {
        try {
            SimpleContainer container = new SimpleContainer(9);
            McooChestMenu menu = new McooChestMenu(
                    MenuType.GENERIC_9x1, 1, new Inventory(null, null), container, 1,
                    new CallbackGui((a, p) -> null), null);

            int emptySlot = 0;
            if (!container.getItem(emptySlot).isEmpty()) {
                fail("空格子测试的起点就不对：槽位 0 本该是空的", null);
                return;
            }

            // 模拟玩家从背包捡起一个物品握在光标上
            menu.setCarried(new ItemStack(Items.DIAMOND));

            menu.clicked(emptySlot, 0, ContainerInput.PICKUP, null);   // 左键
            menu.clicked(emptySlot, 1, ContainerInput.PICKUP, null);   // 右键
            ItemStack moved = menu.quickMoveStack(null, emptySlot);    // Shift+左键
            menu.clicked(emptySlot, 0, ContainerInput.QUICK_CRAFT, null);
            menu.clicked(emptySlot, 0, ContainerInput.SWAP, null);

            boolean slotStillEmpty = container.getItem(emptySlot).isEmpty();
            boolean stillOnCursor = !menu.getCarried().isEmpty()
                    && menu.getCarried().getCount() == 1;
            boolean shiftMovedNothing = moved.isEmpty();

            if (slotStillEmpty && stillOnCursor && shiftMovedNothing) {
                pass("空格子吞不掉物品：点/拖/Shift 都不动，物品安全地留在光标上");
            } else {
                fail("空格子弄丢了东西：槽位仍空=" + slotStillEmpty
                        + "，光标还在=" + stillOnCursor
                        + "，Shift 没搬走=" + shiftMovedNothing, null);
            }

            // 槽位本身也必须锁死 —— 第三道独立的防线，
            // 万一前两道哪天被改坏了，这一道还能兜住。
            var slot = menu.slots.get(emptySlot);
            if (!slot.mayPickup(null) && !slot.allowModification(null)) {
                pass("空格子同样是只读槽位（第三道防线：mayPickup / allowModification 都为 false）");
            } else {
                fail("空格子居然可以拿取/修改", null);
            }

            menu.setCarried(ItemStack.EMPTY);
        } catch (Throwable t) {
            fail("空格子吞不掉物品", t);
        }
    }

    private void pass(String what) {
        pass.incrementAndGet();
        System.out.println("[mcoo][gui自检] [通过] " + what);
    }

    private void fail(String what, Throwable t) {
        fail.incrementAndGet();
        System.out.println("[mcoo][gui自检] [失败] " + what + (t == null ? "" : "：" + t));
        if (t != null) {
            t.printStackTrace();
        }
    }

    private static void say(String text) {
        System.out.println("[mcoo][gui自检] " + text);
    }
}

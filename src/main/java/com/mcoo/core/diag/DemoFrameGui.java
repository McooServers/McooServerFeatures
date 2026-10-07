package com.mcoo.core.diag;

import com.mcoo.core.gui.ChestGui;
import com.mcoo.core.gui.GuiCanvas;
import com.mcoo.core.gui.PagedChestGui;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>临时工具</b> —— 所有演示界面的共用版面 + 六个演示界面。
 *
 * <h2>版面（服主设计，已通过 {@code /mcoo gui dump} 定稿）</h2>
 * <pre>
 *   行0  «««      [自己的头] [返回上一页 / 版本] [开服天数]      »»»
 *   行1  ═══════════════════════════════════════        ← 边框
 *   行2  ║   功能入口（左对齐，从第 1 列开始）        ║
 *   行3  ║                                        ║
 *   行4  ║                                        ║
 *   行5  ═══════════════════════════════════════
 * </pre>
 *
 * <h2>六个界面其实只有一套版面</h2>
 * 差别只有三项：<b>边框颜色</b>、<b>顶栏正中间那格</b>、<b>内容区摆什么</b>。
 *
 * <pre>
 *   玩家 一级 = 黑    玩家 二级 = 淡蓝   玩家 三级 = 蓝
 *   管理 一级 = 红    管理 二级 = 黄     管理 三级 = 红
 * </pre>
 *
 * <p>颜色只帮玩家一眼分清"我在哪一层、这是玩家还是管理界面"，不承担功能含义。
 */
public abstract class DemoFrameGui extends PagedChestGui {

    /** 内容区：第 2~4 行 × 第 1~7 列。 */
    private static final int CONTENT_FIRST_ROW = 2;
    private static final int CONTENT_LAST_ROW = 4;
    private static final int CONTENT_FIRST_COL = 1;

    /**
     * 上一个界面。返回按钮回到<b>它</b>，而不是回主页 ——
     * 多级菜单的「返回」应该往上退一级，直接跳回主页会让玩家丢上下文。
     */
    private final ChestGui parent;

    private final boolean admin;
    private final int level;

    protected DemoFrameGui(ChestGui parent, boolean admin, int level) {
        this.parent = parent;
        this.admin = admin;
        this.level = level;
    }

    /** 返回上一级。没有上一级就关掉界面。 */
    public boolean goBack(ServerPlayer player) {
        if (parent == null) {
            player.closeContainer();
            return true;
        }
        return parent.switchTo(player);
    }

    @Override
    protected Component title() {
        return Component.literal((admin ? "管理" : "玩家") + "界面 " + level + " 级");
    }

    @Override
    protected int pageCount() {
        return 3;
    }

    @Override
    protected void layoutPage(GuiCanvas canvas, int page, ServerPlayer viewer) {
        // 1. 铺淡灰底色。fill 只填空槽，所以摆的顺序不影响已放好的东西。
        canvas.fill(new ItemStack(Items.STAINED_GLASS_PANE.lightGray()));
        // 2. 画边框（盖在底色上）
        drawBorder(canvas, borderItem());
        // 3. 顶栏
        drawHeader(canvas, page, viewer);
    }

    /** 边框颜色：玩家黑/淡蓝/蓝，管理红/黄/红。 */
    protected abstract Item borderItem();

    /**
     * 外框：第 1 行和第 5 行整行，两侧竖边在第 2~4 行的首末列。
     */
    private void drawBorder(GuiCanvas canvas, Item border) {
        for (int col = 0; col < 9; col++) {
            canvas.place(1, col, new ItemStack(border));
            canvas.place(5, col, new ItemStack(border));
        }
        for (int row = CONTENT_FIRST_ROW; row <= CONTENT_LAST_ROW; row++) {
            canvas.place(row, 0, new ItemStack(border));
            canvas.place(row, 8, new ItemStack(border));
        }
    }

    private void drawHeader(GuiCanvas canvas, int page, ServerPlayer viewer) {
        // 箭头做成 3 格宽：点哪一格都翻页，好按。
        for (int col = 0; col <= 2; col++) {
            canvas.button(0, col, ACTION_PREV,
                    named(new ItemStack(Items.STAINED_GLASS_PANE.lime()),
                            "« 上一页", "点这三个格子都能翻"));
        }
        for (int col = 6; col <= 8; col++) {
            canvas.button(0, col, ACTION_NEXT,
                    named(new ItemStack(Items.STAINED_GLASS_PANE.orange()),
                            "下一页 »", "点这三个格子都能翻"));
        }

        // 第 3 格：当前操作者自己的头颅，带自己的皮肤
        canvas.button(0, 3, "demo:who",
                named(headOf(viewer), "你的名字",
                        viewer == null ? "（自检场景没有玩家）" : viewer.getName().getString()));

        // 第 4 格：一级显示版本号，二级/三级显示「返回上一页」
        if (level == 1) {
            canvas.button(0, 4, "demo:version",
                    named(new ItemStack(Items.AMETHYST_SHARD), "轻松维护 v0.0", "显示当前 mod 版本"));
        } else {
            canvas.button(0, 4, "back",
                    named(new ItemStack(Items.GRASS_BLOCK), "返回上一页", "点我退回上一级"));
        }

        canvas.button(0, 5, "demo:uptime",
                named(new ItemStack(Items.CREEPER_BANNER_PATTERN), "开服天数",
                        "示例数据 —— 真功能里会算真实的"));
    }

    // ------------------------------------------------------------------

    /** 入口一律<b>左对齐</b>（服主定的），从第 2 行第 1 列往下排。 */
    protected static void entry(GuiCanvas canvas, int offset, String action,
                                String name, String hint, Item icon) {
        int index = CONTENT_FIRST_COL + offset;
        canvas.button(CONTENT_FIRST_ROW + index / 7, index % 7, action,
                named(new ItemStack(icon), name, hint));
    }

    /**
     * 一颗带<b>真实皮肤</b>的头颅。
     *
     * <p>在线玩家用自己的 {@code GameProfile}（皮肤一定准）；
     * 白名单里还没上线的玩家用「未解析」头颅 —— 客户端自己去查皮肤，
     * 服务端这边不下载、不缓存任何皮肤文件。
     */
    protected static ItemStack headOf(ServerPlayer viewer) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        if (viewer != null) {
            head.set(DataComponents.PROFILE,
                    ResolvableProfile.createResolved(viewer.getGameProfile()));
        }
        return head;
    }

    protected static ItemStack headOf(String playerName) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        if (playerName != null && !playerName.isBlank()) {
            head.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(playerName));
        }
        return head;
    }

    protected static ItemStack named(ItemStack stack, String name, String hint) {
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        stack.set(DataComponents.LORE,
                new ItemLore(List.of(Component.literal(hint))));
        return stack;
    }

    /** 白名单里的全部玩家名（排序后，保证每次摆出来的顺序一样）。 */
    protected static List<String> whitelistNames(MinecraftServer server) {
        String[] raw = server.getPlayerList().getWhiteListNames();
        return raw == null ? List.of()
                : Arrays.stream(raw).sorted(Comparator.naturalOrder()).toList();
    }

    /** 点头颅这类公共按钮时的统一回复，避免每个界面各写一遍。 */
    protected static String commonReply(String action, ServerPlayer player, DemoFrameGui self) {
        return switch (action) {
            case "back" -> self.goBack(player) ? null : "退不回去，详见服务端日志";
            case "demo:who" -> "你叫 " + player.getName().getString();
            case "demo:version" -> "这是 mod 版本号所在的位置";
            case "demo:uptime" -> "开服天数（示例数据）";
            default -> null;
        };
    }

    // ==================================================================
    //  六个演示界面
    // ==================================================================

    /** 点击计数。同一局游戏内跨开关保留。 */
    private static final AtomicInteger CLICKS = new AtomicInteger();

    public static int clicks() {
        return CLICKS.get();
    }

    /** 玩家一级（黑）。 */
    public static final class PlayerL1 extends DemoFrameGui {
        public PlayerL1() {
            super(null, false, 1);
        }

        @Override protected Item borderItem() { return Items.STAINED_GLASS_PANE.black(); }

        @Override
        protected void layoutPage(GuiCanvas canvas, int page, ServerPlayer viewer) {
            super.layoutPage(canvas, page, viewer);
            entry(canvas, 0, "go:l2", "礼物", "点我进二级窗口", Items.CHEST);
            entry(canvas, 1, "go:l2", "邮箱", "点我进二级窗口",
                    Items.COPPER_CHEST.waxed().unaffected());
            entry(canvas, 2, "go:l2", "占位入口 3", "演示用", Items.BOOK);
            entry(canvas, 3, "go:l2", "占位入口 4", "演示用", Items.BOOK);
        }

        @Override
        protected String onPageClick(String action, ServerPlayer player) {
            if ("go:l2".equals(action)) {
                return new PlayerL2(this).switchTo(player) ? null : "进不了二级界面";
            }
            String common = commonReply(action, player, this);
            if (common != null) {
                return common;
            }
            int n = CLICKS.incrementAndGet();
            boolean ok = refresh(player);
            return "点击 +1，第 " + n + " 次。" + (ok ? "（页面已原地刷新）" : "（刷新失败）");
        }
    }

    /** 玩家二级（淡蓝）。 */
    public static final class PlayerL2 extends DemoFrameGui {
        public PlayerL2(ChestGui parent) {
            super(parent, false, 2);
        }

        @Override protected Item borderItem() { return Items.STAINED_GLASS_PANE.lightBlue(); }

        @Override
        protected void layoutPage(GuiCanvas canvas, int page, ServerPlayer viewer) {
            super.layoutPage(canvas, page, viewer);
            entry(canvas, 0, "noop", "礼物详情", "二级窗口的内容", Items.CHEST);
            entry(canvas, 1, "go:l3", "收件人范围", "点我进三级窗口", Items.PLAYER_HEAD);
        }

        @Override
        protected String onPageClick(String action, ServerPlayer player) {
            if ("go:l3".equals(action)) {
                return new PlayerL3(this).switchTo(player) ? null : "进不了三级界面";
            }
            String common = commonReply(action, player, this);
            return common != null ? common : "二级窗口的点到了：" + action;
        }
    }

    /** 玩家三级（蓝）—— 白名单玩家列表。 */
    public static final class PlayerL3 extends DemoFrameGui {
        public PlayerL3(ChestGui parent) {
            super(parent, false, 3);
        }

        @Override protected Item borderItem() { return Items.STAINED_GLASS_PANE.blue(); }

        @Override
        protected void layoutPage(GuiCanvas canvas, int page, ServerPlayer viewer) {
            super.layoutPage(canvas, page, viewer);
            fillWhitelist(canvas, viewer);
        }

        @Override
        protected String onPageClick(String action, ServerPlayer player) {
            String common = commonReply(action, player, this);
            if (common != null) {
                return common;
            }
            if (action.startsWith("pick:")) {
                return "点了玩家：" + action.substring(5);
            }
            return "三级窗口的点到了：" + action;
        }
    }

    /** 管理一级（红）。 */
    public static final class AdminL1 extends DemoFrameGui {
        public AdminL1() {
            super(null, true, 1);
        }

        @Override protected Item borderItem() { return Items.STAINED_GLASS_PANE.red(); }

        @Override
        protected void layoutPage(GuiCanvas canvas, int page, ServerPlayer viewer) {
            super.layoutPage(canvas, page, viewer);
            entry(canvas, 0, "go:l2", "邮箱管理", "点我进二级窗口", Items.CHEST);
            entry(canvas, 1, "go:l2", "全服公告", "点我进二级窗口", Items.BELL);
            entry(canvas, 2, "go:l2", "功能开关", "点我进二级窗口", Items.LEVER);
        }

        @Override
        protected String onPageClick(String action, ServerPlayer player) {
            if ("go:l2".equals(action)) {
                return new AdminL2(this).switchTo(player) ? null : "进不了二级界面";
            }
            return commonReply(action, player, this);
        }
    }

    /** 管理二级（黄）—— 设置物品、选择收件人范围。 */
    public static final class AdminL2 extends DemoFrameGui {
        public AdminL2(ChestGui parent) {
            super(parent, true, 2);
        }

        @Override protected Item borderItem() { return Items.STAINED_GLASS_PANE.yellow(); }

        @Override
        protected void layoutPage(GuiCanvas canvas, int page, ServerPlayer viewer) {
            super.layoutPage(canvas, page, viewer);
            entry(canvas, 0, "noop", "本次发送的物品", "点我选物品（M5 再定）", Items.CHEST);
            entry(canvas, 1, "go:l3", "接受玩家范围", "点我进三级窗口选人", Items.PLAYER_HEAD);
        }

        @Override
        protected String onPageClick(String action, ServerPlayer player) {
            if ("go:l3".equals(action)) {
                return new AdminL3(this).switchTo(player) ? null : "进不了三级界面";
            }
            String common = commonReply(action, player, this);
            return common != null ? common : "管理二级点到了：" + action;
        }
    }

    /** 管理三级（红）—— 指定玩家收件人。 */
    public static final class AdminL3 extends DemoFrameGui {
        public AdminL3(ChestGui parent) {
            super(parent, true, 3);
        }

        @Override protected Item borderItem() { return Items.STAINED_GLASS_PANE.red(); }

        @Override
        protected void layoutPage(GuiCanvas canvas, int page, ServerPlayer viewer) {
            super.layoutPage(canvas, page, viewer);
            fillWhitelist(canvas, viewer);
        }

        @Override
        protected String onPageClick(String action, ServerPlayer player) {
            String common = commonReply(action, player, this);
            if (common != null) {
                return common;
            }
            if (action.startsWith("pick:")) {
                return "选中收件人：" + action.substring(5)
                        + "（真实现象这里会关掉界面并把选择写进存档）";
            }
            return "管理三级点到了：" + action;
        }
    }

    /** 把白名单里的玩家逐个变成头颅列出来。 */
    private static void fillWhitelist(GuiCanvas canvas, ServerPlayer viewer) {
        if (viewer == null) {
            return;   // 自检场景没有玩家，也没有服务器
        }
        MinecraftServer server = viewer.level().getServer();
        if (server == null) {
            return;
        }
        List<String> names = whitelistNames(server);
        for (int i = 0; i < Math.min(names.size(), 21); i++) {
            int index = CONTENT_FIRST_COL + i;
            canvas.button(CONTENT_FIRST_ROW + index / 7, index % 7,
                    "pick:" + names.get(i),
                    named(headOf(names.get(i)), names.get(i), "白名单里的玩家"));
        }
        if (names.isEmpty()) {
            // 白名单是空的也要说清楚，不能让人对着满屏空白猜
            entry(canvas, 0, "noop", "白名单是空的",
                    "去服务器白名单里加名字", Items.BARRIER);
        }
    }
}

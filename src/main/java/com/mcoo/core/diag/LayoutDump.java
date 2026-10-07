package com.mcoo.core.diag;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * <b>临时工具</b> —— 把玩家手上正开着的大箱子导出成一张格子图。
 *
 * <h2>为什么要有它</h2>
 * 排版 GUI 最自然的做法是<b>直接在大箱子里摆</b>（所见即所得），
 * 但"第 3 行第 5 格"这种口述既慢又最容易差一格 ——
 * 而差一格的排版 bug 排查起来最费时间。
 *
 * <p>所以让玩家摆完直接导出：<b>格子图比人报的坐标可信</b>。
 *
 * <h2>怎么用</h2>
 * <ol>
 *   <li>在世界里放一个<b>大箱子</b>（双箱），往里摆你要的排版</li>
 *   <li>正对着它敲 {@code /mcoo gui dump}</li>
 *   <li>服务端日志里会打印格子图 + 图例（字母 → 物品）</li>
 * </ol>
 *
 * <p><b>不要对着本 mod 自己的界面 dump</b> —— 那转出来的就是我们自己摆的东西，
 * 没有意义。所以这里明确排除了 {@link com.mcoo.core.gui.McooChestMenu}。
 */
public final class LayoutDump {

    /** 图例符号，按首次出现顺序发。刻意跳过 I/O/l/1/0 这些容易看错的。 */
    private static final String SYMBOLS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghkmnopqrstuvwxyz23456789";

    /** 射线拾取的距离。创造模式的方块交互距离就是 5 格。 */
    private static final double LOOK_DISTANCE = 5.0;

    private LayoutDump() {
    }

    /**
     * 导出布局。两种取箱子的方式，按可靠性排序：
     *
     * <ol>
     *   <li><b>玩家已经打开的箱子界面</b> —— 这是<b>唯一</b>能正确拿到双箱 54 格的途径。
     *       原版是在建菜单时才把两个 {@code ChestBlockEntity} 合成一个容器的，
     *       方块实体上并没有"合并后的容器"这个公开接口，
     *       自己拼的话顺序很容易搞反，而顺序错了会导致整个排版错位且极难发现。</li>
     *   <li><b>玩家正对着的方块</b> —— 方便，但只能读到单箱（27 格）。
     *       命中双箱时只算半个，会明确提示改用方式一。</li>
     * </ol>
     *
     * @return 导出的报告文本；返回 {@code null} 表示"现在没法导出"，
     *         具体原因已经通过 {@code problems} 回给调用方了
     */
    public static String dump(ServerPlayer player, Problem problems) {
        if (player == null) {
            problems.report("拿不到玩家");
            return null;
        }

        Container chest;
        String source;

        if (player.containerMenu instanceof com.mcoo.core.gui.McooChestMenu) {
            problems.report("这个玩家现在开着的是本 mod 自己的界面 —— 导出它没有意义"
                    + "（那本来就是代码摆的）。请让他对着世界里摆好排版的那个【大箱子】再试。");
            return null;
        } else if (player.containerMenu instanceof net.minecraft.world.inventory.ChestMenu menu) {
            chest = menu.getContainer();
            source = "玩家已打开的箱子界面（" + player.getName().getString() + "）";
        } else {
            chest = lookedAtContainer(player);
            if (chest == null) {
                problems.report("没找到箱子。在原版里【容器界面打开时聊天框是打不开的】，"
                        + "所以正确做法是：玩家【右键打开】大箱子（界面显示着），"
                        + "然后在服务端控制台敲 `mcoo gui dump " + player.getName().getString() + "`。");
                return null;
            }
            source = "玩家正对着的方块（" + player.getName().getString() + "）";
        }

        int size = chest.getContainerSize();
        int rows = (size + 8) / 9;

        if (rows < 6) {
            problems.report("读到的是 " + rows + " 行（" + size + " 格），不是 6 行大箱子。"
                    + "请确认摆排版用的确实是【大箱子】（双箱）。");
            return null;
        }
        if (rows > 6) {
            problems.report("这个箱子有 " + rows + " 行，超过本项目界面框架的 6 行上限，只导出前 6 行。");
            rows = 6;
        }

        Map<Character, String> legend = new LinkedHashMap<>();
        Map<Key, Character> assigned = new LinkedHashMap<>();
        StringBuilder grid = new StringBuilder();

        for (int r = 0; r < rows; r++) {
            grid.append("行").append(r).append(" ");
            for (int c = 0; c < 9; c++) {
                int index = r * 9 + c;
                ItemStack stack = index < size ? chest.getItem(index) : ItemStack.EMPTY;
                if (stack.isEmpty()) {
                    grid.append('.');
                } else {
                    grid.append(symbolFor(stack, legend, assigned));
                }
                grid.append(c < 8 ? ' ' : '\n');
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("========== 箱子布局导出 ==========\n");
        sb.append("来源：").append(source).append("（").append(rows).append(" 行 ").append(size).append(" 格）\n");
        sb.append(grid);
        sb.append("----------------------------------\n");
        sb.append("图例：\n");
        if (legend.isEmpty()) {
            sb.append("  （箱子是空的 —— 如果你刚摆过东西，多半是没摆进这个箱子）\n");
        } else {
            legend.forEach((sym, desc) -> sb.append("  ").append(sym).append(" = ").append(desc).append('\n'));
        }
        sb.append("列号从左到右 0~8，行号从上到下 0~").append(rows - 1);
        sb.append("\n==================================");
        return sb.toString();
    }

    /**
     * 射线拾取到的方块里的容器。
     *
     * <p><b>双箱必须走 {@code ChestBlock.getContainer}</b>：
     * 一个双箱是两个独立的 {@code ChestBlockEntity}，各 27 格。
     * 原版只在"建菜单那一刻"才把它们合成 54 格，
     * 而这个合并口正好是公开的静态方法 —— <b>直接用它，不要自己拼</b>。
     *
     * <p>自己拼的风险不是"少几格"，而是两半的顺序可能反了：
     * 顺序一错，整个排版会左右对调，而且界面上看仍然"每个格子都有东西"，
     * 极难发现。
     */
    private static Container lookedAtContainer(ServerPlayer player) {
        if (!(player.pick(LOOK_DISTANCE, 0.0f, false)
                instanceof net.minecraft.world.phys.BlockHitResult hit)) {
            return null;
        }
        var level = player.level();
        var pos = hit.getBlockPos();
        var state = level.getBlockState(pos);

        if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock chestBlock) {
            // 最后一个参数 false = 不做"箱子被猫坐住"之类的合法性校验，
            // 我们只是要读内容，不需要原版那种"能不能开"的判断。
            return net.minecraft.world.level.block.ChestBlock.getContainer(
                    chestBlock, state, level, pos, false);
        }
        // 其它容器类方块（桶、潜影箱等）直接用方块实体本身。
        if (level.getBlockEntity(pos)
                instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity be) {
            return be;
        }
        return null;
    }

    /** 同一件物品复用同一个符号，所以图例一眼能对上「哪种物品出现在哪些格子」。 */
    private static char symbolFor(ItemStack stack, Map<Character, String> legend,
                                  Map<Key, Character> assigned) {
        Key key = new Key(stack.getItem(), stack.getCount());
        Character existing = assigned.get(key);
        if (existing != null) {
            return existing;
        }
        if (legend.size() >= SYMBOLS.length()) {
            return '?';
        }
        char sym = SYMBOLS.charAt(legend.size());
        assigned.put(key, sym);
        legend.put(sym, stack.getHoverName().getString() + " ×" + stack.getCount()
                + "   [" + itemId(stack) + "]");
        return sym;
    }

    /**
     * 物品的注册表 id，比如 {@code minecraft:diamond}。
     *
     * <p><b>图例里必须带这个</b>：显示名（"上一页"、"礼物"）是玩家自己起的，
     * 在代码里完全没法定位物品。AI 要照着这个 id 写 {@code new ItemStack(Items.XXX)}。
     */
    private static String itemId(ItemStack stack) {
        var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "?" : key.toString();
    }

    /**
     * 图例的归并键。
     *
     * <p>直接用 {@link Item} 对象当一部分 —— 物品在注册表里是单例，
     * 对象的 equals/hashCode 天然可用，比绕注册表拿字符串 id 简单得多
     * （26.2 的 {@code ResourceKey} 上已经没有 {@code location()} 这个方法了）。
     *
     * <p>数量也算进键里：1 个和 64 个钻石在排版上不是同一种东西。
     */
    private record Key(net.minecraft.world.item.Item item, int count) {
    }

    /** 出问题时把"人话原因"报回去的回调。 */
    public interface Problem {
        void report(String message);
    }
}

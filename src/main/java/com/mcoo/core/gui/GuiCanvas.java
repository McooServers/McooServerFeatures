package com.mcoo.core.gui;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * 摆界面的画布。
 *
 * <p>功能用它把按钮摆到指定格子：{@code canvas.button(行, 列, "动作:参数", 物品)}。
 *
 * <p><b>摆错格子会当场抛异常</b>，而不是默默忽略。
 * 越界的格子玩家根本看不见，表现是"按钮没反应"——那种 bug 排查起来最费时间。
 * 在摆界面的当场报错，信息最全。
 */
public final class GuiCanvas {

    private final Container container;
    private final int rows;
    private int placedButtons;

    GuiCanvas(Container container, int rows) {
        this.container = container;
        this.rows = rows;
    }

    /**
     * 同一个容器、但只允许摆前 {@code rows} 行的画布。
     *
     * <p><b>用来给翻页栏留位置</b>：分页界面把最后一行留给上一页/下一页，
     * 功能只能拿到这个受限画布，于是它<b>想</b>摆到保留区也会当场报错，
     * 而不是悄悄盖掉翻页按钮。
     *
     * <p>共享容器（不是复制），所以受限画布摆的东西在原画布里同样能看到。
     */
    GuiCanvas restrictTo(int rows) {
        if (rows > this.rows) {
            throw new IllegalArgumentException(
                    "不能放宽可摆区：" + rows + " 行比原来的 " + this.rows + " 行还多");
        }
        return new GuiCanvas(container, rows);
    }

    /** 可摆区总共多少格。 */
    public int capacity() {
        return rows * GuiLayout.COLUMNS;
    }

    /**
     * 摆一个按钮。
     *
     * <p><b>物品请自行构造完整</b>（要什么名字、什么说明、什么材质都自己定），
     * 本方法只负责放到位并写入动作编码 —— core 不替你猜按钮长什么样。
     *
     * @param row     0 起的行号
     * @param col     0 起的列号
     * @param action  动作 id，约定 {@code 动作名:参数}
     * @param icon    按钮显示的物品
     * @throws IllegalArgumentException 格子越界，或物品为空
     */
    public void button(int row, int col, String action, ItemStack icon) {
        if (!GuiLayout.contains(rows, row, col)) {
            throw new IllegalArgumentException(
                    "按钮摆到了界面外面：行=" + row + " 列=" + col
                            + "（本界面共 " + rows + " 行 × " + GuiLayout.COLUMNS + " 列）");
        }
        if (icon == null || icon.isEmpty()) {
            throw new IllegalArgumentException(
                    "按钮物品为空：行=" + row + " 列=" + col + " 动作=" + action);
        }

        GuiActionCodec.write(icon, action);
        container.setItem(GuiLayout.index(row, col), icon);
        placedButtons++;
    }

    /**
     * 放一个<b>没有动作</b>的物品到指定格子。
     *
     * <p>和 {@link #button} 的区别只是不写动作编码。
     * 用来铺翻页栏这类"框架自己管、功能不参与"的位置。
     *
     * <p>放完请自己 {@link GuiActionCodec#markFiller} 标记成背景，
     * 否则玩家点它会被当成"漏了编码的按钮"报警。
     *
     * @throws IllegalArgumentException 格子越界，或物品为空
     */
    public void place(int row, int col, ItemStack stack) {
        if (!GuiLayout.contains(rows, row, col)) {
            throw new IllegalArgumentException(
                    "物品摆到了界面外面：行=" + row + " 列=" + col
                            + "（可摆区共 " + rows + " 行 × " + GuiLayout.COLUMNS + " 列）");
        }
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException(
                    "物品为空：行=" + row + " 列=" + col);
        }
        container.setItem(GuiLayout.index(row, col), stack);
    }

    /**
     * 用背景物品铺满可摆区。
     *
     * <p>通常放在最后调用。已被按钮占用的格子会被跳过，
     * 不会把按钮覆盖掉 —— 否则功能里"先铺背景再摆按钮"的顺序就成了一种隐式依赖。
     *
     * @param filler 背景物品，一般是灰色玻璃板之类
     */
    public void fill(ItemStack filler) {
        if (filler == null || filler.isEmpty()) {
            throw new IllegalArgumentException("背景物品为空");
        }
        // 注意用 capacity() 而不是 container.getContainerSize()：
        // 受限画布（给翻页栏留了底行）不能铺到保留区里去。
        for (int i = 0; i < capacity(); i++) {
            if (container.getItem(i).isEmpty()) {
                // 每个格子用独立的一份，避免整箱物品共享同一个对象
                // 导致"改一格动全箱"这种诡异 bug。
                ItemStack copy = filler.copy();
                // 标记成背景，这样玩家点它时菜单不会把它当成
                // "漏了编码的按钮"报警。见 GuiActionCodec.markFiller 的注释。
                GuiActionCodec.markFiller(copy);
                container.setItem(i, copy);
            }
        }
    }

    /** 实际摆了多少个按钮。摆完可以断言一下，防止"一个按钮都没摆出来"。 */
    public int placedButtons() {
        return placedButtons;
    }
}

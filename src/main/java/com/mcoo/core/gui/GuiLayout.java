package com.mcoo.core.gui;

/**
 * 箱子界面的格子计算。
 *
 * <p><b>这个类刻意不引用任何 Minecraft 类型</b>，所以它能被普通单元测试直接跑，
 * 不需要启动游戏。这也让它成为"界面摆错了"这类问题的第一道拦截点 ——
 * 布局算错在界面上表现为按钮错位，靠肉眼很难发现。
 *
 * <p>原版箱子界面是 9 列 × 1~6 行。下标从 0 开始，顺序是**从上到下、从左到右**
 * （和玩家背包的排布规则一致，这一点很容易记反）。
 */
public final class GuiLayout {

    /** 箱子界面的列数，固定 9。 */
    public static final int COLUMNS = 9;

    /** 最少一行。 */
    public static final int MIN_ROWS = 1;

    /** 最多六行（对应原版 9x6 大箱子）。 */
    public static final int MAX_ROWS = 6;

    private GuiLayout() {
    }

    /**
     * 行列转下标。
     *
     * @param row 0 起的行号
     * @param col 0 起的列号
     * @throws IllegalArgumentException 行或列越界
     */
    public static int index(int row, int col) {
        if (row < 0 || col < 0 || col >= COLUMNS) {
            throw new IllegalArgumentException(
                    "格子越界：行=" + row + " 列=" + col + "（列只能是 0~" + (COLUMNS - 1) + "）");
        }
        return row * COLUMNS + col;
    }

    /** 下标转行号。 */
    public static int row(int index) {
        return index / COLUMNS;
    }

    /** 下标转列号。 */
    public static int col(int index) {
        return index % COLUMNS;
    }

    /**
     * 这个界面一共多少格。
     *
     * @throws IllegalArgumentException 行数不是 1~6。
     *         <b>刻意抛异常而不是静默夹到合法值</b>：界面摆错格子是代码 bug，
     *         静默纠正只会让 bug 更难找。
     */
    public static int size(int rows) {
        validateRows(rows);
        return rows * COLUMNS;
    }

    /** 校验行数，给出能直接看懂的中文报错。 */
    public static void validateRows(int rows) {
        if (rows < MIN_ROWS || rows > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "界面行数只能是 " + MIN_ROWS + "~" + MAX_ROWS + " 行，收到 " + rows);
        }
    }

    /**
     * 某个格子是否在这个界面范围内。
     *
     * <p>用来挡"功能不小心把按钮摆到第 5 行"这种错误 ——
     * 越界的格子玩家根本看不到，表现是"点了没反应"，极难排查。
     */
    public static boolean contains(int rows, int row, int col) {
        return row >= 0 && row < rows && col >= 0 && col < COLUMNS;
    }
}
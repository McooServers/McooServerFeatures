package com.mcoo.gui;

import com.mcoo.core.gui.GuiActionCodec;
import com.mcoo.core.gui.GuiLayout;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GUI 框架测试。
 *
 * <p><b>为什么这些测试要真的启动游戏引导（{@code Bootstrap}）</b>：
 * 本项目的规矩是"不用 mock 冒充原版对象"—— mock 出来的 {@code ItemStack}
 * 只能证明我们的代码跟 mock 对得上，证明不了跟真原版对得上，
 * 而"跟原版对得上"恰恰是这里唯一重要的事。
 * 引导一次 Minecraft 的注册表只要几百毫秒，之后就能用真的
 * {@link ItemStack}、{@code CustomData} 做断言。
 *
 * <p>代价是这些测试跑起来比纯逻辑测试慢，也因此**摆界面里那些纯数学的部分
 * 被单独拆到 {@link GuiLayout}**，那一半不需要引导，测起来飞快。
 */
class GuiCodecTest {

    @BeforeAll
    static void 启动游戏引导() {
        // 没有这一步，Items.DIAMOND 之类的静态字段会是空壳，ItemStack 造不出来。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---------------- GuiLayout：纯数学，不需要引导 ----------------

    @Test
    @DisplayName("格子下标：9 列，从上到下")
    void 格子下标() {
        assertEquals(0, GuiLayout.index(0, 0));
        assertEquals(8, GuiLayout.index(0, 8), "第一行最后一格");
        assertEquals(9, GuiLayout.index(1, 0), "第二行第一格");
        assertEquals(26, GuiLayout.index(2, 8), "3 行界面的最后一格");
    }

    @Test
    @DisplayName("下标能拆回行和列")
    void 下标拆行列() {
        for (int index = 0; index < 54; index++) {
            assertEquals(index, GuiLayout.index(GuiLayout.row(index), GuiLayout.col(index)),
                    "index=" + index + " 拆回去对不上");
        }
    }

    @Test
    @DisplayName("行数越界立刻报错，不静默纠正")
    void 行数越界报错() {
        assertEquals(9, GuiLayout.size(1));
        assertEquals(54, GuiLayout.size(6));

        // 静默夹到合法值会把"界面定义写错"这个 bug 藏起来
        assertThrows(IllegalArgumentException.class, () -> GuiLayout.size(0));
        assertThrows(IllegalArgumentException.class, () -> GuiLayout.size(7));
        assertThrows(IllegalArgumentException.class, () -> GuiLayout.size(-1));
    }

    @Test
    @DisplayName("列只能是 0~8")
    void 列越界报错() {
        assertThrows(IllegalArgumentException.class, () -> GuiLayout.index(0, 9));
        assertThrows(IllegalArgumentException.class, () -> GuiLayout.index(0, -1));
        assertThrows(IllegalArgumentException.class, () -> GuiLayout.index(-1, 0));
    }

    @Test
    @DisplayName("contains 能识别界面外的格子")
    void contains判定() {
        assertTrue(GuiLayout.contains(3, 2, 8), "3 行界面的右下角在里面");
        assertFalse(GuiLayout.contains(3, 3, 0), "第 4 行在外面");
        assertFalse(GuiLayout.contains(3, 0, 9), "第 10 列在外面");
    }

    // ---------------- 动作 id 的字符串拆分：不需要 ItemStack ----------------

    @Test
    @DisplayName("动作 id 拆成动作名和参数")
    void 动作拆分() {
        assertEquals("claim", GuiActionCodec.actionName("claim:day1"));
        assertEquals("day1", GuiActionCodec.actionArg("claim:day1"));

        // 没有冒号时，名字就是全部，参数是空
        assertEquals("help", GuiActionCodec.actionName("help"));
        assertEquals("", GuiActionCodec.actionArg("help"));

        // 参数里也可以有冒号，只按第一个拆
        assertEquals("gift", GuiActionCodec.actionName("gift:player:Steve"));
        assertEquals("player:Steve", GuiActionCodec.actionArg("gift:player:Steve"));
    }
}
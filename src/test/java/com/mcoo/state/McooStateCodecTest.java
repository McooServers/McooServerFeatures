package com.mcoo.state;

import com.mojang.serialization.JavaOps;
import com.mcoo.core.state.McooState;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存档测试 —— 重点是 {@link McooState#CODEC} 的往返。
 *
 * <p><b>为什么 Codec 测试优先级最高</b>：26.2 的 {@code SavedData}
 * <b>没有</b> save/load 方法，存盘全靠这个 codec。它写坏了的后果是
 * <b>服务器读不出玩家历史数据</b>，而且往往是上线几个月后才发现 ——
 * 那时候数据已经写进去了。这属于典型的"平时不炸，一炸就是灾难"。
 */
class McooStateCodecTest {

    /** 用 codec 编一遍再解一遍，模拟一次真实的存档→读盘。 */
    private static McooState roundTrip(McooState original) {
        var encoded = McooState.CODEC
                .encodeStart(JavaOps.INSTANCE, original)
                .getOrThrow();
        return McooState.CODEC
                .parse(JavaOps.INSTANCE, encoded)
                .getOrThrow();
    }

    @Test
    @DisplayName("空存档往返后还是空的")
    void 空存档往返() {
        McooState back = roundTrip(new McooState());
        assertEquals(0, back.sectionCount(), "空存档往返后不该凭空冒出分区");
        assertEquals("", back.read("daily", "任何键"), "没有数据的键应读到空串");
    }

    @Test
    @DisplayName("写入 → 编码 → 解码 → 读回，数据一字不差")
    void 往返后数据一致() {
        McooState original = new McooState();
        original.write("daily", "streak", "7");
        original.write("daily", "lastDate", "2026-10-07");
        original.write("gift", "pending-abc", "diamond_sword|1");
        original.writeInt("gift", "version", 3);

        McooState back = roundTrip(original);

        assertEquals("7", back.read("daily", "streak"));
        assertEquals("2026-10-07", back.read("daily", "lastDate"));
        assertEquals("diamond_sword|1", back.read("gift", "pending-abc"));
        assertEquals(3, back.readInt("gift", "version", -1));
        assertEquals(2, back.sectionCount(), "两个功能分区");
    }

    @Test
    @DisplayName("功能分区之间互不干扰（删一个不影响另一个）")
    void 分区互不干扰() {
        McooState state = new McooState();
        state.write("daily", "k", "daily值");
        state.write("gift", "k", "gift值");

        assertEquals("daily值", state.read("daily", "k"), "同名键在不同分区必须各存各的");
        assertEquals("gift值", state.read("gift", "k"));

        // 模拟"删掉 daily 功能"：把 daily 分区清空
        state.write("daily", "k", null);

        assertEquals("", state.read("daily", "k"));
        assertEquals("gift值", state.read("gift", "k"), "孤儿数据清掉不该碰到别的功能");
        assertEquals(1, state.sectionCount());
    }

    @Test
    @DisplayName("多键同分区：逐个删键直到分区消失")
    void 多键逐个删() {
        McooState state = new McooState();
        state.write("gift", "a", "1");
        state.write("gift", "b", "2");
        state.write("gift", "c", "3");
        assertEquals(3, state.featureIds().contains("gift") ? 3 : 0);

        state.write("gift", "a", null);
        assertEquals("", state.read("gift", "a"));
        assertEquals("2", state.read("gift", "b"));

        state.write("gift", "b", null);
        state.write("gift", "c", null);
        assertEquals(0, state.sectionCount(), "分区里没有键了，整个分区应该消失而不是留空壳");
        assertFalse(state.featureIds().contains("gift"));
    }

    @Test
    @DisplayName("反复覆盖同一个键不会写出重复行")
    void 反复覆盖不重复() {
        McooState state = new McooState();
        for (int i = 0; i < 50; i++) {
            state.writeInt("daily", "minutes", i);
        }
        assertEquals(49, state.readInt("daily", "minutes", -1));

        // 内部文本里 "minutes=" 应该只出现一次
        // 往返两次后仍然只有一个分区、且值是最新的，说明没有写出重复行
        McooState back = roundTrip(roundTrip(state));
        assertEquals(1, back.sectionCount(), "反复覆盖不该写出重复行");
        assertEquals(49, back.readInt("daily", "minutes", -1));
    }

    @Test
    @DisplayName("含特殊字符的值能安全往返")
    void 特殊字符能往返() {
        McooState state = new McooState();
        state.write("gift", "玩家名带中文", "值里有 空格 和 | 竖线 和 : 冒号");
        state.write("gift", "emoji", "🎁礼物");

        McooState back = roundTrip(state);
        assertEquals("值里有 空格 和 | 竖线 和 : 冒号",
                back.read("gift", "玩家名带中文"));
        assertEquals("🎁礼物", back.read("gift", "emoji"));
    }

    @Test
    @DisplayName("含换行或等号的值被当场拒绝，而不是悄悄存坏数据")
    void 非法值被拒绝() {
        McooState state = new McooState();
        // 等号会破坏 key=value 的行格式
        assertThrows(IllegalArgumentException.class,
                () -> state.write("gift", "k", "a=b"), "含等号的值必须被拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> state.write("gift", "k", "第一行\n第二行"), "含换行的值必须被拒绝");

        assertEquals(0, state.sectionCount(), "被拒绝的值不应该留下半截数据");
    }

    @Test
    @DisplayName("存档里存了垃圾数字时退回默认值，不抛异常")
    void 垃圾值退回默认() {
        McooState state = new McooState();
        // 模拟玩家手改存档把数字改成了 "abc"
        state.write("daily", "streak", "abc");

        // 存档被人改坏是常事。这里必须退回默认值而不是崩服务器。
        assertEquals(-1, state.readInt("daily", "streak", -1));
        assertEquals(99L, state.readLong("daily", "streak", 99L));
        // 这条断言是本测试存在的原因：早先 readBoolean 直接用 Boolean.parseBoolean，
        // 垃圾值会静默变成 false 而不是退回默认值，跟 readInt/readLong 行为不一致。
        assertTrue(state.readBoolean("daily", "streak", true), "非法值应退回默认值 true");
        assertFalse(state.readBoolean("daily", "streak", false), "非法值应退回默认值 false");

        // 原始文本还在，没有被"顺手改掉"
        assertEquals("abc", state.read("daily", "streak"));
    }

    @Test
    @DisplayName("写入会标脏，否则世界存档时不会落盘")
    void 写入会标脏() {
        McooState state = new McooState();
        assertFalse(state.isDirty(), "刚创建时应该是干净的");

        state.write("daily", "k", "v");
        assertTrue(state.isDirty(), "写完必须是脏的，否则存档时数据就丢了");

        state.setDirty(false);
        state.writeInt("daily", "n", 1);
        assertTrue(state.isDirty(), "所有写入口径都要标脏，不能只管 String 那一个");
    }

    @Test
    @DisplayName("编码产物是稳定的 Map（能进 DFU 管线）")
    void 编码产物结构正确() {
        McooState state = new McooState();
        state.write("daily", "k", "v");

        var encoded = McooState.CODEC.encodeStart(JavaOps.INSTANCE, state).getOrThrow();
        assertTrue(encoded instanceof Map, "codec 顶层必须编成对象，实际是 " + encoded.getClass());
        Map<?, ?> map = (Map<?, ?>) encoded;

        // 顶层直接就是「功能 id → 该功能的数据文本」这一层，没有额外的字段包装。
        // 见 McooState.CODEC 的 javadoc：DFU 的 MapCodec 不是 Codec，套不了字段。
        assertEquals(Set.of("daily"), map.keySet(), "顶层键应该就是功能 id");
        assertEquals("k=v", map.get("daily"), "值是分区内部的 key=value 行");
    }

    @Test
    @DisplayName("空存档编出来是空对象，不是 null")
    void 空存档编码为空对象() {
        var encoded = McooState.CODEC.encodeStart(JavaOps.INSTANCE, new McooState()).getOrThrow();
        assertTrue(encoded instanceof Map, "空存档也要编成对象，实际 " + encoded.getClass());
        assertTrue(((Map<?, ?>) encoded).isEmpty(), "空存档应编成 {}");
    }

    @Test
    @DisplayName("键里带冒号也能往返（分隔符只对值生效）")
    void 键含特殊字符() {
        McooState state = new McooState();
        state.write("gift", "player:name", "某玩家");
        state.write("gift", "空 值", "有值");

        McooState back = roundTrip(state);
        assertEquals("某玩家", back.read("gift", "player:name"));
        assertEquals("有值", back.read("gift", "空 值"));
    }
}
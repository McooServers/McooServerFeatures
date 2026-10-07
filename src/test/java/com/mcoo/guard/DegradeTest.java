package com.mcoo.guard;

import com.mcoo.core.feature.Feature;
import com.mcoo.core.feature.FeatureRegistration;
import com.mcoo.core.feature.FeatureRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退化闸 —— 这是"mod 坏了服务器照常跑"的**可执行**版本。
 *
 * <p>{@code FeatureRegistry} 不引用任何 Minecraft / Fabric 类型，
 * 所以这里能不开服、直接在单元测试里把服务器会遇到的情况全部演练一遍。
 *
 * <p>覆盖的场景对应 {@code SAFETY.md} 的失败模式：
 * <ul>
 *   <li>功能全部抛异常 → 服务器照常（本类核心）</li>
 *   <li>功能抛 {@code Error}（OOM / NoClassDefFound 那一类）而不是 {@code Exception}</li>
 *   <li>enable 到一半崩了 → 已登记的东西必须回滚，不能留下永久残留</li>
 *   <li>功能自己写的 disable 崩了 → 照样回滚，不能拖垮整个停服流程</li>
 *   <li>功能完全忘了写 disable → 注册表兜底清理</li>
 * </ul>
 */
class DegradeTest {

    // ------------------------------------------------------------------
    //  测试用的假功能
    // ------------------------------------------------------------------

    /** 正常工作的功能：登记 N 样东西，N 默认 2。 */
    private static final class GoodFeature implements Feature {
        private final String id;
        private final int registrations;

        GoodFeature(String id) {
            this(id, 2);
        }

        GoodFeature(String id, int registrations) {
            this.id = id;
            this.registrations = registrations;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void enable(FeatureRegistration registration) {
            for (int i = 0; i < registrations; i++) {
                registration.track(() -> { });
            }
        }

        @Override
        public void disable() {
        }
    }

    /** enable 抛 RuntimeException 的功能。 */
    private static final class ExplodingFeature implements Feature {
        private final String id;
        private final int registersBeforeExploding;
        private final Throwable toThrow;
        private boolean disableCalled;

        ExplodingFeature(String id, int registersBeforeExploding, Throwable toThrow) {
            this.id = id;
            this.registersBeforeExploding = registersBeforeExploding;
            this.toThrow = toThrow;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void enable(FeatureRegistration registration) {
            // 先登记一些东西，再炸 —— 这是最危险的情形：
            // 半路上车的乘客没被赶下车，就会一直留在服务器上。
            for (int i = 0; i < registersBeforeExploding; i++) {
                registration.track(() -> { });
            }
            sneakyThrow(toThrow);
        }

        @Override
        public void disable() {
            disableCalled = true;
        }

        boolean disableCalled() {
            return disableCalled;
        }
    }

    /** disable 自己抛异常的功能。 */
    private static final class BadDisableFeature implements Feature {
        @Override
        public String id() {
            return "badDisable";
        }

        @Override
        public void enable(FeatureRegistration registration) {
            registration.track(() -> { });
        }

        @Override
        public void disable() {
            throw new IllegalStateException("disable 自己炸了");
        }
    }

    /** 连 id() 都抛异常的最坏情况。 */
    private static final class IdThrowsFeature implements Feature {
        @Override
        public String id() {
            throw new IllegalStateException("连 id() 都拿不到");
        }

        @Override
        public void enable(FeatureRegistration registration) {
        }

        @Override
        public void disable() {
        }
    }

    /** 不声明 throws 的接口里想抛受检异常时的写法。 */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    // ------------------------------------------------------------------
    //  闸门
    // ------------------------------------------------------------------

    @Test
    @DisplayName("全部功能都抛异常，enableAll 不向外抛（= 服务器照常启动）")
    void 全部功能抛异常_不外抛() {
        FeatureRegistry registry = new FeatureRegistry();
        registry.register(new ExplodingFeature("boom1", 0, new RuntimeException("炸了")));
        registry.register(new ExplodingFeature("boom2", 0, new IllegalStateException("又炸了")));
        registry.register(new ExplodingFeature("boom3", 0, new OutOfMemoryError("内存爆了")));

        // 这一行如果抛异常，测试就红 —— 真实场景里就是服务器起不来
        registry.enableAll();

        assertEquals(0, registry.enabledCount(), "三个功能全炸，不该有任何功能生效");
        assertEquals(List.of("boom1", "boom2", "boom3"), registry.failedIds(), "三个失败都要被记录");
        assertFalse(registry.allHealthy(), "存在失败时 allHealthy 必须为 false");
        assertEquals(0, registry.liveCount(), "全炸时不该有任何登记残留");
    }

    @Test
    @DisplayName("功能抛 Error（OOM / NoClassDefFound 那一类）同样被兜住")
    void 抛Error同样被兜住() {
        FeatureRegistry registry = new FeatureRegistry();
        registry.register(new ExplodingFeature("oom", 0, new OutOfMemoryError("模拟 OOM")));
        registry.register(new ExplodingFeature("noclass", 0,
                new NoClassDefFoundError("模拟缺类")));
        registry.register(new GoodFeature("healthy"));

        registry.enableAll();

        // 这正是用 Throwable 而不是 Exception 的理由：
        // NoClassDefFoundError 是"打包出错"最典型的后果，而它不是 Exception。
        assertEquals(List.of("oom", "noclass"), registry.failedIds());
        assertEquals(1, registry.enabledCount(), "没受牵连的功能必须照常生效");
    }

    @Test
    @DisplayName("enable 中途炸了，已登记的东西全部回滚（无残留）")
    void enable中途炸_全部回滚() {
        FeatureRegistry registry = new FeatureRegistry();
        // 先登记 3 样，再炸 —— 最危险的形态
        registry.register(new ExplodingFeature("half", 3, new RuntimeException("炸在半路")));
        registry.register(new GoodFeature("other", 5));

        registry.enableAll();

        assertEquals(1, registry.enabledCount(), "只有 other 应该生效");
        // half 的登记器在 enableOne 里就已经回滚并移出 live，
        // 所以 liveCount 只统计生效中的 other 的 5 项。
        assertEquals(5, registry.liveCount(),
                "失败功能的 3 项登记必须已经回滚，不能留在服务器上");
    }

    @Test
    @DisplayName("好功能和坏功能混在一起时互不影响")
    void 好坏功能互不影响() {
        FeatureRegistry registry = new FeatureRegistry();
        registry.register(new GoodFeature("a", 1));
        registry.register(new ExplodingFeature("b", 2, new RuntimeException("炸")));
        registry.register(new GoodFeature("c", 3));
        registry.register(new ExplodingFeature("d", 0, new IllegalStateException("也炸")));
        registry.register(new GoodFeature("e", 4));

        registry.enableAll();

        assertEquals(3, registry.enabledCount(), "a / c / e 必须照常生效");
        assertEquals(List.of("b", "d"), registry.failedIds());
        assertEquals(1 + 3 + 4, registry.liveCount(), "只有生效功能的登记数算数");
    }

    @Test
    @DisplayName("disableAll 之后残留计数归零（= 没有泄漏）")
    void disableAll之后计数归零() {
        FeatureRegistry registry = new FeatureRegistry();
        registry.register(new GoodFeature("a", 3));
        registry.register(new GoodFeature("b", 7));
        registry.register(new ExplodingFeature("c", 4, new RuntimeException("炸")));

        registry.enableAll();
        assertEquals(10, registry.liveCount(), "启用后 a+b 的登记数");

        registry.disableAll();

        assertEquals(0, registry.liveCount(), "全部关闭后残留必须归零，这是「没有泄漏」的量化指标");
        assertEquals(0, registry.enabledCount(), "全部关闭后生效数必须为 0");
    }

    @Test
    @DisplayName("功能完全忘了写 disable，注册表依然兜底清理")
    void 忘了写disable也兜底() {
        FeatureRegistry registry = new FeatureRegistry();
        registry.register(new GoodFeature("forgetful", 4));

        registry.enableAll();
        assertEquals(4, registry.liveCount());

        registry.disableAll();
        assertEquals(0, registry.liveCount(),
                "GoodFeature 的 disable 是空的，清理必须完全靠注册表兜底");
    }

    @Test
    @DisplayName("功能自己的 disable 抛异常，不影响后续功能关闭")
    void disable抛异常不影响后续() {
        FeatureRegistry registry = new FeatureRegistry();
        registry.register(new BadDisableFeature());
        registry.register(new GoodFeature("after", 2));

        registry.enableAll();

        registry.disableAll();

        assertEquals(0, registry.liveCount(),
                "badDisable 的 disable 炸了，但它登记的东西照样必须被回滚");
        assertEquals(0, registry.enabledCount());
    }

    @Test
    @DisplayName("连 id() 都抛异常的功能不会拖垮整个注册表")
    void id抛异常不拖垮注册表() {
        FeatureRegistry registry = new FeatureRegistry();
        registry.register(new GoodFeature("normal", 2));
        // register() 会调 id()，这个功能根本登记不进来 —— 这本身就是好事。
        boolean rejected = false;
        try {
            registry.register(new IdThrowsFeature());
        } catch (RuntimeException expected) {
            rejected = true;
        }
        assertTrue(rejected, "id() 拿不到的功能应该在登记阶段就被拒绝");

        registry.enableAll();
        assertEquals(1, registry.enabledCount(), "正常功能不受影响");
    }

    @Test
    @DisplayName("重复 id 在登记阶段就被拒绝")
    void 重复id被拒绝() {
        FeatureRegistry registry = new FeatureRegistry();
        registry.register(new GoodFeature("dup"));
        boolean rejected = false;
        try {
            registry.register(new GoodFeature("dup"));
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected, "重复 id 必须当场拒绝，不能等到运行期才发现");
    }

    @Test
    @DisplayName("零功能状态下注册表一切正常")
    void 零功能状态正常() {
        FeatureRegistry registry = new FeatureRegistry();
        registry.enableAll();
        registry.disableAll();

        assertEquals(0, registry.size());
        assertEquals(0, registry.liveCount());
        assertTrue(registry.allHealthy(), "没有功能就没有失败");
    }

    @Test
    @DisplayName("关闭顺序与启用顺序相反")
    void 关闭顺序相反() {
        List<String> order = new ArrayList<>();

        FeatureRegistry registry = new FeatureRegistry();
        registry.register(new OrderedFeature("first", order));
        registry.register(new OrderedFeature("second", order));

        registry.enableAll();
        registry.disableAll();

        assertEquals(List.of("enable:first", "enable:second", "disable:second", "disable:first"),
                order, "后启用的先关");
    }

    private record OrderedFeature(String id, List<String> order) implements Feature {
        @Override
        public void enable(FeatureRegistration registration) {
            order.add("enable:" + id);
        }

        @Override
        public void disable() {
            order.add("disable:" + id);
        }
    }

    @Test
    @DisplayName("大量功能全炸时也不外抛（压力）")
    void 大量功能全炸不外抛() {
        FeatureRegistry registry = new FeatureRegistry();
        int count = 200;
        for (int i = 0; i < count; i++) {
            registry.register(new ExplodingFeature("boom" + i, 2, new RuntimeException("炸" + i)));
        }
        registry.register(new GoodFeature("survivor", 1));

        registry.enableAll();

        assertEquals(count, registry.failedIds().size());
        assertEquals(1, registry.enabledCount());
        assertEquals(1, registry.liveCount(),
                "200 个失败功能各自登记的 2 项必须全部回滚干净，不能有任何残留");
        registry.disableAll();
        assertEquals(0, registry.liveCount());
    }
}
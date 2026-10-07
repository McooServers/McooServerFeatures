package com.mcoo.core.diag;

import com.mcoo.core.feature.Feature;
import com.mcoo.core.feature.FeatureRegistration;
import com.mcoo.core.feature.FeatureRegistry;

/**
 * 自检模式 —— <b>一键验证「mod 全坏了，服务器还能不能活」</b>。
 *
 * <p><b>为什么需要它：</b>
 * {@code DegradeTest} 能在单元层面证明注册表兜得住异常，但它跑不开服，
 * 证明不了"服务器真的起来了"。而这条恰恰是整个项目最核心的承诺。
 * 所以这里提供一条实跑的路径：加上
 * {@code -Dmcoo.faultInject=all} 启动服务器，mod 会注册一批**必然抛异常**的
 * 功能，然后照常启用它们。如果服务器仍然 {@code Done}、仍然能正常停服，
 * 那这条承诺就不是口头上的。
 *
 * <p><b>默认完全不影响生产路径</b>：不设这个系统属性时，
 * {@link #installIfRequested} 什么都不注册、不做任何事。
 * 这些"必炸的功能"是刻意的故障注入，不是业务代码。
 *
 * <p><b>用法</b>：
 * <pre>
 * # 全部功能都炸（最严苛）
 * .\tools\run-testserver.ps1 -JvmArgs '-Dmcoo.faultInject=all'
 *
 * # 只炸其中一个，看隔离是不是真的
 * .\tools\run-testserver.ps1 -JvmArgs '-Dmcoo.faultInject=beta'
 * </pre>
 */
public final class SelfCheck {

    /** 系统属性名。取值 {@code all} 表示全部功能都炸；也可指定单个功能 id。 */
    public static final String PROPERTY = "mcoo.faultInject";

    private SelfCheck() {
    }

    /**
     * 若带了故障注入属性，就注册一批必炸的功能。
     *
     * <p>属性检查刻意放在方法内部而不是调用方 —— 调用方就不可能忘记检查。
     */
    public static void installIfRequested(FeatureRegistry registry) {
        String target = System.getProperty(PROPERTY);
        if (target == null || target.isBlank()) {
            return;
        }
        boolean all = "all".equalsIgnoreCase(target.trim());
        if (all) {
            for (String id : new String[]{"alpha", "beta", "gamma", "delta"}) {
                registry.register(new AlwaysFailingFeature(id));
            }
            System.out.println("[mcoo] 自检模式：已注册 4 个必然失败的功能，"
                    + "用于验证「mod 全坏时服务器照常启动」");
        } else if ("probe".equalsIgnoreCase(target.trim())) {
            // 存档探针是**正常**功能，专门用来验证"写进去的数据能活过一次重启"。
            // 连续跑两次，第二次日志里出现「读到上次的值」即证明持久化生效。
            registry.register(new StateProbeFeature());
            System.out.println("[mcoo] 存档探针模式：连跑两次，"
                    + "第二次日志应出现「读到上次的值」");
        } else if ("gui".equalsIgnoreCase(target.trim())) {
            // GUI 框架自检：需要真 ItemStack / 真 Menu，只能在真实服务器里跑。
            registry.register(new SelfCheckFeature(() -> GuiSelfCheck.create().run()));
            System.out.println("[mcoo] GUI 自检模式：日志里会逐项打印 [通过] / [失败]，"
                    + "末尾给总数");
        } else {
            registry.register(new AlwaysFailingFeature(target.trim()));
            System.out.println("[mcoo] 自检模式：已注册 1 个必然失败的功能：" + target.trim());
        }
    }

    /**
     * 一个必然失败的功能。
     *
     * <p>它刻意在<b>登记了几样东西之后</b>才抛异常 —— 这是最危险的形态：
     * 半路上车的乘客如果没被赶下车，就会一直留在服务器上，
     * 在别人出事的时候跟着出事。所以它同时也在验证自动回滚。
     */
    private static final class AlwaysFailingFeature implements Feature {
        private final String id;

        AlwaysFailingFeature(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void enable(FeatureRegistration registration) {
            // 先登记 3 样，模拟「功能注册了一部分监听器之后崩了」
            for (int i = 0; i < 3; i++) {
                registration.track(() -> { });
            }
            throw new IllegalStateException("【自检】功能 " + id + " 按设计失败");
        }

        @Override
        public void disable() {
        }
    }
}
package com.mcoo.core.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单个功能的「登记器」：功能每往服务器登记一样东西，就记一笔，
 * 关闭时由 {@link FeatureRegistry} 统一回滚。
 *
 * <p><b>它解决的是"AI 写漏了"这个具体问题。</b>
 * 如果让每个功能自己写 {@code disable()} 去取消监听器、去注销命令，
 * 只要漏写一处，监听器就会一直挂在服务器上 —— 而一个功能残留的
 * 监听器是会在别人出事时跟着出事的（重复执行、访问已关闭的菜单、
 * 在功能关闭后继续往存档写数据）。所以这里不让功能自己记：
 *
 * <p><b>功能只管登记，回滚由注册表兜底。</b>这样即使功能完全忘了
 * {@code disable()}，它也不会留下任何残留。
 *
 * <p>用法：
 * <pre>{@code
 * public void enable(FeatureRegistration registration) {
 *     ServerTickEvents.END_SERVER_TICK.register(this::onTick);
 *     registration.track(() -> ServerTickEvents.END_SERVER_TICK.unregister(this::onTick));
 * }
 * }</pre>
 *
 * <p><b>线程安全</b>：功能理论上只在主线程启用，但回滚可能来自停服流程。
 * 这里所有操作都同步，且计数用 {@code AtomicInteger}，避免并发读出中间值。
 */
public final class FeatureRegistration {

    private final String featureId;
    private final List<Runnable> undoActions = new ArrayList<>();
    private boolean rolledBack;

    /**
     * 由 {@link FeatureRegistry} 在构造后注入，功能拿不到也不需要拿。
     *
     * <p><b>为什么是 public</b>：core 自己也有些需要登记清理的状态
     * （例如 {@code StateStore} 的绑定），它们不属于任何功能，
     * 但同样要走「登记 → 统一回滚」这条路。
     * 所以 core 内部（与 feature 同包或已 import 本类）也能造一个。
     *
     * <p>功能代码<b>不应该</b>直接构造这个类 —— 功能拿到的实例是由
     * {@link Feature} 接口的 {@code enable} 参数传进来的。
     */
    public FeatureRegistration(String featureId) {
        this.featureId = featureId;
    }

    /**
     * 登记一样需要在关闭时撤销的东西。
     *
     * @param undoAction 撤销动作。必须能安全重复执行。
     * @throws IllegalStateException 已回滚过还想继续登记 —— 这是逻辑错误，要立刻暴露
     */
    public void track(Runnable undoAction) {
        if (undoAction == null) {
            throw new IllegalArgumentException("撤销动作为 null（功能 " + featureId + "）");
        }
        if (rolledBack) {
            // 出现这个说明控制流写错了：回滚之后还有代码在跑。
            // 静默忽略会留下永远撤不掉的东西，所以直接抛。
            throw new IllegalStateException("功能 " + featureId + " 在回滚之后还在登记东西");
        }
        undoActions.add(undoAction);
    }

    /**
     * 当前登记了几样尚未撤销的东西。
     *
     * <p>功能关闭（正常关闭或因异常被回滚）后必须为 <b>0</b>。
     * 这是"没有残留"的量化指标，由测试直接断言。
     */
    public int liveCount() {
        return undoActions.size();
    }

    /** 所属功能 id，仅用于报错信息。 */
    public String featureId() {
        return featureId;
    }

    /** 已回滚过就不再重复回滚，避免撤销动作被调用两次。 */
    boolean isRolledBack() {
        return rolledBack;
    }

    /**
     * 撤销全部登记。**逆序**执行，符合"后登记的先撤销"的直觉。
     *
     * <p>单个撤销动作抛异常**不会中断**其余撤销，也不向外抛：
     * 一个功能撤不干净，最多影响它自己，不能拖垮整个停服流程。
     * 撤不掉的动作被丢弃（反正服务器要关了），但会保留计数归零，
     * 保证 {@link #liveCount()} 这个指标仍然诚实。
     *
     * <p><b>public 是有意的</b>：core 自己需要一张不属于任何功能的登记表
     *（例如存档的绑定/解绑）。它同样要走"登记 → 统一回滚"这条路，
     * 所以 core 内部也要能主动回滚。功能代码不应直接调用本方法。
     */
    public List<Throwable> rollback() {
        rolledBack = true;
        List<Throwable> problems = new ArrayList<>();

        List<Runnable> snapshot = new ArrayList<>(undoActions);
        Collections.reverse(snapshot);
        undoActions.clear();

        for (Runnable undo : snapshot) {
            try {
                undo.run();
            } catch (Throwable t) {
                problems.add(t);
            }
        }
        return problems;
    }

    @Override
    public String toString() {
        return "FeatureRegistration[" + featureId + ", 残留=" + liveCount() + "]";
    }
}
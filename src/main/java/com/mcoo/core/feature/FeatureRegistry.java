package com.mcoo.core.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 功能注册表 —— **mod 的第一道保险丝**。
 *
 * <p><b>它唯一的职责：任何一个功能出事，都不许影响其它功能，更不许影响服务器。</b>
 *
 * <p>具体保证三条：
 * <ol>
 *   <li><b>逐个 {@code catch (Throwable)}</b>，异常绝不逃出本类。
 *       用 {@code Throwable} 而不是 {@code Exception}：OOM / StackOverflow /
 *       NoClassDefFound 都是 {@code Error}，而这些恰恰是代码写错最典型的后果。</li>
 *   <li><b>失败即降级为"没这个功能"</b>。玩家体验是功能不存在，不是功能出错。</li>
 *   <li><b>失败的功能不留残留</b>。enable 到一半崩了，已登记的东西立刻回滚，
 *       计数归零 —— 否则它的残留监听器会在别人出事时跟着出事。</li>
 * </ol>
 *
 * <p><b>本类不引用任何 Minecraft / Fabric 类型</b>，
 * 因此它能被普通单元测试直接跑，不需要开服。见 {@code DegradeTest}。
 */
public final class FeatureRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("mcoo");

    /** 按注册顺序保存，保证启用/关闭顺序稳定，便于复现问题。 */
    private final Map<String, Feature> features = new LinkedHashMap<>();

    /** 仍在生效的登记器。功能关闭后从 map 移除，用于统计与断言。 */
    private final Map<String, FeatureRegistration> live = new LinkedHashMap<>();

    /** 启用失败的功能 → 失败原因摘要。 */
    private final Map<String, String> failures = new LinkedHashMap<>();

    /**
     * 注册一个功能。<b>这一步不做任何初始化</b>，只登记，等 {@link #enableAll()} 才启用。
     *
     * <p>重复 id 直接拒绝：两个功能抢同一个 id 会导致存档串写和命令冲突，
     * 这属于必须在启动期暴露的逻辑错误，不能等到运行期才发现。
     *
     * @throws IllegalArgumentException id 为空或重复
     */
    public void register(Feature feature) {
        if (feature == null) {
            throw new IllegalArgumentException("功能为 null");
        }
        String id = feature.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("功能 id 为空: " + feature.getClass().getName());
        }
        if (features.containsKey(id)) {
            throw new IllegalArgumentException("功能 id 重复: " + id);
        }
        features.put(id, feature);
    }

    /**
     * 启用全部功能。**本方法保证不向外抛任何异常。**
     *
     * <p>每个功能有自己独立的登记器：一个失败只回滚它自己，
     * 不会误伤已经正常启用的其它功能。
     */
    public void enableAll() {
        for (Feature feature : features.values()) {
            enableOne(feature);
        }
    }

    /** 单个功能的启用流程。整个方法体包在兜底里，任何意外都不外抛。 */
    private void enableOne(Feature feature) {
        String id;
        try {
            id = feature.id();
        } catch (Throwable t) {
            // 连 id() 都拿不到：无法命名这个失败，但绝不能因此中断别人
            LOGGER.error("[mcoo] 某个功能的 id() 本身抛异常，该功能已跳过（不影响其它功能）", t);
            return;
        }

        FeatureRegistration registration = new FeatureRegistration(id);
        try {
            feature.enable(registration);
            live.put(id, registration);
        } catch (Throwable t) {
            // 关键：先把登记过的东西撤干净，再记失败。
            // 顺序反了的话，残留监听器会活得比这次启动还久。
            List<Throwable> rollbackProblems = registration.rollback();
            failures.put(id, describe(t));

            LOGGER.error("[mcoo] 功能 {} 启用失败，已停用（不影响其它功能，不影响服务器）", id, t);
            for (Throwable problem : rollbackProblems) {
                LOGGER.warn("[mcoo] 功能 {} 的回滚过程中又有异常（该功能已停用，不影响其它功能）",
                        id, problem);
            }
        }
    }

    /**
     * 关闭全部功能。**本方法保证不向外抛任何异常。**
     *
     * <p>关闭顺序与启用顺序相反（后启用的先关），符合依赖直觉。
     * 每个功能关完都会回滚登记器，{@link #liveCount()} 归零。
     */
    public void disableAll() {
        List<Feature> ordered = new ArrayList<>(features.values());
        Collections.reverse(ordered);

        for (Feature feature : ordered) {
            String id = safeId(feature);
            if (id == null) {
                continue;
            }
            try {
                feature.disable();
            } catch (Throwable t) {
                // 功能自己写的 disable 抛了：记下来，但继续关下一个，
                // 并且照样回滚它的登记器 —— 不回滚才是真正的泄漏。
                LOGGER.error("[mcoo] 功能 {} 的 disable() 抛异常，仍会回滚其登记内容", id, t);
            }
            rollbackQuietly(id);
        }
    }

    private void rollbackQuietly(String id) {
        FeatureRegistration registration = live.remove(id);
        if (registration == null || registration.isRolledBack()) {
            return;
        }
        List<Throwable> problems = registration.rollback();
        if (!problems.isEmpty()) {
            LOGGER.warn("[mcoo] 功能 {} 回滚时有 {} 项撤不干净（不影响其它功能）",
                    id, problems.size());
        }
    }

    private String safeId(Feature feature) {
        try {
            return feature.id();
        } catch (Throwable t) {
            LOGGER.error("[mcoo] 某个功能的 id() 抛异常，关闭时跳过", t);
            return null;
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    // ---------------- 只读查询（测试与 /mcoo status 用） ----------------

    /** 已注册的功能总数。 */
    public int size() {
        return features.size();
    }

    /** 当前仍在生效的功能数。 */
    public int enabledCount() {
        return live.size();
    }

    /**
     * 所有功能的登记残留总数。
     *
     * <p>**全部功能关闭后必须为 0**，这是"没有泄漏"的量化指标。
     */
    public int liveCount() {
        int total = 0;
        for (FeatureRegistration registration : live.values()) {
            total += registration.liveCount();
        }
        return total;
    }

    /** 启用失败的功能 id，按注册顺序。 */
    public List<String> failedIds() {
        return List.copyOf(failures.keySet());
    }

    /** 某个功能的失败原因；没失败则为空。 */
    public Optional<String> failureOf(String id) {
        return Optional.ofNullable(failures.get(id));
    }

    /** 是否所有功能都正常启用。 */
    public boolean allHealthy() {
        return failures.isEmpty();
    }
}
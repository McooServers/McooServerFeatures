package com.mcoo.core.diag;

import com.mcoo.core.feature.Feature;
import com.mcoo.core.feature.FeatureRegistration;
import com.mcoo.core.state.McooState;
import com.mcoo.core.state.StateStore;

/**
 * 存档持久化探针 —— <b>验证「写进去的数据能活过一次重启」</b>。
 *
 * <p><b>为什么单元测试不够</b>：{@code McooStateCodecTest} 能证明 codec 往返正确，
 * 但它测的是内存里的 Map。真盘走的是完全另一条路：
 * {@code setDirty()} → 世界存档 → NBT 落盘 → 下次启动读回来。
 * 这条路上任何一个环节没接对，单元测试都是绿的，玩家却会发现"每天奖励都没了"。
 *
 * <p><b>用法</b>：
 * <pre>
 * # 第一次：写入
 * .\tools\run-testserver.ps1 -JvmArgs '-Dmcoo.faultInject=probe'
 * # 第二次：读回，日志里应该显示「读到上次的值」，而不是「首次写入」
 * .\tools\run-testserver.ps1 -JvmArgs '-Dmcoo.faultInject=probe'
 * </pre>
 * 两次的 {@code probeValue} 必须一致 —— 这就证明存档真的落盘并读回了。
 *
 * <p><b>只读不写的那一半</b>：每次运行都会先把读到的旧值打进日志，
 * 这样"读回来了"这件事本身就是证据，不用去翻存档文件。
 */
public final class StateProbeFeature implements Feature {

    /** 存档分区名。独立于任何真实功能，不会污染它们的数据。 */
    public static final String SECTION = "probe";

    public static final String KEY = "probeValue";
    public static final String KEY_RUNS = "runCount";

    @Override
    public String id() {
        return "probe";
    }

    @Override
    public void enable(FeatureRegistration registration) {
        McooState state = StateStore.get();
        if (state == null) {
            System.err.println("[mcoo] 存档探针：存档不可用，跳过（不影响服务器）");
            return;
        }

        // 先读旧值 —— 读到了就说明上次的数据真的躺在盘上
        String previous = state.read(SECTION, KEY);
        int runs = state.readInt(SECTION, KEY_RUNS, 0) + 1;

        if (previous.isEmpty()) {
            System.out.println("[mcoo] 存档探针：首次写入（之前没有数据）");
        } else {
            System.out.println("[mcoo] 存档探针：读到上次的值 = " + previous
                    + "（第 " + runs + " 次运行）");
        }

        // 写入本次的值。这里用当前时间戳，保证每次运行都能看出值确实变了。
        // 万一被手工改成固定值，重复运行时也能靠 runCount 发现异常。
        String value = "run-" + runs + "-" + System.currentTimeMillis();
        state.write(SECTION, KEY, value);
        state.writeInt(SECTION, KEY_RUNS, runs);

        System.out.println("[mcoo] 存档探针：本次写入 = " + value);
    }

    @Override
    public void disable() {
    }
}
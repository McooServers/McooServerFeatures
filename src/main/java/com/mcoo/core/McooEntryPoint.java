package com.mcoo.core;

import com.mcoo.core.diag.SelfCheck;
import com.mcoo.core.diag.TestCommands;
import com.mcoo.core.feature.FeatureRegistration;
import com.mcoo.core.feature.FeatureRegistry;
import com.mcoo.core.state.StateStore;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * mod 唯一入口点。
 *
 * <p><b>本类只做两件事：登记功能、启用功能。业务逻辑一律不在这里。</b>
 *
 * <p>为什么这么写，见 {@code SAFETY.md}：
 * <ul>
 *   <li>§1.1 入口点抛异常会导致 Fabric 中断加载，连带其它 mod 加载失败，
 *       极端情况下服务器根本起不来。所以这里整体包 {@code catch (Throwable)}。</li>
 *   <li>§0 一律用 {@code Throwable} 而不是 {@code Exception}：
 *       OOM / StackOverflow / NoClassDefFound 都是 {@code Error}，
 *       而这些恰恰是「代码写错了」最典型的后果。</li>
 * </ul>
 *
 * <p><b>为什么启用功能放在 SERVER_STARTING 事件里</b>：
 * 初始化界面、读存档、注册命令都要求服务器已经有 {@code MinecraftServer} 实例。
 * 在 {@code onInitialize} 阶段拿不到，提前做只会拿到空引用。
 */
public final class McooEntryPoint implements ModInitializer {

    /** mod id。所有资源命名空间、命令前缀都以此为准。 */
    public static final String MOD_ID = "mcoo";

    /** mod 显示名（中文）。 */
    public static final String MOD_NAME = "轻松维护";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * 功能注册表。服务器运行期间只有这一个实例。
     *
     * <p>用静态字段是因为 Fabric 只保证入口点被调用一次，
     * 而 {@code SERVER_STARTING} 的回调需要拿到同一个注册表。
     * 它<b>不可变引用</b>（注册表内部才是可变状态），且只在启动流程里读写。
     */
    private static final FeatureRegistry REGISTRY = new FeatureRegistry();

    @Override
    public void onInitialize() {
        // 入口点绝不允许把异常抛出去 —— 兜住 Throwable，失败也只是少一行日志。
        try {
            // ---- 登记阶段：只登记，不启用 ----

            // 自检模式。默认不带 -Dmcoo.faultInject 时，这里什么都不做。
            SelfCheck.installIfRequested(REGISTRY);

            // 【将来在这里登记真实功能】
            // REGISTRY.register(new DailyFeature());
            // REGISTRY.register(new GiftFeature());
            //
            // 登记必须逐个包 try —— 一个功能的构造/register 炸了，
            // 不能连带后面的功能登记不上。
            // 真正的隔离由 FeatureRegistry 完成，这里只负责"别自己先炸"。

            FabricLoader.getInstance()
                    .getModContainer(MOD_ID)
                    .ifPresentOrElse(
                            container -> LOGGER.info("{} v{} 已加载（仅服务端）",
                                    MOD_NAME, container.getMetadata().getVersion()),
                            () -> LOGGER.info("{} 已加载（仅服务端）", MOD_NAME));

            // ---- 启用阶段：等到服务器真的起来了再启用功能 ----
            // 用 lambda 而不是方法引用，纯粹是可读性：
            //   register(McooEntryPoint::enableAllSafely);        ← 方法引用，对外简洁
            //   register(server -> enableAllSafely(server));      ← lambda，把"参数原样传下去"写明了
            // 两者编译产物等价，这里选后者是因为它把调用关系摊开写，更容易读懂。
            ServerLifecycleEvents.SERVER_STARTING.register(server -> enableAllSafely(server));

        } catch (Throwable t) {
            // 连"打个日志"都失败时，用 System.err 兜底，仍然不让异常逃出去。
            System.err.println("[" + MOD_ID + "] 入口日志失败（不影响加载）：" + t);
        }
    }

    /**
     * 启用全部功能。整个方法包在兜底里。
     *
     * <p>{@link FeatureRegistry#enableAll()} 本身已经保证不外抛，
     * 这里再包一层是为了防"注册表之外的意外"（比如日志系统出问题）。
     */
    private static void enableAllSafely(MinecraftServer server) {
        // 这张登记表不属于任何功能 —— 它属于 core 自己，
        // 所以用独立的登记器，停服时统一回滚。
        FeatureRegistration coreCleanup = new FeatureRegistration("core");

        try {
            // 先绑定存档。绑定失败不抛异常，只是功能没有历史数据。
            StateStore.bind(server, coreCleanup);

            // 临时诊断指令。不带 -Dmcoo.testCommands 时这里什么都不做，
            // 所以上线时不存在"测试用的东西漏进生产服"的风险。
            TestCommands.install(server);

            REGISTRY.enableAll();
            reportStatus();
        } catch (Throwable t) {
            // 到这一步已经是最后一道保险：无论如何，服务器要继续启动。
            LOGGER.error("[mcoo] 启动流程出现意外，已全部跳过（不影响服务器）", t);
        } finally {
            // 登记一条"停服时清理"，让 core 自己的状态也能被回滚，
            // 不依赖 SERVER_STOPPED 事件的注册顺序。
            //
            // 注意这里不登记到任何功能的登记表里 —— core 不属于功能，
            // 功能全部停用时 core 仍然要正常清理。
            ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> coreCleanup.rollback());
        }
    }

    private static void reportStatus() {
        int total = REGISTRY.size();
        int active = REGISTRY.enabledCount();
        List<String> failed = REGISTRY.failedIds();

        LOGGER.info("[mcoo] 存档：{}", StateStore.isBound() ? "已就绪" : "不可用（功能将没有历史数据）");

        if (failed.isEmpty()) {
            LOGGER.info("[mcoo] 功能就绪：{}/{} 个已启用", active, total);
            return;
        }
        // 失败不是异常情况，是设计内的降级。语气要说清楚"服务器没事"。
        LOGGER.warn("[mcoo] 功能降级：{} 个已停用（已停用的功能相当于不存在，不影响服务器）", failed);
        LOGGER.warn("[mcoo]   停用列表：{}", String.join("、", failed));
        for (String id : failed) {
            REGISTRY.failureOf(id).ifPresent(reason ->
                    LOGGER.warn("[mcoo]   {} 失败原因：{}", id, reason));
        }
    }
}
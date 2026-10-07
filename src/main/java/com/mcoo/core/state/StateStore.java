package com.mcoo.core.state;

import com.mcoo.core.compat.SavedDataCompat;
import com.mcoo.core.feature.FeatureRegistration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 存档的持有者与取用入口。
 *
 * <p>功能**不直接**碰 {@link MinecraftServer} 或 {@code SavedDataStorage}，
 * 只通过这里拿到一个 {@link McooState}。这样做的好处：
 * <ul>
 *   <li>功能不需要知道存档挂在哪个维度、用什么 id —— 那些都是 core 的事</li>
 *   <li>读存档失败时（存档坏了、维度还没准备好）返回 null 而不是抛异常，
 *       调用方按"没有存档"处理即可</li>
 *   <li>升 Minecraft 版本时，这里是唯一需要改的地方之一</li>
 * </ul>
 *
 * <p><b>生命周期</b>：在 {@code SERVER_STARTING} 时绑定，在 {@code SERVER_STOPPED} 时解绑。
 * 两者之间 {@link #get()} 才有值；之外返回 null。
 */
public final class StateStore {

    private static final SavedDataType<McooState> TYPE = SavedDataCompat.type(
            McooState.SAVE_ID, McooState::new, McooState.CODEC);

    /**
     * 当前绑定的存档。
     *
     * <p>用 {@code volatile}：绑定发生在主线程，但异步任务可能在别的线程读。
     * 这里不用全局可变静态状态那种危险写法 —— 它不可变，只是可重新指向。
     */
    private static volatile McooState current;

    private StateStore() {
    }

    /**
     * 绑定存档。由 core 在服务器启动时调用。
     *
     * <p><b>任何异常都在这里被吞掉</b>：存档坏了最多是功能没有历史数据，
     * 绝不能让服务器起不来。
     *
     * @param registration 用于登记一条「服务器停服时解绑」的清理动作
     */
    public static void bind(MinecraftServer server, FeatureRegistration registration) {
        try {
            McooState state = server.getDataStorage().computeIfAbsent(TYPE);
            current = state;
            registration.track(StateStore::unbind);
        } catch (Throwable t) {
            // 存档读不出来（文件损坏 / 维度未就绪 / MC 版本不兼容）……
            // 对玩家来说就是"这个 mod 好像没生效"，比服务器起不来好得多。
            System.err.println("[mcoo] 存档绑定失败，功能将没有历史数据（不影响服务器）：" + t);
            current = null;
        }
    }

    /** 解绑。由 core 在服务器停服时调用，也可以由注册表回滚触发。 */
    public static void unbind() {
        current = null;
    }

    /**
     * 取当前存档；还没绑定或绑定失败则返回 {@code null}。
     *
     * <p><b>调用方必须判空。</b>这是有意的：绝大多数功能在"没有存档"时
     * 应该直接当新玩家处理，而不是去造一个假存档把状态搅乱。
     */
    public static McooState get() {
        return current;
    }

    /** 存档是否可用。给日志和 /mcoo status 用。 */
    public static boolean isBound() {
        return current != null;
    }
}
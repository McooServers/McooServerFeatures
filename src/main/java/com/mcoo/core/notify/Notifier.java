package com.mcoo.core.notify;

import com.mcoo.core.compat.SavedDataCompat;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 通知工具：往玩家屏幕上发消息。
 *
 * <p>三种形式，用途不同：
 * <ul>
 *   <li><b>聊天栏</b>（{@code sendSystemMessage}）—— 重要、需要玩家记住的事，比如"礼物已到账"</li>
 *   <li><b>动作栏</b>（{@code sendOverlayMessage}）—— 短暂的，比如"在线时长 +1 分钟"</li>
 *   <li><b>标题</b> —— 留给 M3 GUI 打开时用</li>
 * </ul>
 *
 * <p><b>注意 26.2 的 API 改名</b>：动作栏现在是
 * {@link ServerPlayer#sendOverlayMessage}，不再是旧版本的
 * {@code displayClientMessage}。照抄旧教程必然编译失败。
 *
 * <p><b>每个方法都包 {@code catch (Throwable)}</b>：
 * 通知失败绝不能让玩家的领取流程跟着失败。
 */
public final class Notifier {

    private static final Component PREFIX =
            Component.literal("[轻松维护] ").withStyle(style -> style.withColor(0x55FFFF));

    private Notifier() {
    }

    /** 发到聊天栏。用于重要通知。 */
    public static void chat(ServerPlayer player, String message) {
        try {
            if (player == null) {
                return;
            }
            player.sendSystemMessage(PREFIX.copy().append(Component.literal(message)));
        } catch (Throwable t) {
            quietFail("聊天栏通知", t);
        }
    }

    /** 发到动作栏（屏幕下方那行）。用于短暂提示。 */
    public static void overlay(ServerPlayer player, String message) {
        try {
            if (player == null) {
                return;
            }
            player.sendOverlayMessage(Component.literal(message));
        } catch (Throwable t) {
            quietFail("动作栏通知", t);
        }
    }

    /**
     * 发一个带颜色强调的聊天通知。
     *
     * @param color 0xRRGGBB
     */
    public static void chat(ServerPlayer player, String message, int color) {
        try {
            if (player == null) {
                return;
            }
            player.sendSystemMessage(
                    PREFIX.copy().append(
                            Component.literal(message).withStyle(style -> style.withColor(color))));
        } catch (Throwable t) {
            quietFail("彩色通知", t);
        }
    }

    /**
     * 通知失败只打日志，不往上抛。
     *
     * <p>这里刻意不用 mod 的 LOGGER：通知工具可能被在
     * {@code SERVER_STARTING} 之前调用，那时日志系统还没完全就绪。
     * 兜底用 {@code System.err} 更稳 —— 它一定会出现在服务器控制台。
     */
    private static void quietFail(String what, Throwable t) {
        System.err.println("[mcoo] " + what + "失败（已忽略，不影响玩家操作）：" + t);
    }
}
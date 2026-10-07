package com.mcoo.core.diag;

import com.mcoo.core.McooEntryPoint;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>临时诊断指令</b> —— 只为了人工验证「vanilla 客户端能不能正常使用界面」。
 *
 * <p>平时<b>根本不存在</b>：注册与否由系统属性 {@code mcoo.testCommands} 决定，
 * 和 {@link SelfCheck} 同一套做法。这样上线时不会有任何"测试用的东西漏进生产服"的风险，
 * 也就省掉了"要不要给它加权限、要不要提醒管理员关掉"这一堆后续麻烦。
 *
 * <p><b>为什么需要它</b>：M3 的全部逻辑自检（22 项）都在服务器内部模拟点击完成，
 * 证明不了"客户端真的会把这个界面渲染出来、点得动"—— 而那正是验收标准 C，
 * 也是"仅服务端 mod 也能开 GUI"这个赌注的唯一证据。必须有人真的点一遍。
 *
 * <p><b>用法</b>：
 * <pre>
 * .\tools\run-testserver.ps1 -NoAutoStop -JvmArgs '-Dmcoo.testCommands'
 * # 玩家进服后执行：/mcoo gui
 * </pre>
 *
 * <p>M4 的 {@code daily} 功能上线后，这个演示界面连同本类一起删掉，
 * 换成正式的 {@code /mcoo daily}。
 */
public final class TestCommands {

    /** 打开本指令的系统属性名。不设这个属性时，本指令完全不存在。 */
    public static final String PROPERTY = "mcoo.testCommands";

    /**
     * 这个开关打算是怎么开的。
     *
     * <p><b>坑：</b>{@code -Dmcoo.testCommands}（不带 {@code =值}）在 Java 里
     * 把属性设成<b>空串</b>，不是 {@code null}。
     * 所以写成 {@code "true".equals(属性)} 会判成"没开"，
     * 而使用者明明敲了开关 —— 这种 bug 极难查，因为"没生效"看起来像功能没写。
     *
     * <p>所以这里按布尔开关的直觉语义处理：
     * <ul>
     *   <li>完全没这个属性 → 关</li>
     *   <li>{@code -Dmcoo.testCommands}（空串）→ <b>开</b>（写了就算数）</li>
     *   <li>{@code -Dmcoo.testCommands=false} / {@code 0} / {@code no} → 关</li>
     * </ul>
     */
    private static boolean isEnabled() {
        String raw = System.getProperty(PROPERTY);
        if (raw == null) {
            return false;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return !v.equals("false") && !v.equals("0") && !v.equals("no");
    }

    private TestCommands() {
    }

    /**
     * 服务器起来后登记指令。调用方必须已经包好兜底。
     *
     * @return 真的登记了返回 true；没设属性返回 false
     */
    public static boolean install(MinecraftServer server) {
        if (!isEnabled()) {
            return false;
        }

        // 刻意【不】加权限检查。
        //
        // 原来这里挂过 hasPermission(MODERATORS)，结果踩了个很典型的坑：
        // 权限不通过时 brigadier 会把整棵命令树【隐藏】，
        // 玩家看到的是「未知或不完整的命令」—— 这句话没有任何指向性，
        // 既看不出是权限问题，也看不出命令其实存在，第一次就卡住了。
        //
        // 而它保护的是什么？一条临时测试指令，能改的只有一个内存里的点击计数器。
        // 注册与否已经被 -Dmcoo.testCommands 这个开关完全管住了，
        // 权限门在这里不保护任何东西，只制造静默失败。
        //
        // **给人用的测试指令，宁可少一道门，也不要让人卡在看不出原因的错误上。**
        // 真正的功能指令（M4 起）该加权限必须加，而且失败要给出人话提示，
        // 不能让它退化成一个"未知命令"。
        server.getCommands().getDispatcher().register(
                Commands.literal("mcoo")
                        .then(Commands.literal("gui")
                                .then(Commands.literal("dump")
                                        // 带玩家名：给【控制台】用。
                                        // 原版里容器界面一打开聊天框就打不开，
                                        // 所以玩家想在自己开着的箱子界面里敲命令是做不到的 ——
                                        // 只能由服务端控制台代敲，这里就必须支持"指定某个在线玩家"。
                                        .then(Commands.argument("player",
                                                        com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .suggests((ctx, builder) -> {
                                                    var list = ctx.getSource().getServer()
                                                            .getPlayerList().getPlayers();
                                                    for (ServerPlayer p : list) {
                                                        builder.suggest(p.getName().getString());
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .executes(ctx -> dumpLayout(
                                                        ctx, ctx.getArgument("player", String.class))))
                                        .executes(ctx -> dumpLayout(ctx, null)))
                                .executes(ctx -> openScreen(ctx, false)))
                        .then(Commands.literal("admingui")
                                .executes(ctx -> openScreen(ctx, true))));

        McooEntryPoint.LOGGER.info("[mcoo] 测试指令已启用：/mcoo gui、/mcoo admingui、"
                + "/mcoo gui dump [玩家名]"
                + "（仅在带 " + PROPERTY + " 属性的测试服上存在，上线服不会有这些指令）");
        return true;
    }

    /**
     * 打开演示界面。
     *
     * @param admin true = 管理界面（红边框那套），false = 玩家界面
     */
    private static int openScreen(CommandContext<CommandSourceStack> ctx, boolean admin) {
        CommandSourceStack source = ctx.getSource();
        try {
            ServerPlayer player = source.getPlayer();
            if (player == null) {
                source.sendFailure(Component.literal("这个指令只能由玩家在客户端敲"));
                return 0;
            }
            com.mcoo.core.gui.ChestGui gui = admin
                    ? new DemoFrameGui.AdminL1()
                    : new DemoFrameGui.PlayerL1();
            if (!gui.open(player)) {
                source.sendFailure(Component.literal(
                        "没能打开界面（可能已经开着一个了，详见服务端日志）"));
                return 0;
            }
            source.sendSuccess(() -> Component.literal(admin
                    ? "已打开【管理界面】：邮箱管理 → 接受玩家范围 → 点玩家头。"
                    : "已打开【玩家界面】：礼物 → 收件人范围 → 点玩家头。"), false);
            return 1;
        } catch (Throwable t) {
            source.sendFailure(Component.literal("打开界面失败，服务器不受影响，详见服务端日志"));
            McooEntryPoint.LOGGER.error("[mcoo] 测试界面打开失败", t);
            return 0;
        }
    }

    /**
     * 导出箱子布局。
     *
     * @param name 指定在线玩家；为 null 时用指令来源自己
     */
    private static int dumpLayout(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = resolveTarget(source, name);
        if (player == null) {
            return 0;   // 原因已经回报过了
        }
        final ServerPlayer target = player;

        try {
            String report = LayoutDump.dump(target, msg -> {
                source.sendFailure(Component.literal(msg));
                McooEntryPoint.LOGGER.warn("[mcoo][布局导出] {}：{}", target.getName().getString(), msg);
            });
            if (report == null) {
                return 0;
            }
            // 日志是 AI 真正读的输入；聊天栏只是让人知道导出成功了。
            McooEntryPoint.LOGGER.info("[mcoo][布局导出] 玩家 {}\n{}",
                    target.getName().getString(), report);
            source.sendSuccess(() -> Component.literal(
                    "已导出 " + target.getName().getString()
                            + " 的箱子布局（服务端日志里有完整格子图和图例）。"), false);
            return 1;
        } catch (Throwable t) {
            source.sendFailure(Component.literal("导出失败，服务器不受影响，详见服务端日志"));
            McooEntryPoint.LOGGER.error("[mcoo] /mcoo gui dump 执行失败", t);
            return 0;
        }
    }

    /**
     * 决定这次要导出谁的箱子。
     *
     * @param name 指定在线玩家；为 null 时用指令来源自己
     * @return 玩家；null 表示没定下来，原因已回报
     */
    private static ServerPlayer resolveTarget(CommandSourceStack source, String name) {
        if (name != null) {
            ServerPlayer named = source.getServer().getPlayerList().getPlayerByName(name);
            if (named == null) {
                source.sendFailure(Component.literal(
                        "找不到在线玩家「" + name + "」。请确认对方正连着服务器。"));
            }
            return named;
        }

        ServerPlayer self = source.getPlayer();
        if (self != null) {
            return self;
        }

        // 控制台执行且没给名字。线上不止一个人时绝不能瞎猜一个 ——
        // 读错人的箱子，导出来的排版就是错的，而且很难发现。
        var online = source.getServer().getPlayerList().getPlayers();
        if (online.size() == 1) {
            return online.get(0);
        }
        source.sendFailure(Component.literal(
                "控制台执行必须指定玩家：mcoo gui dump <名字>"
                        + (online.isEmpty() ? "（当前没有在线玩家）"
                        : "（在线：" + online.stream()
                                .map(p -> p.getName().getString()).toList() + "）")));
        return null;
    }
}
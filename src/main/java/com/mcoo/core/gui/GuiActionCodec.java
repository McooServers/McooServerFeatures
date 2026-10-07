package com.mcoo.core.gui;

import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * 把「点了这个按钮要干什么」编码进按钮物品里。
 *
 * <p><b>为什么编码进物品本身</b>：原版箱子界面的槽位只是 9×N 个格子，
 * 客户端只认得"这一格有个物品"，不认得"这一格是个按钮"。
 * 所以服务端必须自己记下"第几格 = 什么动作"，而物品是这个界面上唯一
 * 能跟着格子走的载体 —— 玩家拖动物品时格子会变，编码跟着物品走就不会错位。
 *
 * <p><b>用的是 26.2 的数据组件 {@code DataComponents.CUSTOM_DATA}</b>
 * （26.2 起物品格式全面改成 data components，不再用旧版 NBT tag）。
 *
 * <p><b>26.2 的改名提醒</b>：这个类在
 * {@code net.minecraft.world.item.component.CustomData}，
 * <b>不是</b> {@code net.minecraft.core.component.CustomData}（后者不存在）。
 */
public final class GuiActionCodec {

    /** 存动作 id 的键。带 {@code mcoo} 前缀，避免和其它 mod 的自定义数据撞键。 */
    private static final String KEY_ACTION = "mcooAction";

    /**
     * 背景标记的键。
     *
     * <p><b>为什么需要单独一个标记</b>：界面上的物品分三类 ——
     * 按钮（有动作）、背景填充（点它什么也不该发生）、以及
     * 「本该是按钮却忘了编码」的程序 bug。
     *
     * <p>后两者都表现为"没有动作 id"，如果不加标记就无法区分，
     * 结果就是玩家每点一下背景都往 stderr 打一条
     * 「漏了 encodeAction」的警告。生产环境下这会被刷成刷屏，
     * 而刷屏的真正代价是**让管理员学会忽略警告** ——
     * 哪天真的漏了编码，那条警告也已经没人看了。
     */
    private static final String KEY_FILLER = "mcooFiller";

    private GuiActionCodec() {
    }

    /**
     * 给按钮物品写入动作 id。
     *
     * <p>动作 id 的约定：{@code 动作名:参数}，例如 {@code claim:day1}。
     * 冒号前是动作名，冒号后是参数 —— 参数部分的具体含义由各个功能自己定，
     * core 不解析。
     *
     * @throws IllegalArgumentException 动作 id 为空
     */
    public static void write(ItemStack stack, String actionId) {
        if (actionId == null || actionId.isBlank()) {
            throw new IllegalArgumentException("动作 id 不能为空");
        }
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_ACTION, actionId);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /**
     * 读出动作 id；这个物品不是我们的按钮则返回空。
     *
     * <p>空槽（{@link ItemStack#isEmpty()}）和被玩家改过的普通物品都会返回空，
     * 调用方按"这不是按钮"处理即可。
     */
    public static Optional<String> read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return Optional.empty();
        }
        // 26.2 的 CompoundTag.getString 返回 Optional<String>（旧版直接返回 String）。
        // getStringOr 在键不存在时给空串，正好省掉一次判空。
        String action = data.copyTag().getStringOr(KEY_ACTION, "");
        return action.isEmpty() ? Optional.empty() : Optional.of(action);
    }

    /**
     * 这个物品是不是本 mod 的按钮。
     *
     * <p>比 {@link #read} 更省事：不需要拿到具体动作时用它。
     */
    public static boolean isButton(ItemStack stack) {
        return read(stack).isPresent();
    }

    /**
     * 标记这是背景填充物 —— 点它不触发任何动作，属于预期行为。
     *
     * <p>由 {@link GuiCanvas#fill} 自动调用，功能不需要关心。
     */
    public static void markFiller(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(KEY_FILLER, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /**
     * 这个物品是不是背景填充物。
     *
     * <p>{@code fill} 铺的背景、刻意摆的无动作展示品都算 ——
     * 它们「点了没反应」是设计内的，不该被当成 bug 报警。
     */
    public static boolean isFiller(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && !data.isEmpty()
                && data.copyTag().getBooleanOr(KEY_FILLER, false);
    }

    /**
     * 拆出动作名（冒号前）。
     *
     * <p>整个 id 没有冒号时，原样返回。
     */
    public static String actionName(String actionId) {
        int colon = actionId.indexOf(':');
        return colon < 0 ? actionId : actionId.substring(0, colon);
    }

    /**
     * 拆出动作参数（冒号后）。没有冒号则返回空串。
     */
    public static String actionArg(String actionId) {
        int colon = actionId.indexOf(':');
        return colon < 0 ? "" : actionId.substring(colon + 1);
    }
}
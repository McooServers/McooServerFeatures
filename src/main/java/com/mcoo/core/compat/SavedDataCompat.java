package com.mcoo.core.compat;

import com.mojang.serialization.Codec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * 版本敏感 API 的收口处。
 *
 * <p><b>本包存在的唯一理由</b>：Minecraft 改存档 API 时，改这里一处即可。
 * 升版本时 {@code grep -r "core.compat" src/main} 就能列出所有受影响的调用点，
 * 逐个核对，而不是全代码库大海捞针。见 {@code AGENTS.md} §3.5。
 *
 * <p><b>这里放什么**：对原版 API 的薄包装，一个方法一两句调用。
 * <b>这里不放什么</b>：业务逻辑、状态机、实体。放了就不是隔离层而是第二层 core。
 *
 * <p><b>26.2 的存档 API 与旧版本差别很大</b>（这些是实测出来的，不是猜的）：
 * <ul>
 *   <li>{@code PersistentState}（Fabric 旧抽象）<b>已不存在</b>，
 *       Fabric API 0.161.0 里连 persistence 模块都没了，只剩原版的 {@code SavedData}</li>
 *   <li>{@code DimensionDataStorage} 改名成
 *       {@code net.minecraft.world.level.storage.SavedDataStorage}</li>
 *   <li><b>{@code SavedData} 上没有 {@code save}/{@code load} 抽象方法</b>，
 *       存盘完全由 {@code SavedDataType} 里那个 {@link Codec} 负责</li>
 *   <li>{@code ResourceLocation} 改名成 {@code Identifier}</li>
 * </ul>
 * <b>最后一条尤其要小心</b>：网上和旧项目里的教程全是 {@code ResourceLocation}，
 * 照抄必然编译失败。
 */
public final class SavedDataCompat {

    /**
     * 我们自定义存档的 {@code DataFixTypes}。
     *
     * <p>{@code DataFixTypes} 只影响 Mojang 自己的 DataFixer 升级管线。
     * 我们自己的数据有自己的 {@link Codec}，字段结构由自己掌控，
     * 所以复用通用的 {@link DataFixTypes#LEVEL} 即可，不需要也不能自己造一个。
     */
    public static final DataFixTypes DATA_FIX_TYPE = DataFixTypes.LEVEL;

    /** mod 的命名空间前缀，所有存档 id 都以它开头。 */
    public static final String NAMESPACE = "mcoo";

    private SavedDataCompat() {
    }

    /**
     * 造一个 {@link SavedDataType}。
     *
     * <p>抽出来单独一个方法，是为了把这个「四参数且顺序容易记错」的构造
     * 收敛到一处。参数顺序错了编译器不一定会拦，但存档 id 会跟着错。
     *
     * @param id        存档的唯一标识，会变成存档文件名的一部分，改了等于换存档
     * @param supplier  读不到存档时用的默认实例（必须是"空但可用"的）
     * @param codec     存盘/读盘的编解码器
     */
    public static <T extends SavedData> SavedDataType<T> type(
            String id, java.util.function.Supplier<T> supplier, Codec<T> codec) {
        return new SavedDataType<>(
                identifier(id), supplier, codec, DATA_FIX_TYPE);
    }

    /** 把 {@code "mcoo:main"} 这种字符串转成 {@link Identifier}。 */
    public static Identifier identifier(String path) {
        return Identifier.parse(path.indexOf(':') >= 0 ? path : NAMESPACE + ":" + path);
    }
}
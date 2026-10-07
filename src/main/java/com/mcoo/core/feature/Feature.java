package com.mcoo.core.feature;

/**
 * 一个功能（功能模块）的统一入口。
 *
 * <p><b>为什么接口放在 {@code core} 而不是 {@code feature} 包下：</b>
 * 因为注册表 {@link FeatureRegistry} 在 core 里，它要引用本接口。
 * 如果接口在 {@code com.mcoo.feature} 下，core 就得 import feature，
 * 直接违反 {@code AGENTS.md} §3.1 的依赖方向。放在 core 下，
 * 方向才是干净的 {@code feature → core}。
 *
 * <p><b>不声明 checked exception</b>：{@link FeatureRegistry} 兜的是
 * {@code Throwable}，接口若声明 {@code throws} 反而会诱导实现者
 * 把异常当成"必须处理"的契约，而不是"兜底后降级"的常态。
 */
public interface Feature {

    /**
     * 功能标识，全局唯一。
     *
     * <p>用于日志、配置命名空间、存档分区、{@code /mcoo} 子命令。
     * 必须稳定：改了等于换了存档分区，老数据会被当成孤儿。
     *
     * @return 唯一标识，例如 {@code daily}
     */
    String id();

    /**
     * 启用功能。
     *
     * <p><b>允许抛任何异常，注册表会兜住并降级</b>，不会让异常逃出去。
     * 但请注意：enable 抛异常意味着这个功能**当次启动就是关闭状态**，
     * 玩家的体验是"功能不存在"，不是"功能出错但还能用"。
     *
     * <p>凡是要往服务器注册东西（事件监听、命令、菜单、任务），
     * 都必须通过参数 {@code registration} 登记，**不要自己记着反注册**：
     * 注册表会在功能关闭或出错时自动回滚，漏写 disable 的后果才不会扩散到别的功能。
     *
     * @param registration 本功能的登记器，用来登记需要在关闭时回滚的东西
     */
    void enable(FeatureRegistration registration);

    /**
     * 关闭功能，释放 {@link #enable} 之外持有的资源（例如停掉定时器、清缓存）。
     *
     * <p>登记过的东西注册表会自动回滚，这里**不需要**再手动反注册。
     * 本方法同样允许抛异常，注册表会兜住。
     */
    void disable();
}
package com.mcoo.core.diag;

import com.mcoo.core.feature.Feature;
import com.mcoo.core.feature.FeatureRegistration;

/**
 * 把一段自检代码包成一个功能，这样它能走正常的启用流程。
 *
 * <p>这样设计是为了让自检<b>跑在真实的启用路径上</b>：
 * 如果自检逻辑直接写在入口里，它就绕开了「功能被隔离、被兜底」这一层，
 * 自检通过了也不能说明功能路径是通的。
 */
final class SelfCheckFeature implements Feature {

    private final Runnable body;

    SelfCheckFeature(Runnable body) {
        this.body = body;
    }

    @Override
    public String id() {
        return "guiSelfCheck";
    }

    @Override
    public void enable(FeatureRegistration registration) {
        body.run();
    }

    @Override
    public void disable() {
    }
}

// 干净样本：这些 import 是【合法】的，ArchGuardTest 不许把它们报成违规。
//
// 这条测试很重要：如果守卫见谁咬谁，它会在第一次误伤时被人手动关掉，
// 那之后它就再也拦不住任何东西了。

package com.mcoo.feature.daily;

import com.mcoo.core.feature.Feature;
import com.mcoo.core.feature.FeatureRegistration;
import com.mcoo.core.feature.FeatureRegistry;

// 普通数据类型是允许的 —— 送礼/奖励功能天生要处理这些。
// 禁掉它们没有意义，只会逼着人写更绕的代码。
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

class LegalImports {
}
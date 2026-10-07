// 【故意违规样本】功能之间不得互相依赖。详见同目录 README 说明。

package com.mcoo.feature.gift;

// 【违规 2】gift 不得 import daily。
// 有了这条依赖，删掉 daily 目录就会连带 gift 编译失败，
// "删一个功能目录、其余照常"这个验收标准直接作废。
import com.mcoo.feature.daily.DailyScheduler;

class ViolationFeatureImportsFeature {
}
// 【故意违规样本】功能之间不得互相依赖。详见同目录 README 说明。

package com.mcoo.feature.daily;

// 【违规 2】反向的依赖同样禁止：daily 也不得 import gift。
// 只测一个方向的话，把两个功能合并成一个文件就能绕过检查。
import com.mcoo.feature.gift.GiftService;

class ViolationFeatureImportsOtherDirection {
}
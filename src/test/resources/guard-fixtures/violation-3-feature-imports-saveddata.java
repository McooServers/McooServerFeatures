// 【故意违规样本】功能不得直接碰存档。详见同目录 README 说明。

package com.mcoo.feature.daily;

// 【违规 3】直接 import 存档类 = 可以绕开 McooState 自己写盘。
// 存档是"坏档"唯一的入口，必须收敛到 core 的 McooState 一处。
import net.minecraft.world.level.saveddata.SavedData;

class ViolationFeatureImportsSavedData {
}
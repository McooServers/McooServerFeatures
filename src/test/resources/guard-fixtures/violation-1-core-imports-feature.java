// ============================================================
//  【故意违规样本】这些文件永远不会被编译。
//
//  放在 src/test/resources/ 下，Gradle 只当资源拷贝，不会编译。
//  存在的唯一目的：让 ArchGuardTest 能证明"它真的能抓到违规"，
//  而不是只证明"它跑起来没报错"—— 一个永远绿的守卫等于没有守卫。
//
//  ⚠️ 不要试图让它们编译通过，也不要"修好"它们。
//     修好了，ArchGuardTest 的反向测试会立刻变红（因为抓不到违规了），
//     那正是它该有的反应。
// ============================================================

package com.mcoo.core;

// 【违规 1】core 不得 import 任何具体功能。
// 一旦 core 认识 daily，就再也删不掉 daily 这个功能了。
import com.mcoo.feature.daily.DailyFeature;

class ViolationCoreImportsFeature {
}
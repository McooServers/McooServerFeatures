// 【故意违规样本】通配符 import 不得绕过规则。详见同目录 README 说明。

package com.mcoo.feature.daily;

// 【违规 4】通配 import 同样算违规。
// 如果只精确匹配包名，写一个 `com.mcoo.feature.*` 就能把整个依赖面偷进来。
import net.minecraft.world.level.saveddata.*;

// 【违规 4b】通配 import 到别的功能，同样要被抓到。
import com.mcoo.feature.*;

class ViolationWildcardImport {
}
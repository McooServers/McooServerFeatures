# McooServerFeatures

McooServers 的服务端 Fabric mod 集合。目标版本 Minecraft Java **26.2**。

玩家不需要安装任何东西，vanilla 客户端直接可连。

## 功能

| 功能 | 说明 | 状态 |
|---|---|---|
| `daily` | 每日在线时长奖励 | 开发中 |
| `gift` | 送礼：发布 / 领取 / 定时发放 | 开发中 |

目前没有上线任何功能。jar 装上后只在日志里留一行加载提示，不改变服务器行为。

## 设计目标

可以有 bug，但不能影响服务器运行。

为此：

- 不写 mixin
- 只用原版箱子界面，不注册自定义 MenuType
- 功能之间不互相依赖，失效互不影响
- 存档走 `SavedData`
- 物品发放前后都做校验

## 安装

Minecraft 26.2 + Fabric Loader 0.19.5 或更高，依赖 Fabric API 0.161.0+26.2。

把 `mcoo-server-features-<版本>.jar` 放进服务器 `mods/` 目录。

启动后日志应出现：

```
[mcoo] 轻松维护 v0.0 已加载（仅服务端）
```

## 构建

需要 JDK 25。

```powershell
./gradlew.bat build
```

产物在 `build/libs/`。

## 配置

配置在服务器运行目录的 `config/mcoo/` 下，JSON 格式：

```
config/mcoo/
├─ gift.json      # 送礼：礼物定义、发放对象、时限
└─ daily.json     # 每日奖励：档位、重置时间
```

改配置不需要重新编译，重启服务器生效。

## 加一个新功能

以 `shop` 为例：

1. 新建 `src/main/java/com/mcoo/feature/shop/`
2. 实现 `Feature` 接口：`id()` / `enable()` / `disable()`
3. 在 `McooEntryPoint` 里登记一行
4. 写该功能自己的测试
5. `./gradlew.bat build`

功能之间不互相依赖，不需要改 core。参考实现见 `src/main/java/com/mcoo/core/`。
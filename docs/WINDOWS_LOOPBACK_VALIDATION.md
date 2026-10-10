# Windows 生产链路隔离验证

本轮在 Windows Frank 使用已有 JDK 21.0.12.1+1、Gradle 8.14.3 和缓存；Gradle 始终 `--offline`。HTTP 仅限测试进程自己的 MockWebServer 回环端口。未使用实服、真实账号、旧配置密码或真实存档，未下载依赖、源码或工具。Git 提交推送及远端 HEAD 核验另行执行。

## 生产链路覆盖

`ProductionWebDavFlowTest` 使用真实 `AppendOnlyWebDav`、`AppendProfileSession`、默认 `ShatteredProfileVersions`、`ConnectionSetup` 和 `ConfigFile`。A/B 分别有独立存档、应用状态和配置；本次通过 `PDSYNC_TEST_GAME_JAR` 指向机器已有的官方 `desktop-3.3.8.jar`。没有该变量时，便携 CI 使用明确标注的元数据 fixture JAR。

| 路径 | 断言 |
| --- | --- |
| 首次上传 → B 恢复 | 全文件摘要、runUUID、已校验基线和目录 sidecar 一致 |
| A/B 各改不同局 | 两槽合并，不额外保留旧基线为第三槽 |
| 同一局各自推进 | 保留两个完整分支；新 UUID 记录共同来源 |
| 六槽再分叉 | 需要七槽即拒绝；本机和远端摘要均不变，无待上传残留 |
| 全局记录双方不同 | 拒绝猜测；不混拼 rankings、徽章或日志 |
| 提交已被接收但返回错误且回读失败 | 重启后沿用同一冻结键和值；不重复创建提交 |
| 对象下载失败 | 恢复前停止，空目标、基线及待上传状态不变；网络恢复后可重试 |
| sidecar 应用失败 | 游戏文件回滚，保留备份和重启保护；明确归档后才能重试 |
| 重复上传／恢复 | 未变上传不产生提交；远端领先时报告领先；重复恢复保持内容 |
| 名称及设置 | 改名不改变游戏摘要，但元数据可上传；空密码、选择目录和名称可重启读回 |
| 游戏未停止 | 注入停止检查拒绝恢复；不冒充真实操作系统进程验收 |

服务端 fixture 不提供 ETag，并拒绝 CAS 条件头、head.json 改写和不同字节覆盖不可变键。测试存档是 gzip JSON 与完整目录单元的合成文件；只证明传输、版本门禁及数据保留，**不证明游戏能继续游玩这些合成档案**。

## 静态审计与修复

- 官方本地 3.3.8 JAR 的 `GamesInProgress` 字节码将 `MAX_SLOTS` 设为 `HeroClass.values().length`；该枚举实际为 WARRIOR、MAGE、ROGUE、HUNTRESS、DUELIST、CLERIC 六项。槽位上限没有从其他版本猜测。
- `game.dat` 按有界 gzip／JSON 读取版本；合并搬运整局目录。`rankings.dat` 的 total 来源于游戏记录字段。全局记录按整体比较，不按时间选胜者。
- 恢复沿用已验证快照 UUID；不确定连续性会清空当前 UUID 并保留提示，显式确认才关联。目录 sidecar 的同步声明不能替代仓库的已验证基线。
- sidecar 与其精确保留的临时文件不进入游戏摘要／ZIP／恢复清单。改名只改元数据。
- 通用状态写入原有的非原子降级已移除：同目录临时文件强制写盘，要求原子替换，不支持即失败并清理临时文件。`AtomicStateWriteTest` 注入不支持原子移动，确认旧文件保留。多文件恢复仍是备份、回滚和持久化中断门禁，并非任意断电下的事务。
- 重复未变上传现在比较游戏文件及可传输目录元数据。二者均等于已验证基线时只刷新远端图；名称／身份改变仍产生新提交，已有待上传计划仍精确重试。

## UI 与剩余边界

现有 `HistoryChartRenderTest` 渲染真实隔离会话的生产组件并点击云端选择／命名。连接编辑失效、取消、空密码、重复点击和过期异步结果另有模型／生命周期测试。源代码复查确认连接页编辑即撤销验证、返回不保存，冲突页显式覆盖／合并／取消，实际执行前重新检查游戏状态。

这些证据不等同于全窗口端到端点击。本轮没有可调用的 Windows 原生交互／截图工具，因此未操作系统目录选择器、未创建两份真实游戏进度，也未声称完成双槽恢复后续玩。历史 Linux 游戏验证不能替代本轮 Windows 验收。界面按钮可在游戏运行期间显示可点击，但执行层会拒绝存档操作；本轮未验证真实运行游戏下完整窗口的反馈。

最小人工验收：在最终隔离启动器中选择已有官方 JAR，使用独立测试 HOME 下的游戏创建两个真实进度并退出；经本机测试 WebDAV 在第二个隔离档案恢复，再从该 HOME 启动游戏，逐槽进入并移动后保存。另检查一次系统目录选择／取消、连接测试后改字段使下一步失效、返回不保存，以及游戏运行时恢复被拒绝。不要把本测试的合成存档装入真实档案。

## 最终结果与运行目录

2026-10-10：core 431 项、desktop 63 项，合计 **494 项：489 通过、5 跳过、0 失败**。跳过项依赖 Linux／POSIX 环境。四组生产 HTTP 测试全部通过。最终日志为工作区 `production-e2e-verified.log`；JUnit XML 位于各模块 `build/test-results/test/`。

Compose 分发任务在未缓存的 WiX 工具检查处停止，未下载工具。随后直接使用已有 JDK `jpackage --type app-image` 成功生成免安装目录 `windows-verified-build/PixelDungeonSync`（工作区下，约 133 MiB）。`Start-Isolated.ps1` 启动包内 EXE，独立 HOME、APPDATA 和应用状态都位于包目录。EXE 本身也将 JVM user.home／应用状态固定到包内验证目录。没有替换用户版本。

包内生产入口探针输出 `PDSYNC_UI_SMOKE_OK 1120x800`，stderr 为空，进程自行退出。包中 core.jar／desktop.jar 与完成回归的构建产物 SHA-256 一致：

- core.jar：`a7b63bdda914c6e5b6db64bddda1bfc276cf38c1e91f6ffbde205582d722ff18`
- desktop.jar：`00f3ea30bd103f48dfe51432fc0aa7a8951b895d0665105c4e4cd8fca156258e`

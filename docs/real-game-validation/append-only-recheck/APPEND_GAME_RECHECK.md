# 追加式 v2：官方游戏实际复验

2026-10-09 UTC，结果：通过。全部操作在 dot 云端 Linux 的两个新建隔离档案 A/B 中执行；没有使用用户电脑、真实存档、生产 WebDAV 或 Cloudreve 凭据。此记录属于本轮新协议，不借用旧版截图或旧夹具成功。

## 范围与方法

- 从[官方 v4.0.2 发布](https://github.com/00-Evan/shattered-pixel-dungeon/releases/tag/v4.0.2)下载未经修改的[Java JAR](https://github.com/00-Evan/shattered-pixel-dungeon/releases/download/v4.0.2/ShatteredPD-v4.0.2-Java.jar)。SHA-256 为 `bcefd52a9c14f69c69d3ccc08a212cdd3830b2dc60d78d80895cd803fa1ec602`，与当前受信任指纹一致。内部版本 922。
- 使用 JDK 21、软件 OpenGL。A/B 各自设置 HOME、XDG_DATA_HOME 与 JVM user.home，游戏实际使用 XDG 下的官方默认路径。通过受支持的 CUA 原生键鼠操作游戏；本轮原生输入可用。
- 同步通过最终构建的 `AppendProfileSession` / `AppendOnlyWebDav` 控制器，loopback `127.0.0.1:8777` 和公开演示账号；未使用旧 ProfileSession 的条件写入路径。控制器调用程序为同目录 `AppendGameDriver.java`。
- 操作存档前，先在游戏菜单保存并完整退出，再确认游戏窗口消失。对持久文件做只读 gzip/JSON 状态提取及 SHA-256 比对。未修改游戏 JAR 或手工构造游戏序列化状态。

## 已通过

1. 实际新建 A 战士，完成初次引导，正常游玩、保存退出。原始种子 `707192118351`（游戏显示 `DKB-HBP-YXR`），层 1、生命 20/20，turn 7 / pos 316。
2. 用追加协议发布 A，再在空 B 恢复。3 个受管理文件（game1/game.dat、game1/depth1.dat、journal.dat）逐字节匹配 A；B 空目录恢复前备份登记在历史。
3. B 的设置属于设备本地，仍会显示游戏首次引导。通过正常游戏 UI 完成引导，另建的 game2 没有覆盖转来的 game1。随后在保存列表明确选中原种子，官方游戏成功读入 A 的 turn 7 / pos 316，继续移动、保存到 turn 10 / pos 215，再完整退出。此时完整档案含 5 个受管理文件。
4. 发布 B 的新进度，其父提交为最初 A 提交。A 未恢复 B，直接从原档实际继续到 turn 9 / pos 365，再保存退出。发布 A 后得到两个同胞分支；冲突阶段未覆盖任一存档。
5. 控制器明确选择 B 的整份快照，新建以两条 tips 为父提交的 resolution。A 的全部 5 个受管理文件精确匹配 B；A 的原 turn 9 档案完整备份保留。再次打开官方游戏 A，实际读入 turn 10 / pos 215，继续移动到 turn 11 / pos 214 并保存退出。
6. 在 A 恢复最初历史快照。恢复前自动备份精确匹配 turn 11 的完整档案，恢复后 A 回到 turn 7 / pos 316。缺失的 game2 被移除，属于整份快照恢复的预期行为；其原始字节仍在备份与远端历史。随后发布新 kind=restore 提交，父提交为当前 resolution，没有回退旧提交 ID。
7. 再次打开官方游戏 A，历史恢复档正常加载，继续到 turn 8 / pos 315，保存并完整退出。最后退出于约 02:06:37 UTC。
8. 独立于控制器，重新解压全部 5 个远端 ZIP 和 3 个恢复前备份 ZIP，核对每文件 SHA-256。远端保留 5 个独立提交和 5 个 ZIP，没有 head.json，初始、两个分支、resolution、restore 均保留。

| 状态 | turn | pos |
|---|---:|---:|
| A 初始 / B 传输后 | 7 | 316 |
| B 实际继续并保存 | 10 | 215 |
| A 独立分支 | 9 | 365 |
| A 选择 B 后 | 10 | 215 |
| A 选择后实际继续 | 11 | 214 |
| A 历史恢复后 | 7 | 316 |
| A 历史恢复后实际继续 | 8 | 315 |

## 提交

- 初始：`707f3b5c-83aa-43b3-9d5e-6656cdda6894`
- B 分支：`25b49254-8356-4a28-a90a-191506dac3c9`
- A 分支：`39c74eaa-24b1-4e1d-b3af-9868aea0aa4c`
- 明确选择 B：`9e0583fc-0453-471e-a7d8-41958ce7fd25`
- 历史恢复新提交：`164f0c10-3f29-4fb0-bfd9-01bcad78d55f`

## 限制与遇到的环境问题

这是单一 Linux 云端上的双隔离目录与真实游戏复验，不是两台物理设备或 Windows/macOS/Android 验证。不证明生产 Cloudreve、恶意服务端、断电、Steam Cloud 并发、可靠进程检测或完整游戏战绩。本轮未生成 rankings.dat / badges.dat，故不能把此前旧协议的全局战绩/徽章测试冒充新协议实测。

此报告的同步操作是控制器级调用；游戏操作是真实原生 UI。它不覆盖同步器 Compose 向导、冲突选择对话框的按钮行为，后者单独记录。

最初一次 loopback 服务器进程未跨执行会话存活，导致网络请求失败且没有发布；改为同一次命令内启动服务、运行控制器、关闭服务后完成。一次误提前打开 B 只生成了 settings.xml；仅有设置文件的非空目标被控制器拒绝。将这个新建隔离设置文件暂存后向空 B 恢复，再原样放回；没有放宽生产校验或删除用户数据。B 完成首次引导后才从保存列表载入转入的 game1。

## 证据

`APPEND_GAME_RECHECK.json` 包含各阶段状态、受管理文件哈希、提交 DAG、独立备份归档检查及本次核心 JAR 指纹；`controller-*.log` 是本次四阶段结果。8 张 JPEG 是本次 CUA 直接截图，未修改画面。源包中不含游戏 JAR、原始存档、ZIP 存档、凭据或运行状态目录。

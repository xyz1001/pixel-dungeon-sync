# 真实游戏同步 / 恢复验证

**结果：通过。** 2026-10-08（UTC）在 dot 云端完成真实游戏 `A → WebDAV → B → WebDAV → A` 往返；两次恢复后均在官方游戏中实际读档、移动、保存并退出。最后一次游戏退出于 09:19:51 UTC。

## 游戏与隔离环境

- 官方 [Shattered Pixel Dungeon v4.0.2](https://github.com/00-Evan/shattered-pixel-dungeon/releases/tag/v4.0.2)，2026-10-07 发布；内部存档版本 922。
- 使用未经修改的官方 [Java JAR](https://github.com/00-Evan/shattered-pixel-dungeon/releases/download/v4.0.2/ShatteredPD-v4.0.2-Java.jar)。SHA-256：`bcefd52a9c14f69c69d3ccc08a212cdd3830b2dc60d78d80895cd803fa1ec602`，与官方 GitHub release asset digest 一致。
- Eclipse Temurin Java 21.0.12.1+1-LTS，Linux 云端 Xfce，软件 OpenGL。A/B 使用独立的 HOME、XDG_DATA_HOME；未使用用户已有存档或用户电脑。
- Linux 实际存档位置：`$XDG_DATA_HOME/.shatteredpixel/shattered-pixel-dungeon/`。游戏运行产生的 `game.dat` / `depth1.dat` 为 gzip JSON bundle。
- 传输经仅绑定 loopback 的带认证 WebDAV 测试服务（端口 8766），没有公开部署。

## 实际操作与结果

1. 在官方游戏创建 A 的战士存档并正常游玩到 10 回合；退出游戏。另建独立 B 存档，随机种子不同，作为覆盖恢复和自动备份的目标。
2. A 的第二个独立测试角色通过正常游戏操作结束一局，生成真正的排行榜记录和饥饿死亡徽章。A 的第一局 `game.dat` 与 `depth1.dat` 前后逐字节不变。
3. 同步器界面配置 A，上传并回读校验；切到 B，读取远端、确认自动备份并恢复。六个受管理文件的 SHA-256 与 A 完全一致；B 自动备份逐字节匹配原始 B。
4. 启动官方游戏 B，排行榜显示转移来的 “Starved to Death”，日记徽章页显示 “Starved”。继续 A 的战士，看到原位置和物品；向下三步、向右一步，正常保存并退出。
5. 同步器界面从 B 上传新版本。对仍持有旧版本的 A 触发冲突，保留双方分支；取消一次后确认未覆盖 A/远端，再打开并选择备份后采用远端。A 的六个受管理文件完全匹配已推进的 B；A 自动备份完全匹配恢复前的 A。
6. 启动官方游戏 A，实际读入 B 推进后的位置，继续向下两步，正常保存并完全退出。

| 检查点 | 回合数 duration | 位置 pos | 生命 | 层 / 级别 |
|---|---:|---:|---|---|
| A 原始可玩存档 | 10 | 1153 | 20/20 | 1 / 战士 1 |
| B 恢复后 | 10 | 1153 | 20/20 | 1 / 战士 1 |
| B 实际继续游玩并保存 | 14 | 1277 | 20/20 | 1 / 战士 1 |
| A 反向恢复后 | 14 | 1277 | 20/20 | 1 / 战士 1 |
| A 再次游玩并保存 | 16 | 1359 | 20/20 | 1 / 战士 1 |

整个往返保持种子 `3699376585355`（游戏显示 `RSP-JHC-CBJ`）、短剑、布甲及背包：3 投石、1 食物、1 水袋、1 绒布袋。三项全局文件在往返后保持一致。

- A→B 快照：`7dd4b3af-7dc0-448b-b428-79c740fd633b`
- B→A 快照：`9a6432da-8bc8-4080-9d63-9399b6b133a9`，parent 为 A→B 快照
- B 恢复前备份：`0a8561ed-d304-4f2f-a2d2-015c430646a2`
- A 恢复前备份：`c609fdc3-8b77-438e-ba99-27c9c3b64b11`

## 全局记录与范围

真实 `rankings.dat` 含 1 局已结束游戏（战士 1 级、层 1、得分 133、饥饿死亡），总局数 1 / 获胜 0；`badges.dat` 含 `DEATH_FROM_HUNGER`；`journal.dat` 来自真实游戏。死亡后的 `game2/game.dat` 是游戏生成的合法 1 字节占位文件，也随快照传输。

同步范围是 `gameN` 局目录和 `rankings.dat` / `badges.dat` / `journal.dat`。`settings.xml` 和 `bones.dat` 不参与同步；实测 B 的设置、A 的设置与遗骸文件均未改变。

官方 v4.0.2 的 [Rankings.java](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v4.0.2/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Rankings.java) 将排行榜保留条数限制为 11。复制排行榜能保留游戏现有记录及总局数，不能重建游戏已丢弃的完整历次战绩。

这是同一 Linux 云端中的双隔离档案验证，证明实际序列化存档可往返恢复并继续游玩；**没有认证物理 Windows/macOS/Linux 跨系统组合、Steam Cloud 并发或生产 WebDAV 服务兼容性**。仅有无声卡及联网新闻/更新请求告警，没有阻断读档与游玩的错误。

## 证据

- `B-restored-playable.png`：B 恢复后实际可玩位置
- `B-advanced-playable.png`：B 已继续移动
- `A-reverse-restored-playable.png`：A 读入 B 推进的位置
- `A-final-advanced-playable.png`：A 再次继续移动
- `B-restored-rankings.png`、`B-restored-badge.png`：真实全局记录在 B 游戏中显示
- `A-genuine-record-gameover.png`：独立测试局及徽章真实生成
- `verification-summary.json`：各阶段状态、文件哈希及快照/备份 ID

此证据目录只含报告、摘要和截图，不含游戏 JAR、真实存档或账户凭据。

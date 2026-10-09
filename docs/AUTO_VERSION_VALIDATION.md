# 自动版本识别与兼容性（2026-10-08）

## 实现边界

版本号编辑框已移除。存档根目录和目标游戏 JAR 分别选择，版本以只读信息显示。旧配置的手动 version 字段被丢弃；旧快照的人工标签不被当作实际版本，恢复前会重新检查归档内的原始存档字节。

每个 `gameN/game.dat` 独立读取顶层整数 `version`，保留混合版本信息。`init_ver` 是开局版本，不替代最近写入版本。空文件和 1 字节文件按照上游的非进行中槽位规则处理；实际死亡槽位是 `01`。`rankings.dat`、`badges.dat`、`journal.dat` 不用于推断统一写入版本。没有可识别活跃局或损坏版本可以原样归档，但恢复失败关闭。

支持 gzip 和明文 JSON。输入上限8MiB，解压上限16MiB，JSON深度上限64；拒绝重复顶层 version（含转义别名）、错误类型、非正整数、溢出、尾随内容、无效UTF-8及损坏gzip。只扫描数据，不反序列化Java对象。

所选JAR只被读取，不被启动。校验路径、256MiB文件上限、ZIP目录/本地条目一致性、条目数量与总解压限额、64KiB清单上限、重复条目及重复/冲突清单字段。读取发行版、内部版本和Shattered桌面入口身份；目前只有已核实官方4.0.2 JAR的SHA-256被信任：

`bcefd52a9c14f69c69d3ccc08a212cdd3830b2dc60d78d80895cd803fa1ec602`

检测出一个版本号不等于兼容性已被证明。未知指纹/目标、跨游戏身份、未知版本、过旧版本和降级都被阻止，发生在备份或目标变更之前。真实恢复期间仍执行游戏停止确认、完整备份、二次停止/变更检查；版本检查不能替代并发防护。

## 当前兼容规则与来源

目标官方4.0.2（内部922）采用最低写入版本850；每一个活跃局都必须落在850–922范围。最低值来自固定版本上游源码；上限是同步器的保守防降级策略，不是对上游任意未来版本的推测。不能仅取多个存档版本的最大值。全局记录仍作为原始字节整体保存，不为它们伪造一个最近写入版本。

- [Dungeon.java](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v4.0.2/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java)：存储 init_ver 与 Game.versionCode，删除局时生成占位文件。
- [GamesInProgress.java](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v4.0.2/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/GamesInProgress.java)：只将大小大于1的game.dat视为进行中，拒绝低于v3_1_1的局。
- [ShatteredPixelDungeon.java](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v4.0.2/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/ShatteredPixelDungeon.java)：v3_1_1的内部代码850。
- [DesktopLauncher.java](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v4.0.2/desktop/src/main/java/com/shatteredpixel/shatteredpixeldungeon/desktop/DesktopLauncher.java) 与 [desktop/build.gradle](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v4.0.2/desktop/build.gradle)：JAR清单发行版与内部版本的生成和读取。

本轮真实运行仅验证922→922。历史850–921档案允许进入已核实的源码范围，但未逐个版本实际运行升级；全局记录格式也没有统一的最近写入版本证据。新游戏加载旧档后可能升级，本工具不转换或向下修改存档。

## 状态迁移

自动版本状态不按每次变化的存档版本拆分父版本。旧scope只有在其原hash能证明匹配当前规范目录、游戏、端点、档案与旧标签时，才被复制导入。每份归档校验后保留原ID/时间/备份历史；待上传保留原manifest和字节，避免重试生成另一个远端对象。多个待上传ID或多个基线不一致时停止，不作猜测；旧文件和旧pending标记都不删除。完成标记确保新队列清理后不会在下次启动复活旧pending。

导入是一次性迁移；不要再同时用旧版客户端写旧scope。未绑定的旧第一轮演示数据不会被猜测合并。新manifest有附加版本证据字段，旧客户端可能拒绝它，应更新所有同步端。

## 测试与实际验证

- 82个JVM测试全部通过：原核心37、档案控制器18、版本解析17、版本控制器集成6、旧scope迁移4。Python演示服务回归1个通过。
- 源码严格解析的初次测试把单字节“{”误当损坏JSON，实际按上游规则它是非进行中槽位；测试改为两字节损坏输入后通过，生产解析未放宽。
- 真JAR和真存档探针通过：目标4.0.2 / 内部922 / 已验证指纹；game1实际writer922；game2非进行中。见 `cloud-evidence/auto-version-real-probe.log`。
- UI实际显示只读检测结果，没有版本号输入框；上传自动版本快照 `c5218d79-3a21-400f-83ba-200cb609e704`，经独立loopback8768回读，在B上重新检查目标和每局版本、备份并恢复。备份 `414b3e7a-671a-4b70-89a0-b931d74f6942` 保留在历史。截图见 `auto-detected-versions.png` / `auto-version-B-restored.png`。
- 自动版本改动后再次在官方游戏读档、移动两步、保存并退出，通过；B读入A的turn16/pos1359，继续到turn18/pos1277，全部受管理文件与备份逐字节核对。见 [本轮真实游戏复验](real-game-validation/auto-version-recheck/AUTO_VERSION_RECHECK.md)。这不是借用此前人工版本轮次的成功。

Windows/macOS原生包/Steam安装定位未验证，只支持明确选择JAR的这一条已测试目标检测路径；未来版本需要独立核实兼容规则，不能自动假设所有高版本都向后兼容。

独立安全复核的两个问题已修复：只读兼容性探针不竞争写锁，实际恢复仍持锁重验；旧scope按可证明身份无损导入。复核无剩余具体阻塞。

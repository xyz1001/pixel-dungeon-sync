# 单局身份与冲突合并调研

2026-10-10。结论与设计建议，未修改同步协议、Compose 或网页原型。以 Shattered Pixel Dungeon 3.3.8 为主要样本。

## 结论

在检查过的活跃单局持久化入口中，没有找到可直接使用的原生 run UUID。排行榜确有 `gameID` UUID，但在提交结局记录时才生成，不是开局生成并随活跃存档传递的身份。`seed`、槽位、时间戳和内容 SHA-256 都不能替代单局身份。

可以支持“合并或覆盖”的冲突界面，但不能承诺除同一局双端更新外都能自动合并：不同局仍共享全局统计与成就，可能槽位不足，也可能涉及删除、结局、槽位复用和版本差异。首版应只合并能够证明安全的独立单局；其余冲突列明后由用户选择保留内容。

## 证据与版本

使用本机已有 `desktop-3.3.8.jar`（Manifest：Specification-Version 3.3.8、Implementation-Version 896）静态 `javap -p -c` 检查 Dungeon、GamesInProgress、Rankings/Record，并核对官方 v3.3.8 源码。指定的 `D:\repos\github\shattered-pixel-dungeon` 实际只有 JAR，没有源码 checkout。另检查 v2.5.4 的 Dungeon 源码，以及已有 Experienced 2.19、Too Cruel 1.0.4 JAR 的相关类字段声明。

本轮未执行游戏、未读取真实存档或凭据、未下载 JAR/依赖/仓库。仅获取八个明确指定的官方文本源码文件，合计 149,992 字节；以下链接按所检查 tag 和源码实际行号定位。静态结论不等于跨版本、跨模组或真实存档运行验证。

| 候选标识 | 证据 | 能否标识活跃单局 |
| --- | --- | --- |
| `gameID` | `Rankings.submit()` 新建记录后在第 120 行生成 UUID；Record 第 539–541 行读取或为缺失 ID 补随机值，第 565 行保存 | 不能：它标识结局记录。已存记录复制后保留 ID；同一活跃局复制后分别结算会各生成 ID，旧记录缺 ID 时两端也可能分别补不同 ID |
| `seed` | `Dungeon.initSeed()` 第 217–230 行支持每日和自定义种子；随机种子取值空间有限 | 不能：同种子可以开多局，每日挑战也可重复 |
| `gameN` 槽位 | `GamesInProgress` 第 47–70 行按槽号组装外部目录路径 | 不能：槽位可复用，各设备相同槽位可能是不同局 |
| `last_played` | Dungeon 第 634 行每次保存用当前游戏时间更新 | 不能：不是创建身份，且版本间字段不同 |
| Actor 数字 ID | Dungeon 第 240 行开局重置，保存/加载局内 Actor ID 状态 | 不能：用于局内引用 |
| 内容哈希 | 同一局每次推进内容会改变；复制内容可相同 | 只能标识状态，不能证明单局身份或因果关系 |

身份与序列化来源：[Dungeon v3.3.8](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v3.3.8/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L601)、[Rankings.submit](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v3.3.8/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Rankings.java#L82)、[Record 读写](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v3.3.8/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Rankings.java#L512)、[种子初始化](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v3.3.8/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L217)。

v2.5.4 的 Dungeon 同样未见持久化 run UUID，且尚无 v3.3.8 的 `last_played` 键；这不是所有历史版本的证明。缓存模组的顶层 Dungeon 字段也没有发现 run UUID，而 Rankings.Record 有 gameID；模组仅做字段层静态对照，不据此启用合并或推断完整格式兼容。[v2.5.4 保存入口](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v2.5.4/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L599)。

## 合并的最小单位与槽位

一局应作为完整 `gameN` 内容单元处理，包括 `game.dat`、所有 `depthN.dat` 和 `depthN-branchM.dat`。主文件包含英雄、物品、任务、生成器、统计、已生成楼层、局内徽章和 Actor ID 状态；楼层另行保存。不能因双方改了不同文件就拼起来：跨文件引用与游戏状态可能不一致。`saveAll()` 也是依次写主文件和当前楼层，采集仍需现有进程与稳定性检查。[保存/加载实现，第 624–839 行](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v3.3.8/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L624)。

3.3.8 的 `MAX_SLOTS = HeroClass.values().length`，该版本有六个职业，因此检查范围为槽位 1–6。路径由外部槽号决定，静态证据支持把完整独立单局放到另一个空槽的可行性，但还需在隔离环境验证，并在游戏关闭时操作以避免缓存/并发写入。合并结果超过六局时不能偷偷写到 game7，也不能覆盖现有局。[槽位与路径，第 38–85 行](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v3.3.8/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/GamesInProgress.java#L38)、[六职业，第 85–92 行](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v3.3.8/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/HeroClass.java#L85)。

## 全局数据是独立冲突来源

- `rankings.dat`：包含排行、总局数、胜局数和每日记录。常规排行最多 11 条，不能据此重建终身计数；自定义种子和每日局还有不同统计规则。同一局两端结算可生成不同 gameID，故 UUID 去重后合并排行仍可能重复计局。直接相加会重复共享历史，取最大值会丢失两端独立新增结果；即使有基线，增量相加也需先证明事件独立。[Rankings 第 120–163、374–433 行](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v3.3.8/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Rankings.java#L120)。
- `badges.dat`：保存全局徽章集合，职业解锁读取其中对应徽章。已知版本、仅新增且没有重置时可研究集合并集，但源码也有删除徽章入口和名称迁移，不能无条件并集。总局数相关徽章又与排行计数有关。[Badges 第 257–338 行](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v3.3.8/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Badges.java#L257)、[删除/解锁入口](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v3.3.8/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Badges.java#L1203)、[职业解锁](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v3.3.8/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/HeroClass.java#L330)。
- `journal.dat`：聚合 Catalog、Bestiary、Document 三类全局状态；本轮未逐项审计其合并语义，不能当成可直接合并的文本或无条件集合。[Journal 第 32–79 行](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v3.3.8/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/journal/Journal.java#L32)。

最小安全实现先把有相互依赖的全局文件作为保守的整体三方比较单元：仅一端改动时取该端，双方都改且不同则保留为显式冲突。后续若实现版本专用语义解析，应分别定义计数、去重、截断、重置和迁移规则，再考虑自动合并。

## 必须保留共同基线与删除记录

仅比较左右两份快照不能区分“另一端新增一局”和“这端已经结束/删除该局”。`deleteGame()` 可删除楼层，并把 game.dat 改成长度 1 的文件；`gameExists()` 以长度大于 1 判断有效局。因此删除不一定表现为目录或文件消失。结局提交排行与活跃槽位生命周期也不能靠最后修改时间推断。[删除实现，第 842–854 行](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v3.3.8/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java#L842)、[有效局判断](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v3.3.8/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/GamesInProgress.java#L52)。

以下规则要求单局身份已确认、基线 B 已验证，L/R 为同一身份的当前状态，删除也是一种状态：

| 情况 | 处理 |
| --- | --- |
| L = R | 采用共同内容 |
| L = B，R 有变化 | 采用 R；反向同理 |
| 同一局 L/R 都变化且不同 | 不拼接，用户选择保留哪端 |
| 一端删除，另一端等于 B | 传播删除，记录 tombstone |
| 一端删除，另一端继续推进 | 明确冲突，不能悄悄复活或删除 |
| 已确认不同身份的局各自新增/推进 | 可合并完整单局，前提是槽位足够、版本适配、全局数据冲突已解决 |
| 没有共同基线或身份不明 | 不猜测，用户确认来源/选择版本 |

结束的局被旧设备带回、或用户明确恢复旧快照时，必须区分“迟到的旧副本”和“有意恢复”。前者遵守 tombstone，后者是显式操作并记录新的因果关系。时间更晚不等于进度更正确。

## 没有原生 ID 时的最小 sidecar 方案

在 [目录元数据设计](SNAPSHOT_METADATA.md) 上扩展同步器管理的 runUUID，不往游戏的 game.dat 注入字段。建议记录每局随机 UUID、当前槽位、当前内容摘要、已验证基线引用及删除标记；明确 schema 版本。

1. 首次纳管时为已确认的单局生成一次 UUID，随首个已验证共享快照发布。另一设备从该快照导入时必须一起导入原 UUID，不能重新生成。目录迁移/槽位重排保持 UUID；内容推进只改变摘要。
2. sidecar 自身不计入游戏内容哈希；但 UUID 映射必须作为协议元数据随快照校验、发布和恢复，不能只有本机 sidecar，也不能只复制游戏字节而丢失映射。它不必进入游戏文件 ZIP。复用既有提交 DAG/发布验证，不以 sidecar 自报基线代替可信基线。
3. 可另定义相对于单局目录内部路径的 runContentHash，用于槽位移动后的内容比较；原目录总摘要仍包含 gameN 路径，保持既有定义。内容摘要不当作 UUID。
4. 两台设备各自纳管已有存档时，即使字节完全相同，也不能从 seed 或内容哈希断言历史身份；通过同一个已验证导入快照建立共同映射，或让用户确认同源。内容相同可去重存储，但不自动证明是同一局。不同进度且无共同身份时必须标为未知。
5. 观察到旧局消失并确认新开局后，新局分配新 UUID，旧 UUID 保留删除记录。困难是同步器关闭期间同槽已被新局替换，尤其重用同一种子：没有游戏原生身份，sidecar 无法完全可靠地识别此过程。种子、职业、初始版本可提示异常，但不是证明；存在歧义时要求确认，不能宣称完全自动。要消除该限制需游戏本身在开局生成并保存稳定 ID，或可靠的游戏生命周期集成。

## 建议的首版交互与待验证项

冲突弹窗可以提供“合并”和“覆盖/选择版本”，但应先计算方案，再说明将保留的单局、槽位安排、全局冲突和可能丢失的内容。无法安全合并时显示具体原因，不能把同名目录覆盖包装成合并。沿用现有“仅可能丢失独有内容时确认”的要求；本调研不额外增加备份步骤。

实现前仍需隔离合成/可信测试样本验证：整局换槽加载、六槽满载、同槽独立开局、同局双端推进、删除对旧副本、删除对新进度、结局双端提交、无 sidecar 导入、旧版迁移与全局文件并发变化。模组需要独立适配声明。当前没有真实存档运行验证，也没有实现自动合并；这些是明确覆盖边界，不应作为已支持功能对外描述。

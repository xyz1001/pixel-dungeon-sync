# 云端 Linux 原生存档同步验证

日期：2026-10-10。证据截止：16:13 UTC。

本轮验证的是云端 Linux 上的正式同步器入口、官方 Shattered Pixel Dungeon 3.3.8 与隔离 loopback WebDAV。A、B 是同一台云计算机上的两个独立目录及应用状态，不是两台真实设备。没有使用用户真实存档、真实 WebDAV 服务或私人凭据。

已完成：494 项 JVM 测试全部通过；原生两局存档上传、恢复及分别继续游玩；运行中恢复拒绝、取消覆盖；不同局并行变化的两槽合并；同局分叉为三槽且全部三槽分别原生继续；历史别名重启保留；无变化重复上传；通过真实游戏整局克隆执行的七槽容量拒绝。

**计划中的原生闭环均已完成。最终三槽继续产生的本机新进度没有再次上传，sidecar 仍待正常身份确认；本报告不宣称最后本机状态已经云端收敛。**

## 受测版本与边界

- 源码提交：`5bbff0f653d79a656ea16e88c32028273f2e4ef2`。
- 受测源码树：`2c43c443caae866fbd2765ab6ca2516632a5ee49`。构建及此报告审查均未修改受控源码、锁文件或正式业务逻辑。
- 游戏：官方 Shattered Pixel Dungeon 3.3.8，内部写入版本 896。游戏 JAR SHA-256：`b90d28d1b17700704920454549a4a6eddce5079b6140cb71a4dfcb3cc398a060`。
- Java：21.0.12.1；Gradle：8.14.3；生产依赖按源码及 Linux 锁文件解析。
- A、B 分别使用独立 HOME、XDG 数据目录及同步器工作目录。容量场景另用 `capacity-A`、`capacity-B` 和独立远端档案 `capacity-native`，不混入 A、B 的 `default` 档案。
- 原生游戏生成并继续写入真实格式存档。辅助工具不修改 game.dat、楼层数据或游戏二进制。不同局合并及同局分叉流程中的身份确认通过正式 UI 按钮完成；只有独立容量 runner 使用正式 `confirmRunIdentity` API。两类操作在本报告中分别标注。

## 构建和运行环境

最终完整离线重跑结果：core 431 项、desktop 63 项，共 **494 项通过，0 失败、0 错误、0 跳过**；`BUILD SUCCESSFUL`，耗时 1 分 18 秒。

本轮补齐了两项独立的云端环境条件：

1. 系统 Java 带编译模块，但缺少 headful AWT 原生库。先前桌面测试即使已有 DISPLAY，仍会出现 HeadlessException。使用匹配版本的官方 Debian `openjdk-21-jre_21.0.12.1+1-1~deb13u1_amd64.deb`，仅 209,200 字节，把三个原生库提取到工作区内的 JVM 副本。包 SHA-256 与 Debian 官方页面一致；没有运行安装脚本或修改系统 Java。
2. 系统默认字体环境出现过 Skiko 原生字体崩溃。最终运行使用进程级 SOFTWARE 渲染，以及指向现有 DejaVu、Noto CJK 字体的 FONTCONFIG_FILE。没有安装或替换系统图形栈、修改系统字体设置。

Gradle 发行版只下载一次，其 SHA-256 同时匹配 wrapper 固定值和官方校验文件：`bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531`。旧第三方 runtime JAR 得到复用，其余依赖通过正常官方仓库解析。

首次 headless 测试、随后缺库及字体崩溃的记录均保留；崩溃后遗留的旧 XML 没有计入最终结果。最终 XML 写到独立目录。**上述成功不表示未补齐的默认云端 JVM 和默认字体路径已经通过。**

原生 UI 使用冻结的 48 个纯生产 JAR，不含测试类。冻结 core/desktop JAR 的所有归档条目与最终受测构建逐字节一致；重构建时归档时间戳变化造成整体 JAR SHA 不同，已有单独核验记录。

## 原生引导与首次传输

原生操作覆盖 A、B 的四步引导、JAR 选择器取消后重新选择，以及 Linux 没有 zenity 时通过路径手填继续的回退流程。此项是原生操作观察；不据此宣称 Linux 图形目录选择器本身已经完成选择验收。

A 中通过官方游戏建立两局存档，再由正式同步器 UI 上传。B 经正式引导和恢复 UI 获取它们。当时的实际同步文件共五项：

- `game1/game.dat`、`game1/depth1.dat`
- `game2/game.dat`、`game2/depth1.dat`
- `journal.dat`

A、B 五个文件逐项 SHA-256 完全一致。两局 runUUID 与内容摘要保持关联；sidecar 的本机 scope 按独立安装计算，不能要求整个 sidecar 文件在不同设备目录中完全相同。首次核验明确记录了 scope 以外的 sidecar 一致性。

在 B 上分别通过官方游戏加载、移动并正常保存两局，观察到：

- game1：hero.pos 从 425 变为 462。
- game2：hero.pos 从 682 变为 714。

两局都保持内部版本 896 与各自原来的种子。这证明本次恢复内容可以在官方游戏中分别继续；种子仅作为辅助观察，未被用来生成或推断 runUUID。

证据：`02-first-restore-equality.json`、`03-before-native-resume.json`、`11-after-tutorial-overwrite.json`、`12-native-restored-game1.jpg`、`13-first-run-continued.json`、`14-both-restored-runs-continued.json`。

## 首次安装的游戏初始化边界

新设备恢复同步白名单后，不会同步 `settings.xml`。官方游戏首次安装的 introduction 状态因此可能仍为开启。B 的首次启动曾进入新角色／教程入口，而不是立即展示恢复后的存档列表；这时五个恢复文件的哈希仍然正确，不能将该现象解释为恢复丢失游戏字节。

本轮通过官方游戏正常完成临时 game3 的初始化，然后在同步器正式 UI 中明确覆盖恢复原来的两槽快照。game3 从同步文件集合中移除，原两槽内容恢复一致，B 本地 `settings.xml` 哈希保持不变。随后两局均完成上述实际读档、移动和保存。

没有手改 settings.xml、游戏数据或游戏代码来绕过初始化。这一新安装行为仍应在使用说明中保留；本轮没有修改产品去自动完成原游戏引导。

证据：`04-native-first-install-tutorial.json`、`10-local-settings-before-restore.sha256`、`11-after-tutorial-overwrite.json`；官方 WelcomeScene 和 TitleScene 的只读检查记录另行保留。

## 恢复保护与取消

### 游戏运行时拒绝恢复

真实官方游戏进程运行时，正式恢复操作显示“请退出游戏后重试”。前后核验中，B 的同步文件、sidecar、真实本地基线及远端不可变文件集合均未变化。

证据：`05-before-running-game-guard.json`、`06-after-running-game-guard.json`、`07-running-game-guard-result.json`。

### 取消覆盖不产生变化

在覆盖确认中取消后，B 的同步文件、sidecar、基线和远端文件集合保持不变。此结论来自取消前后独立取证的比较，不是仅依据界面提示。

证据：`08-before-cancel-restore.json`、`09-after-cancel-restore.json`。

## 不同局并行变化的合并

从两端都已建立的同一真实基线出发，A 只在官方游戏中继续 game1，B 只继续 game2。已有同局身份经正式 UI 按钮显式确认后，在正式 UI 执行合并。

结果仍为两个槽位：

- 合并后的 game1 完整目录逐文件等于 A 新 game1，hero.pos 为 461。
- 合并后的 game2 完整目录逐文件等于 B 新 game2，hero.pos 为 713。
- 两个 runUUID 均与共同基线保持一致。
- 产生新的 resolution 提交，父提交包含已保留的两条来源分支；没有删除旧云端对象。

合并结果提交为 `fc1deb5f-50e3-40a0-9ab4-3126cd1cacd2`，父提交为 `290557c4-0a39-402f-b1c0-40e9e07cbc28` 和 `4c52c3fc-8d11-4c90-8fde-9ad766569ea7`。完整目录比较包含当时实际存在的所有游戏与楼层文件，未通过重写二进制数据拼接两局。

证据：`16-before-independent-changes.json` 至 `22-after-independent-merge.json`、`23-two-slot-merge-ui.jpg`。`21-independent-merge-result.json` 保存了逐目录预期来源及哈希比较。

## 无变化上传

两槽合并后，对 A 再次执行无本机内容变化的上传。远端所有既有 JSON 和 ZIP 的名称及 SHA-256 与操作前完全一致，没有额外创建提交或对象。

证据：`22-after-independent-merge.json`、`24-after-unchanged-A-upload.json`。

## 七槽容量拒绝

此项通过独立 runner 调用正式 `AppendProfileSession`、loopback `AppendOnlyWebDav` 与真实 `GameProcessDetector` 完成。它不是容量错误界面的原生按钮验收。

准备过程采用一份真实原生整局的完整目录，克隆成六个初始槽位，并通过正常首次导入建立六个不同 runUUID。这是六个完整测试克隆，**不是六局分别游玩的独立存档**。随后正常上传共同基线、在 B 恢复，并为同一原始 runUUID 分别放入两份真实原生继续版本。五个其他槽位与公共 journal.dat 保持不变。来源和三个完整文件集合的 SHA-256 均有记录。

A 发布左侧继续版本，B 保留原基线和右侧未发布内容。正式 `mergeCurrent` 需要保留该 runUUID 的两个完整分支，加上五个未变槽位，共七槽，产生准确的容量错误：

`合并失败：需要 7 个游戏槽位，容量只有 6 个。未修改存档。`

验证结果：

- runner 退出码 0，状态为 `PASSED_EXPECTED_CAPACITY_REJECTION`。
- 容量预检阶段的远端发布尝试为 0。
- B 的游戏文件、sidecar、基线及 pending 状态前后不变。
- 该远端档案的 JSON／ZIP 名称和哈希前后不变。
- 三份原生输入夹具保持不变。
- 独立 Python 只读审计再次吻合 A/B 文件集合、目录摘要、runUUID 与远端内容。

runner 的发布保护包装器会记录并拒绝任何意外写尝试；本轮计数为零，且实际错误来自正式 RunMergePlanner 的七槽容量门槛。它没有注入 Stopped、伪造版本或替换正式会话实现。

**准备容量基线和 A 分支本身正常产生了四次 PUT，分别对应两份 JSON 和两份 ZIP。零写入结论仅适用于最后的容量预检，不适用于整个夹具准备过程。** 服务日志中容量档案的四次 PUT 与这些既有对象完全对应。

证据：`capacity-run.json`、`capacity-run.exit`、`capacity-run.stdout.json`、`30-capacity-independent-audit.json`；实现与编译记录为 `CapacityRunner.kt`、`CapacityGateVerifier.kt`、`capacity-compile-manifest.json`。

## 同一局分叉及子槽原生继续

A、B 从同一已同步基线继续 game1，经正式 UI 身份确认后，在正式 UI 合并。合并产物为三个完整槽位，逐文件比较得到：

- game1 完整等于 B 的 game1 继续版本，初始 hero.pos 为 462。
- game3 完整等于 A 的 game1 继续版本，初始 hero.pos 为 425；原来的 game1 目录被完整搬到新槽位，未改写其中的游戏二进制。
- game2 完整等于共同基线，hero.pos 保持 713，runUUID 仍为 `7edc2c31-4554-4261-86d6-c6ef84892dde`。

两个分叉子 runUUID 分别为 `708f76ce-f926-4473-a5ca-5e3f9403bbaa` 和 `af588229-93c6-4d78-a498-d126b7ef3150`。两者不同，且均不同于原 game1 的 `db6ddda7-f3a7-4950-be69-95b90222bf62`；两者的 `parentRunUUIDs` 都明确指向该原 UUID。

resolution 提交为 `f2ba40fe-e7f1-40e5-ad80-f313f33d498e`，父提交为 `105a6325-167b-4d52-8657-1bd4ee25ffc6` 和 `56361a83-dedf-4cb3-bf65-743499f5b49f`。提交、不可变 ZIP 清单与对应三份原生来源目录的完整文件集合一致；审计重新计算了目录摘要，没有仅采信结果 JSON 中的成功布尔值。

官方 3.3.8 的原生存档列表实际显示三局。随后分别选择两个子槽及保留槽、加载、移动，并通过正常 Main Menu 返回保存：

1. B 来源的 game1：hero.pos 从 462 变为 426，game.dat 和 depth1.dat 均产生新的哈希；game2、game3 的完整文件集合不变。
2. A 来源的新 game3：hero.pos 从 425 变为 424，game.dat 和 depth1.dat 均产生新的哈希；已经保存的 game1 与保留的 game2 不变。
3. 保留的 game2：同样通过官方游戏加载，向上移动并通过 Main Menu 保存；hero.pos 从 713 变为 680。game.dat 和 depth1.dat 产生新的哈希，而两个子槽的完整文件集合不变。

全部三槽继续保存后均保持版本 896 和各自原种子，两个子槽的父系记录保留。此时远端与同步基线未再次改变，sidecar 中三槽的内容摘要仍指向合并时状态，因此其 `contentMatchesFiles=false` 是原生游戏继续写入后的预期未确认状态。**这里证明的是合并后全部三份完整原生存档都能继续游玩，不宣称这些继续产生的新字节已经再次上传或同步。**

证据：`25-fork-common-baseline.json`、`26-fork-A-advanced.json`、`29-before-fork-merge.json`、`31-fork-merge-result.json`、`32-native-three-fork-slots.jpg`、`33-fork-first-child-continued.json`、`34-fork-child-B-loaded.jpg`、`35-fork-child-A-relocated-loaded.jpg`、`36-both-fork-children-continued.json`、`37-all-three-merged-slots-continued.json`。

## 历史别名及正常重启

退出官方游戏后，在 B 的正式同步器 UI 中把首个两槽历史快照命名为 `qa-history`，随后正常关闭并重新启动同步器。重启后仍显示历史别名 `qa-history` 和当前名称 `qa-base`，自动上传仍暂停，没有重复首次引导。

38、39、40 三个检查点的 B 游戏文件、sidecar、本地基线和全部远端不可变文件哈希一致；A 状态也保持不变。别名是本地展示信息，此项没有新写云端快照。可重算 JSON 证明内容状态不变，别名显示、暂停状态和没有重新引导由原生操作观察及截图证明，不混同为 JSON 自动重演 UI。

证据：`38-before-history-alias.json`、`39-after-history-alias.json`、`40-after-B-restart.json`、`41-history-alias-restart-result.json`、`42-history-alias-after-restart.jpg`。

## 独立审查与证据范围

对现有 JSON、JUnit XML、编译记录及容量请求日志进行了 42 项只读一致性检查，全部通过，结果保存在 `verification/read-only-result-review.json`。检查涵盖最终测试计数、运行中拒绝与取消前后不变、首次恢复、初始化设置保留、两槽合并、UUID 保留、重复上传和容量证据对应关系。该审查是截至容量阶段的历史检查点。

同局分叉的专用检查器 `verification/audit_fork_evidence.py` 对六份固定 JSON 进行 **59 项重算，全部通过**，结果为 `verification/fork-evidence-review.json`。最终审计又纳入第三槽继续、别名重启、收尾、容量结果及 JUnit case 结果属性，**133 项检查全部通过**，结果为 `verification/final-evidence-review.json`。它可以在没有游戏夹具、设备配置或 JAR 的证据副本中运行。原生操作的执行方式来自操作者记录及截图，JSON 审计本身不声称重演 GUI。

最终审计复用 `audit_fork_evidence.py`、`evidence_bundle.py` 和 `audit_final_evidence.py`。`validation-evidence.json` 是约 255 KiB 的纯文本去重包：完整保留选定 JSON 的语义内容，记录原文件 SHA-256；JUnit 仅保留 suite/case 结果属性，截图只记录 SHA-256。包中不含存档、游戏 JAR、云端 ZIP 对象、设备配置或凭据。从仓库根目录可直接重放：

```sh
python3 docs/validation/2026-10-10-cloud/audit_final_evidence.py
```

仓库保留本报告、精简审核结果、约 255 KiB 去重 JSON、三个离线审计脚本及 [证据附件索引](validation/2026-10-10-cloud/evidence-index.json)。完整数值 JSON、截图和脚本位于索引所列的同名 ZIP 证据附件中，不表示本文引用的原始文件全部存在于仓库。索引包含最终 ZIP SHA-256 与包内逐文件哈希。完整 ZIP 作为此次验证结果的独立附件提供。

只读证据脚本仅处理隔离验证目录中的白名单文件，不读取应用 config.json 或凭据；game.dat 仅输出版本、种子、深度及有限角色位置字段，settings.xml 仅输出 SHA-256，不输出文件内容。远端 ZIP 在内存中有界读取，不解压写入目录，并独立比较 ZIP SHA、manifest 文件集合与文件 SHA。

取证不是整个文件系统的原子事务。需要对停止写入后的稳定检查点作前后比较；不能仅凭 UI 截图或旧 XML 推断已完成的操作。

证据路径约定：本文未带路径的数值 JSON／截图文件名相对于证据 ZIP 的 `evidence/` 目录；构建证据在包内 `build-evidence/`，辅助脚本与独立审查在包内 `verification/`。仓库中的精简审核结果和索引位于 `docs/validation/2026-10-10-cloud/`，不需要复制整个验证工作区。

构建主要证据：验证目录的 `build-evidence/test-summary.json`、`headful-jdk-full-tests.log`、`runtime-source-equivalence.json`、`headful-package-verification.json`；完整原始 JUnit XML 同时收入附件，附件中的 `validation-evidence.json` 保存其原文件 SHA 和去除日志/属性后的 494 个 case 结果，供离线重新统计。实际归档文件以索引为准。

## 尚未证明的事项

- 最后三槽本机继续产生的新进度再次身份确认、上传及与 A 收敛；本轮明确停在未再上传状态。
- 两台真实设备、真实 WebDAV／Cloudreve、Windows／macOS 或移动端原生运行。
- 所有默认安装／字体／渲染环境、全部目录选择器和所有游戏启动方式。
- 六局独立长程游玩、全部楼层／支线内容或所有历史游戏版本的原生迁移。
- Steam Cloud 并发、恶意路径替换、任意断电事务及所有网络故障组合。
- 本轮以 journal.dat 为实际公共记录输入，不扩大为所有排行榜／成就语义冲突的原生 UI 验收。

原生已通过范围、辅助器 API 验证和待验证项应分别保留，不将本报告表述为生产服务或跨平台全面认证。

## 收尾与结论

全部场景结束后，官方游戏和两端同步器均通过原生窗口操作正常关闭。操作者核对专用 loopback 进程身份后发送 SIGTERM，记录验证了进程消失和 loopback 端口关闭，并在原生桌面的进程／网络环境里再次确认同一端口已关闭，见 `43-cleanup.json`。这些为操作者记录的收尾状态；离线审计检查记录一致性，不声称此刻重新扫描进程。

本轮未发现需要业务源码修复的问题，受测源码未更改。构建环境的独立 JVM 库/字体补齐仅属于此次云端验证准备。本次记录仅增加验证文档和精简证据，不包含业务逻辑更改。

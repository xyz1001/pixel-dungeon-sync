# 追加式协议验证（2026-10-09）

## 来源与环境

- 从 Library 源码包版本 3 恢复；原始文件名 `pixel-dungeon-sync-source-20261008.zip`。
- 原始 ZIP SHA-256：`a1c2ff347e2efd3b2f25fc9827402ce73c57b909b457de6af692e162b2101ebf`。
- ZIP 注释中的源提交：`1177a6babb4e85a7e80ce48f555bc1f59486bcd8`。这是来源证据；源码包不含 `.git`，未恢复原仓库提交历史。当前 Git 由已核验源码新建恢复基线，不声称保留原 Git ancestry。
- 只在 dot 云端工作，未访问用户电脑、Cloudreve 生产服务或私人凭据；未 push 或部署。
- Linux amd64；官方 Eclipse Temurin JDK `21.0.12.1+1-LTS`，输出 JVM 17。官方 JDK 压缩包 SHA-256 `ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94`，与官方 API checksum 匹配。
- Gradle 8.14.3；下载 SHA-256 与 wrapper 固定值 `bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531` 匹配。使用容器原有系统 CA truststore，不关闭 TLS 证书验证。
- Kotlin、Compose、Serialization、OkHttp 与平台依赖锁文件保持原版本。

## 已执行结果

1. `gradle :core:test :desktop:classes --offline --no-daemon --rerun-tasks`：完整重新编译/运行通过。236 个 JVM 测试，0 failures / 0 errors / 0 skipped。桌面 Compose Kotlin 编译通过。
2. `gradle build :core:installDist --no-daemon`：完整 Gradle build 与核心 distribution 生成通过。desktop 没有独立 UI 自动化测试，不能将 `desktop:test NO-SOURCE` 说成 UI 测试通过。
3. `python3 -m unittest discover -s tools -p 'test_*.py' -v`：2 个 Python 测试通过。`py_compile` 与 `git diff --check` 通过。
4. 独立空目录上的 Python loopback WebDAV 服务 + 核心 distribution CLI：
   `PDSYNC_E2E_OK immutable-upload/readback/parent-DAG/restore/backup/retained-branches/resolution/no-head`。
   检查到 4 个独立提交文件、4 个对象文件；没有 `head.json`。包含初始提交、后续提交、同父分支与显式多父解决，恢复内容/备份均核验。
5. 官方 Shattered 4.0.2 / 内部922实际游戏复验通过：新建 A → 传输恢复 B → B继续游玩 → A独立分支 → 显式选择B → 再次实际继续 → 历史恢复并发布新提交 → 恢复后实际继续。5个远端对象与3个备份独立解压/哈希核验通过，没有共享head。不是两台物理设备，不含本轮 rankings/badges 的实际产生；详见 [实际游戏报告](real-game-validation/append-only-recheck/APPEND_GAME_RECHECK.md)。
6. 新 Compose 原生界面有界冒烟通过：首次中文向导、取消不落盘、滚动测试连接、下一步/上一步、保存进入主界面、读取两个并发分支、缺少目标JAR时阻止恢复。前后存档与远端文件哈希未变、无PUT。默认GL/字体路径首次崩溃；仅进程级软件渲染和现有字体配置成功，不能称默认路径已通过。未测最终冲突/恢复按钮执行和全部交互，详见 [UI 冒烟报告](real-game-validation/append-only-recheck/APPEND_UI_SMOKE.md)。
7. 独立只读代码安全复核：观察 ID 持久保留、完整祖先要求、真实字节扫描预算、已验证基线推进、普通恢复/历史恢复/冲突恢复的中断标记、失败恢复保留 gate 均复核。未发现正常流程的数据丢失阻塞项。

日志位于 `cloud-evidence/append-only-tests.log`、`append-only-build.log`、`append-only-loopback.log`。本次 JUnit XML 在 `cloud-evidence/append-only-junit/`，汇总与文件哈希在 `cloud-evidence/append-only-results.json`。

## 新增/更新覆盖

- `AppendRepositoryTest`：51 测试。冻结重试与重启、各上传/回读失败点、已接收但断线、同键不同字节不覆盖、独立随机 ID、完整分页、重复游标、父提交漏列/缺失、环路、损坏/无效 UTF-8/重复和深度 JSON、坏 ZIP、晚到分支、已知历史漏列、已见 manifest 改写、扫描预算、缺失基线、旧协议阻断。
- `AppendProfileSessionTest`：22 测试。基线与脏数据保护、初始非空目录不覆盖、实际父提交、分支保留、明确整份选择、多父解决、历史恢复新提交、版本/备份守卫、失败重试仍保持脏状态、中断 apply gate、旧队列不混用、游戏变体隔离。
- `AppendOnlyWebDavTest`：16 测试。无 ETag/条件头依赖、唯一对象路径、只读旧 head、PROPFIND 分页、同源/同集合约束、无效 href/UUID/XML、XXE/深度/长度/条数边界、401 与 404 区分、重定向与可见截断拒绝。
- `ConnectionSetupTest` 更新为完整只读列表能力校验，包含全部分页、只有 head 404 不算连接证明、旧 head 阻止混用、不上传内容。
- 原路径、系统凭据、历史备份、存档版本、旧 CAS 兼容测试继续通过。旧 CAS 测试并不代表 Cloudreve 条件写有效；当前桌面与 CLI 已退出该发布路径。

## 接入点

- `AppendOnly.kt`：格式、DAG、冻结计划、观察缓存、发布回读与基线。
- `AppendOnlyWebDav.kt`：受限 WebDAV GET/PUT/PROPFIND；只写随机 v2 文件。
- `AppendProfileSession.kt`：本地历史/备份/版本守卫与 v2 控制层。
- `Main.kt`：多分支展示/选择、观测语言、明确恢复/归档确认。
- `IsolatedDefaultPathTransfer`：实际游戏样本的隔离默认路径传输入口；另有实际游戏复验使用 `AppendGameDriver.java` 调用同一最终控制层，证据在上述报告。

## 明确限制

本次尚未在用户真实 Cloudreve 运行，不以 loopback/mock 结果替代真实服务验证。目录列举延迟无法证明全局最新；不完整不可变键会停止，需显式人工恢复或新档案；没有自动远端 GC。扫描所有已知历史有预算限制。没有签名认证、恶意服务端保证、fsync 断电事务或敌对并发路径替换防护。独立安装若使用不同本地状态目录，不共享文件锁。

Windows、macOS x64/arm64 原生运行、系统凭据成功存取、原生安装包和实际多设备 Steam 并发尚未本轮验证；Android 未实现。本轮真实游戏原生操作已单独复验通过；同步器 Compose 界面也完成上述有界冒烟，仅软件渲染/现有字体配置路径成功，默认渲染曾失败；完整冲突/恢复按钮流程未测。不借用历史截图或旧日志冒充。

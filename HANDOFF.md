# 云端迁移交接（2026-10-08，源码包说明）

本机开发/构建/UI 已停止，最后指令到达前的运行已完成：34 测试、0 failure/error/skipped；BUILD SUCCESSFUL；PDSYNC_UI_SMOKE_OK 790x612。相关原始 JUnit XML 与日志在 docs/evidence。本包不含 .local、Gradle/JDK 缓存、构建产物、真实存档、测试存档或远端数据，也不含私人服务凭据。源码保留公开 loopback demo/demo 及测试假值，它们不是用户凭据。

## 已完成安全修复

- ZIP 路径拒绝绝对路径、冒号/反斜线、空段、`.`、`..`、大小写别名、尾点、Windows 保留名；仅接受保守 ASCII 文件名。最多 4096 个文件，压缩/展开 64 MiB；远端 JSON 2 MiB，远端 ZIP 64 MiB，含无 Content-Length 的分块响应限制。
- 真实文件系统符号链接在采集/恢复拒绝；备份与存档用实际路径判定不可嵌套。ZIP 文件只以普通字节文件写入，不应用 ZIP Unix 权限或链接元数据、不会创建归档指定的符号链接。并发改路径的 TOCTOU 风险仍未解决，不得用于真实数据。
- 恢复中途写入失败回滚全局记录和局目录，备份保留；回滚本身失败会报告备份位置及两次异常，断电事务尚未实现。
- 强 ETag 语法检查，缺失/弱/畸形 ETag 拒绝；初次发布 If-None-Match:*，已有 head If-Match。失败不静默覆盖。
- 同一快照部分上传后重试：412 时回读已有不可变对象并逐字节比较；不同内容拒绝。发布成功但响应丢失时，重试确认同一 head 与远端快照后收敛。断网通过 MockWebServer DISCONNECT_AFTER_REQUEST 模拟。
- 桌面 .local/pending/active.txt 只存端点 SHA-256 与快照 ID，失败/重启重用同一快照；用户名密码不持久化。服务 URL 禁止 userinfo/query/fragment，HTTP 仅精确 loopback host，禁止重定向及自动连接重试。

## 默认路径与首启冲突

桌面固定在项目根 .local/demo 使用合成存档，无真实路径选择。原本机 .local/webdav 曾运行 core:run，首次桌面同步会出现预期父版本冲突。本源码包排除了该目录，云端首次解压是干净状态；如果云端先执行 core:run，它会留下演示远端 head，然后启动桌面也将出现预期首启冲突。不要将清理演示数据的方法用于真实服务；缺少冲突选择 UI 时保留双方并停止即可。

## 锁定版本与迁移

- JDK 21（本机 Temurin 21.0.12.1），输出 JVM 17。
- Gradle Wrapper 8.14.3，distribution SHA-256 bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531。
- Kotlin/JVM、Serialization 插件、Compose 编译插件 2.2.20；Compose 1.9.3；其 Skiko 0.9.22.2。
- 协程 1.10.2、JSON 1.9.0、OkHttp/MockWebServer 4.12.0、JUnit 5.11.4。
- core/gradle-linux-amd64.lockfile 与 desktop/gradle-linux-amd64.lockfile 锁定已解析的 Linux 传递依赖；插件版本在构建脚本固定。每种主机 OS/架构用独立锁文件，不拿 Linux 锁假称 macOS/Windows 已验证。新平台需实际解析/构建后生成自己的锁文件。

云端安装/提供官方 JDK 21；无需迁移本机 .local。解压后在根目录 chmod +x gradlew，设置 JAVA_HOME 指向云端 JDK 21，然后 ./gradlew :core:test :desktop:classes。运行 python3 tools/webdav_demo.py 和另终端 ./gradlew :desktop:run 仅用于隔离演示。无图形桌面的云端只能验证核心与编译，不应宣称 UI 运行通过。

Windows 用户设备与 Apple Silicon Mac 尚未连接；Intel Mac 没有测试机。对应平台的构建/运行/安装包/游戏闭环均待验证。macOS x64/arm64 的固定 Compose/Skiko POM 存在，不等于支持通过。

## 未完成与交接限制

真实路径发现、可靠进程检测、恢复/冲突 UI、Steam Cloud 并发测试、断电事务与真实游戏闭环均未完成。Android 宿主未证明；iOS 排除。异步 UI 上的实际按钮操作与视觉检查仍未验证。本包没有在云端重构建；父端应从现有证据开始迁移验证。原 README/PHASE1 的第一轮描述保留，最新停机安全复核状态以本说明和刷新后的 evidence 为准。

## 云端完成记录

2026-10-08：云端已完成完整 JDK 构建、37 个 Kotlin/JVM 测试、1 个 Python 演示服务测试与原生 UI 手动同步验证。新增两项核心安全修复和演示连接处理修复。最新结论、环境条件与限制见 `docs/CLOUD_VALIDATION.md`；迁移前记录保留供追溯。

## 存档管理后续

已接入目录选择、历史、恢复前可见备份与冲突选择；详见 `docs/SAVE_MANAGEMENT_VALIDATION.md`。本文件早期“未开放 UI”说明仅代表迁移时状态。继续保留隔离副本约束和未验证平台说明。

## 自动版本后续

当前版本已移除手动版本号，识别实际局存档与所选官方JAR并限制已核实兼容范围。旧scope无损导入，未知/降级阻止。最新依据与验证见 `docs/AUTO_VERSION_VALIDATION.md`；早期同字符串版本检查仅为历史基线。

## 默认位置、启动向导与安全密码存储（最新）

当前已取消主界面任意存档目录输入和选择，按官方系统路径只读解析。WebDAV 移入首次启动/设置对话框；重启可从系统安全存储取密码，否则显示简短密码提示。密码保存依赖操作系统凭据服务，没有明文后备。旧配置先备份，旧目录历史不删除不混用。

本轮 144 个 JVM 测试与 Python 回归通过；真实游戏存档的隔离副本通过默认路径解析器上传/回读/备份恢复校验。云端 CUA 返回过期 AT-SPI 进程错误，重置与重新绑定、启动终端后仍不能输入，因此本轮新 UI 视觉/按钮流程与实际游戏读档均未完成，不借用历史截图冒充新界面。原生凭据存取成功与 Windows/macOS 运行未验证。详见 `docs/DEFAULT_PATH_SETUP_VALIDATION.md`。

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

## 追加式协议 v2（2026-10-09）

桌面与隔离 CLI 已改用随机独立 ZIP/提交 + 父 DAG。旧共享 head/CAS 不再是当前桌面发布路径；旧协议检测阻止静默混用。完整重新运行 236 JVM 测试与 2 Python 测试通过，完整 Gradle build 与 loopback 分支/解决闭环通过。协议、明确迁移/恢复边界见 `docs/APPEND_ONLY_PROTOCOL.md`，本轮证据/限制见 `docs/APPEND_ONLY_VALIDATION.md`。源码从验证后的 ZIP 恢复，没有原 `.git` 历史；新本地 Git 基线不能冒称原提交祖先。

本轮另用官方 Shattered 4.0.2 新建隔离 A/B，真实游戏读入、继续、分支选择和历史恢复后继续均通过；5个远端对象和3个备份独立哈希核验。详见 `docs/real-game-validation/append-only-recheck/APPEND_GAME_RECHECK.md`。这是云端 Linux 单机双目录复验，不代表真实 Cloudreve 或跨平台完成。

新 Compose 向导及只读双分支展示的有界原生冒烟也通过；默认 GL/字体渲染先失败，改用既有进程级软件渲染与现有字体配置后成功，未声称默认路径或完整冲突/恢复按钮流程通过。详见 `APPEND_UI_SMOKE.md`（同一实际游戏复验目录）。

## 单步连接修复（2026-10-09，v5）

连接界面现为地址、用户名、密码加固定可见的单个“连接”按钮，成功直接进入主界面。新空间由适配器自动指定，原有profile保留；显式连接验证DAV根并按需创建仅两级应用目录，无需用户手动建文件夹。错误固定可见、失败保留输入，自动检查仍只读。275个JVM测试、3个Python测试和完整构建通过；详见 `docs/ONE_STEP_CONNECTION_VALIDATION.md`。用户上一轮实际HTTP码未观察，不声称已确定404。

本轮新原生短窗口连接复验通过：401保留输入、一次连接创建应用目录并进入主界面、取消不写、旧profile保留；隔离存档不变且无存档PUT。详情和5张新截图见 `docs/onboarding-v5-evidence/UI_SMOKE.md`。

## 单窗口引导改造（2026-10-09，v6）

首次连接、设置、冲突和操作确认改为同一个主窗口内的页面，移除连接DialogWindow和应用AlertDialog。保留显式JAR选择的标准文件选择器；没有开展Android实现。启动串行化和页面生命周期/代次保护防止旧回调或重复点击写入。285个JVM测试（核心275+桌面10）、3个Python测试及完整构建通过；见 `docs/SINGLE_WINDOW_VALIDATION.md`。

本轮原生复验也通过：13次窗口检查（运行11次均1个根、退出2次均0），双击去重、忙碌关闭内联提示、设置取消与确认返回重置均验证。冲突选择页未实际点击；标准JAR选择器未打开。7张新截图与窗口清单见 `docs/single-window-v6-evidence/UI_SMOKE.md`。

## 待上传重试诊断（2026-10-09，v7）

未修改 v6 的隔离原生 A3/B5 恢复→实际确认重试通过，未复现本机静默返回。v7 增加仅本机的闭合枚举操作事件（有界轮转、无凭据/URL/存档内容/异常文本）与明确取消反馈。完整构建、295 JVM 测试和3 Python测试通过；原生 restore→取消重试→HTTP503失败→成功重试的阶段日志及计划/基线/存档校验通过。核心业务和依赖未变，不声称本机根因修复。详见 `docs/RETRY_DIAGNOSTICS_VALIDATION.md`。

## 自动上传、双按钮与设备时间（2026-10-09，v8）

主界面改为上传/恢复，连接、冲突、恢复确认仍在同一窗口。自动上传观察文件变化/游戏退出；既有基线补传离线变化，新档案启动只观察。网络前冻结计划，单飞、静默检查、指数退避、暂停、只读云端刷新及明确冲突选择均实现。恢复取消人工勾选，使用当前账号可见游戏进程、文件内容/属性静默、确切备份指纹和原有版本保护。当前设备主机名+稳定随机ID及独立UTC时间进入同步元数据；恢复保留原来源，不改原始游戏字节。

完整 build + core:installDist 通过，368 JVM 测试（336核心+32桌面）0失败/错误/跳过，3 Python测试与7项独立安全测试通过。Linux同命名空间无害Java夹具、长参数回退经过真实JDK检查；Windows/macOS为模型测试，非原生通过。

生产云桌面原生UI验证双按钮、连接、暂停持久化、未知状态停止且无写入；一个当前账号常驻进程的可执行身份不可见，内核读取明确AccessDenied，生产上传/恢复正确阻止。这是可用性发布门槛，不能以测试注入绕过并宣称产品就绪。另用明确标记且只在testclasses存在的注入守卫验证原生界面业务流程：首次手动上传、文件变更自动上传、无勾选恢复、先备份、自动发布恢复提交、无自触发循环、暂停和手动上传。这个流程不证明生产进程检测闭环。用户真实会话只读探针待独立复验。

协议依然追加式，不覆盖共享引用。新元数据可向后读，旧v7严格解码器不能读新字段，使用同一云端的客户端需协调升级。详情见 docs/AUTOMATIC_SYNC_V8.md、docs/AUTOMATIC_PROCESS_SAFETY.md、docs/SNAPSHOT_METADATA.md、docs/PROCESS_CHECK_HANDOFF.md 和 docs/automatic-v8-evidence/UI_VALIDATION.md。没有真实Cloudreve写入、用户凭据访问、停止旧实例或远端Git推送。

## 普通权限游戏范围与生产流程复验（2026-10-09，v9）

按用户明确的普通权限游戏假设调整检测：无游戏关联的受限进程不再全局阻塞；已知Java参数不可见、已关联游戏信息丢失和枚举失败仍停止。改名JAR通过有界静态manifest识别，其他可识别Java程序不阻塞。同一目标复用独立检测器以保留PID/启动身份关联。没有重试或绕过AccessDenied路径，不再要求用户下载探针。

373 JVM、3 Python及8独立对抗测试通过。生产MainKt原生云Linux流程通过：手动上传、文件变化自动上传、真实普通权限官方游戏运行时拦截及退出后自动上传、无勾选备份恢复及恢复提交自动发布。此次没有注入Stopped；最终运行副本与构建JAR一致。首次恢复遇到暂时Unknown安全拒绝，明确重试后成功，失败记录保留。验证对象是合成存档和loopback，不代表本轮真实Cloudreve/游戏载入或Windows/macOS原生通过。详见 docs/AUTOMATIC_SYNC_V9.md 与 docs/automatic-v9-evidence/UI_VALIDATION.md。

## 三步引导、完整比较门槛（2026-10-09，v10）

首次流程改为同一主窗口内的三步：有界扫描默认存档位置和可信游戏，配置 WebDAV，完整核验云端 DAG 后明确选择上传／恢复／相同内容的云端关联。没有下载游戏或付费服务实现。扫描不创建目录，第一步“继续”才明确准备缺失的空默认目录。多个或不完整的游戏候选必须手动确认。旧 v9 配置可读；已核实目标及真实基线可继续既有设置；新设备、延期或无真实基线的重启仍先比较。

自动上传的持久门槛独立于可见页面，连接成功、取消／返回、改账号、重启均不会绕过首次决定。双方空的明确决定只建立当前会话的空观察起点，不创建云端基线或空上传；实际游戏创建进度并退出后才上传。不同内容恢复先保留本机分支和备份，再追加明确的解决提交；相同字节不自动猜测祖先。完整历史缺失／坏对象／未知状态保持 BLOCKED。冻结上传重试保留原键和字节。

完整 build + core:installDist 通过，431 JVM 测试（391 核心 + 40 桌面）、3 Python 测试与 26 独立审查测试通过，均零失败／错误／跳过。生产云端 Linux 原生检查覆盖未发现／手动选择、401、取消与重入、双方空、本机独有、云端独有、不同内容的上传与恢复、相同内容关联、缺失云端对象，以及真实官方游戏运行时阻止写入、退出后首次自动上传。恢复前备份、父图与实际 ZIP／文件哈希独立核验。没有注入 Stopped。测试程序与隔离服务已关闭。

UI 检查中修正两项展示问题：完成／重启后不再默认选择空安全备份；比较被阻止时显示文件数尚未核验。原生证据按三个冻结桌面构建 A/B/C 分开记录，核心 JAR 全程逐字节一致。最终 C 重放了恢复、重启与阻止展示；没有冒称整套原生矩阵均在 C 重跑。详见 docs/ONBOARDING_V10.md、docs/INSTALLATION_DISCOVERY.md 和 docs/onboarding-v10-evidence/native-ui-report.md。

本轮继续保留规范 Git 父历史，并按发现、比较、状态门槛、界面与两项展示修正分开提交。源码归档不包含 .git、游戏二进制、原始真实／合成存档、运行配置、缓存或凭据。没有修改用户本机运行中的 v9。Windows/macOS 原生、真实 Cloudreve、跨设备、Steam Cloud 互斥、断电与恶意 TOCTOU 仍未验证。

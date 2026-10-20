> RECONSTRUCTED code92 source recovery candidate. Not byte-for-byte original source recovery; not a released APK. See [recovery status](RECONSTRUCTION_STATUS.md).

> code89 / 0.5.68-test：按历史记录生成时间显示和排列存档脉络，最新记录在上，保留88原页冲突弹窗；详见 [CODE89_HISTORY_RECORD_TIME](docs/CODE89_HISTORY_RECORD_TIME.md)。

> code88 / 0.5.67-test：冲突分支选择在主页弹窗显示，普通上传保持原有路径；详见 [CODE88_CONFLICT_DIALOG](docs/CODE88_CONFLICT_DIALOG.md)。

> code87 / 0.5.66-test：主页“存档脉络”右侧新增经授权的刷新按钮，只读重载本机与云端存档；详见 [CODE87_HOME_HISTORY_REFRESH](docs/CODE87_HOME_HISTORY_REFRESH.md)。

> code86 / 0.5.65-test：续期查询确认可用后，返回主页一次性只读加载本地与云端存档；详见 [CODE86_RENEWAL_ARCHIVE_READ](docs/CODE86_RENEWAL_ARCHIVE_READ.md)。

> code85 / 0.5.64-test：赞赏页新增经授权的“刷新 / Refresh”，查询最新期限并更新主页显示；详见 [CODE85_APPRECIATION_REFRESH](docs/CODE85_APPRECIATION_REFRESH.md)。

> code84 / 0.5.63-test：主页到期时在原状态位显示“服务已到期”，并按授权增加赞赏入口；设置/赞赏往返保持状态，续期更新后移除。Android/Desktop共享UI同步，其他同步权限不变。

> code83 / 0.5.62-test：已认证的待续费账号可点击原有“完成配置”进入主页；身份保存仍仅发生在明确点击完成时，远程同步继续独立校验真实期限。

> code82 / 0.5.61-test：保持79原登录，客户端统一将官方服务器有限期限减一个UTC日历年作为真实有效期；无需标记或部署。详见 [期限规则](docs/EXPIRY_GRACE_PROTOCOL.md)。源码内server恢复79，注册/续期保存“实际+一年”为后续待做。本次未上线、未迁移、未加控件提示或清理动作。

> Android code69 / 0.5.48-test：手机页面按内容高度排布，取消600dp卡内撑高；详见 [CODE69_MOBILE_CONTENT_HEIGHT](docs/CODE69_MOBILE_CONTENT_HEIGHT.md)。以下版本记录保留供追溯。

> 当前 Android 测试版：0.5.40-test / code61。安装确认修复、证据边界与签名说明见 [CODE61_SYSTEM_CONFIRMATION](docs/CODE61_SYSTEM_CONFIRMATION.md)。

# 像素地牢存档同步器：存档管理原型

Kotlin/JVM 独立核心 + Compose Desktop。默认使用游戏的系统存档目录，也支持显式选择专用目录，提供四步首次引导、文件设置持久化、存档变化/游戏退出后的自动上传、整个档案的历史快照、备份后恢复、显式冲突选择，以及 WebDAV 追加式分支同步。仅在隔离测试目录中验证；不是可直接托管唯一存档的正式产品。

## code60 · Android 主流程修复（2026-10-18）

版本 `0.5.39-test`（code60）。修复选择应用列表被页面重绘销毁，以及“修改 APK”仅改变模式而不执行所选文件处理的问题，保留 M3 界面。根因、可执行回归和验证边界见 [code60 修复说明](docs/CODE60_ANDROID_FLOW_FIX.md)。本次开发证书已变更，不能直接覆盖 code59。

## code59 · v21-M3 云端版本（2026-10-16）

Android 版本为 `0.5.38-test`（code59）。本次按指定 v21-M3 HTML 改造共享页面与 Android 接入，验证方法、测试结果和剩余差异见 [M3 云端验证](docs/M3_CLOUD_VALIDATION.md)，参考范围见 [M3 对照说明](docs/M3_REFERENCE_CONTRACT.md)。历史验证文档不代表本次 Android 真机结果。源码清单 `SOURCE_MANIFEST.json` 已重新生成。

## Android / 桌面共享 Compose 构建

连接、验证码、注册与账号界面共用 `shared-ui/src/main/kotlin` 的同一份源码，由桌面 Kotlin 和 Android Gradle Plugin 分别编译。Android 主应用入口为 `./gradlew :android-app:assembleRelease`，产物为 `android-app/build/outputs/apk/release/android-app-release-unsigned.apk`；不生成调试签名密钥。

先安装 SDK 36 / Build Tools 36.0.0，并设置 `ANDROID_HOME`、`PDSYNC_BRIDGE_RUNTIME_DEX`、`PDSYNC_ROOT_NATIVE_DIR`、`PDSYNC_APKSIG_JAR`。已有依赖缓存时运行 `./gradlew :core:test :desktop:test :android-app:assembleRelease --offline`。具体输入、首次受控依赖解析和锁文件见 [共享 UI 构建说明](shared-ui/README.md)。独立 bridge 与 root-native 构建保持原流程；旧 SDK-only 脚本不再构建含 Kotlin/Compose 的主应用。Android 真机、旋转、输入法及 Root 加载仍需独立验证。

## 最新协议：追加式 v2

桌面和隔离演示使用独立随机 ID 的 ZIP 与提交文件，以父提交 DAG 保留并发分支，不再依赖 Cloudreve 的条件覆盖或共享 head。只有完整对象和全部祖先验证后，提交才可用；重试复用已冻结的相同键与相同字节。单一观测分支不代表全局最新。历史恢复与显式冲突选择创建新提交，不删除旧分支或合并二进制存档。

具体协议、迁移边界、故障恢复与验证限制见 [追加式协议说明](docs/APPEND_ONLY_PROTOCOL.md)。旧 CAS 兼容模块和历史验证文档继续保留，不能视为当前桌面协议。

## 运行

使用 JDK 21（输出 JVM 17 字节码）：

```sh
./gradlew :core:test :desktop:classes
./gradlew :desktop:run
# 独立 CLI 夹具（另一个终端启动 demo 服务后执行 core:run）
python3 tools/webdav_demo.py
./gradlew :core:run
```

首次引导在同一个主窗口内分四步：选择游戏 → 设置存档路径并预览实际槽位、游玩次数与时间 → 测试 WebDAV 连接后点击下一步（密码可空） → 比较并选择存档或完成。主页采用云端／本机双列真实历史图；只有独有内容可能被覆盖时才明确选择覆盖、合并或取消。当前功能、命名和验证边界见 [正式桌面界面说明](docs/DESKTOP_V11.md)。

扫描不写目录。默认存档目录尚不存在时，“继续”会明确准备该空目录供之后恢复，不生成游戏存档。只有本机时提示上传，只有云端时提示恢复；双方都有且不同则保留本机后明确选择，多个分支不按时间自动选胜者。不完整、缺失或校验失败的云端会阻止比较，不会当作空云端。双方都空时可完成配置，不上传空快照。已有相同基线且内容一致时可直接进入主页；相同内容但没有关联时需明确采用云端关联，不伪造历史。

服务地址指向已经存在的 WebDAV 根目录。客户端验证根目录确为集合，只在其下创建游戏和内部同步空间两级目录；不会创建根目录、遍历上层路径、覆盖同名文件或把405当作成功。新连接自动使用适配器默认空间，旧配置的内部ID保留，不重定向已有数据。旧版 head.json 继续阻止新旧协议静默混用。

右上角“设置”包含自动上传暂停、目标游戏 JAR、云端连接以及重新检查四步引导。标准 JAR 文件选择器仍用于显式选择；连接、比较、冲突和操作确认都在同一个主窗口。旧 v9 的可信目标和真实同步基线可继续既有设置，无需重复完整引导；缺失目标或连接身份变更时重新核验。“稍后处理”保留双方进度并持续暂停自动上传，之后点击主页上传／恢复即可继续比较。

设置按用户选择保存到本地配置文件。测试连接不会保存配置；点击下一步或保存后才写入。旧系统安全存储不会被读取或导出。

存档根目录不可编辑，不再提供任意文件夹选择或合成演示切换。按官方 Shattered 4.0.2 规则解析：

- Windows：`user.home/AppData/Roaming/.shatteredpixel/Shattered Pixel Dungeon`
- macOS：`user.home/Library/Application Support/Shattered Pixel Dungeon`
- Linux：`XDG_DATA_HOME/.shatteredpixel/shattered-pixel-dungeon`；只有变量未设置时才使用 `user.home/.local/share`

扫描发现目录不存在时会明确显示；用户在第一步继续才准备该空默认目录供后续恢复，不会改用别处。空/相对 XDG、点路径片段、符号链接、未知系统等会失败关闭。[完整规则和来源](docs/DEFAULT_PATH_RULES.md)。目标游戏 JAR 与存档目录是两件事：在第一步确认扫描结果或手动选择，设置中也可更换，用于只读版本校验及当前账号的游戏进程识别；详见 [自动版本识别](docs/AUTO_VERSION_VALIDATION.md)。

演示服务公开凭据为 demo/demo，仅供固定 demo 空间的 CLI/自动化夹具。内部空间由测试配置注入，不重新暴露在正常界面。正常桌面连接应使用实际 WebDAV 根地址，其应用目录会按需自动创建。为了避免接触真实存档，Linux 测试应在隔离进程中同时给游戏与同步器设置相同的 `HOME`、`XDG_DATA_HOME` 与 JVM `-Duser.home`；不要向真实默认目录写合成文件。命令行 `:core:run` 仍是独立的合成演示，不改变桌面默认路径。

## 操作与安全约束

- 上传与恢复按游戏以普通权限运行的假设，检查当前系统账号的可识别游戏进程，并等待文件静默、复核内容与文件属性。无游戏线索的受限进程不拦截；已关联游戏身份不明或进程枚举失败仍停止，不再要求退出游戏勾选。只支持已验证的官方 JAR 身份；其他账号、Steam Cloud 等写入者仍须避免并发，这不是云同步互斥或无竞争保证。
- 快照是所有 `gameN` 完整目录，加 `rankings.dat`、`badges.dat`、`journal.dat`。设备设置不参与。`bones.dat` 也不在本轮文件白名单，保持目标原状；这不是整个游戏文件夹克隆，其覆盖策略待单独确认。不解析、合并或转换二进制存档；恢复时缺失文件表示删除。
- 主界面仅保留“上传”和“恢复”两个操作。运行且已连接时每秒观察允许的存档文件，变化或游戏退出后，经过静默期再自动上传；设置可暂停，手动上传仍可用。手动上传在第一次网络请求前冻结快照和真实基线父提交；自动上传先完整比较真实云端历史，仅在只有本机进度、同一云端基线上的本机变化或父提交仍匹配的冻结重试时继续，并在写入前重验同一比较。上传并回读 ZIP，再发布独立提交。网络写入失败指数退避至最多两分钟，重启仍重试原计划；比较不满足自动条件时留在主页，等待新变化或明确重试，不重复发送。首次未完成配置且无基线时先完整比较并明确选择，不自动上传启动时已有内容；双方空且明确完成后才观察未来新增进度，重启后的首次观察也不假定本地曾为空；已有基线会检测并补传关闭同步器期间的变化。不上传空快照。
- 云端分支在连接后及空闲时每分钟自动读取，会核验完整祖先并展示所有已观察分支，存入本地历史；单一观测分支不代表全局最新。普通远端恢复仅允许空的新目标或未变化的本地基线向后代推进；脏存档须先保留本地分支并显式选择。
- 每份新快照显示采集设备名、稳定设备短 ID、采集时间、文件修改时间及本机云端核验时间；UTC 存储、按本地时区显示。旧快照未知来源不猜测；恢复/选择保留原来源，新提交另外记录操作设备。元数据不写入原始游戏文件。
- 历史列表是当前目录/服务/档案/版本的本地快照库，也包含本机读取过的远端版本、冲突双方与恢复前自动备份。它不是远端历史枚举，也不是完整游戏战绩。游戏排行榜条数有限，本工具不重建此前没有保存的战绩、不合并累计计数。
- 恢复前从实际归档重新读取每局版本并核对所选游戏，再验证完整文件集合、ZIP 路径及校验和；先写备份并将它登记进可见历史，再进行游戏停止检查及文件变更复核，然后替换。普通写入故障可回滚；回滚失败会报告备份位置。操作进行中，普通关闭窗口会被阻止。
- 冲突保留所有已观察分支并逐一展示。明确选择整份进度后，以所展示分支为多父版本创建新提交；需要替换本地时先备份。历史恢复也会创建待上传的新提交，不回退远端引用。不会静默覆盖或合并二进制；选择期间进度变化需重新选择。
- 有待上传快照或未确认完成的恢复时，其他恢复/发布会被阻止。可检查当前文件与备份后明确归档，再重新选择操作。归档不删除历史或远端。

## 配置与存储

默认设置固定保存在 `user.home/.pixel-dungeon-sync/.local/config.json`，与启动工作目录无关。隔离验证可显式设置 `-Dpdsync.workspace=...`，并始终复用相同目录。旧工作目录的配置不自动迁移；发现它时提示保留位置，避免静默丢失设置。

按用户选择，配置文件直接保存游戏 JAR 路径、WebDAV 地址、用户名和明文密码，以及引导进度和上传暂停状态。测试成功且点击下一步或保存后原子保存；保存失败保留编辑内容并明确提示。重启自动加载和只读验证连接；旧配置没有密码时提示补填，不读取或导出旧系统凭据。配置损坏时不显示原文，原文件保留，重新保存前备份损坏内容。配置及备份不要分享或放入诊断附件；Git 和构建归档排除它们，配置对象的文本表示隐藏密码。仓库没有配置诊断导出功能。

未完成连接时，已选择游戏的路径单独写入同目录的 `setup-game.txt`，启动仍重新识别 JAR。目标仍可信时，本机明确完成的配置（包括双方空的决定）在重启后继续进入主页，不因此生成同步基线。旧配置没有明确完成决定时，仍需真实基线或重新比较；配置完成、连接成功和同步关系分别校验，不会因已保存密码或相同存档内容而绕过恢复和自动上传保护。

地址不允许 userinfo、query 或 fragment；不要在 URL 路径中放凭据。HTTPS 是默认要求，只有精确 loopback 主机允许 HTTP。

`.local/manager/profiles/<scope>/` 保存历史与备份；独立 `append-v2/` 保存已观察提交、冻结计划、真实本机基线及恢复中断标记。旧本机 head/队列只保留，绝不解释成 v2 基线。新 scope 绑定规范化真实目录、游戏适配器、远端端点和档案名；每份快照保留各局的自动版本信息。不同 scope 不共用父版本或重试队列。可证明属于同一目录/端点/档案的上一轮手动版本 scope 会非破坏性导入，校验快照且保留时间、备份历史和待上传 ID；多个待上传或同步基线有歧义时停止，不自动选择。原数据不删除。不要同时继续使用旧版客户端。旧第一轮 `.local/demo`、`.local/pending`、`.local/device-head` 不自动迁入新管理目录。

WebDAV 适配层验证用户配置的根目录，再通过显式“连接”按需 MKCOL 两级应用目录。需要 GET、PUT、MKCOL 与完整的 PROPFIND 列表；不依赖 ETag、条件覆盖、LOCK/MOVE。发现旧 head 时阻止静默混用，须停用旧客户端并明确使用新档案迁移。`tools/webdav_demo.py` 只供 loopback 隔离测试，可用 `--port` 和 `--root` 启动独立测试存储，不能用作生产服务。

## 验证与限制

v10 增加 [三步引导与比较门槛](docs/ONBOARDING_V10.md) 及 [有界安装发现](docs/INSTALLATION_DISCOVERY.md)。431 个 JVM 测试、3 个 Python 测试和 26 项独立审查通过；[生产 Linux 原生矩阵](docs/onboarding-v10-evidence/native-ui-report.md) 覆盖首次比较与真实游戏退出上传，并明确区分 A/B/C 桌面构建及最终复验范围。v9 按普通权限游戏的使用方式收窄进程识别，无游戏关联的受限进程不再全局阻止同步。自动上传、双按钮和元数据沿用 [v8 功能说明](docs/AUTOMATIC_SYNC_V8.md)，当前检测规则及验证结果见 [v9 验证](docs/AUTOMATIC_SYNC_V9.md)。只读进程探针是可选诊断，不是使用前置步骤。历史 v8 注入测试及当时的阻塞记录保留供追溯，不代表当前检测规则。

待上传重试诊断与取消反馈见 [v7 操作诊断](docs/RETRY_DIAGNOSTICS_VALIDATION.md)。本机现象尚未复现，此轮不声称根因已修复。

最新单窗口改造见 [单窗口验证](docs/SINGLE_WINDOW_VALIDATION.md)，单步连接修复见 [单步连接验证](docs/ONE_STEP_CONNECTION_VALIDATION.md)，追加协议记录见 [追加式协议验证](docs/APPEND_ONLY_VALIDATION.md)。默认位置与设置的上一轮验证见 [默认位置与连接设置验证](docs/DEFAULT_PATH_SETUP_VALIDATION.md)。历史记录见 [云端验证](docs/CLOUD_VALIDATION.md) 和 [存档管理验证](docs/SAVE_MANAGEMENT_VALIDATION.md)。历史迁移记录保留在 [HANDOFF.md](HANDOFF.md)。

本轮仍未解决：断电事务、恶意并发路径替换的 TOCTOU 风险、所有启动方式/进程可见性的可靠检测、Steam Cloud 并发、系统安全存储原生实测、其他安装包/版本的识别规则和生产 WebDAV 兼容性。Windows 已完成隔离构建与启动验证；Apple Silicon Mac、Intel Mac 及安装包尚未验证。Android 独立宿主未证明，iOS 路径另行评估。Linux 双目录测试不能冒充两台真实设备或跨平台验证。

## 自动版本识别与兼容性

- `gameN/game.dat` 支持有界 gzip / 明文 JSON，只读取顶层整数 `version`（最近写入版本）；`init_ver` 不能代替它。每局单独记录；空/1字节槽位视为非进行中，不能据此猜测版本。
- 全局记录不冒充统一写入版本。只有全局记录、未知或损坏版本仍可原样归档，但不能恢复；混合局版本逐一校验，不能只取最大值。
- 目标游戏来自有界候选扫描或手动选择的 JAR；只读取游戏标识、启动类条目、发行版和内部版本，不运行它，不使用校验和白名单或来源认证。文件名不决定版本。缺失或矛盾元数据会说明原因；其他可识别版本可以选择，但未知恢复能力仍阻止恢复。详见 [JAR 识别与恢复边界](docs/JAR_IDENTIFICATION.md)。
- 选择与恢复是独立门槛：3.3.8 / 内部896的恢复写入版本范围为802–896，4.0.2 / 内部922为850–922。下限来自已检查的加载规则，上限保守阻止降级。这里只按版本元数据判断，不认证发布者或保证改版行为；未逐个验证历史存档迁移，也未运行本机3.3.8游戏。同步器不转换二进制，原始快照和恢复前备份保留。
- 可只读检查 Windows/macOS 原生包和默认 Steam 目录里的固定 JAR 候选槽位；这不等于支持其原生包身份，也不扫描自定义 Steam 库。多个候选需明确选择，未唯一识别时手动选 JAR。不能用系统设置里的“上次启动版本”或某一份存档版本替代目标游戏版本。

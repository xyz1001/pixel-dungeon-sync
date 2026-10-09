# 首步安装发现：范围、信任与验证

2026-10-09。`ShatteredInstallationDiscovery` 是只读、有界的安装候选探测器。默认存档位置仍完全使用 `ShatteredDefaultSavePaths`；没有改变官方路径规则，也不会创建存档目录或读取其中的存档内容。

## API

- `ShatteredInstallationDiscovery(context = SavePathContext.current(), roots = null)`：生产默认从显式系统上下文生成候选路径；测试必须传入临时目录与替代根列表，不扫描实际主目录。
- `discover(configuredTarget = "")`：先检查既有配置的 JAR，再检查候选目录。返回默认存档路径/目录是否可用、每个 JAR 的来源和静态结果、有界检查诊断。
- `validateJar(Path)`：用户手动选择的一个 JAR，仍调用 `ShatteredVersions.target`。不启动游戏，不加载游戏类。
- `InstallationDiscoveryResult.trustedTargets`：只含 `TargetGame.trusted == true` 的结果。
- `suggestedTarget`：仅完整检查后恰好一个可信目标时返回。多个可信目标、路径不可读取或任何限额导致检查不完整，都不自动选择第一个。
- `requiresSelection`：多个可信目标或检查不完整时为 true；没有命中时仍须由 UI 提供手动定位/刷新。

`available` 只说明默认目录存在且通过路径检查，不说明其中有有效存档、游戏已停止或可恢复。`suggestedTarget` 是候选建议，不是用户已经选择了该目标的事实。UI 应展示路径并由用户继续；恢复时原有版本与停止/备份保护仍须重验。

## 路径来源与明确的推断

本轮可读取的官方资料：

1. [Shattered 官方桌面构建说明](https://github.com/00-Evan/shattered-pixel-dungeon/blob/master/docs/getting-started-desktop.md)：桌面包使用 jpackage，Windows/Linux 发布可移动文件夹，macOS 为可放进 Applications 的 `.app`；该项目不使用安装器功能。因此不能声称 Windows/Linux 便携包有唯一官方安装目录。
2. [官方 desktop/build.gradle](https://github.com/00-Evan/shattered-pixel-dungeon/blob/master/desktop/build.gradle)：应用图像名取 `appName`；JAR 和原生包构建流程不同。它是本轮读取到的 master，不是固定 4.0.2 标签证据；本轮固定标签的在线读取返回 cache miss，未据此扩展任何版本信任。
3. [Oracle JDK 21 jpackage 图像布局](https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html)：Windows 的 `app`、Linux 的 `lib/app`、macOS 的 `Contents/app` 是应用 JAR 槽位。只检查这些固定相对目录和图像根；不会遍历 `runtime`。
4. [Steam 官方默认库说明](https://help.steampowered.com/en/faqs/view/4578-18A7-C819-8620)：Windows 默认 `C:\Program Files (x86)\Steam\steamapps\common`，也允许其他库位置。[Steam macOS 帮助](https://help.steampowered.com/en/faqs/view/30EB-87BF-531F-512D)使用用户 Library/Application Support/Steam。
5. Linux `.local/share/Steam`、`.steam/steam` 是有限的常见客户端布局候选，不是 Shattered 官方安装保证；[Valve 上游客户端问题中的实际路径记录](https://github.com/ValveSoftware/steam-for-linux/issues/12100)提供前一种布局的直接记录。它是用户提交的运行记录，不冒称 Valve 的安装规范。

具体策略：

- `user.home/Downloads` 只枚举一层，且只考虑文件名含 `shattered` 的 `.jar`。
- `user.home/Downloads/Shattered Pixel Dungeon`、`user.home/Games/Shattered Pixel Dungeon` 是便携包放置位置的便利推断；macOS 对应 `.app`。这些不是检测到的安装事实。
- macOS 额外尝试 `/Applications/Shattered Pixel Dungeon.app` 与用户 `Applications`。
- Steam 只尝试默认客户端目录下 `steamapps/common/Shattered Pixel Dungeon`。游戏名目录是候选提示，不读取 Steam 账号/注册表、不声称已查明实际安装目录。macOS 同时考虑该目录内同名 `.app/Contents/app` 槽位。
- Windows 从显式上下文的有效 `ProgramFiles(x86)` / `ProgramFiles` 生成默认 Steam 候选；都没有时只尝试 `C:/Program Files (x86)` / `C:/Program Files`。
- 不解析其他 Steam 库、磁盘卷、快捷方式、Flatpak/Snap、任意解压后的另一个目录名。未命中只表示这些候选未找到可用 JAR，不能推断游戏未安装。用户可手动选择实际 JAR。

搜索提示不能授予信任。仅官方 4.0.2 多平台 JAR 的既有 SHA-256 白名单与清单校验可产生可信目标；原生启动器、未知原生/Steam 组件、修改版、只有相同清单的伪造 JAR、其他版本均不会因此被认作可信。没有新增下载或安装，也没有宣称验证了原生包/Steam 的完整游戏安装。

## 安全与工作上限

- 不递归遍历。最多 12 个候选根、48 个固定目录槽位、512 个目录条目，最多 4 次 JAR 检查，配置 JAR 计入同一限额。
- 自动发现每次 `ShatteredVersions.target` 最多读取 64 MiB 输入并解压 128 MiB。整个自动检查最多 256 MiB 输入与 512 MiB 解压数据（有界流读取至多增加每候选 1 个哨兵字节、最后一块解压检查至多增加 8191 字节）。ZIP 中央目录先验证声明的解压总量，再执行有界解压；条目/清单/身份/哈希保护不变。
- 手动检查只读一个 JAR，沿用已有 256 MiB 输入、512 MiB 解压限额。未放宽原有校验。
- 拒绝相对路径、`.` / `..` 片段以及文件/目录/祖先中的符号链接；先检查原始片段，再规范化。不跟随链接去猜测另一个安装。
- 不读取原生程序、脚本内容，不执行程序或命令，不读存档正文，不访问网络、WebDAV 或凭据，不写配置。
- 缺失目录是正常未命中；访问拒绝、错误类型、坏路径、不可检查候选及扫描达到上限单独标记为不完整，保留已发现列表供用户明确选择。
- 路径与文件可能在探测后变化；此 API 不宣称解决恶意并发路径替换的 TOCTOU 问题，不能替代实际操作时重验。

## 本轮验证

`InstalledGameDiscoveryTest` 使用显式临时主目录/安装根，不访问开发者或用户实际主目录。覆盖：无命中且不创建目录、独立默认存档状态、错误 XDG、目录误为文件/链接、单目标/多目标、配置优先与去重、失效配置不静默替代、伪造清单不可信、坏 JAR、原生脚本不执行、固定槽位不递归、文件与祖先链接、链接加 `..`、候选/目录条目/根/槽位限制、不可读目录、超大输入和声明解压限额。

可信结果选择逻辑的单元测试使用显式 `synthetic-test-only` 检查器，不冒充真实哈希验证；伪造 JAR 测试使用生产检查器。真实官方 JAR 的静态检查另记，不运行游戏。Windows/macOS 路径为模型测试，尚未原生验证。

聚焦命令：`gradle :core:test --tests pdsync.InstalledGameDiscoveryTest --tests pdsync.SaveVersionsTest --offline`。全量构建和 UI 集成验证由当前主任务统一记录，不能以这些核心测试代替。

已完成记录：19 项发现单元测试全部通过，0 failure/error/skipped，JUnit 原始结果见 [发现测试](installation-discovery-evidence/TEST-pdsync.InstalledGameDiscoveryTest.xml)。对既有官方 JAR 的隔离复制件，生产检查器确认单一目标可建议、两份可信副本必须选择、手动选择仍可信；全程没有游戏执行或创建默认存档目录。见 [静态探针结果](installation-discovery-evidence/official-static-probe.log) 与 [可复查的探针源码](installation-discovery-evidence/DiscoveryOfficialProbe.java)。官方 JAR 约 54.3 MB，声明解压约 106.0 MB，确实落在自动发现的 64/128 MiB 限额内。

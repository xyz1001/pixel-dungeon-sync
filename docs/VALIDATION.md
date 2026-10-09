# 验证记录（2026-10-08）

环境：Linux x86_64 / Wayland，隔离 Temurin 21.0.12.1、Gradle 8.14.3，Kotlin 2.2.20、Compose 1.9.3、Skiko 0.9.22.2、协程 1.10.2、OkHttp 4.12.0、Serialization 1.9.0。

## 实际证据

- `:core:test :desktop:classes` 构建成功。最终 18 项测试，0 failure、0 error、0 skipped：[JUnit XML](evidence/core-tests.xml)。包含 ZIP 往返/持久记录、上传中断、归档与文件校验失败、401、412/If-Match、弱 ETag 拒绝、离线分叉、恢复失败回滚、删除不复活、路径别名、游戏未停止/版本不符、HTTP 发布顺序、ZIP 穿越、符号链接拒绝、备份目录别名重叠拒绝。
- loopback Python 服务实际完成两次有父关系发布、ZIP/JSON 回读、恢复/备份和离线冲突，输出 `PDSYNC_E2E_OK upload/readback/conditional-parent/restore/backup/offline-conflict`。最初沙箱阻止本地 socket，经环境审批后运行成功。测试服务已停止，隔离远端快照保留。完整构建日志见 [构建测试](evidence/build-tests.log)，窗口生命周期日志见 [Linux UI](evidence/linux-ui-lifecycle.log)；带已知截屏失败的端到端原始日志也保存在 evidence。
- Linux Compose 实际启动及窗口显示检查，原生 Skiko 加载无错误，3 秒后正常退出，输出 `PDSYNC_UI_SMOKE_OK 790x612`。
- 视觉验收未通过/未完成：AWT paint 对 GPU 窗口输出空白，随后 Robot 捕获被 Wayland 拒绝 `SecurityException: Screen Capture in the selected area was not allowed`。已移除截屏代码，没有修改安全设置。不能把 Gradle 成功退出当成截图通过，也没有宣称实际按钮交互通过。

## 平台矩阵

| 平台 | 依赖存在证据 | 本机编译 | 窗口生命周期 | 视觉/按钮交互 | 安装包 | 真实游戏 |
|---|---|---|---|---|---|---|
| Linux x64（本机） | 已解析 | 通过 | 通过 | 未验证 | 未验证 | 未验证 |
| Windows x64 | 尚未完整解析验证 | 未验证 | 未验证 | 未验证 | 未验证 | 未验证 |
| macOS Intel x64 | Compose/Skiko POM 存在 | 未验证 | 未验证 | 未验证 | 未验证 | 未验证 |
| macOS Apple Silicon arm64 | Compose/Skiko POM 存在 | 未验证 | 未验证 | 未验证 | 未验证 | 未验证 |

Steam 与官方独立版均没有安装/存档定位或游戏读取实测；其他渠道手动路径 UI 也未完成。不同设备/OS 的版本兼容仅提供保守接口，未从游戏格式验证。没有用 CI 配置代替实机结果。Android 宿主未实现/可行性未证明；iOS 排除。

## 依赖核实与网络

[当前 Compose 官方兼容表](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)（1.12.1）仅列 macOS 13 arm64，并不证明旧版 x64 不可能。固定 1.9.3 的 [macOS x64 POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/desktop/desktop-jvm-macos-x64/1.9.3/desktop-jvm-macos-x64-1.9.3.pom) 和 [arm64 POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/desktop/desktop-jvm-macos-arm64/1.9.3/desktop-jvm-macos-arm64-1.9.3.pom) 已实际取回，分别声明 Skiko 0.9.22.2 架构运行包，副本在 evidence。Intel 是待实机验证的发布门槛，不声称已支持；当前不必先替换 UI。

[Kotlin Gradle 官方表](https://kotlinlang.org/docs/gradle-configure-project.html)中 2.2.20–2.2.21 对应 Gradle 7.6.3–8.14。系统 JDK 27 没有用于最终构建，避免旧 Gradle 运行在不兼容 Java 上。

沙箱 `downloads.gradle.org`、`repo.maven.apache.org` DNS 失败；经环境 require_escalated 审批后官方下载成功，期间 Java 27 下 Maven HTTPS 有 handshake terminated。工作区官方 JDK 21 与兼容 Gradle 解阻，无系统安装、无 DNS/代理设置更改。下载涉及 api.adoptium.net / GitHub 重定向、downloads.gradle.org、plugins.gradle.org、repo.maven.apache.org、Google Maven。Wrapper 固定官方 SHA-256，实际发行包校验一致。

## 可复现演示

在空的隔离 `.local/webdav` 上运行 `python3 tools/webdav_demo.py`，另一个终端执行 `./gradlew :core:run`。该命令只生成临时合成存档并清理，不接受用户存档路径；运行后演示远端保留快照，后续桌面初次上传将正确触发冲突。需要重新演示时仅清理已知隔离测试目录，不能迁移该做法到真实服务。

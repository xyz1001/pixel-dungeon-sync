> 历史版本验证记录。期限方案已由code82取代，不应用本文指导部署；当前规则见EXPIRY_GRACE_PROTOCOL.md。

# code80 / 0.5.59-test 开发交付验证

本轮日期：2026-10-20。开发源码已修改；没有线上部署、真实账号迁移、原签名 Android APK 或 Windows 真机运行声明。

## 基线与范围

从 Library 中完整 code79 源码 ZIP 独立恢复，SHA-256 为 `537826a1e9cafd93bdc7ed776ba139eb56b4f3e93aa6dfaa53df4a8579f76412`。ZIP 共 1769 个文件，原清单记录 1768 个文件（不包含清单本身）。没有复用未采纳的 SFTPGo fork/双鉴权补丁。

本轮改变：原生 token 登录、短期 Bearer 期限读取、SFTPGo 真实/宽限期限映射、注册续期幂等转换、只读迁移审查、协议测试和文档。共享界面的现有真实期限禁同步逻辑保留；没有增加控件或无关提示。

## 验证结果

- Java host 注册/原生登录协议：2518 项检查通过。
- Java host 账号/赞赏协议：1095 项检查通过。
- core JUnit：833 项通过，0 失败，0 跳过。
- managed-service Python：163 项通过。
- manual-orders Python：108 项通过。
- Windows portable 打包规则 Python：8 项通过。
- Python 编译和禁用示例配置离线校验通过。
- Desktop JUnit 最终重跑：566 项，0 失败，0 错误，1 项按环境条件跳过（需要真实图形窗口的 NativeGameChooserTest）；其余 565 项通过。

协议 host 测试使用合成传输，不连接真实服务。Python 测试使用合成账号/隔离数据和本机测试服务，不改真实账号。迁移 dry-run 测试实际调用 CLI，核对数据库调用前后 SHA-256 完全一致，并确认不存在可执行的 `--apply` 模式。

## 构建与环境记录

原项目完整 Gradle 配置在离线及正常在线解析时均无法取得 Android Gradle Plugin 8.11.1。官方 Google Maven 的该 POM 可直接读取，因此这里仅记录当前 Gradle 解析失败，不归因为已验证的网络域限制。没有修改依赖版本或锁文件绕过该问题；当前环境也没有 Android SDK、桥接 dex、原签名 keystore 等完整 Android 构建输入。

因此 JVM 验证采用工程外部配置：保留原 core/desktop/shared-ui 源码、依赖和模块脚本，仅在外部根配置中排除 Android 插件/模块。JDK 21 已有 compiler 模块但缺 javac 启动器，在隔离工具目录补充了调用该现有模块的启动器；没有改变产品代码或下载替代版本。

首次 Desktop 完整测试为 566 项、124 失败、1 跳过。失败主要是 Skia 在不可写默认 home 下解包 native 库失败，另有一项原生登录诊断回归。诊断已补回原有安全日志结构并使用 GET；随后通过独立可写 home/tmp、现有 headful JDK 和软件渲染重跑，未删除或禁用原测试。

## 独立复核

检查了原生认证、Bearer 回源验证、身份前后快照、期限标记、写入幂等、旧账号及无限期路径。发现并修正：未启用 native-expiry 的旧 local-gate 配置不能接受新 Bearer 期限协议，否则原生 0 可能错误覆盖账本到期。现已返回 503 `NATIVE_EXPIRY_REQUIRED`，并有回归测试。

旧 Basic entitlement 入口为兼容旧客户端保留；本次客户端仅把密码交给原生 token（注册仍使用注册服务），不会通过该兼容入口登录或读取期限。Token 不写入缓存或永久存储。

## 仍未验证／部署前要求

- 真实 SFTPGo/native token 和 Nginx 新精确路由的端到端运行；本环境无 nginx 二进制，未声称 nginx 配置语法已检查。
- 真实账号身份绑定迁移、宽限配置启用和续期在线闭环；此次不执行这些动作。
- Android 原签名构建、安装及真机流程；Windows 原生 UI、凭据存储及真实设备同步。
- 必须先审查旧账号只读 dry-run、账号不复用/单写入者约束、原服务配置与路由，再另行批准上线。不能直接用示例覆盖完整线上配置。
- 没有增加账号/存档删除、清理或定时清理功能。

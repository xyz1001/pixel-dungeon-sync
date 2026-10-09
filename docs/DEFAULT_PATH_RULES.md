# 默认存档目录规则（2026-10-08）

## 核实范围与来源

本规则针对官方 Shattered Pixel Dungeon **4.0.2 / 内部版本 922 桌面版**。没有扫描安装目录、用户磁盘或其他游戏，也不根据一份存档猜测路径。

- 固定版本源码：[DesktopLauncher.java](https://github.com/00-Evan/shattered-pixel-dungeon/blob/v4.0.2/desktop/src/main/java/com/shatteredpixel/shatteredpixeldungeon/desktop/DesktopLauncher.java)。本轮读取之前隔离游戏验证保存的源码副本，并使用已验证官方 JAR 的 `javap -c` 反汇编交叉核对路径分支；本轮在线抓取失败，没有把失败的网络读取当作新证据。
- 源码副本 SHA-256：`9d8a4bcb37dc4b63d1e5db4ce84d5d84f4441c1ba627332356af2a7e7d90831f`。
- 官方 JAR SHA-256：`bcefd52a9c14f69c69d3ccc08a212cdd3830b2dc60d78d80895cd803fa1ec602`，与此前[版本验证](AUTO_VERSION_VALIDATION.md)的可信指纹一致。
- 从该 JAR 清单读取：`Specification-Title: Shattered Pixel Dungeon`、`Implementation-Title: com.shatteredpixel.shatteredpixeldungeon`、`Specification-Version: 4.0.2`、`Implementation-Version: 922`。启动器取实现名称中第二段作为 vendor，即 `shatteredpixel`。
- 对 JAR 内 `Lwjgl3Files` / `Lwjgl3FileHandle` 的字节码检查确认：`External` 路径基于 JVM 的 `user.home`。Windows 规则不是读取 `%APPDATA%` 或 `%USERPROFILE%`，即使这些变量指向另一个目录。

## 路径

以下 `user.home` 是启动当前 JVM 的系统属性，不能用 `HOME` 环境变量替代。

| 平台 | 默认存档根目录 |
| --- | --- |
| Windows，除精确名称 `Windows XP` | `user.home/AppData/Roaming/.shatteredpixel/Shattered Pixel Dungeon` |
| 精确名称 `Windows XP` | `user.home/Application Data/.shatteredpixel/Shattered Pixel Dungeon` |
| macOS | `user.home/Library/Application Support/Shattered Pixel Dungeon` |
| Linux，`XDG_DATA_HOME` 未设置 | `user.home/.local/share/.shatteredpixel/shattered-pixel-dungeon` |
| Linux，`XDG_DATA_HOME` 已设置 | `XDG_DATA_HOME/.shatteredpixel/shattered-pixel-dungeon` |

启动器显式将名称包含 `FreeBSD` 的系统按 Linux 处理。其余桌面识别来自该 JAR 内 libGDX：区分大小写，依次检查 `os.name` 是否包含 `Windows`、`Linux`、`Mac`。`Windows XP` 分支额外要求整个名称精确相等。`Darwin` 不属于这些判断，不能因为名称里含有小写 `win` 就当作 Windows，也不能擅自当作 macOS。

Windows/macOS 保留标题中的空格和大小写；Linux 使用根区域设置转小写并把空格改成连字符。macOS 不含 `.shatteredpixel` 层。CPU 架构不参与这些路径分支；这不等于 Windows、Intel Mac、Apple Silicon Mac 或 FreeBSD 的构建运行已经验证。

## 明确的安全收紧

上游 Linux 分支只在 `XDG_DATA_HOME == null` 时回退。它没有按 XDG 规范将空值或相对路径自动改成默认值：

- 空值直接拼接后得到 `/.shatteredpixel/shattered-pixel-dungeon/`。
- 相对值 `data` 得到 `data/.shatteredpixel/shattered-pixel-dungeon/`。即使标记为 libGDX 的 `Absolute` 类型，底层 `java.io.File` 仍保留相对路径，最终依赖工作目录。

同步器对这两种输入**明确报错并停止**，不去读取这些危险或含糊的目录，也不静默回退到另一份档案。包含 NUL 的输入同样拒绝。只有实际需要 `user.home` 时才验证它；合法绝对 `XDG_DATA_HOME` 不需要 `user.home`。缺失、相对或无效的必需主目录，以及没有核实规则的系统，同样报错，不回退到工作目录、演示目录或先前选择的自定义目录。

`ShatteredDefaultSavePaths` 只作字符串解析，不读取文件、不创建目录、不探测存在性、不启动游戏。存在性、目录类型、符号链接与恢复安全检查由调用层执行。`DefaultProfileLocation` 在规范化前拒绝 `.` / `..` 片段，避免符号链接加父目录段被提前消除后指向另一份存档。

## API 与测试

- `SavePathContext(osName, userHome, environment)` 是可注入上下文；`SavePathContext.current()` 只负责采集当前进程上下文。
- `GameSavePaths.resolve(context): String` 是独立的每游戏路径规则接口。
- `ShatteredDefaultSavePaths` 实现已核实的 Shattered 规则。
- 返回值使用正斜线，Windows JVM 也支持；Windows 输入反斜线仅转换为同义分隔符。解析器不调用当前主机的 `Path` 提供器判断另一平台的路径，不裁剪空格、不展开 `~` 或环境变量、不消除 `.` / `..` / 重复分隔符。比如输入路径末尾已有 `/` 时，拼接结果保留双 `/`；只有调用方在本机使用时才转换为 `Path`。返回目录字符串不保留上游的终止 `/`，目录含义不变。
- `DefaultSavePathsTest` 对 Windows、XP、UNC、macOS、Linux、FreeBSD、Darwin、大小写识别、缺失主目录、XDG 未设置/绝对/空/相对/NUL、空格/Unicode/反斜线/末尾分隔符/点段与可替换接口进行纯合成上下文测试。没有访问用户的游戏存档。

聚焦验证命令：`./gradlew :core:test --tests pdsync.DefaultSavePathsTest`。全量测试和桌面集成结果应以最终交付时的构建记录为准；纯路径测试不是跨平台原生运行证明。

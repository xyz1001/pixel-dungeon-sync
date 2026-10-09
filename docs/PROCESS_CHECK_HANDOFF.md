# 只读进程检测复验

用于在真实 Windows/macOS/Linux 用户会话核对生产进程检测。不要停止正在运行的 v7 同步器或覆盖它使用的 JAR。先将 v8 源码放进独立目录，使用 JDK21：

```sh
./gradlew :core:installDist
java -cp "core/build/install/core/lib/*" pdsync.ProcessCheckKt "/absolute/path/to/ShatteredPD-v4.0.2-Java.jar"
```

Windows 在源码目录用 `gradlew.bat :core:installDist`，然后同样的 `java -cp "core/build/install/core/lib/*" ...` 命令，传入用户实际选择的官方 JAR 绝对路径。macOS 与 Linux 使用自己的路径；不能把云端路径当用户本机路径。

程序只读 JAR 版本/哈希及当前账号可见进程。不会访问存档、网络、密码或系统安全存储，不启动/关闭游戏，不修改文件。只输出可信目标与 Running/Stopped/Unknown、相关进程 ID、缺少可执行身份的进程 ID；不输出命令行、用户名、环境变量或文件内容。

应在同一个正常用户桌面会话分别观察游戏已退出、正常运行、正常退出后的结果。运行和退出由用户自行完成或另行明确授权。Unknown 是正确的失败关闭，不能手动改为 Stopped。若当前账号进程身份不可读或权限受限，记录最小诊断并停止，不绕过安全权限；在信息可见的正常系统会话再复验。未知常驻进程可能让本版本无法上传/恢复，这是当前可用性限制。

测试专用 `desktop/src/test/.../InjectedUiSmoke` 不在生产 desktop.jar 内，只验证界面与业务流程，标题明确标为 injected process guard；它不能作为上述生产进程检测通过的证据。

## 不需完整应用的独立探针

独立 `pdsync-process-check-v8.jar` 约 1.8 MiB，JDK17+ 可运行：

```sh
java -jar pdsync-process-check-v8.jar "/actual/local/path/to/ShatteredPD-v4.0.2-Java.jar"
```

它包含生产检测类的原样字节码和 Kotlin 标准库，`PRODUCTION_CLASSES.json` 可与 core.jar 逐类核验；Java 薄包装先核对同一官方 JAR SHA-256，再调用生产检测器。源码 `tools/ProcessVisibilityCheck.java`、可复现打包工具 `tools/build_process_probe.py` 一同提供。探针不要替换既有应用的运行文件。

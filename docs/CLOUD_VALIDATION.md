# 云端验证（2026-10-08）

## 当前结论

原源码包校验一致后在 dot 云端继续开发。Linux 原生 Compose 窗口已打开，中文与密码遮罩正常；手动同步、失败后重启重试、再次同步已实际操作并验证。所有操作仅涉及 `.local` 下的合成演示存档与 loopback 演示服务。

- Kotlin/JVM：37 个测试全部通过，failure/error/skipped 均为 0。
- Python 演示服务：1 个回归测试通过，覆盖 404、首次条件上传、回读、重复上传 412、原内容保留。
- 全量重新构建：`BUILD SUCCESSFUL`，8 个任务实际执行。
- 原生窗口生命周期：`PDSYNC_UI_SMOKE_OK`；退出后重新打开成功。
- 手动同步：界面显示“上传及回读校验成功”；本地 device-head 与模拟远端 head 一致，ZIP SHA-256 与合成内容核对成功。
- 中断恢复：首次尝试在连接断开时没有发布 head；待上传快照保留。关闭并重启后重试复用同一快照 ID，验证已有不可变对象，再发布成功并清除 pending 标记。
- 再次同步：生成新快照，parent 正确指向上一次成功快照；回读校验与 pending 清理正常。

## 本轮修复

1. 恢复前第二次游戏停止检查移到回滚范围外。游戏重新启动导致检查失败时，不会将新存档覆盖为旧备份。
2. 采集入口只接受真实目录（NOFOLLOW_LINKS），拒绝目录符号链接和普通文件，避免误生成空快照。
3. HTTP/1.0 演示服务在成功响应中明确声明 `Connection: close`；PUT 成功响应增加零长度正文，避免禁用自动重试的客户端复用已关闭连接。

前两项新增 3 个测试，修复前均可重现失败；修复后原有 34 个测试与新增 3 个测试全部通过。演示服务另有独立 Python 回归测试。

## 环境与复现

- Linux x86_64，Temurin JDK 21.0.12.1+1，JVM 输出目标仍为 17。
- Gradle Wrapper 8.14.3 与原有 Linux 依赖锁未改动。
- 云端预装 Java 可编译，但缺少 headful AWT 库。本轮使用项目内完整官方 JDK，没有修改系统 Java。
- 云端渲染使用 `-Dskiko.renderApi=SOFTWARE`，并通过仅作用于该进程的 `FONTCONFIG_FILE` 选择本机 DejaVu 与 Noto CJK 字体。未改变系统字体、显示或网络设置。
- 工具链和构建缓存不在源码包内。正常开发仍按 README 提供 JDK 21，执行 `./gradlew :core:test :desktop:classes`。演示服务测试：`python3 tools/test_webdav_demo.py`。
- 启动演示服务后，以 README 的 `:desktop:run` 启动界面。不要将演示数据的清理、重建流程应用于真实存档或真实远端服务。

## 证据

`cloud-evidence/build-tests.log` 为最终 JDK 下的全量重建；`core-tests.xml` 为 37 测试结果；`pre-fix-regression.log` 和 `pre-fix-core-tests.xml` 为三项修复前失败；`demo-server-test.log` 为 Python 测试；`ui-flow.json` 记录合成快照验证结果；`cloud-sync-success.jpg` 为真实运行截图。

`desktop-run.log` 按时间保留首次缺失 AWT、初始云端字体/渲染失败及后续成功启动，不能将早期失败误认为最终状态。原 `docs/evidence` 与 HANDOFF 的 34 测试记录为迁移前历史证据，以本文件和 `docs/cloud-evidence` 为最新状态。

## 仍未完成

这是隔离原型。真实存档路径发现、可靠进程检测、恢复/冲突选择 UI、断电事务、并发路径变更防护、Steam Cloud 并发、真实游戏闭环均未完成。Windows/macOS 构建运行与安装包未验证，Android 宿主未证明，iOS 排除。不能据此承诺真实存档安全或跨平台正式可用。

## 后续存档管理里程碑

目录、历史、恢复和冲突 UI 已继续实现；最新测试与实际游戏闭环以 [SAVE_MANAGEMENT_VALIDATION.md](SAVE_MANAGEMENT_VALIDATION.md) 为准。本文前面的 37 测试、合成演示与“未完成”列表是上一轮历史状态。

最新自动版本改动及追加真实游戏复验见 [AUTO_VERSION_VALIDATION.md](AUTO_VERSION_VALIDATION.md)。

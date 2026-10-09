# v7 操作诊断与取消反馈（2026-10-09）

## 结论与范围

本轮未复现用户本机报告的“重试确认后立即回主页、仍显示上次恢复完成、待上传计划不变”。因此这是诊断增强与取消反馈改善，不是已确定根因的上传修复。没有修改核心快照、备份、恢复、DAG、远端协议或凭据行为。

此前 v6 原生冒烟仅取消了确认页，没有提交最终操作。本轮先用未经修改、JAR 哈希与 v6 记录相同的版本，在新的隔离 HOME/XDG_DATA_HOME/workspace 中补测完整流程：

1. 用公开 demo/demo 与 loopback 服务预置 A（3 文件）及其后续 B（5 文件）。
2. 在原生 Compose 页面选 A，实际勾选两框并提交恢复。恢复后 A 的三文件保留，B 的五文件备份进入历史，新的 restore 提交冻结待上传。
3. 点击“重试待上传快照”，勾选两框，实际点击右侧绿色“确认继续”。
4. 发布与回读完成，状态变为“追加提交已回读校验。所选进度已成为本机同步基线。”，按钮变回“同步当前存档”；新增 restore 提交以 B 为父，active-plan 消失，基线推进到该 restore 提交。

结果与请求记录见 `retry-v7-evidence/unmodified-v6-native-result.json`、`v6-loopback-requests.txt`、`v6-native-retry-success.jpg`。这只能说明本轮云端路径正常，不能否定用户本机现象或断言其点击了取消。

## 诊断记录

新日志位于应用 workspace 下的 `.local/operation-events.log`，旧一代为 `.local/operation-events.log.1`。每个文件最多 128 KiB。只记录 UTC 时间、与存档 ID 无关的随机操作 UUID、动作枚举、事件枚举以及可选的保护原因/失败类别。没有网络遥测。

不会记录 URL、用户名、密码、认证头、存档内容、存档/提交 ID、文件路径、原始异常消息或调用栈。粗分类来自异常类型或既有状态枚举，不解析异常文本。写入强制刷新；失败返回 false，不改变业务操作或回显含路径的写入异常。目录不可写时日志可能缺失，应先检查记录是否生成，不能把“没有记录”直接解释为用户未点击。

事件读法：

- `CONFIRMATION_ENTERED`：打开确认页。重复进入会有新 UUID；同页的提交、取消与保护拒绝使用该页同一 UUID。
- `CONFIRMATION_CANCELED`：取消/返回回调已执行。此时主页显示明确的取消信息；待上传重试取消显示“已取消上传，待上传快照仍保留。”
- `CONFIRMATION_SUBMITTED`：提交回调已进入。
- `GUARD_REJECTED`：提交被 BUSY、STARTUP、CLOSED 或 NO_SESSION 保护拒绝，确认页显示原因，不静默返回。
- `OPERATION_QUEUED` / `OPERATION_STARTED` / `WORK_STARTED`：依次到达排队、协程执行及 IO 工作。
- `WORK_SUCCEEDED`：IO 工作已返回结果（包含已观察分支冲突结果，不等于用户已解决冲突）。此事件在 IO 内发出。
- `WORK_FAILED`：业务异常，附粗分类；`OPERATION_CANCELED`：协程中断，不代表没有发生文件或远端变化。
- `HISTORY_REFRESH_*`：业务后历史刷新阶段。
- `OPERATION_FINISHED`：协程结束；业务或刷新失败类别会保留到该事件。排队后即取消可有 FINISHED(CANCELED) 而无 STARTED。

当前日志只围绕需要确认的快照/上传/恢复/解决/归档操作。连接、只读刷新、JAR 选择不新增日志。入口按钮的忙碌禁用逻辑与已有生命周期机制不在本轮重构范围。未声称解决取消在开始前留下 busy 标记等尚未实际复现的生命周期问题。

## 本机一次诊断的安全边界

先验证 v7 源码/构建与日志可写，再由用户在原会话中重试一次；收集对应操作 UUID 的允许字段，以及待上传计划/基线是否变化。无需读取、导出或复制密码；不删除、归档或重建待上传计划，不改真实远端数据来“修复”症状。重启若需要重新输入会话密码，由用户自行输入。

## 验证记录

完整构建、自动化及原生诊断流程结果见本目录证据。只使用云端 Linux 的隔离服务、公开 demo 凭据和测试存档，没有访问用户电脑或真实 Cloudreve；软件渲染/字体与跨平台限制继续成立。

### 已执行结果

- 完整 Gradle 命令：`:core:test :desktop:test :desktop:classes build :core:installDist --offline --no-daemon`，BUILD SUCCESSFUL。295 项 JVM 测试（核心 275、桌面 20），0 失败/错误/跳过；Python 3 项通过。没有依赖或锁文件变化。
- v7 原生流程：恢复五文件历史备份，产生待上传 restore 计划；重试确认两框后取消，状态明确显示取消，计划、基线、远端、存档哈希及请求数均不变。
- 再次确认重试，loopback 夹具受控返回 HTTP 503：界面显示操作未完成，日志保留提交、排队、开始、NETWORK 失败、历史刷新与带 NETWORK 的终止；原计划、基线、远端和存档哈希不变。
- 解除夹具故障后再次实际确认：使用同一冻结计划发布，基线变为该计划提交，父关系正确，active-plan 消失，存档哈希不变。日志完整记录成功阶段。
- `native-action-events.txt` 的四组操作顺序经程序核验，取消与失败/成功可明确区分。该日志没有 demo 凭据、URL、存档文件名或发布提交 ID。截图见 `v7-cancel-pending.jpg`、`v7-failed-retry.jpg`、`v7-successful-retry.jpg`。

未对本机 Wayland 输入、真实服务、任意中断/磁盘故障或跨平台作成功声明。磁盘无权限与符号链接等日志失败情形由单元测试覆盖，不是本次原生故障注入。

# 飞书专用群续期机器人（实现与离线验证，默认关闭）

管理员在固定专用群直接发**精确用户名**，例如 `alice`。不需要 @，不要求私聊，
不读“我是管理员”等文本身份声明，不从消息提取金额、年数或付款凭证。
一条获授权的平台消息代表一次人工续期授权；同一条消息的重复投递不是新授权。
管理员主动另发一条同样用户名的新消息会再续一年，因此不要用重新发消息来处理超时。

本目录提供严格事件适配、持久 inbox/outbox、恢复处理器和官方 SDK 长连接接线。
没有连接飞书、部署、创建应用/凭据/权限，也没有真实账户查询或生产权益写入适配器。
不修改 Windows 或手机。现阶段不是可直接上线的生产服务。

## 授权与事件边界

- `Binding()` 默认关闭；启用时必须填写 app ID、tenant key、专用 chat ID、管理员
  `open_id` 白名单以及机器人自身 `open_id`。样例只有占位符，不能直接启用。
- 唯一生产入站是 `sdk_transport.build_long_connection` 注册到官方
  `lark_oapi.ws.Client` 的 `im.message.receive_v1` 回调。它只接受 SDK 的
  `P2ImMessageReceiveV1` 对象。没有 HTTP/webhook 入口，没有直接信任外部 JSON 的路由。
- 长连接通过应用凭据与飞书平台建立可信连接；类型检查和字段白名单并不是独立的验签。
  不能把内部 `accept_sdk_event`/`parse_sdk_event` 方法接到任意 HTTP 请求上。
  也不能把空 webhook 密钥的 dispatcher 用在 HTTP 服务上。
- 同时核对 header app/tenant、sender tenant、`sender_type=user`、白名单 open ID、
  `chat_type=group`、固定 chat ID、`message_type=text`；忽略机器人消息和非授权事件。
- content 必须是只有一个 `text` 键的严格 JSON，重复键、额外字段、空白修剪、大小写转换、
  多行/多命令、@ 提及和身份自报均不支持。用户名沿用现有登录规则
  `[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}`，由账户适配器精确查找现有账户。
- 幂等键是 `(app_id, tenant_key, message.message_id)`，不是 header event ID。
  同 key 的 username/chat/sender 不能重绑。原始事件正文、secret、token 不写日志。

### 飞书侧必须人工核实的设置

企业自建应用需要机器人能力、将机器人加入专用群、事件订阅 `im.message.receive_v1`，
订阅方式选择长连接。直接收取未 @ 的群消息需要 `im:message.group_msg`，它是敏感权限，
可能需要管理员审批和发布后生效；仅有 @ 机器人消息权限不满足这个交互。
回复需要 `im:message:send_as_bot`。具体租户的可用性、发布范围和审批仍需现场核实。
没有这些权限时应报告阻塞，不能擅自改为必须 @。

官方参考：[消息接收事件](https://open.feishu.cn/document/server-docs/im-v1/message/events/receive)、
[长连接接收事件](https://open.feishu.cn/document/server-docs/event-subscription-guide/event-subscriptions/event-subscription-configure-/choose-a-subscription-mode/request-url-configuration-case)、
[回复消息](https://open.feishu.cn/document/server-docs/im-v1/message/reply)。

## 首次只读接通与 ID 发现（先于正式处理器）

`setup_cli.py` 不需要完整 binding，也不导入机器人业务、SQLite 队列或续期 ledger。
它只做官方 SDK 长连接握手、有限群列表 GET、机器人信息 GET 或选定群的 ID 观察。
不会调用续期、查询账号、修改权益、发送群消息、自动选择管理员或写入/启用 binding。
这些命令**会连接飞书**，仅供获授权的人在自己的受限服务端手动执行；仓库测试不会执行它们。

**只允许在正式处理器启用前运行。** 同一应用的多个长连接会分摊事件，setup 会 ACK 后丢弃
业务消息；与正式处理器并行可能使续期消息被吞掉。运行前停止同应用的其他监听实例，
确认后才传 `--pre-production`；此参数只是人工确认，不能探测其他主机上的连接。
setup 期间不要发送真实续期授权。

最简顺序：

1. 在飞书开发者后台准备企业自建应用并启用机器人能力。由管理员核实/批准权限及发布范围。
   只读发现不需要发送消息权限；免 @ 的群消息需要 `im:message.group_msg`。
   首次不知道 chat ID 才需要群列表读权限 `im:chat:read`，已有可用的
   `im:chat:readonly` 等官方等价权限也可以。无需为此申请通讯录或群成员列表权限。
2. 在受控 Python 3.10+ 环境中安装、审查固定的 `requirements.txt` 及传递依赖，先通过
   已安装 SDK 的离线 smoke test。App Secret 由你已有的 secret 存储注入环境变量
   `FEISHU_APP_SECRET`，或放在仓库外、当前用户独占读取的现有文件中（POSIX 权限 0600）。
   CLI 不创建/保存 secret，不接受 secret 值参数；不要把它贴到聊天、群、Git 或 shell 命令里。
3. 在本目录运行下面的 handshake。在它仍运行时，回到后台选择“使用长连接接收事件”并保存，
   然后订阅 `im.message.receive_v1`。`STARTING_CHECK_CONSOLE` 只表示开始尝试；
   **必须以控制台保存成功/显示已连接为准**，不能把进程存活当作接通。
   保存完成后 Ctrl-C 停止 handshake，创建版本并经管理员批准发布，使机器人、权限和订阅生效。
4. 将机器人加入专用群。运行 `bot-info` 获取机器人自己的 `bot_open_id`；该官方接口无需额外
   scope，但须已启用并发布机器人能力。若还没有 chat ID，运行 `groups` 后由人核实并选择群。
5. 把已选群 ID 显式传给 `discover`。让准备授权的管理员本人在这个群发一条普通诊断文字
   （不需要 @）；CLI 仅打印 `app_id`、header 的 `tenant_key`、`chat_id`、`sender.open_id`。
   不读/打印正文、完整事件、token，也不记录到文件。输出只是实际平台 ID，**不是管理员授权**；
   由管理者另行核对本人身份、目标群及租户后，人工填入仓库外仍为 `enabled:false` 的 binding。
   本实现沿用正式处理器的同租户限制，header tenant 与 sender tenant 不同的事件会被忽略。
6. 停止 setup，运行离线配置校验。即使 ID 已齐全，正式账户/权益适配器、生产防护和获授权的
   实际联调仍须完成；不要因为 setup 接通就启用正式处理器。

以下 `cli_example` / `oc_selected` 必须替换为你核实的真实 ID。命令默认只读取已有的
`FEISHU_APP_SECRET`，示例不包含任何 secret 值：

```sh
cd server/feishu-renewal-bot
python setup_cli.py handshake --app-id cli_example --pre-production
# 控制台保存长连接并订阅事件后，先 Ctrl-C，再发布应用/权限并加入专用群。
python setup_cli.py bot-info --app-id cli_example --pre-production
python setup_cli.py groups --app-id cli_example --pre-production
python setup_cli.py discover --app-id cli_example --chat-id oc_selected --pre-production
python check_config.py /path/outside/repository/binding.local.json
```

其他 secret 注入方式只能二选一：`--secret-env EXISTING_ENV_NAME` 或
`--secret-file /path/outside/repository/app-secret`。使用文件时请移除当前进程环境中的
`FEISHU_APP_SECRET`；两个来源同时存在会拒绝启动。文件允许一个末尾换行，不接受空值、
空白、多行、过大值或样例占位符。未知 CLI 参数只返回固定错误码，不回显参数值。

所有命令有 `--duration` 硬时限（默认 180 秒，范围 1–600）；Ctrl-C、SIGTERM 或终端关闭
会停止并回收 SDK 子进程，不留后台续期 worker。日志与未处理的 SDK 输出被屏蔽；失败只打印
固定错误码。`OBSERVATION_WINDOW_ENDED` 只表示观察时间已结束，并不证明接通或控制台已保存。
`NO_MATCHING_EVENT` 表示未观察到符合上述限制的事件，请检查选定群、发布、权限和租户。
不确定是否连通时查官方控制台，不要开启 SDK debug 日志或上传原始错误。

`groups` 只请求第一批最多 20 个群，输出 chat ID/群名和 `has_more`；可用 `--limit 100`
扩大这一批。`has_more:true` 表示未列全，目标不在结果内时通过[官方群列表 API 调试台](https://open.feishu.cn/document/server-docs/group/chat/list)
继续人工定位并复制 chat ID；CLI 不输出分页 token，也不无限扫描或自动选择第一群。
`discover --limit N` 最多输出 N 个不同发送者（默认 20，最多 100），只在显式指定的群观察。
终端中的 ID/群名也是私有配置资料，不要提交到仓库或公开报告。

官方依据：[机器人信息及权限](https://open.feishu.cn/document/client-docs/bot-v3/obtain-bot-info)、
[机器人所在群列表及权限](https://open.feishu.cn/document/server-docs/group/chat/list)、
[固定 SDK 群列表 request 源码](https://github.com/larksuite/oapi-sdk-python/blob/0b9e6e48b74bb4b34462fc67b7e738b27e73e697/lark_oapi/api/im/v1/model/list_chat_request.py)、
[固定 SDK 通用 GET request 源码](https://github.com/larksuite/oapi-sdk-python/blob/0b9e6e48b74bb4b34462fc67b7e738b27e73e697/lark_oapi/core/model/base_request.py)。

## 持久状态与回复

回调只做本地校验和 SQLite inbox 提交，再唤醒后台处理器。持久写入失败会继续抛给 SDK，
不会假装成功 ACK。SQLite 锁等待上限为 0.5 秒；账号查询、续期和回复网络 I/O 均不在回调中。
官方 SDK 的回调异常生成 500 响应，正常回调才返回 200。该实现没有承诺磁盘严重故障时
仍能满足平台时限；应监控持久失败并保证本地磁盘可用。

后台任务使用短事务 claim 和 30 秒持久租约，事务外调用 `FeishuRenewals.renew(...)`。
inbox/outbox 和权益 ledger 可以使用同一文件，但不依赖跨外部 API 的事务原子性。
若 ledger 已提交、进程却在 outbox 提交前退出，恢复后仍调用原 message ID，权益 ledger
必须返回原固定目标，不能采用“相对加一年”的非幂等接口。

`RenewalBot.run()` 在启动后主动处理已有队列，不依赖飞书重投或管理员重新发用户名。
重试采用持久延迟（5 秒起，最大 300 秒）与最多 8 次自动尝试；进程退出后的租约到期可恢复。
超过次数、授权配置变化或不安全结果进入 `review`，保留原 message ID 和审计记录。
运维必须检查 review 行，核实后使用**同一授权操作 ID**进行恢复，不能删除记录或发新消息替代。
本阶段不提供远程运维入口或自动无限重试。

回复仅由已验证的 ledger 结果决定：

- `unknown_user` 且返回用户名匹配：`未知用户。`；未知结果也持久化，后来创建同名账户不会
  让原消息变成新授权。
- reserved/applying/retry_required，且有匹配用户名、operation ID 和正数冻结目标：
  `续期已记录，待执行。`；reserved 不能表述为已生效。
- confirmed 且上述字段有效：`已续期，到期时间：YYYY-MM-DD HH:MM:SS UTC。`
- 尚未建立权益记录、禁用、冲突等：`未完成续期，请检查。`；不泄露上游错误详情。

outbox 独立于续期任务，回复失败永远不会重跑一个新的续期授权。每条回复持久保存标准 UUIDv5；
飞书的回复 UUID 去重只有一小时，自动重试仅在首次尝试后 55 分钟内且不超过 8 次。
超窗、时钟倒退、崩溃后的不确定发送结果必须人工核对，不能盲目换 UUID 重发。
已获得最终结果时取消旧的未发送“待执行”回复；先前发送结果不明的中间回复保留为 review。
回复 API 的成功响应只代表平台接受，不证明管理员已读。

## 本地测试（不需要飞书账号）

Python 3.10+：

```sh
cd server/feishu-renewal-bot
python check_config.py binding.example.json
python -m unittest discover -v
```

标准库测试覆盖：授权字段、严格 JSON/文本、机器人和私聊忽略、未知账户、SQLite 并发、
重投和重启、入站写入失败、ledger 提交后崩溃、真实 ledger 与 entitlement GET 一致、
reserved/confirmed 分离、回复失败/发送后崩溃、UUID 超窗、启动恢复、有限重试。
`test_ledger_integration.py` 使用真实 `FeishuRenewals`、真实持久队列与真实 GET dispatch，
只有账户/外部权益及飞书传输是 fake。`test_sdk_transport.py` 明确区分 fake builder 测试和
需要真正已安装 SDK 的可选离线 smoke test，后者仍禁止网络。

生产传输依赖固定为 `lark-oapi==1.7.4`，版本信息见
[官方 PyPI 页面](https://pypi.org/project/lark-oapi/1.7.4/)。SDK 接口参考
[官方长连接源码](https://github.com/larksuite/oapi-sdk-python/blob/0b9e6e48b74bb4b34462fc67b7e738b27e73e697/lark_oapi/ws/client.py)及
[事件注册源码](https://github.com/larksuite/oapi-sdk-python/blob/0b9e6e48b74bb4b34462fc67b7e738b27e73e697/lark_oapi/event/dispatcher_handler.py)。
这些源码参考不代替实际安装版本验证。部署前必须完成依赖锁定和审查、已安装 SDK 的
离线 smoke test，以及获授权的真实长连接、权限、消息接收与回复联调；fake builder 测试
不能证明真实 SDK 或平台集成已经可用。
`test_setup_cli.py` 另外验证只读初始化接线、secret 输入边界、元数据白名单、日志脱敏及
有限时长/Ctrl-C 子进程回收；它的真实 SDK smoke test 同样在 SDK 未安装时明确 skip。

## 部署接线样例（尚不能直接上线）

复制 `binding.example.json` 到 Git 之外，保持 `enabled:false`，从可信管理来源核实所有 ID。
app secret 由受限的部署 secret 存储注入进程，不能写入 binding、Git、群消息或命令行参数。
本实现不生成或保存凭据，也不申请权限。

部署方另行实现并评审真实账户精确查询、稳定账户 ID 验证、绝对权益目标的单调/幂等写入和
读回确认；在生产防护（含旧 DAV/自定义客户端的上传限制）完成前不要启用。
保留原账户、密码与登录/下载，不得把 SFTPGo 登录过期当作仅上传到期。

仅在这些条件及授权具备后，站点自己的启动脚本可以使用以下集成接口；没有内置假生产后端：

```python
# site-owned code; accounts and entitlements must be reviewed production adapters.
from bot import Binding
from feishu_renewals import FeishuRenewals
from sdk_transport import run_service

binding = Binding.load(BINDING_PATH)
ledger = FeishuRenewals(LEDGER_PATH, enabled=True,
                       accounts=accounts, entitlements=entitlements)
run_service(binding, QUEUE_PATH, ledger, secret_from_deployment_store)
```

模块搜索路径需要同时包含本目录与 `server/manual-orders`。队列及 ledger 放在受限持久目录，
部署账户独占写入，使用备份并监控 `review`、worker 存活和 SDK 断连；不要放在临时目录/NFS，
不要让普通用户修改绑定或数据库。无需入站公网端口，出站仅连接官方飞书 HTTPS/WebSocket。
不提供 systemd 自动启用、安装或部署命令，避免把准备工作误当作上线授权。

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

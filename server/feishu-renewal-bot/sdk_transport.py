"""Official SDK only: no HTTP callback, direct event endpoint, or fallback auth."""
import importlib.metadata
import json
import threading

from bot import Binding, DurableQueue, RenewalBot

SDK_VERSION = "1.7.4"


def _load_sdk():
    if importlib.metadata.version("lark-oapi") != SDK_VERSION:
        raise RuntimeError("SDK_VERSION_MISMATCH")
    import lark_oapi as lark
    from lark_oapi.api.im.v1 import P2ImMessageReceiveV1, ReplyMessageRequest, ReplyMessageRequestBody
    return lark, P2ImMessageReceiveV1, ReplyMessageRequest, ReplyMessageRequestBody


def _check_binding_secret(binding, app_secret):
    if not isinstance(binding, Binding) or not binding.enabled or not binding.complete():
        raise ValueError("BOT_BINDING_DISABLED_OR_INCOMPLETE")
    if type(app_secret) is not str or not app_secret or "REPLACE" in app_secret.upper():
        raise ValueError("APP_SECRET_REQUIRED")


def build_long_connection(binding, service, app_secret):
    """Build only; no socket is opened until the caller explicitly calls start().

    The SDK receives events over the app-authenticated TLS WebSocket. Empty
    dispatcher webhook keys follow its WS example; they are NOT HTTP auth.
    Never expose this dispatcher through Flask, ASGI, do(), or raw HTTP input.
    """
    _check_binding_secret(binding, app_secret)
    if service.binding != binding:
        raise ValueError("SERVICE_BINDING_MISMATCH")
    lark, event_type, _, _ = _load_sdk()

    def receive(event):
        if not isinstance(event, event_type):
            raise TypeError("EXPECTED_OFFICIAL_SDK_EVENT")
        service.accept_sdk_event(event)
        # No exception swallowing: durable failure must produce SDK's error ACK.

    handler = (lark.EventDispatcherHandler.builder("", "")
               .register_p2_im_message_receive_v1(receive).build())
    return lark.ws.Client(binding.app_id, app_secret, event_handler=handler,
                          log_level=lark.LogLevel.ERROR)


class OfficialReplies:
    def __init__(self, binding, app_secret):
        _check_binding_secret(binding, app_secret)
        lark, _, self.request_type, self.body_type = _load_sdk()
        # A bounded request is shorter than the durable outbox lease. The SDK
        # may acquire an app token first; callers must also bound their backend.
        self.client = (lark.Client.builder().app_id(binding.app_id).app_secret(app_secret)
                       .timeout(10).log_level(lark.LogLevel.ERROR).build())

    def send_reply(self, message_id, text, uuid):
        request = (self.request_type.builder().message_id(message_id)
                   .request_body(self.body_type.builder().msg_type("text")
                                 .content(json.dumps({"text": text}, ensure_ascii=False))
                                 .uuid(uuid).build()).build())
        response = self.client.im.v1.message.reply(request)
        if not response.success():
            # Do not leak response payload, account data or credential-bearing errors.
            raise RuntimeError("FEISHU_REPLY_NOT_CONFIRMED")
        return getattr(getattr(response, "data", None), "message_id", None)


def run_service(binding, queue_path, ledger, app_secret):
    """Explicit trusted deployment seam, never called by imports or offline tests.

    A production account lookup and monotonic entitlement adapter are not supplied
    by this module. Deployment must provide the separately reviewed enabled ledger.
    """
    _check_binding_secret(binding, app_secret)
    if getattr(ledger, "enabled", False) is not True:
        raise ValueError("RENEWAL_LEDGER_DISABLED")
    queue = DurableQueue(queue_path)
    service = RenewalBot(binding, queue, ledger, OfficialReplies(binding, app_secret))
    websocket = build_long_connection(binding, service, app_secret)
    worker = threading.Thread(target=service.run, name="feishu-renewal-worker", daemon=True)
    worker.start()  # Also recovers preexisting durable work before any new event.
    try:
        websocket.start()
    finally:
        service.stop()
        worker.join(timeout=25)

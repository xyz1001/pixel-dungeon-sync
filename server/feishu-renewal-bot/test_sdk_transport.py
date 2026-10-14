"""Builder tests use fake SDK classes. Optional smoke requires the real pinned SDK."""
from importlib.util import find_spec
from types import SimpleNamespace as NS
from unittest.mock import Mock, patch
import json
import sqlite3
import unittest

from bot import Binding
from sdk_transport import build_long_connection, OfficialReplies, run_service
from test_bot import binding, event


class Builder:
    def __init__(self):
        self.values = {}
    def __getattr__(self, name):
        def set_value(value):
            self.values[name] = value
            return self
        return set_value
    def build(self):
        return NS(**self.values)


class Dispatcher(Builder):
    @staticmethod
    def builder(encrypt_key, verification_token):
        assert (encrypt_key, verification_token) == ("", "")
        return Dispatcher()


class Request:
    @staticmethod
    def builder():
        return Builder()


class FakeEvent(NS):
    pass


class SDKBuilderTests(unittest.TestCase):
    def sdk(self):
        return NS(EventDispatcherHandler=Dispatcher, ws=NS(Client=Mock()), LogLevel=NS(ERROR="ERROR"))

    def test_registers_official_shape_and_does_not_start_network(self):
        sdk = self.sdk()
        service = NS(binding=binding(), accept_sdk_event=Mock())
        with patch("sdk_transport._load_sdk", return_value=(sdk, FakeEvent, Request, Request)):
            connection = build_long_connection(binding(), service, "test-only-placeholder-secret")
        sdk.ws.Client.assert_called_once()
        connection.start.assert_not_called()
        args, kwargs = sdk.ws.Client.call_args
        self.assertEqual(binding().app_id, args[0])
        self.assertEqual("ERROR", kwargs["log_level"])
        receive = kwargs["event_handler"].register_p2_im_message_receive_v1
        sample = FakeEvent(**vars(event()))
        self.assertIsNone(receive(sample))
        service.accept_sdk_event.assert_called_once_with(sample)
        with self.assertRaises(TypeError):
            receive({"event": "HTTP must never reach the handler"})

    def test_callback_persistence_error_is_not_swallowed_by_sdk_wrapper(self):
        sdk = self.sdk()
        service = NS(binding=binding(), accept_sdk_event=Mock(side_effect=sqlite3.OperationalError("busy")))
        with patch("sdk_transport._load_sdk", return_value=(sdk, FakeEvent, Request, Request)):
            build_long_connection(binding(), service, "test-only-placeholder-secret")
        receive = sdk.ws.Client.call_args.kwargs["event_handler"].register_p2_im_message_receive_v1
        with self.assertRaises(sqlite3.OperationalError):
            receive(FakeEvent(**vars(event())))

    def test_disabled_startup_refuses_before_sdk_import(self):
        with patch("sdk_transport._load_sdk") as loader:
            with self.assertRaises(ValueError):
                build_long_connection(Binding(), NS(), "")
            with self.assertRaises(ValueError):
                run_service(binding(), "/unused", NS(enabled=False), "test-only-placeholder-secret")
            loader.assert_not_called()

    def test_reply_uses_original_message_id_fixed_uuid_and_text_only(self):
        reply = OfficialReplies.__new__(OfficialReplies)
        reply.request_type = reply.body_type = Request
        send = Mock(return_value=NS(success=lambda: True, data=NS(message_id="om_reply")))
        reply.client = NS(im=NS(v1=NS(message=NS(reply=send))))
        self.assertEqual("om_reply", reply.send_reply("om_origin", "未知用户。", "fixed-uuid"))
        request = send.call_args.args[0]
        self.assertEqual("om_origin", request.message_id)
        self.assertEqual("text", request.request_body.msg_type)
        self.assertEqual("fixed-uuid", request.request_body.uuid)
        self.assertEqual({"text": "未知用户。"}, json.loads(request.request_body.content))
        send.return_value = NS(success=lambda: False, msg="sensitive upstream detail")
        with self.assertRaisesRegex(RuntimeError, "^FEISHU_REPLY_NOT_CONFIRMED$"):
            reply.send_reply("om_origin", "未知用户。", "fixed-uuid")


@unittest.skipUnless(find_spec("lark_oapi"), "pinned official SDK unavailable; fake builder tests are not SDK validation")
class InstalledSDKSmokeTests(unittest.TestCase):
    def test_real_sdk_builds_and_deserializes_without_connection(self):
        # Refuse all network, even if a future SDK unexpectedly connects at build time.
        import lark_oapi as lark
        from lark_oapi.api.im.v1 import P2ImMessageReceiveV1
        data = event()
        raw = json.loads(json.dumps(data, default=vars))
        typed = P2ImMessageReceiveV1(raw)
        service = NS(binding=binding(), accept_sdk_event=Mock())
        with patch("socket.socket.connect", side_effect=AssertionError("offline test")), \
                patch.object(lark.ws.Client, "start", side_effect=AssertionError("must not start")):
            connection = build_long_connection(binding(), service, "test-only-placeholder-secret")
            self.assertIsInstance(connection, lark.ws.Client)
            reply = OfficialReplies(binding(), "test-only-placeholder-secret")
            # Build a real SDK reply request, with outbound call replaced.
            with patch.object(reply.client.im.v1.message, "reply", return_value=NS(success=lambda: True, data=NS(message_id="om_reply"))):
                self.assertEqual("om_reply", reply.send_reply("om_one", "未知用户。", "b9f57e6c-b7b1-5a32-bab1-03a12c36a4d2"))
            from bot import parse_sdk_event
            self.assertEqual("alice", parse_sdk_event(typed, binding()).username)


if __name__ == "__main__":
    unittest.main()

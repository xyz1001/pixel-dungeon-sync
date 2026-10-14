"""Read-only setup tests. Fake SDK and lifecycle; no real network or credentials."""
from contextlib import redirect_stdout, redirect_stderr
from dataclasses import replace
from importlib.util import find_spec
import ast
import io
import json
import logging
import multiprocessing
import os
from pathlib import Path
import signal
import tempfile
import time
from types import SimpleNamespace as NS
import unittest
from unittest.mock import Mock, patch

import setup_cli as setup

SECRET = "test-only-external-secret"
CANARY = "NEVER_PRINT_SECRET_OR_MESSAGE"


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


class Request:
    @staticmethod
    def builder():
        return Builder()


class Dispatcher(Request):
    @staticmethod
    def builder(*keys):
        assert keys == ("", "")
        return Builder()


class FakeEvent(NS):
    pass


class NoContent(NS):
    @property
    def content(self):
        raise AssertionError("Discovery must never inspect content")


def options(command="handshake", **kwargs):
    return setup.Options(command, "cli_test", **kwargs)


def event(sender="ou_admin", **kwargs):
    return FakeEvent(schema="2.0", header=NS(app_id="cli_test", tenant_key="tenant_test",
                     event_type="im.message.receive_v1", token=CANARY),
                     event=NS(sender=NS(sender_id=NS(open_id=sender, user_id=CANARY),
                                        tenant_key="tenant_test", sender_type="user"),
                              message=NoContent(chat_id="oc_selected", chat_type="group", **kwargs)))


def sdk():
    client = NS(im=NS(v1=NS(chat=NS(list=Mock()))), request=Mock())
    rest_builder = Builder()
    rest_builder.build = Mock(return_value=client)
    lark = NS(EventDispatcherHandler=Dispatcher, ws=NS(Client=Mock()),
              LogLevel=NS(ERROR="ERROR"), Client=NS(builder=Mock(return_value=rest_builder)),
              AccessTokenType=NS(TENANT="tenant"), HttpMethod=NS(GET="GET"), BaseRequest=Request)
    return (lark, FakeEvent, Request), client, rest_builder


class ArgumentsAndSecretsTests(unittest.TestCase):
    def test_requires_confirmation_and_explicit_selected_chat(self):
        for argv in (["handshake", "--app-id", "cli_test"],
                     ["discover", "--app-id", "cli_test", "--pre-production"],
                     ["handshake", "--app-id", "cli_test", "--pre-production", "--duration", "601"],
                     ["groups", "--app-id", "cli_test", "--pre-production", "--limit", "0"]):
            with self.subTest(argv=argv), self.assertRaises(setup.SetupError):
                setup.parse_args(argv)
        parsed = setup.parse_args(["discover", "--app-id", "cli_test", "--chat-id", "oc_selected",
                                  "--pre-production"])
        self.assertEqual("oc_selected", parsed.chat_id)

    def test_parser_never_echoes_mistaken_argv_secret_or_errors(self):
        cases = [["--app-secret", CANARY], ["--duration", CANARY], ["--app-id", CANARY],
                 ["--secret-env", "MY_SECRET", "--secret-file", CANARY]]
        for extra in cases:
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err), patch.object(setup, "supervise") as run:
                result = setup.main(["handshake", "--app-id", "cli_test", "--pre-production"] + extra)
            self.assertEqual(2, result)
            self.assertNotIn(CANARY, out.getvalue() + err.getvalue())
            self.assertEqual("", err.getvalue())
            run.assert_not_called()

    def test_env_and_file_sources_are_exclusive_and_bounded(self):
        self.assertEqual(SECRET, setup.read_secret(options(), {"FEISHU_APP_SECRET": SECRET}))
        for value in ("", "short", "x" * 4097, SECRET + "\n", "REPLACE_WITH_APP_SECRET", "秘密" * 20):
            with self.subTest(value=value), self.assertRaises(setup.SetupError):
                setup.read_secret(options(), {"FEISHU_APP_SECRET": value})
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "secret"
            path.write_text(SECRET + "\n")
            path.chmod(0o600)
            config = options(secret_env="", secret_file=str(path))
            self.assertEqual(SECRET, setup.read_secret(config, {}))
            self.assertEqual(SECRET + "\n", path.read_text())
            with self.assertRaisesRegex(setup.SetupError, "AMBIGUOUS_SECRET_SOURCE"):
                setup.read_secret(config, {"FEISHU_APP_SECRET": SECRET})
            with self.assertRaisesRegex(setup.SetupError, "AMBIGUOUS_SECRET_SOURCE"):
                setup.read_secret(replace(config, secret_env="OTHER_SECRET"), {})
            path.write_text("x" * 5000)
            with self.assertRaises(setup.SetupError):
                setup.read_secret(config, {})
            if os.name == "posix":
                path.chmod(0o644)
                with self.assertRaisesRegex(setup.SetupError, "SECRET_FILE_PERMISSIONS_REQUIRED"):
                    setup.read_secret(config, {})
        with self.assertRaisesRegex(setup.SetupError, "SECRET_FILE_MUST_BE_OUTSIDE_REPOSITORY"):
            setup.read_secret(options(secret_env="", secret_file=str(setup.REPOSITORY_ROOT / ".secret")), {})

    def test_unknown_setup_error_never_exposes_supplied_text(self):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err), \
                patch.object(setup, "parse_args", side_effect=setup.SetupError(CANARY)):
            self.assertEqual(2, setup.main([]))
        self.assertEqual('{"code":"SETUP_FAILED"}\n', out.getvalue())
        self.assertEqual("", err.getvalue())
        self.assertNotIn(CANARY, str(setup.SetupError(CANARY)))

    def test_sdk_version_is_checked_before_import(self):
        with patch("importlib.metadata.version", return_value="1.0.0"):
            with self.assertRaisesRegex(setup.SetupError, "SDK_VERSION_MISMATCH"):
                setup.load_sdk()

    def test_module_has_no_business_storage_or_ledger_import(self):
        tree = ast.parse(Path(setup.__file__).read_text())
        names = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                names.update(item.name.split(".")[0] for item in node.names)
            elif isinstance(node, ast.ImportFrom):
                names.add((node.module or "").split(".")[0])
        self.assertFalse(names & {"bot", "sdk_transport", "feishu_renewals", "sqlite3"})


class SDKWiringTests(unittest.TestCase):
    def test_handshake_builds_only_official_ws_and_does_not_inspect_events(self):
        fake, _, _ = sdk()
        emit = Mock()
        connection = setup.build_connection(options(), SECRET, emit, fake)
        connection.start.assert_not_called()
        args, kwargs = fake[0].ws.Client.call_args
        self.assertEqual(("cli_test", SECRET), args)
        self.assertFalse(kwargs["auto_reconnect"])
        self.assertEqual("https://open.feishu.cn", kwargs["domain"])
        callback = kwargs["event_handler"].register_p2_im_message_receive_v1
        callback(object())
        emit.assert_not_called()

    def test_discovery_only_selected_group_typed_user_metadata_deduplicated_and_capped(self):
        fake, _, _ = sdk()
        emit = Mock()
        config = options("discover", chat_id="oc_selected", limit=2)
        setup.build_connection(config, SECRET, emit, fake)
        callback = fake[0].ws.Client.call_args.kwargs["event_handler"].register_p2_im_message_receive_v1
        callback(event())
        callback(event())
        callback(event("ou_second"))
        callback(event("ou_third"))
        self.assertEqual(2, emit.call_count)
        metadata = emit.call_args_list[0].args[1]
        self.assertEqual({"app_id": "cli_test", "tenant_key": "tenant_test", "chat_id": "oc_selected",
                          "sender": {"open_id": "ou_admin"}}, metadata)
        self.assertNotIn(CANARY, json.dumps(metadata))

    def test_discovery_ignores_wrong_origin_group_bot_private_missing_and_cross_tenant(self):
        config = options("discover", chat_id="oc_selected")
        bad = []
        for part, key, value in (("header", "app_id", "cli_other"),
                                 ("header", "event_type", "other"),
                                 ("message", "chat_id", "oc_other"),
                                 ("message", "chat_type", "p2p"),
                                 ("sender", "sender_type", "app"),
                                 ("sender", "tenant_key", "external_tenant")):
            sample = event()
            target = sample.header if part == "header" else getattr(sample.event, part)
            setattr(target, key, value)
            bad.append(sample)
        bad += [{"header": CANARY}, NS(), event(sender="bad\nID")]
        for sample in bad:
            self.assertIsNone(setup.discovery_metadata(sample, FakeEvent, config))

    def test_groups_is_bounded_tenant_get_and_does_not_expose_other_fields(self):
        fake, client, builder = sdk()
        client.im.v1.chat.list.return_value = NS(success=lambda: True, data=NS(
            items=[NS(chat_id="oc_one", name="Selected\nName", owner_id=CANARY, description=CANARY),
                   NS(chat_id="oc_two", name="Second")], has_more=True, page_token=CANARY))
        result = setup.list_groups(options("groups", limit=1), client, Request, fake[0])
        self.assertEqual({"app_id": "cli_test", "groups": [{"chat_id": "oc_one", "name": "Selected\nName"}],
                          "has_more": True}, result)
        request = client.im.v1.chat.list.call_args.args[0]
        self.assertEqual(1, request.page_size)
        self.assertEqual("ByCreateTimeAsc", request.sort_type)
        self.assertEqual({"tenant"}, request.token_types)
        self.assertNotIn(CANARY, json.dumps(result))
        client.im.v1.chat.list.return_value = NS(success=lambda: False, msg=CANARY)
        with self.assertRaisesRegex(setup.SetupError, "GROUPS_READ_FAILED_CHECK_PERMISSION"):
            setup.list_groups(options("groups"), client, Request, fake[0])

    def test_bot_info_generic_get_exact_root_schema_and_allowlist(self):
        fake, client, _ = sdk()
        raw = {"code": 0, "msg": CANARY, "bot": {"open_id": "ou_bot", "app_name": CANARY,
                                                           "ip_white_list": [CANARY]}}
        client.request.return_value = NS(success=lambda: True, raw=NS(content=json.dumps(raw).encode()))
        self.assertEqual({"app_id": "cli_test", "bot_open_id": "ou_bot"},
                         setup.bot_info(options("bot-info"), client, fake[0]))
        request = client.request.call_args.args[0]
        self.assertEqual("GET", request.http_method)
        self.assertEqual("/open-apis/bot/v3/info", request.uri)
        self.assertEqual({"tenant"}, request.token_types)
        client.request.return_value.raw.content = json.dumps({"code": 0, "data": {"bot": raw["bot"]}})
        with self.assertRaisesRegex(setup.SetupError, "BOT_INFO_RESPONSE_INVALID"):
            setup.bot_info(options("bot-info"), client, fake[0])

    def test_run_command_separates_ws_from_rest_without_binding(self):
        fake, client, builder = sdk()
        client.request.return_value = NS(success=lambda: True, raw=NS(
            content=b'{"code":0,"bot":{"open_id":"ou_bot"}}'))
        emit = Mock()
        setup.run_command(options("bot-info"), SECRET, emit, fake)
        fake[0].ws.Client.assert_not_called()
        self.assertEqual(10, builder.values["timeout"])
        self.assertEqual("https://open.feishu.cn", builder.values["domain"])
        self.assertEqual(("done", {}), emit.call_args.args)
        fake[0].ws.Client.return_value.start.side_effect = RuntimeError(CANARY)
        emit.reset_mock()
        with self.assertRaises(RuntimeError):
            setup.run_command(options(), SECRET, emit, fake)
        self.assertEqual(("status", {"code": "STARTING_CHECK_CONSOLE"}), emit.call_args.args)

    def test_worker_suppresses_sdk_logs_prints_traceback_and_exception_details(self):
        pipe = Mock()
        out, err = io.StringIO(), io.StringIO()
        def failing(*args):
            logging.critical(CANARY)
            print(CANARY)
            print(CANARY, file=__import__("sys").stderr)
            raise RuntimeError(CANARY)
        with patch.object(setup.signal, "signal"), patch.object(setup, "read_secret", return_value=SECRET), \
                patch.object(setup, "run_command", side_effect=failing), redirect_stdout(out), redirect_stderr(err):
            setup.worker_main(options(), pipe)
        self.assertEqual("", out.getvalue() + err.getvalue())
        pipe.send.assert_called_once_with(("error", {"code": "SETUP_FAILED"}))
        pipe.close.assert_called_once()
        pipe.reset_mock()
        with patch.object(setup.signal, "signal"), patch.object(setup, "read_secret", return_value=SECRET), \
                patch.object(setup, "run_command", side_effect=setup.SetupError(CANARY)):
            setup.worker_main(options(), pipe)
        pipe.send.assert_called_once_with(("error", {"code": "SETUP_FAILED"}))


class LifecycleTests(unittest.TestCase):
    def fixture(self, messages=(), poll=True, clock=None):
        reader, writer, process, context = Mock(), Mock(), Mock(), Mock()
        reader.poll.return_value = poll
        reader.recv.side_effect = list(messages)
        context.Pipe.return_value = reader, writer
        context.Process.return_value = process
        process.is_alive.side_effect = [True, False, False]
        return reader, writer, process, context

    def test_success_joins_worker(self):
        reader, writer, process, context = self.fixture([("result", {"bot_open_id": "ou_bot"}), ("done", {})])
        emit = Mock()
        self.assertEqual(0, setup.supervise(options("bot-info"), emit, context, clock=lambda: 0))
        process.start.assert_called_once()
        process.terminate.assert_called_once()
        process.join.assert_called_once_with(timeout=3)
        process.close.assert_called_once()
        reader.close.assert_called_once()
        self.assertEqual(1, emit.call_count)

    def test_timeout_and_interrupt_stop_worker(self):
        for interrupt in (False, True):
            reader, writer, process, context = self.fixture()
            if interrupt:
                reader.poll.side_effect = KeyboardInterrupt
                clock = lambda: 0
            else:
                clock = Mock(side_effect=[0, 999])
            emit = Mock()
            result = setup.supervise(options("discover", chat_id="oc_selected"), emit, context, clock)
            self.assertEqual(130 if interrupt else 3, result)
            process.terminate.assert_called_once()
            process.join.assert_called_once()
            process.close.assert_called_once()
            self.assertEqual({"code": "STOPPED" if interrupt else "NO_MATCHING_EVENT"}, emit.call_args.args[0])

    def test_limited_discovery_terminates_after_required_observations(self):
        reader, writer, process, context = self.fixture([("metadata", {"sender": {"open_id": "ou_one"}})])
        emit = Mock()
        result = setup.supervise(options("discover", chat_id="oc_selected", limit=1), emit, context, clock=lambda: 0)
        self.assertEqual(0, result)
        process.terminate.assert_called_once()
        self.assertEqual({"code": "DISCOVERY_LIMIT_REACHED"}, emit.call_args.args[0])

    def test_stuck_worker_escalates_to_kill_and_join(self):
        process = Mock()
        process.is_alive.side_effect = [True, True, False]
        setup.stop_worker(process)
        process.terminate.assert_called_once()
        process.kill.assert_called_once()
        self.assertEqual(2, process.join.call_count)
        process.close.assert_called_once()

    def test_signal_handlers_interrupt_cleanup_and_are_restored(self):
        original_supervise = setup.supervise
        for name in ("SIGTERM", "SIGHUP"):
            signum = getattr(signal, name, None)
            if signum is None:
                continue
            with self.subTest(signal=name):
                previous = {getattr(signal, key): signal.getsignal(getattr(signal, key))
                            for key in ("SIGTERM", "SIGHUP") if hasattr(signal, key)}
                reader, writer, process, context = self.fixture()

                def interrupt(*args):
                    signal.getsignal(signum)(signum, None)

                def isolated(config, emit):
                    return original_supervise(config, emit, context, clock=lambda: 0)

                reader.poll.side_effect = interrupt
                out = io.StringIO()
                with patch.object(setup, "supervise", side_effect=isolated), redirect_stdout(out):
                    result = setup.main(["handshake", "--app-id", "cli_test", "--pre-production"])
                self.assertEqual(130, result)
                self.assertEqual('{"code":"STOPPED"}\n', out.getvalue())
                process.terminate.assert_called_once()
                process.join.assert_called_once()
                process.close.assert_called_once()
                for saved_signal, handler in previous.items():
                    self.assertIs(handler, signal.getsignal(saved_signal))

    def test_real_spawn_missing_secret_exits_and_leaves_no_child(self):
        # A real process, but no SDK load, network, or credentials: validation fails first.
        before = {p.pid for p in multiprocessing.active_children()}
        emit = Mock()
        with patch.dict(os.environ, {}, clear=True):
            result = setup.supervise(options(duration=10), emit)
        self.assertEqual(1, result)
        self.assertEqual(before, {p.pid for p in multiprocessing.active_children()})
        emit.assert_called_once_with({"code": "SECRET_MISSING_OR_INVALID"})


@unittest.skipUnless(find_spec("lark_oapi"), "pinned official SDK unavailable; fake tests do not validate installation")
class InstalledSDKSmokeTests(unittest.TestCase):
    def test_real_sdk_builders_without_network(self):
        with patch("socket.socket.connect", side_effect=AssertionError("offline only")):
            real = setup.load_sdk()
            lark, event_type, request_type = real
            with patch.object(lark.ws.Client, "start", side_effect=AssertionError("must not start")):
                connection = setup.build_connection(options(), SECRET, Mock(), real)
                self.assertIsInstance(connection, lark.ws.Client)
                client = setup.build_rest_client(options(), SECRET, lark)
                response = NS(success=lambda: True, data=NS(items=[], has_more=False))
                with patch.object(client.im.v1.chat, "list", return_value=response) as send:
                    setup.list_groups(options("groups"), client, request_type, lark)
                request = send.call_args.args[0]
                self.assertEqual(lark.HttpMethod.GET, request.http_method)
                self.assertEqual("/open-apis/im/v1/chats", request.uri)
                self.assertEqual({lark.AccessTokenType.TENANT}, request.token_types)
                response = NS(success=lambda: True, raw=NS(content=b'{"code":0,"bot":{"open_id":"ou_bot"}}'))
                with patch.object(client, "request", return_value=response) as send:
                    setup.bot_info(options("bot-info"), client, lark)
                self.assertEqual(lark.HttpMethod.GET, send.call_args.args[0].http_method)


if __name__ == "__main__":
    unittest.main()

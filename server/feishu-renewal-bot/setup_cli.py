"""Pre-production, read-only Feishu setup. No bot, queue, or ledger imports.

Only the explicit CLI opens connections. No binding or event payload is written.
The isolated SDK process is stopped at the observation deadline or on Ctrl-C.
"""
import argparse
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import dataclass
import importlib.metadata
import json
import logging
import multiprocessing
import os
from pathlib import Path
import re
import signal
import stat
import sys
import time

SDK_VERSION = "1.7.4"
FEISHU_DOMAIN = "https://open.feishu.cn"
MAX_SECRET_BYTES = 4096
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
ID_PATTERN = re.compile(r"[A-Za-z0-9_-]{1,200}", re.ASCII)


SAFE_ERROR_CODES = frozenset({
    "AMBIGUOUS_SECRET_SOURCE", "BOT_INFO_READ_FAILED", "BOT_INFO_RESPONSE_INVALID",
    "CHAT_ID_ONLY_FOR_DISCOVER", "EXPLICIT_CHAT_ID_REQUIRED",
    "GROUPS_READ_FAILED_CHECK_PERMISSION", "GROUPS_RESPONSE_INVALID", "INVALID_APP_ID",
    "INVALID_ARGUMENTS_USE_HELP", "INVALID_BOUNDS", "INVALID_COMMAND",
    "INVALID_SECRET_ENV_NAME", "SDK_CONNECTION_RETURNED", "SDK_NOT_INSTALLED",
    "SDK_VERSION_MISMATCH", "SECRET_FILE_MUST_BE_OUTSIDE_REPOSITORY",
    "SECRET_FILE_MUST_BE_REGULAR", "SECRET_FILE_PERMISSIONS_REQUIRED",
    "SECRET_FILE_UNREADABLE", "SECRET_MISSING_OR_INVALID", "WORKER_STOP_FAILED",
    "SETUP_FAILED",
})

class SetupError(Exception):
    """Only fixed, non-sensitive error codes may be supplied here."""

    def __init__(self, code):
        self.code = code if type(code) is str and code in SAFE_ERROR_CODES else "SETUP_FAILED"
        super().__init__(self.code)


@dataclass(frozen=True)
class Options:
    command: str
    app_id: str
    secret_env: str = "FEISHU_APP_SECRET"
    secret_file: str = ""
    chat_id: str = ""
    duration: int = 180
    limit: int = 20


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse normally echoes invalid input, which could include a secret.
        raise SetupError("INVALID_ARGUMENTS_USE_HELP")


def identifier(value, prefix=""):
    return (type(value) is str and ID_PATTERN.fullmatch(value) is not None
            and value.startswith(prefix) and len(value) > len(prefix)
            and "REPLACE" not in value.upper())


def parse_args(argv=None):
    parser = SafeParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("command", choices=("handshake", "groups", "bot-info", "discover"))
    parser.add_argument("--app-id", required=True, help="Application ID; never App Secret")
    secrets = parser.add_mutually_exclusive_group()
    secrets.add_argument("--secret-env", help="Existing secret environment variable (default FEISHU_APP_SECRET)")
    secrets.add_argument("--secret-file", help="Existing protected secret file OUTSIDE the repository")
    parser.add_argument("--chat-id", help="Explicit selected group ID; required for discover")
    parser.add_argument("--duration", type=int, default=180, help="Hard observation deadline, 1..600 seconds")
    parser.add_argument("--limit", type=int, default=20, help="Maximum groups or distinct senders, 1..100")
    parser.add_argument("--pre-production", action="store_true", required=True,
                        help="Confirm no production or other listener is using this app")
    args = parser.parse_args(argv)
    if not identifier(args.app_id, "cli_"):
        raise SetupError("INVALID_APP_ID")
    if not 1 <= args.duration <= 600 or not 1 <= args.limit <= 100:
        raise SetupError("INVALID_BOUNDS")
    if args.command == "discover":
        if not identifier(args.chat_id, "oc_"):
            raise SetupError("EXPLICIT_CHAT_ID_REQUIRED")
    elif args.chat_id is not None:
        raise SetupError("CHAT_ID_ONLY_FOR_DISCOVER")
    env_name = args.secret_env or "FEISHU_APP_SECRET"
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,127}", env_name, re.ASCII):
        raise SetupError("INVALID_SECRET_ENV_NAME")
    return Options(args.command, args.app_id, "" if args.secret_file else env_name,
                   args.secret_file or "", args.chat_id or "", args.duration, args.limit)


def read_secret(options, environ=None):
    """Read an existing external secret only. Never create, write, or print it."""
    environ = os.environ if environ is None else environ
    if options.secret_file:
        if options.secret_env or "FEISHU_APP_SECRET" in environ:
            raise SetupError("AMBIGUOUS_SECRET_SOURCE")
        path = Path(options.secret_file).resolve()
        if path == REPOSITORY_ROOT or REPOSITORY_ROOT in path.parents:
            raise SetupError("SECRET_FILE_MUST_BE_OUTSIDE_REPOSITORY")
        try:
            with path.open("rb") as handle:
                info = os.fstat(handle.fileno())
                if not stat.S_ISREG(info.st_mode):
                    raise SetupError("SECRET_FILE_MUST_BE_REGULAR")
                if os.name == "posix" and stat.S_IMODE(info.st_mode) & 0o077:
                    raise SetupError("SECRET_FILE_PERMISSIONS_REQUIRED")
                raw = handle.read(MAX_SECRET_BYTES + 3)
            # A single terminal newline is allowed for ordinary secret mounts.
            raw = raw.removesuffix(b"\n").removesuffix(b"\r")
            secret = raw.decode("ascii")
        except (OSError, UnicodeError):
            raise SetupError("SECRET_FILE_UNREADABLE") from None
    else:
        secret = environ.get(options.secret_env, "")
    if (type(secret) is not str or not 16 <= len(secret) <= MAX_SECRET_BYTES
            or any(ord(c) < 33 or ord(c) > 126 for c in secret)
            or "REPLACE" in secret.upper()):
        raise SetupError("SECRET_MISSING_OR_INVALID")
    return secret


def load_sdk():
    try:
        if importlib.metadata.version("lark-oapi") != SDK_VERSION:
            raise SetupError("SDK_VERSION_MISMATCH")
        import lark_oapi as lark
        from lark_oapi.api.im.v1 import P2ImMessageReceiveV1, ListChatRequest
    except (ImportError, importlib.metadata.PackageNotFoundError):
        raise SetupError("SDK_NOT_INSTALLED") from None
    return lark, P2ImMessageReceiveV1, ListChatRequest


def discovery_metadata(event, event_type, options):
    """No content access. This is observation, never an admin authorization."""
    if not isinstance(event, event_type):
        return None
    try:
        header, sender, message = event.header, event.event.sender, event.event.message
        open_id = sender.sender_id.open_id
        if (event.schema != "2.0" or header.event_type != "im.message.receive_v1"
                or header.app_id != options.app_id
                or not identifier(header.tenant_key)
                or sender.tenant_key != header.tenant_key
                or sender.sender_type != "user" or not identifier(open_id, "ou_")
                or message.chat_type != "group" or message.chat_id != options.chat_id):
            return None
        return {"app_id": header.app_id, "tenant_key": header.tenant_key,
                "chat_id": message.chat_id, "sender": {"open_id": open_id}}
    except (AttributeError, TypeError):
        return None


def build_connection(options, secret, emit, sdk=None):
    lark, event_type, _ = load_sdk() if sdk is None else sdk
    seen = set()

    def receive(event):
        if options.command != "discover" or len(seen) >= options.limit:
            return  # Handshake ACKs and discards events without inspecting payloads.
        data = discovery_metadata(event, event_type, options)
        if data is None:
            return
        key = (data["tenant_key"], data["sender"]["open_id"])
        if key not in seen:
            seen.add(key)
            emit("metadata", data)

    handler = (lark.EventDispatcherHandler.builder("", "")
               .register_p2_im_message_receive_v1(receive).build())
    # No retries hidden behind setup. Parent hard deadline also bounds the SDK's
    # initial endpoint request, which has no public timeout in this source pin.
    return lark.ws.Client(options.app_id, secret, event_handler=handler,
                          domain=FEISHU_DOMAIN, auto_reconnect=False,
                          log_level=lark.LogLevel.ERROR)


def build_rest_client(options, secret, lark):
    return (lark.Client.builder().app_id(options.app_id).app_secret(secret)
            .domain(FEISHU_DOMAIN).timeout(10).log_level(lark.LogLevel.ERROR).build())


def list_groups(options, client, request_type, lark):
    request = (request_type.builder().page_size(options.limit)
               .sort_type("ByCreateTimeAsc").build())
    # Never allow a user token or directory API fallback.
    request.token_types = {lark.AccessTokenType.TENANT}
    response = client.im.v1.chat.list(request)
    if not response.success():
        raise SetupError("GROUPS_READ_FAILED_CHECK_PERMISSION")
    data = response.data
    if type(data.items) is not list or type(data.has_more) is not bool:
        raise SetupError("GROUPS_RESPONSE_INVALID")
    items = []
    for item in data.items[:options.limit]:
        if not identifier(item.chat_id, "oc_") or type(item.name) is not str:
            raise SetupError("GROUPS_RESPONSE_INVALID")
        # Do not publish owner IDs, avatars, descriptions, or pagination tokens.
        items.append({"chat_id": item.chat_id, "name": item.name[:200]})
    return {"app_id": options.app_id, "groups": items,
            "has_more": data.has_more or len(data.items) > options.limit}


def bot_info(options, client, lark):
    request = (lark.BaseRequest.builder().http_method(lark.HttpMethod.GET)
               .uri("/open-apis/bot/v3/info")
               .token_types({lark.AccessTokenType.TENANT}).build())
    response = client.request(request)
    if not response.success():
        raise SetupError("BOT_INFO_READ_FAILED")
    raw = response.raw.content
    if type(raw) not in (bytes, str) or len(raw) > 65536:
        raise SetupError("BOT_INFO_RESPONSE_INVALID")
    try:
        payload = json.loads(raw)
        bot = payload["bot"]  # Official v3 response is root-level bot, NOT data.bot.
        if type(payload["code"]) is not int or payload["code"] != 0:
            raise ValueError
        open_id = bot["open_id"]
        if not identifier(open_id, "ou_"):
            raise ValueError
    except (ValueError, KeyError, TypeError, RecursionError):
        raise SetupError("BOT_INFO_RESPONSE_INVALID") from None
    return {"app_id": options.app_id, "bot_open_id": open_id}


def run_command(options, secret, emit, sdk=None):
    sdk = load_sdk() if sdk is None else sdk
    lark, _, request_type = sdk
    if options.command in ("handshake", "discover"):
        connection = build_connection(options, secret, emit, sdk)
        emit("status", {"code": "STARTING_CHECK_CONSOLE"})
        # This does not imply connected. Confirm the live connection in Feishu's
        # console; SDK construction and an idle process are not connection proof.
        connection.start()
        raise SetupError("SDK_CONNECTION_RETURNED")
    client = build_rest_client(options, secret, lark)
    if options.command == "groups":
        emit("result", list_groups(options, client, request_type, lark))
    elif options.command == "bot-info":
        emit("result", bot_info(options, client, lark))
    else:
        raise SetupError("INVALID_COMMAND")
    emit("done", {})


def worker_main(options, pipe):
    # The CLI owns this process. SDK/asyncio tracebacks and error logs can contain
    # tokens, full WebSocket URLs or payloads, so only our pipe allowlist is shown.
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    previous_disable = logging.root.manager.disable
    logging.disable(logging.CRITICAL)
    with open(os.devnull, "w") as sink, redirect_stdout(sink), redirect_stderr(sink):
        try:
            run_command(options, read_secret(options), lambda kind, data: pipe.send((kind, data)))
        except SetupError as error:
            pipe.send(("error", {"code": error.code}))
        except BaseException:
            pipe.send(("error", {"code": "SETUP_FAILED"}))
        finally:
            pipe.close()
            logging.disable(previous_disable)


def stop_worker(process):
    if process.is_alive():
        process.terminate()
    process.join(timeout=3)
    if process.is_alive():
        process.kill()
        process.join(timeout=3)
    if process.is_alive():
        raise SetupError("WORKER_STOP_FAILED")
    process.close()


def supervise(options, emit, context=None, clock=time.monotonic):
    context = multiprocessing.get_context("spawn") if context is None else context
    reader, writer = context.Pipe(duplex=False)
    process = context.Process(target=worker_main, args=(options, writer), daemon=True)
    started, observed = False, 0
    try:
        process.start()
        started = True
        writer.close()
        deadline = clock() + options.duration
        while clock() < deadline:
            if reader.poll(min(0.2, max(0, deadline - clock()))):
                try:
                    kind, data = reader.recv()
                except EOFError:
                    emit({"code": "SETUP_WORKER_EXITED"})
                    return 1
                if kind == "done":
                    return 0
                emit(data)
                if kind == "error":
                    return 1
                if kind == "metadata":
                    observed += 1
                    if observed >= options.limit:
                        emit({"code": "DISCOVERY_LIMIT_REACHED"})
                        return 0
            elif not process.is_alive():
                emit({"code": "SETUP_WORKER_EXITED"})
                return 1
        if options.command == "discover" and not observed:
            emit({"code": "NO_MATCHING_EVENT"})
            return 3
        emit({"code": "OBSERVATION_WINDOW_ENDED"})
        return 0 if options.command in ("handshake", "discover") else 3
    except KeyboardInterrupt:
        emit({"code": "STOPPED"})
        return 130
    finally:
        reader.close()
        writer.close()
        if started:
            stop_worker(process)


def main(argv=None):
    def emit(data):
        # JSON escapes terminal controls in group names; no raw platform JSON.
        print(json.dumps(data, ensure_ascii=True, separators=(",", ":")), flush=True)
    previous = {}

    def interrupted(signum, frame):
        raise KeyboardInterrupt

    try:
        options = parse_args(argv)
        for name in ("SIGTERM", "SIGHUP"):
            signum = getattr(signal, name, None)
            if signum is not None:
                previous[signum] = signal.signal(signum, interrupted)
        return supervise(options, emit)
    except SetupError as error:
        emit({"code": error.code})
        return 2
    except Exception:
        emit({"code": "SETUP_FAILED"})
        return 1
    finally:
        for signum, handler in previous.items():
            signal.signal(signum, handler)


if __name__ == "__main__":
    raise SystemExit(main())

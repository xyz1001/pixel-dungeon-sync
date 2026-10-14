#!/usr/bin/env bash
# Scripted host-only tests. No socket, live service, registration, or Android device is used.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
SRC="$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android"
JAVA=${JAVA_BIN:-java}
RUNTIME=${PDSYNC_RUNTIME_LIB:?Set PDSYNC_RUNTIME_LIB to the existing pinned runtime jars}
CORE=${PDSYNC_CORE_JAR:-$REPO/core/build/libs/core.jar}
if [[ ! -f "$CORE" ]]; then
  echo "Build the current core first: ./gradlew :core:jar" >&2
  exit 1
fi
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-official-service.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
# Exercise the checked-in Python response producer rather than duplicating its number types.
# Only this public module is imported; all upstream access is mocked and socket creation denied.
python3 - "$REPO/server/sftpgo-auth/auth_v2.py" "$OUT/server-account-info.jsonl" <<'PY'
import importlib.util
import json
import pathlib
import sys
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("pdsync_auth_v2_host_fixture", sys.argv[1])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
username = "syntheticuserneverregister"
env = {"SFTPGO_API_URL": "https://upstream.invalid", "get_admin_token": lambda: "",
       "PUBLIC_WEBDAV_HOST": "https://webdav.xyz1001.top:8443/dav/"}
fixtures = []
with patch("socket.socket", side_effect=AssertionError("Host fixture must not open sockets")):
    for quota, upload, download in [(5242880, 100, 100), (0, 0, 0), (1, 7, 13), (5242881, 100, 50)]:
        upstream = {"username": username, "status": 1, "quota_size": quota,
                    "upload_bandwidth": upload, "download_bandwidth": download}
        with patch.object(module, "fetch_json", return_value=upstream) as fetch:
            data = module.account_info(env, username)
            assert fetch.call_count == 1
        assert "password" not in data and "token" not in data
        fixtures.append(json.dumps({"code": 200, "message": "success", "data": data}, ensure_ascii=False))
assert '"quota_mb": 5.0' in fixtures[0]
pathlib.Path(sys.argv[2]).write_text("\n".join(fixtures) + "\n", encoding="utf-8")
PY
CP="$CORE:$(find -L "$RUNTIME" -maxdepth 1 -name '*.jar' | sort | paste -sd:)"
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -encoding UTF-8 -cp "$CP" -d "$OUT" \
  "$SRC/OfficialServiceRegistrationClient.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/OfficialServiceRegistrationChecks.java"
"$JAVA" -cp "$OUT:$CP" io.github.xyz1001.pdsync.android.OfficialServiceRegistrationChecks "$OUT/server-account-info.jsonl"

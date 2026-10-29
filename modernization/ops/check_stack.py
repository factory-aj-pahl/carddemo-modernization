"""Checks a running stack (ops/up.sh) and prints one line per check; exit 1 when any fails.

API health UP, web app served with the API behind its proxy, Airflow healthy with the owed DAGs listed
and no import errors, and the RabbitMQ queues of the API declared in the carddemo vhost. Ports and
credentials come from the environment set by stack-env.sh; no secret is printed.
"""

from __future__ import annotations

import json
import os
import socket
import struct
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

OWED_DAGS = ("carddemo_daily_cycle", "carddemo_transaction_report", "carddemo_auth_purge",
             "carddemo_trantype_maintenance")
# AuthQueues and InquiryQueues: declared by the API when its broker connection opens.
QUEUES = ("AWS.M2.CARDDEMO.PAUTH.REQUEST", "AWS.M2.CARDDEMO.PAUTH.REPLY", "CARD.DEMO.REQUEST.DATE",
          "CARD.DEMO.REPLY.DATE", "CARD.DEMO.REQUEST.ACCT", "CARD.DEMO.REPLY.ACCT", "CARD.DEMO.ERROR",
          "CARD.DEMO.DLQ")


def http(url: str, data: dict | None = None, headers: dict[str, str] | None = None) -> tuple[int, str]:
    req = urllib.request.Request(url, data=None if data is None else json.dumps(data).encode(),
                                 headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except OSError as e:
        return 0, str(e)


def airflow_token(base: str) -> str:
    user = os.environ.get("AIRFLOW_USERNAME", "carddemo")
    pwfile = Path(os.environ.get("AIRFLOW_PASSWORD_FILE", Path.home() / ".carddemo/airflow/simple_auth_manager_passwords.json.generated"))
    password = json.loads(pwfile.read_text())[user]
    status, body = http(f"{base}/auth/token", {"username": user, "password": password})
    if status // 100 != 2:
        raise RuntimeError(f"POST /auth/token answered {status}")
    return json.loads(body)["access_token"]


def amqp_queues(names: tuple[str, ...], vhost: str = "carddemo", user: str = "carddemo") -> dict[str, int | None]:
    """Passive Queue.Declare of each name over AMQP 0-9-1 (stdlib only; the management API needs a tag the
    application user does not have). Returns name -> message count, or None when the queue does not exist."""
    password = os.environ.get("RABBITMQ_PASSWORD", "")

    def shortstr(s: str) -> bytes:
        b = s.encode()
        return struct.pack("B", len(b)) + b

    def frame(channel: int, class_id: int, method_id: int, args: bytes) -> bytes:
        payload = struct.pack(">HH", class_id, method_id) + args
        return struct.pack(">BHI", 1, channel, len(payload)) + payload + b"\xce"

    with socket.create_connection(("127.0.0.1", int(os.environ.get("CARDDEMO_AMQP_PORT", "5672"))), timeout=10) as s:
        f = s.makefile("rb")

        def read() -> tuple[int, int, int, bytes]:
            while True:
                head = f.read(7)
                if len(head) < 7:
                    raise RuntimeError("RabbitMQ closed the connection (check user, password, and vhost)")
                kind, channel, size = struct.unpack(">BHI", head)
                body = f.read(size + 1)[:-1]
                if kind == 1:
                    class_id, method_id = struct.unpack(">HH", body[:4])
                    return channel, class_id, method_id, body[4:]

        s.sendall(b"AMQP\x00\x00\x09\x01")
        read()  # Connection.Start
        response = b"\x00" + user.encode() + b"\x00" + password.encode()
        s.sendall(frame(0, 10, 11, struct.pack(">I", 0) + shortstr("PLAIN") + struct.pack(">I", len(response))
                        + response + shortstr("en_US")))
        _, c, m, args = read()
        if (c, m) != (10, 30):
            raise RuntimeError("RabbitMQ refused the sign-on")
        channel_max, frame_max, _ = struct.unpack(">HIH", args[:8])
        s.sendall(frame(0, 10, 31, struct.pack(">HIH", channel_max, frame_max, 0)))
        s.sendall(frame(0, 10, 40, shortstr(vhost) + shortstr("") + b"\x00"))
        if read()[1:3] != (10, 41):
            raise RuntimeError(f"RabbitMQ refused vhost {vhost}")
        found: dict[str, int | None] = {}
        for i, name in enumerate(names, start=1):
            s.sendall(frame(i, 20, 10, shortstr("")))
            read()  # Channel.Open-Ok
            s.sendall(frame(i, 50, 10, struct.pack(">H", 0) + shortstr(name) + b"\x01" + struct.pack(">I", 0)))
            _, c, m, args = read()
            if (c, m) == (50, 11):
                n = args[0]
                found[name] = struct.unpack(">I", args[1 + n:5 + n])[0]
                s.sendall(frame(i, 20, 40, struct.pack(">H", 200) + shortstr("") + struct.pack(">HH", 0, 0)))
                read()  # Channel.Close-Ok
            else:  # the broker closed the channel: 404 NOT_FOUND
                found[name] = None
                s.sendall(frame(i, 20, 41, b""))
        s.sendall(frame(0, 10, 50, struct.pack(">H", 200) + shortstr("") + struct.pack(">HH", 0, 0)))
        return found

def checks() -> list[tuple[str, bool, str]]:
    env = os.environ
    api = f"http://127.0.0.1:{env.get('CARDDEMO_API_PORT', '8081')}"
    web = f"http://127.0.0.1:{env.get('CARDDEMO_WEB_PORT', '5173')}"
    af = f"http://127.0.0.1:{env.get('CARDDEMO_AIRFLOW_PORT', '8080')}"
    out = []

    s, b = http(f"{api}/actuator/health")
    out.append(("api health", s == 200 and '"UP"' in b, f"{api}/actuator/health -> {s} {b[:60]}"))
    s, b = http(f"{web}/")
    out.append(("web app", s == 200 and "<div id=\"root\"" in b, f"{web}/ -> {s}"))
    s, b = http(f"{web}/actuator/health")
    out.append(("web proxy to api", s == 200 and '"UP"' in b, f"{web}/actuator/health -> {s}"))

    s, b = http(f"{af}/api/v2/monitor/health")
    ok = s == 200 and all(json.loads(b).get(k, {}).get("status") == "healthy" for k in ("metadatabase", "scheduler"))
    out.append(("airflow health", ok, f"metadatabase and scheduler healthy -> {s}"))
    try:
        auth = {"Authorization": f"Bearer {airflow_token(af)}"}
        s, b = http(f"{af}/api/v2/dags?limit=100", headers=auth)
        dags = {d["dag_id"]: d for d in json.loads(b).get("dags", [])} if s == 200 else {}
        missing = [d for d in OWED_DAGS if d not in dags]
        out.append(("airflow owed DAGs", not missing,
                    f"{len(OWED_DAGS) - len(missing)}/{len(OWED_DAGS)} listed" + (f", missing {missing}" if missing else "")))
        paused = dags.get("carddemo_transaction_report", {}).get("is_paused")
        out.append(("report DAG unpaused", paused is False, f"carddemo_transaction_report is_paused={paused}"))
        s, b = http(f"{af}/api/v2/importErrors", headers=auth)
        n = json.loads(b).get("total_entries") if s == 200 else None
        out.append(("airflow import errors", n == 0, f"{n} import errors"))
    except (OSError, KeyError, ValueError, RuntimeError) as e:
        out.append(("airflow REST API", False, str(e)))

    try:
        queues = amqp_queues(QUEUES)
        missing = [q for q, n in queues.items() if n is None]
        out.append(("rabbitmq queues", not missing, f"{len(QUEUES) - len(missing)}/{len(QUEUES)} declared in vhost carddemo"
                    + (f", missing {missing}" if missing else "")))
    except (OSError, RuntimeError, struct.error) as e:
        out.append(("rabbitmq queues", False, str(e)))
    return out


def main() -> int:
    # --wait N retries for up to N seconds: the scheduler heartbeat and the first DAG parse lag the start.
    wait = int(sys.argv[sys.argv.index("--wait") + 1]) if "--wait" in sys.argv else 0
    deadline = time.monotonic() + wait
    results = checks()
    while any(not ok for _, ok, _ in results) and time.monotonic() < deadline:
        time.sleep(5)
        results = checks()
    for name, ok, detail in results:
        print(f"{'OK  ' if ok else 'FAIL'} {name:24} {detail}")
    failed = sum(1 for _, ok, _ in results if not ok)
    print(f"stack check: {len(results) - failed}/{len(results)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

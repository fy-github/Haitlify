#!/usr/bin/env python3
"""TCP ↔ stdio 桥：把一个 Hermes TUI Gateway 进程暴露成 TCP 上的换行分隔 JSON-RPC。

为什么需要它：平台在容器里、Hermes 在另一个容器里，两者都不能拿 docker socket。
桥只做字节转发（stdio 透明隧道），不解析、不改写、不缓存任何消息。
"""

from __future__ import annotations

import os
import shlex
import signal
import socket
import subprocess
import threading

LISTEN_HOST = os.environ.get("HERMES_BRIDGE_HOST", "0.0.0.0")
LISTEN_PORT = int(os.environ.get("HERMES_BRIDGE_PORT", "9420"))
GATEWAY_CMD = shlex.split(os.environ.get("HERMES_GATEWAY_CMD", "python3 -m tui_gateway.entry"))


def pump(src, dst, on_done=None):
    """逐行转发：JSON-RPC 是换行分隔的，不能用 read(65536)（会等满缓冲才返回）。"""
    try:
        for line in src:
            dst.write(line)
            dst.flush()
    except Exception:
        pass
    finally:
        try:
            dst.close()
        except Exception:
            pass
        if on_done:
            on_done()


def handle(conn: socket.socket) -> None:
    try:
        child = subprocess.Popen(
            GATEWAY_CMD,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=None,
            cwd=os.environ.get("HERMES_CWD") or None,
            start_new_session=True,
        )
    except Exception as exc:  # 启动失败直接把错误写回调用方，不静默挂住
        try:
            conn.sendall(("{\"error\":\"gateway spawn failed: %s\"}\n" % exc).encode())
        finally:
            conn.close()
        return

    def close_child():
        if child.poll() is None:
            try:
                os.killpg(child.pid, signal.SIGTERM)
            except Exception:
                try:
                    child.terminate()
                except Exception:
                    pass

    t1 = threading.Thread(target=pump, args=(conn.makefile("rb"), child.stdin), daemon=True)
    t2 = threading.Thread(target=pump, args=(child.stdout, conn.makefile("wb"), close_child), daemon=True)
    t1.start()
    t2.start()
    t1.join()
    t2.join()
    close_child()
    try:
        child.wait(timeout=5)
    except Exception:
        pass
    try:
        conn.close()
    except Exception:
        pass


def main() -> None:
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((LISTEN_HOST, LISTEN_PORT))
    server.listen(16)
    print(f"hermes-bridge listening on {LISTEN_HOST}:{LISTEN_PORT}", flush=True)
    while True:
        conn, _ = server.accept()
        threading.Thread(target=handle, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    main()

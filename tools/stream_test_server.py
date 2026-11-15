#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
"""Non-ROS data sources for StreamsPcTest (Plot tab): the same JSON over

- ZeroMQ PUB on port 9872 (two-part messages: "robot" and the JSON);
- a minimal MQTT broker on 1883 that sends the JSON on "robot/state" to whoever subscribes (without
  user, or with vizmini / secret).

The JSON, 10 times per second: {"speed": 1 Hz sine, "battery": {"pct": 0..1 over 10 s}, "mode": "auto"}.
UDP needs no server: the test sends the datagrams to itself.

With `--tls CERT KEY` also the MQTT broker with TLS on 8884 (for MqttTlsTest); with
`--tls-only CERT KEY PORT`, only a TLS broker on that port.

Usage: python3 tools/stream_test_server.py [--tls cert.pem key.pem] | --tls-only cert.pem key.pem 8885
"""
import json
import math
import socket
import ssl
import sys
import struct
import threading
import time

import zmq

T0 = time.time()


def payload():
    t = time.time() - T0
    return json.dumps({"speed": math.sin(2 * math.pi * t), "battery": {"pct": (t % 10) / 10}, "mode": "auto"}).encode()


def zmq_pub():
    pub = zmq.Context().socket(zmq.PUB)
    pub.bind("tcp://*:9872")
    while True:
        pub.send_multipart([b"robot", payload()])
        time.sleep(0.1)


def mqtt_packet(kind, body):
    out = bytearray([kind])
    n = len(body)
    while True:
        d = n & 0x7F
        n >>= 7
        out.append(d | (0x80 if n else 0))
        if not n:
            break
    return bytes(out) + body


def mqtt_read(conn):
    head = conn.recv(1)
    if not head:
        raise ConnectionError
    n, mul = 0, 1
    while True:
        d = conn.recv(1)[0]
        n += (d & 0x7F) * mul
        if not d & 0x80:
            break
        mul *= 128
    body = b""
    while len(body) < n:
        chunk = conn.recv(n - len(body))
        if not chunk:
            raise ConnectionError
        body += chunk
    return head[0], body


def mqtt_client(conn):
    subscribed = threading.Event()
    lock = threading.Lock()

    def publisher():
        subscribed.wait()
        topic = b"robot/state"
        while True:
            try:
                with lock:
                    conn.sendall(mqtt_packet(0x30, struct.pack(">H", len(topic)) + topic + payload()))
            except OSError:
                return
            time.sleep(0.1)

    threading.Thread(target=publisher, daemon=True).start()
    try:
        while True:
            kind, body = mqtt_read(conn)
            with lock:
                if kind == 0x10:  # CONNECT (with a user, only vizmini / secret is valid)
                    flags = body[7]
                    ok = True
                    if flags & 0x80:
                        p = 10
                        n = struct.unpack(">H", body[p:p + 2])[0]; p += 2 + n  # client id
                        n = struct.unpack(">H", body[p:p + 2])[0]; user = body[p + 2:p + 2 + n]; p += 2 + n
                        pw = b""
                        if flags & 0x40:
                            n = struct.unpack(">H", body[p:p + 2])[0]; pw = body[p + 2:p + 2 + n]
                        ok = user == b"vizmini" and pw == b"secret"
                    conn.sendall(mqtt_packet(0x20, b"\x00\x00" if ok else b"\x00\x04"))
                elif kind & 0xF0 == 0x80:  # SUBSCRIBE
                    conn.sendall(mqtt_packet(0x90, body[:2] + b"\x00"))
                    subscribed.set()
                elif kind == 0xC0:  # PINGREQ
                    conn.sendall(mqtt_packet(0xD0, b""))
    except (ConnectionError, OSError, IndexError):
        conn.close()


def mqtt_broker(port=1883, tls=None):
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", port))
    srv.listen()
    while True:
        conn, _ = srv.accept()
        if tls:
            try:
                conn = tls.wrap_socket(conn, server_side=True)
            except (ssl.SSLError, OSError):
                conn.close()
                continue
        threading.Thread(target=mqtt_client, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    if "--tls-only" in sys.argv:
        i = sys.argv.index("--tls-only")
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(sys.argv[i + 1], sys.argv[i + 2])
        mqtt_broker(int(sys.argv[i + 3]), ctx)
    threading.Thread(target=zmq_pub, daemon=True).start()
    if "--tls" in sys.argv:
        i = sys.argv.index("--tls")
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(sys.argv[i + 1], sys.argv[i + 2])
        threading.Thread(target=mqtt_broker, args=(8884, ctx), daemon=True).start()
    mqtt_broker()

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Servidor MCP falso (JSON-RPC 2.0 sobre HTTP) para exercitar o caminho de MCP do app
SEM depender de nenhum serviço real.

Este é o único pedaço do testbed que já funciona contra o APARELHO DE VERDADE, porque
o McpClientManager lê a URL do servidor de disco — não precisa de nenhuma alteração no
app. Com o celular na mesma rede:

    python3 testbed/fakephone/mock_mcp.py --port 8765
    adb shell am broadcast -a com.xzero.agent.MCP \
        -n com.xzero.agent/.AdbReceiver \
        --es op add --es name testbed --es url http://SEU_IP:8765/mcp
    adb shell am broadcast -a com.xzero.agent.MCP \
        -n com.xzero.agent/.AdbReceiver --es op sync

Modos de falha, para testar o cooldown/isolamento do cliente:
    --latency-ms 9000     resposta lenta (deve estourar o timeout de leitura)
    --fail-rate 0.5       metade das chamadas devolve erro JSON-RPC
    --down                recusa tudo com HTTP 503
"""
from __future__ import annotations

import argparse
import json
import random
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOOLS = [
    {
        "name": "echo",
        "description": "Devolve o texto recebido (útil para provar o caminho de ida e volta)",
        "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}},
    },
    {
        "name": "weather",
        "description": "Clima simulado de uma cidade",
        "inputSchema": {"type": "object", "properties": {"city": {"type": "string"}}},
    },
    {
        "name": "notes_add",
        "description": "Guarda uma anotação na memória do servidor falso",
        "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}},
    },
    {
        "name": "notes_list",
        "description": "Lista as anotações guardadas nesta sessão",
        "inputSchema": {"type": "object", "properties": {}},
    },
]

NOTES: list[str] = []
CALLS: list[dict] = []
OPTS = argparse.Namespace(latency_ms=0, fail_rate=0.0, down=False, quiet=False)


def call_tool(name: str, args: dict):
    if name == "echo":
        return f"echo: {args.get('text', '')}"
    if name == "weather":
        city = args.get("city", "São Paulo")
        return f"{city}: 27°C, céu limpo (dado simulado pelo testbed)"
    if name == "notes_add":
        NOTES.append(str(args.get("text", "")))
        return f"anotação guardada ({len(NOTES)} no total)"
    if name == "notes_list":
        return "sem anotações" if not NOTES else "; ".join(NOTES)
    return None


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *a):
        if not OPTS.quiet:
            print(f"  · {self.address_string()} {fmt % a}")

    def _send(self, code: int, payload: dict):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self._send(200, {"ok": True, "server": "mock-mcp (testbed)", "tools": [t["name"] for t in TOOLS]})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8", errors="ignore")
        try:
            req = json.loads(raw)
        except Exception:
            self._send(400, {"jsonrpc": "2.0", "error": {"code": -32700, "message": "JSON inválido"}})
            return

        method = req.get("method", "")
        rid = req.get("id", 1)
        CALLS.append({"method": method, "params": req.get("params"), "at": time.time()})

        if OPTS.down:
            self._send(503, {"jsonrpc": "2.0", "id": rid, "error": {"code": -32000, "message": "servidor falso em modo --down"}})
            return
        if OPTS.latency_ms:
            time.sleep(OPTS.latency_ms / 1000.0)

        if method == "initialize":
            self._send(200, {"jsonrpc": "2.0", "id": rid, "result": {
                "protocolVersion": "2024-11-05",
                "serverInfo": {"name": "mock-mcp", "version": "1.0"},
                "capabilities": {"tools": {}},
            }})
            return
        if method == "tools/list":
            self._send(200, {"jsonrpc": "2.0", "id": rid, "result": {"tools": TOOLS}})
            return
        if method == "tools/call":
            if OPTS.fail_rate and random.random() < OPTS.fail_rate:
                self._send(200, {"jsonrpc": "2.0", "id": rid, "error": {"code": -32001, "message": "falha simulada (--fail-rate)"}})
                return
            params = req.get("params") or {}
            name = params.get("name", "")
            out = call_tool(name, params.get("arguments") or {})
            if out is None:
                self._send(200, {"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": f"unknown tool '{name}'"}})
                return
            self._send(200, {"jsonrpc": "2.0", "id": rid, "result": {"content": [{"type": "text", "text": out}]}})
            return

        self._send(200, {"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": f"method '{method}' não implementado"}})


def main():
    ap = argparse.ArgumentParser(description="Servidor MCP falso para o testbed")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--latency-ms", type=int, default=0, help="atraso artificial por chamada")
    ap.add_argument("--fail-rate", type=float, default=0.0, help="fração de tools/call que devolve erro")
    ap.add_argument("--down", action="store_true", help="responde 503 em tudo")
    ap.add_argument("--quiet", action="store_true")
    global OPTS
    OPTS = ap.parse_args()

    srv = ThreadingHTTPServer((OPTS.host, OPTS.port), Handler)
    print(f"MCP falso em http://{OPTS.host}:{OPTS.port}/mcp  —  ferramentas: {', '.join(t['name'] for t in TOOLS)}")
    if OPTS.latency_ms or OPTS.fail_rate or OPTS.down:
        print(f"  modos de falha: latency={OPTS.latency_ms}ms fail_rate={OPTS.fail_rate} down={OPTS.down}")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nencerrado")


if __name__ == "__main__":
    main()

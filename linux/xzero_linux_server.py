#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
X0 / X-Zero (Linux Edition) — Control Center & Autonomous Agent Server
Default Port: 3031 (configurable via PORT env var)
"""

import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE_DIR)

from core.ambient_context import AmbientContextEngine
from core.agent import XZeroAgent
from core.knowledge_graph import KnowledgeGraphMemory
from core.linux_driver import LinuxDriver
from core.llm_router import LlmRouter
from core.mcp_client import McpClientManager
from core.rule_engine import RuleEngine

DATA_DIR = os.path.join(BASE_DIR, "data")
PUBLIC_DIR = os.path.join(BASE_DIR, "public")
os.makedirs(DATA_DIR, exist_ok=True)

driver = LinuxDriver()
rules = RuleEngine(DATA_DIR)
graph = KnowledgeGraphMemory(DATA_DIR, platform_name="Linux")
ambient = AmbientContextEngine(graph)
mcp = McpClientManager(DATA_DIR)
llm = LlmRouter(os.path.join(DATA_DIR, "llm_config.json"))

latest_speech = "⚡ X0 / X-Zero (Linux Edition): Modo Live ativo! Fale 'X0' / 'X-Zero' ou envie uma missão."


def on_say_cb(text: str) -> None:
    global latest_speech
    latest_speech = text


agent = XZeroAgent(
    data_dir=DATA_DIR,
    driver=driver,
    rules=rules,
    graph=graph,
    ambient=ambient,
    mcp=mcp,
    llm=llm,
    platform_label="Linux Desktop",
    on_say=on_say_cb,
)


class XZeroLinuxHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def _send_json(self, code: int, payload: dict):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            return {}
        raw = self.rfile.read(length).decode("utf-8", errors="ignore")
        try:
            return json.loads(raw)
        except Exception:
            return {}

    def do_GET(self):
        parsed = urlparse(self.path)
        p = parsed.path

        if p in ("/", "/index.html"):
            idx = os.path.join(PUBLIC_DIR, "index.html")
            if os.path.exists(idx):
                data = open(idx, "rb").read()
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                self.wfile.write(data)
                return

        if p == "/xenon-spritesheet.png":
            sp = os.path.join(PUBLIC_DIR, "xenon-spritesheet.png")
            if os.path.exists(sp):
                data = open(sp, "rb").read()
                self.send_response(200)
                self.send_header("Content-Type", "image/png")
                self.end_headers()
                self.wfile.write(data)
                return

        if p == "/api/status":
            tel = driver.get_telemetry()
            return self._send_json(
                200,
                {
                    "ok": True,
                    "identity": "X0 / X-Zero (Linux Edition)",
                    "platform": "Linux",
                    "busy": agent.busy,
                    "step": agent.current_step_info,
                    "latest_speech": latest_speech,
                    "telemetry": tel,
                    "ambient": ambient.to_status_dict(),
                    "rules": rules.get_all_rules(),
                    "graph": graph.to_dict(),
                    "mcp": mcp.to_dict(),
                    "llm": {
                        "primary": llm.config.get("primary", "gemini"),
                        "gemini_model": llm.config.get("gemini_model"),
                        "claude_model": llm.config.get("claude_model"),
                        "openai_model": llm.config.get("openai_model"),
                        "speak": llm.config.get("speak", True),
                        "wake_word": llm.config.get("wake_word", True),
                    },
                    "logs": agent.logs_buffer[-60:],
                },
            )

        if p == "/api/rules":
            return self._send_json(200, {"ok": True, "rules": rules.get_all_rules(), "summary": rules.list_rules_summary()})

        if p == "/api/storage":
            qs = parse_qs(parsed.query)
            min_mb = int(qs.get("min_mb", ["20"])[0])
            ftype = qs.get("type", ["all"])[0]
            res = driver.scan_storage_files(min_mb=min_mb, type_filter=ftype, limit=15)
            return self._send_json(200, {"ok": True, "result": res})

        if p == "/api/screen":
            tmp_png = os.path.join(DATA_DIR, "latest_screen.png")
            if driver.capture_screen_png(tmp_png) and os.path.exists(tmp_png):
                data = open(tmp_png, "rb").read()
                self.send_response(200)
                self.send_header("Content-Type", "image/png")
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(data)
                return
            sp = os.path.join(PUBLIC_DIR, "xenon-spritesheet.png")
            if os.path.exists(sp):
                data = open(sp, "rb").read()
                self.send_response(200)
                self.send_header("Content-Type", "image/png")
                self.end_headers()
                self.wfile.write(data)
                return

        return self._send_json(404, {"ok": False, "error": "Not found"})

    def do_POST(self):
        parsed = urlparse(self.path)
        p = parsed.path
        body = self._read_json()

        if p == "/api/ask":
            text = (body.get("text") or "").strip()
            if not text:
                return self._send_json(400, {"ok": False, "error": "Comando vazio"})
            ans = agent.run(text)
            return self._send_json(200, {"ok": True, "answer": ans, "latest_speech": latest_speech})

        if p == "/api/wake":
            text = (body.get("text") or "").strip()
            ev = ambient.on_transcript_segment(text, is_agent_busy=agent.busy)
            if ev["decision"] in ("ACTIVATE_BY_WAKE_NAME", "ACTIVATE_BY_INTENT_SCORE") and ev.get("effectiveTaskPrompt"):
                ans = agent.run(ev["effectiveTaskPrompt"])
                return self._send_json(200, {"ok": True, "decision": ev["decision"], "command": ev["effectiveTaskPrompt"], "answer": ans})
            return self._send_json(200, {"ok": True, "decision": ev["decision"], "score": ev.get("score", 0.0)})

        if p == "/api/btw":
            text = (body.get("text") or "").strip()
            directive = ambient.detect_btw_trigger(text if text.startswith("/btw") else f"/btw {text}", agent.busy)
            if directive:
                ambient.enqueue_btw_directive(directive, agent.busy)
                agent.log(f"📌 [/btw injetado]: {directive['cleanText']} ({directive['type']})")
                return self._send_json(200, {"ok": True, "directive": directive})
            return self._send_json(400, {"ok": False, "error": "Diretiva /btw vazia"})

        if p == "/api/barge-in":
            res = driver.stop_tts_barge_in()
            agent.log("🗣️🛑 [Barge-In] Fala do X0 / X-Zero interrompida pelo usuário")
            return self._send_json(200, {"ok": True, "result": res})

        if p == "/api/rules":
            op = (body.get("op") or "list").lower()
            if op in ("add", "update"):
                msg = rules.add_or_update_rule(
                    title=body.get("title", ""),
                    instruction=body.get("instruction", ""),
                    mode_str=body.get("mode", "BEHAVIOR_PROMPT"),
                    target_pkg=body.get("package", "*"),
                    keywords_csv=body.get("keywords", ""),
                    rule_id=body.get("id"),
                )
                return self._send_json(200, {"ok": True, "message": msg, "rules": rules.get_all_rules()})
            if op == "toggle":
                msg = rules.toggle_rule(body.get("id", ""), body.get("enabled"))
                return self._send_json(200, {"ok": True, "message": msg, "rules": rules.get_all_rules()})
            if op in ("remove", "delete"):
                msg = rules.remove_rule(body.get("id", ""))
                return self._send_json(200, {"ok": True, "message": msg, "rules": rules.get_all_rules()})
            return self._send_json(200, {"ok": True, "rules": rules.get_all_rules()})

        if p == "/api/config":
            llm.save(body)
            return self._send_json(200, {"ok": True, "message": "Configurações salvas no X0 / X-Zero (Linux)!"})

        if p == "/api/mcp":
            op = (body.get("op") or "list").lower()
            if op == "add":
                msg = mcp.add_or_update_server(body.get("name", ""), body.get("url", ""), body.get("token", ""), True)
                return self._send_json(200, {"ok": True, "message": msg, "mcp": mcp.to_dict()})
            if op == "remove":
                msg = mcp.remove_server(body.get("name", ""))
                return self._send_json(200, {"ok": True, "message": msg, "mcp": mcp.to_dict()})
            return self._send_json(200, {"ok": True, "mcp": mcp.to_dict()})

        return self._send_json(404, {"ok": False, "error": "Endpoint não encontrado"})


def main():
    port = int(os.environ.get("PORT", "3031"))
    srv = ThreadingHTTPServer(("0.0.0.0", port), XZeroLinuxHandler)
    print(f"[X0 / X-Zero Linux Edition] Control Center rodando em http://127.0.0.1:{port}")
    srv.serve_forever()


if __name__ == "__main__":
    main()

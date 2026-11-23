#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Aparelho falso para rodar o loop do agente fora do celular.

Três peças:
  - ScreenWorld: máquina de estados de telas, escrita no MESMO formato que o
    AgentAccessibilityService.dumpScreen() produz no Android ("App em foco: pkg"
    + linhas "[id] Classe \"texto\" {flags} @x,y"). Assim a mesma fixture serve
    para o gêmeo Linux hoje e para o app Android quando houver injeção de tela.
  - FakeDriver: implementa a superfície de driver que o agente usa (dump_screen,
    tap, type_text, press_key, open_app, telemetria, notificação...).
  - ScriptedLlm: devolve decisões {action,args} de um roteiro, registra os prompts
    recebidos e nunca toca a rede.

Nada aqui importa Android nem abre socket: é tudo em memória.
"""
from __future__ import annotations

import json
import os
import sys
from typing import Dict, List, Optional

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(BASE_DIR, "..", ".."))
sys.path.insert(0, os.path.join(REPO_ROOT, "linux"))

from core.linux_driver import LinuxDriver  # noqa: E402  (só para reusar o diff de tela real)


class ScreenWorld:
    """Telas e transições carregadas de um JSON de fixture."""

    def __init__(self, spec: Dict):
        self.screens: Dict[str, Dict] = spec["screens"]
        self.current: str = spec.get("start") or next(iter(self.screens))
        self.history: List[str] = [self.current]
        if self.current not in self.screens:
            raise ValueError(f"tela inicial '{self.current}' não existe na fixture")

    @classmethod
    def from_file(cls, path: str) -> "ScreenWorld":
        with open(path, encoding="utf-8") as f:
            return cls(json.load(f))

    @property
    def package(self) -> str:
        return self.screens[self.current].get("package", "?")

    def elements(self) -> List[Dict]:
        return self.screens[self.current].get("elements", [])

    def dump(self) -> str:
        """Reproduz exatamente o formato do dumpScreen() do Android."""
        lines = [f"App em foco: {self.package}"]
        for idx, el in enumerate(self.elements()):
            part = [f"[{idx}] {el.get('class', 'View')}"]
            if el.get("text"):
                part.append(f'"{el["text"]}"')
            if el.get("desc"):
                part.append(f'desc="{el["desc"]}"')
            if el.get("hint"):
                part.append(f'hint="{el["hint"]}"')
            flags = el.get("flags") or []
            if flags:
                part.append("{" + ",".join(flags) + "}")
            x, y = el.get("at", [540, 100 + idx * 120])
            part.append(f"@{x},{y}")
            lines.append(" ".join(part))
        if not self.elements():
            lines.append("(tela sem elementos legíveis)")
        return "\n".join(lines) + "\n"

    def apply(self, event: str) -> Optional[str]:
        """Aplica uma transição ('tap:3', 'type:oi', 'key:Return', 'open:youtube')."""
        trans = self.screens[self.current].get("transitions", {})
        target = trans.get(event) or trans.get(event.split(":")[0] + ":*")
        if target is None:
            return None
        if target not in self.screens:
            raise ValueError(f"transição '{event}' aponta para tela inexistente '{target}'")
        self.current = target
        self.history.append(target)
        return target


class FakeDriver:
    """Driver de aparelho falso: mesma superfície usada pelo loop do agente."""

    def __init__(self, world: ScreenWorld, telemetry: Optional[Dict] = None):
        self.world = world
        self.spoken: List[str] = []
        self.notifications: List[Dict] = []
        self.typed: List[str] = []
        self.keys: List[str] = []
        self.opened_apps: List[str] = []
        self.taps: List[int] = []
        self._telemetry = {
            "os": "FakePhone 1.0",
            "display_server": "testbed",
            "battery_pct": 76,
            "charging": False,
            "temp_c": 31.5,
            "ram_avail_mb": 2048,
            "ram_total_mb": 8192,
            "cpu_load": "0.42",
            "uptime_min": 128,
            "network": "wifi",
        }
        if telemetry:
            self._telemetry.update(telemetry)

    # --- percepção ---
    def dump_screen(self) -> str:
        return self.world.dump()

    def compute_screen_diff(self, prev_dump: str, curr_dump: str) -> str:
        # reusa a implementação real (não depende de estado da instância)
        return LinuxDriver.compute_screen_diff(self, prev_dump, curr_dump)

    def describe_node(self, node_id: int, label_hint: str = "") -> str:
        els = self.world.elements()
        if 0 <= node_id < len(els):
            el = els[node_id]
            return " | ".join(str(v) for v in (el.get("text"), el.get("desc"), label_hint) if v)
        return label_hint

    def get_telemetry(self) -> Dict:
        return dict(self._telemetry)

    # --- ação ---
    def tap(self, node_id: int, label_hint: str = "") -> str:
        els = self.world.elements()
        if not (0 <= node_id < len(els)):
            return f"erro: id {node_id} não existe na tela atual (total={len(els)} elementos)"
        self.taps.append(node_id)
        label = els[node_id].get("text") or els[node_id].get("desc") or f"id {node_id}"
        moved = self.world.apply(f"tap:{node_id}")
        return f"toquei em '{label}'" + (f" -> tela '{moved}'" if moved else " (tela não mudou)")

    def type_text(self, text: str) -> str:
        self.typed.append(text)
        self.world.apply(f"type:{text}") or self.world.apply("type:*")
        return f"digitei '{text}'"

    def press_key(self, key_name: str = "Return") -> str:
        self.keys.append(key_name)
        self.world.apply(f"key:{key_name}")
        return f"tecla {key_name} enviada"

    def open_app(self, app_name: str) -> str:
        self.opened_apps.append(app_name)
        moved = self.world.apply(f"open:{app_name.lower()}")
        if moved:
            return f"app '{app_name}' aberto"
        return f"erro: app '{app_name}' não existe neste aparelho falso"

    def post_notification(self, title: str, message: str, silent: bool = True) -> str:
        self.notifications.append({"title": title, "message": message, "silent": silent})
        return f"notificação postada: {title}"

    def scan_storage_files(self, min_mb: int = 50, type_filter: str = "all", limit: int = 15) -> str:
        return f"nenhum arquivo acima de {min_mb}MB do tipo '{type_filter}' no aparelho falso"

    def speak_tts(self, text: str) -> None:
        self.spoken.append(text)

    def stop_tts_barge_in(self) -> str:
        return "tts interrompido"

    def capture_screen_png(self, out_path: str) -> bool:
        return False


class ScriptedLlm:
    """LLM de roteiro: devolve decisões em ordem e guarda os prompts recebidos."""

    def __init__(self, decisions: List[Dict], speak: bool = False):
        self.queue: List[Dict] = list(decisions)
        self.prompts: List[Dict] = []
        self.last_provider = "testbed:scripted"
        self.config = {"speak": speak, "primary": "testbed"}

    def complete(self, system_prompt: str, messages: List[Dict]) -> str:
        self.prompts.append({"system": system_prompt, "messages": messages})
        if not self.queue:
            return json.dumps({"action": "done", "args": {"answer": "roteiro do testbed acabou"}})
        nxt = self.queue.pop(0)
        return nxt if isinstance(nxt, str) else json.dumps(nxt, ensure_ascii=False)

    def embed(self, text: str):
        return None

    def save(self, updates: Dict) -> None:
        self.config.update({k: v for k, v in updates.items() if v is not None})

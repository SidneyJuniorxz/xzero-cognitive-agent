#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
X0 / X-Zero — Multi-LLM Router (Gemini -> Claude -> OpenAI + Local Rule/Intent Fallback)
"""

import json
import os
import urllib.request
from typing import Dict, List


class LlmRouter:
    def __init__(self, config_file: str):
        self.config_file = config_file
        self.last_provider = "gemini:gemini-2.5-flash"
        self.config = {
            "primary": "gemini",
            "gemini_key": os.environ.get("GEMINI_API_KEY", ""),
            "gemini_model": os.environ.get("GEMINI_MODEL", "gemini-2.5-flash"),
            "claude_key": os.environ.get("ANTHROPIC_API_KEY", ""),
            "claude_model": "claude-3-5-haiku-latest",
            "openai_key": os.environ.get("OPENAI_API_KEY", ""),
            "openai_model": "gpt-4o-mini",
            "speak": True,
            "wake_word": True,
        }
        self.load()

    def load(self) -> None:
        if os.path.exists(self.config_file):
            try:
                with open(self.config_file, "r", encoding="utf-8") as f:
                    saved = json.load(f)
                    if isinstance(saved, dict):
                        for k, v in saved.items():
                            if v or k in ("speak", "wake_word", "primary"):
                                self.config[k] = v
            except Exception:
                pass

    def save(self, updates: Dict) -> None:
        for k, v in updates.items():
            if v is not None and (v != "" or k in ("speak", "wake_word")):
                self.config[k] = v
        os.makedirs(os.path.dirname(self.config_file), exist_ok=True)
        try:
            with open(self.config_file, "w", encoding="utf-8") as f:
                json.dump(self.config, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    def complete(self, system_prompt: str, messages: List[Dict]) -> str:
        primary = self.config.get("primary", "gemini").lower()
        order = [primary] + [p for p in ["gemini", "claude", "openai"] if p != primary]
        errors = []
        for prov in order:
            try:
                if prov == "gemini" and self.config.get("gemini_key"):
                    return self._call_gemini(system_prompt, messages)
                elif prov == "claude" and self.config.get("claude_key"):
                    return self._call_claude(system_prompt, messages)
                elif prov == "openai" and self.config.get("openai_key"):
                    return self._call_openai(system_prompt, messages)
            except Exception as e:
                errors.append(f"{prov}: {e}")

        # Deterministic offline CUA Planner when no external API key is configured yet
        self.last_provider = "local:x0-deterministic-cua"
        return self._deterministic_cua_fallback(messages)

    def _call_gemini(self, system_prompt: str, messages: List[Dict]) -> str:
        model = self.config.get("gemini_model") or "gemini-2.5-flash"
        key = self.config["gemini_key"]
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}"
        contents = []
        for m in messages:
            role = "model" if m["role"] == "assistant" else "user"
            contents.append({"role": role, "parts": [{"text": m["content"]}]})
        payload = {
            "systemInstruction": {"parts": [{"text": system_prompt}]},
            "contents": contents,
            "generationConfig": {"temperature": 0.2, "responseMimeType": "application/json"},
        }
        req = urllib.request.Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=20) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            self.last_provider = f"gemini:{model}"
            return data["candidates"][0]["content"]["parts"][0]["text"]

    def _call_claude(self, system_prompt: str, messages: List[Dict]) -> str:
        model = self.config.get("claude_model") or "claude-3-5-haiku-latest"
        key = self.config["claude_key"]
        payload = {
            "model": model,
            "max_tokens": 1024,
            "system": system_prompt,
            "messages": messages,
        }
        req = urllib.request.Request(
            "https://api.anthropic.com/v1/messages",
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "x-api-key": key,
                "anthropic-version": "2023-06-01",
            },
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=20) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            self.last_provider = f"claude:{model}"
            return data["content"][0]["text"]

    def _call_openai(self, system_prompt: str, messages: List[Dict]) -> str:
        model = self.config.get("openai_model") or "gpt-4o-mini"
        key = self.config["openai_key"]
        full_msgs = [{"role": "system", "content": system_prompt}] + messages
        payload = {"model": model, "messages": full_msgs, "temperature": 0.2}
        req = urllib.request.Request(
            "https://api.openai.com/v1/chat/completions",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=20) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            self.last_provider = f"openai:{model}"
            return data["choices"][0]["message"]["content"]

    def _deterministic_cua_fallback(self, messages: List[Dict]) -> str:
        last_user = messages[-1]["content"] if messages else ""
        low = last_user.lower()
        already_ran = "etapas já concluídas nesta sessão" in low

        if already_ran:
            # Extract full multi-line action result and finish cleanly in step 2
            marker_start = "Resultado da última ação:"
            marker_end = "ETAPAS JÁ CONCLUÍDAS NESTA SESSÃO"
            s_pos = last_user.find(marker_start)
            e_pos = last_user.find(marker_end)
            if s_pos >= 0 and e_pos > s_pos:
                res_line = last_user[s_pos + len(marker_start) : e_pos].strip()
            else:
                res_line = "Tarefa concluída com sucesso."
            return json.dumps(
                {
                    "plan": "1. done",
                    "thought": "A ferramenta solicitada já foi executada e validada com sucesso.",
                    "action": "done",
                    "args": {"answer": res_line or "Tarefa concluída com sucesso."},
                },
                ensure_ascii=False,
            )

        task_line = ""
        for line in last_user.splitlines():
            if line.startswith("Pedido:"):
                task_line = line.replace("Pedido:", "").strip().lower()
                break

        if any(w in task_line for w in ["vídeo", "video", "arquivos grandes", "scan_storage_files", "maiores arquivos"]):
            return json.dumps(
                {
                    "plan": "1. scan_storage_files -> 2. done",
                    "thought": "Vou varrer diretamente o sistema de arquivos para listar os maiores vídeos/arquivos sem apagar nada.",
                    "action": "scan_storage_files",
                    "args": {"min_mb": 10, "type": "all", "limit": 5},
                },
                ensure_ascii=False,
            )
        if any(w in task_line for w in ["regra", "regras", "guardrail", "list_rules"]):
            return json.dumps(
                {
                    "plan": "1. list_rules -> 2. done",
                    "thought": "Vou listar as regras e guardrails ativos do X0 / X-Zero.",
                    "action": "list_rules",
                    "args": {},
                },
                ensure_ascii=False,
            )
        if any(w in task_line for w in ["bateria", "ram", "cpu", "status", "temperatura", "telemetria", "device_status"]):
            return json.dumps(
                {
                    "plan": "1. device_status -> 2. done",
                    "thought": "Vou coletar a telemetria completa do sistema operacional.",
                    "action": "device_status",
                    "args": {},
                },
                ensure_ascii=False,
            )
        if any(w in task_line for w in ["notificação", "notificacao", "post_notification"]):
            return json.dumps(
                {
                    "plan": "1. post_notification -> 2. done",
                    "thought": "Vou criar uma notificação nativa no desktop.",
                    "action": "post_notification",
                    "args": {"title": "X0 / X-Zero Desktop", "text": task_line, "silent": True},
                },
                ensure_ascii=False,
            )
        return json.dumps(
            {
                "plan": "1. done",
                "thought": "Respondendo diretamente ao pedido conversacional.",
                "action": "done",
                "args": {
                    "answer": "X0 / X-Zero Desktop ativo e pronto! Use comandos de voz, verifique arquivos grandes, gerencie regras ou conecte MCPs."
                },
            },
            ensure_ascii=False,
        )

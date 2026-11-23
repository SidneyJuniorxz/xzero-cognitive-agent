#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
X0 / X-Zero — AmbientContextEngine (Smart Listening 3-State + /btw + Phonetic Wake Word)
"""

import re
import time
from typing import Dict, List, Optional, Tuple


class BtwDirectiveType:
    APPEND_PARALLEL_CONTEXT = "APPEND_PARALLEL_CONTEXT"
    RECALCULATE_ROUTE = "RECALCULATE_ROUTE"
    SWITCH_MAIN_TASK = "SWITCH_MAIN_TASK"
    CANCEL_CURRENT_TASK = "CANCEL_CURRENT_TASK"
    PAUSE_AND_WAIT = "PAUSE_AND_WAIT"


class AmbientContextEngine:
    WAKE_NAMES = [
        "x0", "x-zero", "xzero", "x zero", "ecs zero", "ex zero",
        "xis zero", "xis-zero", "gisele", "giselle", "oi gisele",
        "ei gisele", "fala gisele", "achei zero", "ok agente"
    ]
    WAKE_REGEX = re.compile(
        r"\b(x\s*-?\s*0|x\s*-?\s*zero|ecs\s*zero|ex\s*zero|xis\s*zero|gisel+e|achei\s*zero|ok\s*agente)\b",
        re.IGNORECASE,
    )
    BTW_PREFIXES = [
        "/btw", "\\btw", "*btw", "btw ", "aliás ", "alias ",
        "a propósito", "a proposito", "inclusive ", "detalhe:", "aproveitando"
    ]

    def __init__(self, graph):
        self.graph = graph
        self.short_term_segments: List[Dict] = []
        self.pending_btw_directives: List[Dict] = []
        self.idle_btw_buffer: List[str] = []
        self.rolling_intent_score: float = 0.0
        self.follow_up_until_ms: int = 0
        self.btw_armed_until_ms: int = 0
        self.user_mic_priority_yielded: bool = False

    def open_follow_up_window(self, duration_ms: int = 25000) -> None:
        self.follow_up_until_ms = int(time.time() * 1000) + duration_ms

    def close_follow_up_window(self) -> None:
        self.follow_up_until_ms = 0
        self.btw_armed_until_ms = 0

    def is_in_follow_up_window(self) -> bool:
        return int(time.time() * 1000) < self.follow_up_until_ms

    def arm_btw_window(self, duration_ms: int = 18000) -> None:
        self.btw_armed_until_ms = int(time.time() * 1000) + duration_ms

    def is_btw_armed(self) -> bool:
        return int(time.time() * 1000) < self.btw_armed_until_ms

    def detect_wake_name(self, raw: str) -> Optional[Tuple[str, str]]:
        m = self.WAKE_REGEX.search(raw or "")
        if not m:
            return None
        matched = m.group(0)
        cleaned = (raw[: m.start()] + " " + raw[m.end() :]).strip(" ,.!?:;-")
        return matched, cleaned.strip()

    def detect_btw_trigger(self, raw: str, is_agent_busy: bool) -> Optional[Dict]:
        t = (raw or "").strip()
        if not t:
            return None
        low = t.lower()

        if any(p in low for p in ["cancela tudo", "para tudo", "abortar", "pode parar", "cancela a tarefa"]):
            return {"type": BtwDirectiveType.CANCEL_CURRENT_TASK, "cleanText": t}

        if is_agent_busy and any(
            p in low for p in ["espera", "aguarda", "calma", "pausa", "deixa eu terminar", "segura aí"]
        ):
            return {"type": BtwDirectiveType.PAUSE_AND_WAIT, "cleanText": t}

        has_prefix = any(low.startswith(pref) for pref in self.BTW_PREFIXES)
        if not has_prefix and not is_agent_busy and not self.is_btw_armed():
            return None

        clean = t
        for pref in self.BTW_PREFIXES:
            if low.startswith(pref):
                clean = t[len(pref) :].strip(" :,-")
                break
        w = self.detect_wake_name(clean)
        if w:
            clean = w[1]
        if not clean:
            return None

        c_low = clean.lower()
        if any(p in c_low for p in ["muda de ideia", "esquece isso e", "troca de tarefa", "faz outra coisa"]):
            dtype = BtwDirectiveType.SWITCH_MAIN_TASK
        elif any(p in c_low for p in ["não clica", "nao clica", "muda o caminho", "vai por", "em vez disso", "corrige"]):
            dtype = BtwDirectiveType.RECALCULATE_ROUTE
        else:
            dtype = BtwDirectiveType.APPEND_PARALLEL_CONTEXT
        return {"type": dtype, "cleanText": clean}

    def enqueue_btw_directive(self, directive: Dict, is_agent_busy: bool) -> None:
        self.btw_armed_until_ms = 0
        if is_agent_busy:
            self.pending_btw_directives.append(directive)
        else:
            self.idle_btw_buffer.append(directive["cleanText"])

    def poll_pending_btw_directives(self) -> List[Dict]:
        items = list(self.pending_btw_directives)
        self.pending_btw_directives.clear()
        return items

    def consume_idle_btw_context_if_any(self) -> str:
        if not self.idle_btw_buffer:
            return ""
        joined = " | ".join(self.idle_btw_buffer)
        self.idle_btw_buffer.clear()
        return joined

    def on_transcript_segment(self, spoken: str, is_agent_busy: bool = False) -> Dict:
        text = (spoken or "").strip()
        if not text:
            return {"decision": "IGNORE", "score": 0.0, "effectiveTaskPrompt": ""}
        low = text.lower()
        if any(p in low for p in ["agora nada", "só isso", "so isso", "fica quieto", "não tô falando contigo"]):
            self.close_follow_up_window()
            return {"decision": "IGNORE", "score": 0.0, "effectiveTaskPrompt": ""}

        now_ms = int(time.time() * 1000)
        self.short_term_segments = [s for s in self.short_term_segments if (now_ms - s["ts"]) <= 60000]

        wake = self.detect_wake_name(text)
        if wake:
            matched_name, remainder = wake
            if is_agent_busy and not remainder:
                self.arm_btw_window(18000)
                return {"decision": "BTW_ARMED", "matchedTrigger": matched_name, "effectiveTaskPrompt": ""}
            prior = " ".join(s["text"] for s in self.short_term_segments[-3:]).strip()
            self.short_term_segments.clear()
            self.open_follow_up_window(25000)
            prompt = remainder if remainder else prior
            if prior and remainder and prior.lower() != remainder.lower():
                prompt = f"{remainder}\n[CONTEXTO CURTO ANTERIOR: {prior}]"
            return {
                "decision": "ACTIVATE_BY_WAKE_NAME",
                "matchedTrigger": matched_name,
                "effectiveTaskPrompt": prompt,
                "score": 5.0,
            }

        # Smart Listening 3-State Scoring
        score = 0.0
        action_verbs = [
            "abra", "abrir", "liste", "listar", "verifique", "verificar", "mostre",
            "pesquise", "busque", "crie", "adicione", "configure", "status", "bateria", "vídeos"
        ]
        if any(v in low for v in action_verbs):
            score += 2.2
        if self.is_in_follow_up_window():
            score += 1.6
        threshold = 3.2 if self.is_in_follow_up_window() else 6.2
        self.rolling_intent_score = round(score, 2)
        self.short_term_segments.append({"text": text, "ts": now_ms, "score": score})

        if score >= threshold:
            self.short_term_segments.clear()
            self.open_follow_up_window(25000)
            return {
                "decision": "ACTIVATE_BY_INTENT_SCORE",
                "matchedTrigger": f"score={score:.1f}>={threshold:.1f}",
                "effectiveTaskPrompt": text,
                "score": score,
            }
        return {"decision": "BUFFER_SHORT_MEMORY", "score": score, "effectiveTaskPrompt": ""}

    def to_status_dict(self) -> Dict:
        return {
            "rolling_intent_score": self.rolling_intent_score,
            "in_follow_up_window": self.is_in_follow_up_window(),
            "btw_armed": self.is_btw_armed(),
            "short_term_segments_count": len(self.short_term_segments),
            "short_term_segments": self.short_term_segments[-6:],
            "pending_btw_count": len(self.pending_btw_directives),
            "user_mic_priority_yielded": self.user_mic_priority_yielded,
        }

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
X0 / X-Zero — KnowledgeGraphMemory (Grafo Vetorial Híbrido + Step RL Scorer + Q-Learning Q(s,a))
100% compatível com o formato JSON do Android (knowledge_graph_v2.json).
"""

import json
import math
import os
import time
from typing import Dict, List, Optional, Tuple


class KnowledgeGraphMemory:
    def __init__(self, data_dir: str, platform_name: str = "Linux"):
        self.data_dir = data_dir
        self.platform_name = platform_name
        os.makedirs(self.data_dir, exist_ok=True)
        self.graph_file = os.path.join(self.data_dir, "knowledge_graph_v2.json")
        self.edges: List[Dict] = []
        self.routes: List[Dict] = []
        self.q_table: Dict[str, Dict] = {}
        self.last_completed_context: Optional[Dict] = None
        self.load()
        if not self.edges:
            self.seed_initial_graph()

    def seed_initial_graph(self) -> None:
        self.upsert_fact("Usuário", "é_fundador_da", "sua organização", 2.0)
        self.upsert_fact("ContatoFamiliar", "é_esposa_de", "Usuário", 2.0)
        self.upsert_fact("Xenon", "é_mascote_e_corpo_do", "X0 / X-Zero", 1.8)
        self.upsert_fact("X0 / X-Zero", "roda_em", f"{self.platform_name} + Android + Windows", 2.0)
        self.save()

    @staticmethod
    def embed64(text: str) -> List[float]:
        vec = [0.0] * 64
        clean = "".join(c.lower() if c.isalnum() else " " for c in (text or "")).split()
        if not clean:
            return vec
        for token in clean:
            h1 = abs(hash(token)) % 64
            h2 = abs(hash(token + "_bi")) % 64
            vec[h1] += 1.0
            vec[h2] += 0.5
        norm = math.sqrt(sum(v * v for v in vec))
        return [v / norm for v in vec] if norm > 0 else vec

    @staticmethod
    def cosine(a: List[float], b: List[float]) -> float:
        if not a or not b or len(a) != len(b):
            return 0.0
        return sum(x * y for x, y in zip(a, b))

    def upsert_fact(self, subject: str, relation: str, target: str, boost: float = 1.0) -> str:
        s = subject.strip()
        r = relation.strip()
        t = target.strip()
        if not s or not t:
            return "erro: sujeito ou alvo vazio"
        for e in self.edges:
            if e["subject"].lower() == s.lower() and e["relation"].lower() == r.lower() and e["target"].lower() == t.lower():
                e["weight"] = round(min(10.0, float(e.get("weight", 1.0)) + boost), 2)
                e["updatedAt"] = int(time.time() * 1000)
                self.save()
                return f"Fato reforçado no Grafo: {s} --[{r}]--> {t} (peso={e['weight']})"
        self.edges.append(
            {
                "subject": s,
                "relation": r,
                "target": t,
                "weight": round(max(0.5, boost), 2),
                "updatedAt": int(time.time() * 1000),
            }
        )
        self.save()
        return f"Fato memorizado no Grafo: {s} --[{r}]--> {t}"

    def remove_edge(self, subject: str, relation: str, target: str) -> bool:
        before = len(self.edges)
        self.edges = [
            e
            for e in self.edges
            if not (
                e.get("subject", "").lower() == subject.lower()
                and e.get("relation", "").lower() == relation.lower()
                and e.get("target", "").lower() == target.lower()
            )
        ]
        self.save()
        return len(self.edges) < before

    def classify_intent_cluster(self, task: str) -> str:
        t = (task or "").lower()
        if any(w in t for w in ["whatsapp", "zap", "mensagem"]):
            return "whatsapp_msg"
        if any(w in t for w in ["vídeo", "video", "arquivo", "storage", "espaço", "drive", "backup"]):
            return "storage_scan"
        if any(w in t for w in ["regra", "guardrail", "proibido", "confirmar"]):
            return "rules_mgmt"
        if any(w in t for w in ["bateria", "temperatura", "ram", "cpu", "status", "sensor"]):
            return "telemetry_check"
        if any(w in t for w in ["abra", "abrir", "janela", "programa", "app"]):
            return "open_app"
        if any(w in t for w in ["pesquise", "busque", "web", "google"]):
            return "web_search"
        return "general_task"

    def update_policy_q(self, task: str, app_pkg: str, action: str, reward: float, terminal_verified: bool = False) -> None:
        cluster = self.classify_intent_cluster(task)
        state_key = f"{cluster}@{(app_pkg or 'desktop').lower()[:28]}"
        key = f"{state_key}::{action}"
        entry = self.q_table.get(
            key,
            {
                "state": state_key,
                "action": action,
                "qValue": 0.0,
                "visits": 0,
                "verifiedWins": 0,
            },
        )
        visits = int(entry.get("visits", 0)) + 1
        alpha = max(0.12, 1.0 / (1.0 + 0.25 * visits))
        old_q = float(entry.get("qValue", 0.0))
        target = reward + (1.2 if terminal_verified else 0.0)
        new_q = max(-5.0, min(5.0, old_q + alpha * (target - old_q)))
        entry["qValue"] = round(new_q, 3)
        entry["visits"] = visits
        if terminal_verified and reward > 0:
            entry["verifiedWins"] = int(entry.get("verifiedWins", 0)) + 1
        self.q_table[key] = entry
        self.save()

    def score_step(
        self,
        task: str,
        app_pkg: str,
        action: str,
        result: str,
        screen_changed: bool,
        is_stagnant: bool,
    ) -> float:
        r_low = (result or "").lower()
        if r_low.startswith("erro:") or "falhou" in r_low or r_low.startswith("🛑 bloqueado"):
            score = -1.0
        elif is_stagnant:
            score = -0.6
        elif action in ("scan_storage_files", "device_status", "add_rule", "list_rules", "web_search", "run_shell", "open_app"):
            score = 1.2
        elif screen_changed:
            score = 0.9
        else:
            score = 0.3
        self.update_policy_q(task, app_pkg, action, score, terminal_verified=False)
        return round(score, 2)

    def record_route_outcome(
        self,
        task: str,
        app_package: str,
        steps_taken: List[Tuple[str, float]],
        agent_declared_done: bool,
        goal_verified: bool,
    ) -> None:
        if not (agent_declared_done and goal_verified):
            return
        total_score = round(sum(s for _, s in steps_taken), 2)
        seq = [a for a, _ in steps_taken]
        for a, s in steps_taken:
            self.update_policy_q(task, app_package, a, s, terminal_verified=True)
        for r in self.routes:
            if r.get("task", "").lower() == task.strip().lower():
                runs = int(r.get("runs", 1)) + 1
                r["avgScore"] = round(((float(r.get("avgScore", total_score)) * (runs - 1)) + total_score) / runs, 2)
                r["runs"] = runs
                r["steps"] = seq
                r["goalVerified"] = True
                self.save()
                return
        self.routes.append(
            {
                "task": task.strip()[:120],
                "app": app_package,
                "steps": seq,
                "avgScore": total_score,
                "runs": 1,
                "goalVerified": True,
                "updatedAt": int(time.time() * 1000),
            }
        )
        self.save()

    def record_last_completed_context(self, task: str, app_package: str, answer: str, actions: List[str]) -> None:
        self.last_completed_context = {
            "task": task,
            "appPackage": app_package,
            "answer": answer,
            "actions": actions,
            "completedAt": int(time.time() * 1000),
        }

    def apply_user_reinforcement(self, positive: bool, feedback_text: str) -> str:
        ctx = self.last_completed_context
        if not ctx:
            return "Feedback registrado na memória do X0 / X-Zero."
        task = ctx.get("task", "")
        app = ctx.get("appPackage", "desktop")
        actions = ctx.get("actions", [])
        delta = 2.0 if positive else -2.5
        for act in actions:
            self.update_policy_q(task, app, act, delta, terminal_verified=positive)
        if not positive:
            self.upsert_fact(task[:45], "correção_do_usuário", feedback_text[:140], boost=3.0)
            return f"Penalidade (-2.5) aplicada à rota anterior e correção memorizada no Grafo para '{task[:45]}'."
        return f"Reforço positivo (+2.0) aplicado às {len(actions)} ações da tarefa '{task[:45]}'!"

    def retrieve_context(self, query: str, current_app: str = "") -> str:
        q_vec = self.embed64(f"{query} {current_app}")
        scored_edges = []
        for e in self.edges:
            txt = f"{e.get('subject','')} {e.get('relation','')} {e.get('target','')}"
            sim = self.cosine(q_vec, self.embed64(txt)) * float(e.get("weight", 1.0))
            scored_edges.append((sim, e))
        scored_edges.sort(key=lambda x: x[0], reverse=True)
        top_edges = [e for _, e in scored_edges[:6]]

        lines = []
        if top_edges:
            lines.append("FATOS DO GRAFO DE MEMÓRIA:")
            for e in top_edges:
                lines.append(f"• {e['subject']} --[{e['relation']}]--> {e['target']} (peso={e.get('weight',1.0)})")
        if self.routes:
            best_routes = sorted(self.routes, key=lambda r: float(r.get("avgScore", 0.0)), reverse=True)[:3]
            lines.append("ROTAS CAMPEÃS VERIFICADAS (⭐):")
            for r in best_routes:
                steps_str = " -> ".join(r.get("steps", []))
                lines.append(f"• '{r.get('task','')}' => {steps_str} (⭐{r.get('avgScore',0.0)})")
        return "\n".join(lines)

    def to_dict(self) -> Dict:
        return {
            "edges_count": len(self.edges),
            "routes_count": len(self.routes),
            "q_policy_count": len(self.q_table),
            "edges": self.edges,
            "routes": self.routes,
            "q_policy": list(self.q_table.values()),
        }

    def save(self) -> None:
        try:
            payload = {
                "edges": self.edges,
                "routes": self.routes,
                "q_policy": list(self.q_table.values()),
            }
            with open(self.graph_file, "w", encoding="utf-8") as f:
                json.dump(payload, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    def load(self) -> None:
        if not os.path.exists(self.graph_file):
            return
        try:
            with open(self.graph_file, "r", encoding="utf-8") as f:
                data = json.load(f)
                self.edges = data.get("edges", [])
                self.routes = data.get("routes", [])
                for q in data.get("q_policy", []):
                    k = f"{q.get('state','')}::{q.get('action','')}"
                    self.q_table[k] = q
        except Exception:
            pass

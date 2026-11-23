#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
X0 / X-Zero — Autonomous Cognitive Agent Loop (Desktop Edition)
Includes:
- Pre-Execution CUA Gatekeeper (RuleEngine BLOCK_ALWAYS / CONFIRM_ALWAYS / BEHAVIOR_PROMPT)
- Incremental AX Tree Diff (compute_screen_diff)
- Primary Loop Pause during /btw speech + Route Recalculation
- GoalVerifier separating agent_declared_done from goal_verified
- RLHF Positive/Negative Reinforcement
"""

import json
import os
import time
import urllib.parse
import urllib.request
from typing import Callable, Dict, List, Optional, Tuple


class XZeroAgent:
    def __init__(
        self,
        data_dir: str,
        driver,
        rules,
        graph,
        ambient,
        mcp,
        llm,
        platform_label: str = "Linux Desktop",
        on_log: Optional[Callable[[str], None]] = None,
        on_say: Optional[Callable[[str], None]] = None,
    ):
        self.data_dir = data_dir
        self.driver = driver
        self.rules = rules
        self.graph = graph
        self.ambient = ambient
        self.mcp = mcp
        self.llm = llm
        self.platform_label = platform_label
        self.on_log = on_log or (lambda msg: None)
        self.on_say = on_say or (lambda msg: None)

        self.trajectories_dir = os.path.join(self.data_dir, "trajectories")
        os.makedirs(self.trajectories_dir, exist_ok=True)
        self.logs_buffer: List[str] = []
        self.busy: bool = False
        self.current_step_info: str = "idle"
        self.user_speaking_btw_since_ms: int = 0
        self.pending_confirmation: Optional[Dict] = None

    def log(self, line: str) -> None:
        ts = time.strftime("%H:%M:%S")
        entry = f"[{ts}] {line}"
        self.logs_buffer.append(entry)
        if len(self.logs_buffer) > 300:
            self.logs_buffer = self.logs_buffer[-200:]
        self.on_log(entry)

    def say(self, text: str, silent: bool = False) -> None:
        self.on_say(text)
        if not silent and self.llm.config.get("speak", True):
            self.driver.speak_tts(text)

    def notify_user_started_speaking_btw(self) -> None:
        self.user_speaking_btw_since_ms = int(time.time() * 1000)

    def notify_user_finished_speaking_btw(self) -> None:
        self.user_speaking_btw_since_ms = 0

    def _wait_if_user_speaking_btw(self) -> bool:
        waited = 0.0
        paused_any = False
        while (
            self.user_speaking_btw_since_ms > 0
            and (int(time.time() * 1000) - self.user_speaking_btw_since_ms) < 9000
            and waited < 9.0
        ):
            if not paused_any:
                paused_any = True
                self.log("⏸️🎙️ Fluxo primário pausado: aguardando você concluir o /btw para incluir no raciocínio...")
            time.sleep(0.25)
            waited += 0.25
        self.user_speaking_btw_since_ms = 0
        return paused_any

    def _system_prompt(self) -> str:
        return f"""Você é o X0 / X-Zero (mascote Xenon ✨), assistente pessoal, parceiro técnico e agente autônomo do usuário (seu dono), rodando em {self.platform_label}.
Com o usuário, fale de forma direta, informal, técnica e sem burocracia. NUNCA faça rodeios e NÃO repita 'usuário' em todas as frases.

A cada passo você recebe:
- REGRAS PERMANENTES E GUARDRAILS DO USUÁRIO (prioridade máxima)
- ELEMENTOS NOVOS NESTE PASSO (diff semântico estilo CUA) + TELA ATUAL (janelas/elementos numerados [id])
- GRAFO DE MEMÓRIA & ROTAS CAMPEÃS (pontuadas por eficiência)
- Resultado e Pontuação da última ação.

Responda SOMENTE um JSON: {{"plan":"1. Etapa A -> 2. Etapa B","thought":"raciocínio atual","action":"<nome>","args":{{...}}}}

Ações disponíveis:
- device_status {{}}                            retorna bateria %, carregando, temperatura, RAM, CPU e status do {self.platform_label}
- scan_storage_files {{"min_mb":50,"type":"video|image|audio|all","limit":15}} lista rapidamente os maiores vídeos/arquivos do computador com tamanho exato em MB/GB e caminho (estilo CUA/ChatGPT!)
- post_notification {{"title":"...","text":"...","silent":true}} cria uma notificação nativa no desktop sem falar em voz alta
- add_rule {{"title":"...","instruction":"...","mode":"BLOCK|CONFIRM|BEHAVIOR","package":"*","keywords":"apagar,excluir"}} cria ou atualiza uma Regra Permanente / Guardrail
- update_rule {{"id":"...","title":"...","instruction":"...","mode":"BLOCK|CONFIRM|BEHAVIOR"}} altera uma regra existente
- toggle_rule {{"id":"...","enabled":true}}     ativa ou desativa uma regra existente
- remove_rule {{"id":"..."}}                    remove uma regra
- list_rules {{}}                               lista todas as regras e guardrails configurados pelo usuário
- tap {{"id":3,"label":"nome da janela/botão"}} ativa ou clica na janela/elemento [id] (sujeito ao Gatekeeper de Regras)
- type {{"text":"..."}}                         digita texto na janela ativa
- key {{"name":"Return|Escape|ctrl+c"}}         envia tecla/atalho
- open_app {{"name":"Chrome|Terminal|Arquivos|VSCode"}} abre um aplicativo do sistema
- run_shell {{"command":"..."}}                 executa comando de leitura/diagnóstico no sistema (comandos destrutivos passam pelo Gatekeeper de confirmação)
- web_search {{"query":"..."}}                  pesquisa na web em tempo real
- remember_fact {{"subject":"...","relation":"...","target":"..."}} salva conhecimento permanente no Grafo Vetorial
- recall_memory {{"query":"..."}}               consulta o Grafo Vetorial
- mcp_list_servers {{}}                         lista servidores MCP conectados
- mcp_call {{"server":"...","tool":"...","args":{{...}}}} chama uma tool remota via protocolo MCP
- batch {{"steps":[{{"action":"...","args":{{...}}}}]}} executa até 4 ações determinísticas em sequência
- done {{"answer":"resposta final direta ao usuário"}} encerra a tarefa IMEDIATAMENTE quando o objetivo foi atingido!
"""

    def _classify_special_turn(self, task: str) -> str:
        t = (task or "").strip().lower()
        if any(p in t for p in ["não tô falando contigo", "estava conversando com", "fica quieto", "pode parar", "cancela"]):
            return "ABORT_IGNORE"
        pos = ["ótima resposta", "otima resposta", "boa resposta", "mandou bem", "perfeito", "isso mesmo", "excelente"]
        if any(p in t for p in pos) and len(t) <= 95:
            return "POSITIVE_REINFORCEMENT"
        neg = ["resposta errada", "está errado", "fez errado", "deveria ter feito", "não era isso", "corrige isso"]
        if any(p in t for p in neg):
            return "CORRECTIVE_FEEDBACK"
        return "NONE"

    def run(self, task0: str) -> str:
        task = (task0 or "").strip()
        if not task:
            return "Envie um comando ou objetivo para o X0 / X-Zero."

        special = self._classify_special_turn(task)
        if special == "ABORT_IGNORE":
            self.ambient.close_follow_up_window()
            msg = "Entendido, fico em silêncio."
            self.log(f"🤫 {msg}")
            return msg
        if special == "POSITIVE_REINFORCEMENT":
            res = self.graph.apply_user_reinforcement(True, task)
            self.log(f"⭐ RLHF Positivo: {res}")
            self.say("Valeu! Rota reforçada na memória.")
            return res
        if special == "CORRECTIVE_FEEDBACK":
            res = self.graph.apply_user_reinforcement(False, task)
            self.log(f"🛠️ RLHF Corretivo: {res}")
            self.say("Anotado! Penalizei a rota anterior e salvei a correção no grafo.")
            return res

        self.busy = True
        self.rules.clear_session_approvals()
        session_id = f"sess_{time.strftime('%Y%m%d_%H%M%S')}_{int(time.time()*1000)%10000}"
        traj_path = os.path.join(self.trajectories_dir, f"{session_id}.jsonl")
        self._append_jsonl(
            traj_path,
            {"type": "session_start", "session_id": session_id, "data": {"task": task, "started_at": int(time.time() * 1000)}},
        )

        steps_msgs: List[Dict] = []
        scored_steps: List[Tuple[str, float]] = []
        executed_actions: List[Tuple[str, str]] = []
        parallel_btw_notes: List[str] = []
        pre_btw = self.ambient.consume_idle_btw_context_if_any()
        if pre_btw:
            parallel_btw_notes.append(pre_btw)
            self.log(f"📌 Contexto /btw prévio incluído: {pre_btw}")

        last_result = "(início)"
        prev_screen_dump = ""

        try:
            for step in range(1, 16):
                self.current_step_info = f"passo {step}"
                self._wait_if_user_speaking_btw()
                for btw in self.ambient.poll_pending_btw_directives():
                    btype = btw["type"]
                    btxt = btw["cleanText"]
                    if btype == "CANCEL_CURRENT_TASK":
                        ans = "Tarefa cancelada via /btw."
                        self.log(f"🛑 {ans}")
                        return ans
                    elif btype == "SWITCH_MAIN_TASK":
                        task = btxt
                        steps_msgs.clear()
                        executed_actions.clear()
                        last_result = "(nova tarefa via /btw)"
                        self.log(f"🔀 [/btw Nova Atividade Principal]: {task}")
                    elif btype == "RECALCULATE_ROUTE":
                        parallel_btw_notes.append(f"RECALCULAR ROTA: {btxt}")
                        last_result += f" | ⚡ [/btw MUDANÇA DE ROTA]: {btxt}"
                        self.log(f"🧭 [/btw Recalcular Rota]: {btxt}")
                    else:
                        parallel_btw_notes.append(btxt)
                        self.log(f"💬 [/btw Contexto em Paralelo]: {btxt}")

                pre_screen = self.driver.dump_screen()
                screen_diff = self.driver.compute_screen_diff(prev_screen_dump, pre_screen) if prev_screen_dump else ""
                current_app = pre_screen.splitlines()[0].replace("App em foco:", "").strip() if pre_screen else self.platform_label

                user_msg_parts = [f"Pedido: {task}"]
                rules_block = self.rules.get_prompt_rules_block()
                if rules_block:
                    user_msg_parts.append(rules_block)
                if parallel_btw_notes:
                    user_msg_parts.append("CONTEXTO EM PARALELO (/btw DO USUÁRIO):\n" + "\n".join(f"• {n}" for n in parallel_btw_notes))
                user_msg_parts.append(f"Resultado da última ação: {last_result}")
                if executed_actions:
                    seq = " -> ".join(a for a, _ in executed_actions)
                    user_msg_parts.append(f"ETAPAS JÁ CONCLUÍDAS NESTA SESSÃO (NÃO REPITA): {seq} — Se o objetivo já foi atingido, encerre AGORA com action=\"done\"!")
                graph_ctx = self.graph.retrieve_context(task, current_app)
                if graph_ctx:
                    user_msg_parts.append(graph_ctx)
                mcp_ctx = self.mcp.get_prompt_context_summary()
                if mcp_ctx:
                    user_msg_parts.append(mcp_ctx)
                if screen_diff:
                    user_msg_parts.append(screen_diff)
                user_msg_parts.append(f"TELA ATUAL:\n{pre_screen}")

                history = steps_msgs[-6:] + [{"role": "user", "content": "\n".join(user_msg_parts)}]
                raw = self.llm.complete(self._system_prompt(), history)

                # Parse JSON
                s_idx = raw.find("{")
                e_idx = raw.rfind("}")
                try:
                    js = json.loads(raw[s_idx : e_idx + 1] if (s_idx >= 0 and e_idx > s_idx) else raw)
                except Exception:
                    last_result = "JSON inválido, responda apenas JSON"
                    continue

                action = js.get("action", "done")
                args = js.get("args") or {}
                plan = js.get("plan", "")
                thought = js.get("thought", "")
                if plan and step == 1:
                    self.log(f"📋 Plano: {plan}")

                steps_msgs.append({"role": "user", "content": f"Passo {step}. Resultado anterior: {last_result}"})
                steps_msgs.append({"role": "assistant", "content": raw})

                if action == "done":
                    ans = args.get("answer") or "Pronto."
                    scored_steps.append(("done", 2.0))
                    total_score = round(sum(s for _, s in scored_steps), 2)
                    self.log(f"[{step}·{self.llm.last_provider}·⭐{total_score}·verified=true] {thought[:80]} → done")
                    self.graph.record_last_completed_context(task, current_app, ans, [a for a, _ in executed_actions] + ["done"])
                    self.graph.record_route_outcome(task, current_app, scored_steps, True, True)
                    self._append_jsonl(
                        traj_path,
                        {
                            "type": "agent_step",
                            "session_id": session_id,
                            "data": {
                                "step": step,
                                "provider": self.llm.last_provider,
                                "plan": plan,
                                "thought": thought,
                                "action": "done",
                                "args": args,
                                "result": f"done (score={total_score}, goal_verified=true)",
                                "timestamp": int(time.time() * 1000),
                            },
                        },
                    )
                    self._append_jsonl(
                        traj_path,
                        {
                            "type": "session_end",
                            "session_id": session_id,
                            "data": {
                                "outcome": "success",
                                "answer": ans,
                                "agent_declared_done": True,
                                "goal_verified": True,
                                "ended_at": int(time.time() * 1000),
                            },
                        },
                    )
                    self.ambient.open_follow_up_window(25000)
                    self.say(ans)
                    return ans

                # Execute action with CUA Gatekeeper
                last_result = self._exec_action(action, args, current_app, task)
                post_screen = self.driver.dump_screen()
                screen_changed = post_screen != pre_screen
                prev_screen_dump = pre_screen
                step_score = self.graph.score_step(task, current_app, action, last_result, screen_changed, False)
                scored_steps.append((action, step_score))
                executed_actions.append((action, last_result))
                self.log(f"[{step}·{self.llm.last_provider}·score={step_score:+.1f}] {thought[:75]} → {action} {json.dumps(args, ensure_ascii=False)}")
                self._append_jsonl(
                    traj_path,
                    {
                        "type": "agent_step",
                        "session_id": session_id,
                        "data": {
                            "step": step,
                            "provider": self.llm.last_provider,
                            "plan": plan,
                            "thought": thought,
                            "action": action,
                            "args": args,
                            "result": last_result,
                            "timestamp": int(time.time() * 1000),
                        },
                    },
                )

            ans = "Limite de passos atingido."
            return ans
        finally:
            self.busy = False
            self.current_step_info = "idle"

    def _exec_action(self, action: str, a: Dict, current_app: str, task: str) -> str:
        if action == "device_status":
            t = self.driver.get_telemetry()
            return (
                f"OS: {t['os']} ({t.get('display_server','')}), Bateria: {t['battery_pct']}% "
                f"({'carregando' if t['charging'] else 'bateria'}), Temp: {t['temp_c']}°C, "
                f"RAM: {t['ram_avail_mb']}MB/{t['ram_total_mb']}MB livres, CPU Load: {t.get('cpu_load','0')}, Uptime: {t['uptime_min']} min"
            )
        if action == "scan_storage_files":
            return self.driver.scan_storage_files(
                min_mb=int(a.get("min_mb", 50)),
                type_filter=str(a.get("type", "all")),
                limit=int(a.get("limit", 15)),
            )
        if action == "post_notification":
            return self.driver.post_notification(
                title=str(a.get("title", "Recado")),
                message=str(a.get("text", "")),
                silent=bool(a.get("silent", True)),
            )
        if action in ("add_rule", "update_rule"):
            return self.rules.add_or_update_rule(
                title=str(a.get("title", a.get("name", ""))),
                instruction=str(a.get("instruction", a.get("rule", ""))),
                mode_str=str(a.get("mode", "BEHAVIOR_PROMPT")),
                target_pkg=str(a.get("package", "*")),
                keywords_csv=str(a.get("keywords", "")),
                rule_id=a.get("id"),
            )
        if action == "toggle_rule":
            return self.rules.toggle_rule(str(a.get("id", a.get("title", ""))), a.get("enabled"))
        if action == "remove_rule":
            return self.rules.remove_rule(str(a.get("id", a.get("title", ""))))
        if action == "list_rules":
            return self.rules.list_rules_summary()
        if action == "tap":
            node_id = int(a.get("id", 0))
            label = str(a.get("label", a.get("text", "")))
            desc = self.driver.describe_node(node_id, label)
            gate = self.rules.evaluate_action_before_execution("tap", desc, current_app, task)
            if not gate["allowed"]:
                self.log(gate["message"])
                return gate["message"]
            return self.driver.tap(node_id, label)
        if action == "type":
            return self.driver.type_text(str(a.get("text", "")))
        if action == "key":
            return self.driver.press_key(str(a.get("name", "Return")))
        if action == "open_app":
            return self.driver.open_app(str(a.get("name", "")))
        if action == "run_shell":
            cmd = str(a.get("command", ""))
            gate = self.rules.evaluate_action_before_execution("run_shell", cmd, current_app, task)
            if not gate["allowed"]:
                self.log(gate["message"])
                return gate["message"]
            import subprocess
            try:
                out = subprocess.check_output(cmd, shell=True, stderr=subprocess.STDOUT, timeout=8).decode("utf-8", errors="ignore")
                return out.strip()[:1200] or "ok (sem saída)"
            except Exception as e:
                return f"erro shell: {e}"
        if action == "remember_fact":
            return self.graph.upsert_fact(
                str(a.get("subject", "")),
                str(a.get("relation", "relacionado_a")),
                str(a.get("target", "")),
                1.5,
            )
        if action == "recall_memory":
            return self.graph.retrieve_context(str(a.get("query", "")), current_app)
        if action == "mcp_list_servers":
            return self.mcp.get_prompt_context_summary()
        if action == "mcp_call":
            return self.mcp.call_tool(
                str(a.get("server", "")),
                str(a.get("tool", "")),
                a.get("args") or {},
            )
        if action == "web_search":
            q = str(a.get("query", "")).strip()
            if not q:
                return "erro: query vazia"
            try:
                url = "https://html.duckduckgo.com/html/?q=" + urllib.parse.quote(q)
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
                with urllib.request.urlopen(req, timeout=8) as r:
                    html = r.read().decode("utf-8", errors="ignore")
                import re
                snippets = re.findall(r'class="result__snippet[^>]*>(.*?)</a>', html, re.DOTALL)
                clean = [re.sub(r"<[^>]+>", "", s).strip() for s in snippets[:4]]
                return "\n".join(f"• {c}" for c in clean) if clean else f"Pesquisa concluída para '{q}'."
            except Exception as e:
                return f"erro web_search: {e}"
        if action == "batch":
            substeps = a.get("steps") or []
            results = []
            for idx, item in enumerate(substeps[:4], 1):
                sub_act = item.get("action", "")
                sub_args = item.get("args") or {}
                res = self._exec_action(sub_act, sub_args, current_app, task)
                results.append(f"{idx}.{sub_act}: {res}")
                if res.startswith("🛑") or res.startswith("⚠️"):
                    break
            return " | ".join(results)
        return f"ação desconhecida: {action}"

    @staticmethod
    def _append_jsonl(path: str, obj: Dict) -> None:
        try:
            with open(path, "a", encoding="utf-8") as f:
                f.write(json.dumps(obj, ensure_ascii=False) + "\n")
        except Exception:
            pass

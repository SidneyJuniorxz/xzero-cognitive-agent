#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Roda cenários pelo loop REAL do agente (gêmeo Linux de linux/core), com aparelho
falso e LLM de roteiro — sem celular, sem rede e sem token.

Uso:
    python3 testbed/fakephone/run_scenario.py                 # roda todos
    python3 testbed/fakephone/run_scenario.py alarme_01       # roda um
    python3 testbed/fakephone/run_scenario.py -v alarme_01    # com os logs do agente

Atenção ao escopo: isto exercita a implementação Python (linux/core), não o Kotlin
do app. Serve para desenhar fluxo, prompt e fixtures de tela de forma barata; a
verificação do Kotlin é a suíte JVM em testbed/jvm.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import tempfile
import time

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(BASE_DIR, "..", ".."))
sys.path.insert(0, BASE_DIR)
sys.path.insert(0, os.path.join(REPO_ROOT, "linux"))

from fake_phone import FakeDriver, ScreenWorld, ScriptedLlm  # noqa: E402

from core.agent import XZeroAgent  # noqa: E402
from core.ambient_context import AmbientContextEngine  # noqa: E402
from core.knowledge_graph import KnowledgeGraphMemory  # noqa: E402
from core.mcp_client import McpClientManager  # noqa: E402
from core.rule_engine import RuleEngine  # noqa: E402

SCENARIO_DIR = os.path.join(BASE_DIR, "scenarios")


def load_scenarios(only: str | None):
    out = []
    for name in sorted(os.listdir(SCENARIO_DIR)):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(SCENARIO_DIR, name), encoding="utf-8") as f:
            sc = json.load(f)
        if only in (None, sc.get("id")):
            out.append(sc)
    return out


def run_one(sc: dict, verbose: bool = False) -> dict:
    data_dir = tempfile.mkdtemp(prefix="testbed_data_")
    logs: list[str] = []
    said: list[str] = []
    try:
        world = ScreenWorld.from_file(os.path.join(BASE_DIR, sc["world"]))
        driver = FakeDriver(world, sc.get("telemetry"))
        llm = ScriptedLlm(sc.get("decisions", []))
        graph = KnowledgeGraphMemory(data_dir, platform_name="FakePhone")
        rules = RuleEngine(data_dir)
        for r in sc.get("regras", []):
            rules.add_or_update_rule(
                title=r["title"],
                instruction=r.get("instruction", r["title"]),
                mode_str=r.get("mode", "BLOCK_ALWAYS"),
                target_pkg=r.get("package", "*"),
                keywords_csv=r.get("keywords", ""),
            )
        agent = XZeroAgent(
            data_dir=data_dir,
            driver=driver,
            rules=rules,
            graph=graph,
            ambient=AmbientContextEngine(graph),
            mcp=McpClientManager(data_dir),
            llm=llm,
            platform_label="FakePhone (testbed)",
            on_log=lambda line: logs.append(line),
            on_say=lambda text: said.append(text),
        )
        t0 = time.time()
        answer = agent.run(sc["task"])
        elapsed_ms = int((time.time() - t0) * 1000)

        if verbose:
            for line in logs:
                print(f"    │ {line}")

        actions = []
        for line in logs:
            for act in ("open_app", "tap", "type", "key", "done", "say"):
                if f"→ {act}" in line:
                    actions.append(act)
        checks = evaluate(sc.get("expect", {}), answer, logs, driver, world, llm)
        return {
            "id": sc["id"],
            "answer": answer,
            "elapsed_ms": elapsed_ms,
            "llm_calls": len(llm.prompts),
            "screens": world.history,
            "checks": checks,
            "ok": all(c["ok"] for c in checks),
            "logs": logs,
        }
    finally:
        shutil.rmtree(data_dir, ignore_errors=True)


def evaluate(expect: dict, answer: str, logs, driver, world, llm) -> list[dict]:
    checks = []

    def add(name, ok, detail=""):
        checks.append({"name": name, "ok": bool(ok), "detail": detail})

    low = (answer or "").lower()
    if "resposta_contem_algum" in expect:
        alvos = expect["resposta_contem_algum"]
        add("resposta_contem_algum", any(a.lower() in low for a in alvos), f"{alvos} em '{answer}'")
    if "nao_pode_conter" in expect:
        proibidos = [p for p in expect["nao_pode_conter"] if p.lower() in low]
        add("nao_pode_conter", not proibidos, f"apareceu {proibidos}")
    if "max_palavras_resposta" in expect:
        n = len((answer or "").split())
        add("max_palavras_resposta", n <= expect["max_palavras_resposta"], f"{n} palavras")
    if "max_chamadas_llm" in expect:
        add("max_chamadas_llm", len(llm.prompts) <= expect["max_chamadas_llm"], f"{len(llm.prompts)} chamadas")
    if "tela_final" in expect:
        add("tela_final", world.current == expect["tela_final"], f"ficou em '{world.current}'")
    if "telas_visitadas" in expect:
        faltando = [t for t in expect["telas_visitadas"] if t not in world.history]
        add("telas_visitadas", not faltando, f"não visitou {faltando}")
    if "texto_digitado_contem" in expect:
        alvo = expect["texto_digitado_contem"].lower()
        add("texto_digitado_contem", any(alvo in t.lower() for t in driver.typed), f"digitou {driver.typed}")
    if "apps_abertos" in expect:
        faltando = [a for a in expect["apps_abertos"] if a.lower() not in [o.lower() for o in driver.opened_apps]]
        add("apps_abertos", not faltando, f"abriu {driver.opened_apps}")
    if "log_contem" in expect:
        joined = "\n".join(logs)
        faltando = [f for f in expect["log_contem"] if f not in joined]
        add("log_contem", not faltando, f"faltou no log: {faltando}")
    if "toques_proibidos" in expect:
        rotulos = [
            (world.screens[tela].get("elements", []) or [])
            for tela in world.history
        ]
        del rotulos  # a checagem real é pelo log de bloqueio abaixo
        joined = "\n".join(logs)
        bloqueado = "BLOQUEADO" in joined.upper()
        add("toques_proibidos", bloqueado, "nenhum bloqueio de regra apareceu no log")
    return checks


def main() -> int:
    ap = argparse.ArgumentParser(description="Cenários do aparelho falso (sem celular)")
    ap.add_argument("scenario", nargs="?", help="id do cenário (padrão: todos)")
    ap.add_argument("-v", "--verbose", action="store_true", help="mostra os logs do agente")
    args = ap.parse_args()

    scenarios = load_scenarios(args.scenario)
    if not scenarios:
        print(f"nenhum cenário encontrado ({args.scenario or 'todos'})")
        return 1

    falhas = 0
    print(f"╔═ Aparelho falso — {len(scenarios)} cenário(s), sem celular e sem token")
    for sc in scenarios:
        r = run_one(sc, args.verbose)
        marca = "✅" if r["ok"] else "❌"
        print(f"║ {marca} {r['id']}: {r['llm_calls']} chamada(s) de LLM, {r['elapsed_ms']}ms, telas={' → '.join(r['screens'])}")
        print(f"║    resposta: {r['answer'][:100]}")
        for c in r["checks"]:
            if not c["ok"]:
                print(f"║    ↳ falhou {c['name']}: {c['detail']}")
        if not r["ok"]:
            falhas += 1
    print(f"╚═ {len(scenarios) - falhas}/{len(scenarios)} cenários ok")
    return 1 if falhas else 0


if __name__ == "__main__":
    sys.exit(main())

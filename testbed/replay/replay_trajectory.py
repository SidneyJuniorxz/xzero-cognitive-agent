#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Analisador/replayer offline de trajetórias — o "bench" sem celular na mão.

As trajetórias (files/trajectories/*.jsonl) já contêm tudo que o bench mede: passos,
provedor, ação, resultado, desfecho e resposta. Então, uma vez coletadas, toda a
avaliação e a comparação entre versões podem ser feitas no PC, offline.

Uso:
    # analisa trajetórias já copiadas para um diretório
    python3 testbed/replay/replay_trajectory.py --dir bench/trajectories

    # analisa as do gêmeo Linux (geradas pelos cenários do aparelho falso)
    python3 testbed/replay/replay_trajectory.py --dir linux/data/trajectories

    # baixa as do aparelho (único passo que precisa de adb) e analisa
    python3 testbed/replay/replay_trajectory.py --pull-from-device --dir /tmp/traj

    # confere contra os critérios da suíte bench/scenarios.json
    python3 testbed/replay/replay_trajectory.py --dir /tmp/traj --check-scenarios

    # compara duas coletas (antes/depois de uma mudança)
    python3 testbed/replay/replay_trajectory.py --compare /tmp/traj_antes /tmp/traj_depois
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
PKG = "com.xzero.agent"


def sh(cmd: str, timeout: int = 90) -> str:
    try:
        return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout).stdout
    except subprocess.TimeoutExpired:
        return ""


def pull_from_device(dest: str) -> int:
    adb = os.environ.get("ADB", "adb")
    serial = os.environ.get("ANDROID_SERIAL")
    prefix = f"{adb} -s {serial}" if serial else adb
    os.makedirs(dest, exist_ok=True)
    names = [n.strip() for n in sh(f'{prefix} shell "run-as {PKG} ls files/trajectories"').splitlines() if n.strip().endswith(".jsonl")]
    for n in names:
        raw = sh(f'{prefix} shell "run-as {PKG} cat files/trajectories/{n}"')
        if raw.strip():
            with open(os.path.join(dest, n), "w", encoding="utf-8") as f:
                f.write(raw)
    print(f"baixadas {len(names)} trajetória(s) para {dest}")
    return len(names)


def read_session(path: str) -> list[dict]:
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except Exception:
                pass
    return out


def summarize(path: str) -> dict:
    ev = read_session(path)
    start = next((e["data"] for e in ev if e.get("type") == "session_start"), {})
    end = next((e["data"] for e in ev if e.get("type") == "session_end"), {})
    steps = [e["data"] for e in ev if e.get("type") == "agent_step"]
    approvals = [e["data"] for e in ev if e.get("type") == "approval_event"]

    providers = [s.get("provider", "") for s in steps]
    used_llm = any(str(p).startswith(("gemini", "claude", "openai")) for p in providers)
    lat = (end.get("ended_at", 0) - start.get("started_at", 0)) if start and end else -1
    answer = end.get("answer", "")
    return {
        "file": os.path.basename(path),
        "task": start.get("task", ""),
        "passos": len(steps),
        "acoes": [s.get("action") for s in steps],
        "providers": providers,
        "usou_llm": used_llm,
        "latencia_ms": lat,
        "desfecho": end.get("outcome", "(sem session_end)"),
        "verificado": bool(end.get("goal_verified")),
        "declarou_done": bool(end.get("agent_declared_done")),
        "motivo_verificacao": end.get("verification_reason", ""),
        "resposta": answer,
        "palavras_resposta": len(str(answer).split()),
        "aprovacoes": len(approvals),
        "incompleta": not end,
    }


def load_dir(d: str) -> list[dict]:
    if not os.path.isdir(d):
        print(f"diretório não encontrado: {d}")
        return []
    files = sorted(f for f in os.listdir(d) if f.endswith(".jsonl"))
    return [summarize(os.path.join(d, f)) for f in files]


def pct(values: list[float], p: float) -> float:
    if not values:
        return -1
    s = sorted(values)
    k = min(len(s) - 1, int(round((p / 100.0) * (len(s) - 1))))
    return s[k]


def aggregate(sessions: list[dict]) -> dict:
    lats = [s["latencia_ms"] for s in sessions if s["latencia_ms"] >= 0]
    return {
        "sessoes": len(sessions),
        "verificadas": sum(1 for s in sessions if s["verificado"]),
        "declarou_done_sem_verificar": sum(1 for s in sessions if s["declarou_done"] and not s["verificado"]),
        "incompletas": sum(1 for s in sessions if s["incompleta"]),
        "sem_llm": sum(1 for s in sessions if not s["usou_llm"]),
        "passos_medio": round(sum(s["passos"] for s in sessions) / len(sessions), 2) if sessions else 0,
        "latencia_p50": pct(lats, 50),
        "latencia_p90": pct(lats, 90),
    }


def print_report(sessions: list[dict], title: str) -> None:
    agg = aggregate(sessions)
    print(f"\n╔═ {title}")
    for s in sessions:
        marca = "✅" if s["verificado"] else ("⏳" if s["incompleta"] else "⚠️ ")
        print(f"║ {marca} {s['task'][:52]:<52} {s['passos']:>2} passo(s) {s['latencia_ms']:>7}ms  {s['desfecho']}")
        if not s["verificado"] and s["motivo_verificacao"]:
            print(f"║      ↳ {s['motivo_verificacao'][:100]}")
    print(f"╟─ {agg['sessoes']} sessões | verificadas {agg['verificadas']} | "
          f"done sem verificação {agg['declarou_done_sem_verificar']} | sem LLM {agg['sem_llm']} | "
          f"incompletas {agg['incompletas']}")
    print(f"╚═ passos médio {agg['passos_medio']} | latência p50 {agg['latencia_p50']}ms p90 {agg['latencia_p90']}ms")


def check_scenarios(sessions: list[dict]) -> int:
    """Confere as sessões contra os critérios de bench/scenarios.json, casando pelo prompt."""
    path = os.path.join(REPO_ROOT, "bench", "scenarios.json")
    with open(path, encoding="utf-8") as f:
        suite = json.load(f)
    falhas = 0
    print("\n╔═ Critérios de bench/scenarios.json aplicados offline")
    for c in suite.get("cenarios", []):
        prompt = c["prompt"].lower()
        sess = next((s for s in sessions if prompt in s["task"].lower()), None)
        if not sess:
            print(f"║ ⊘ {c['id']}: nenhuma trajetória com o prompt '{c['prompt']}'")
            continue
        exp = c.get("espera", {})
        problemas = []
        if "max_passos" in exp and sess["passos"] > exp["max_passos"]:
            problemas.append(f"passos {sess['passos']}>{exp['max_passos']}")
        if "max_ms" in exp and not (0 <= sess["latencia_ms"] <= exp["max_ms"]):
            problemas.append(f"latência {sess['latencia_ms']}>{exp['max_ms']}ms")
        if exp.get("sem_llm") and sess["usou_llm"]:
            problemas.append("usou LLM onde devia ser local")
        if "max_palavras_resposta" in exp and sess["palavras_resposta"] > exp["max_palavras_resposta"]:
            problemas.append(f"resposta com {sess['palavras_resposta']} palavras")
        if "resposta_contem_algum" in exp:
            low = str(sess["resposta"]).lower()
            if not any(k.lower() in low for k in exp["resposta_contem_algum"]):
                problemas.append("resposta não contém nenhuma palavra esperada")
        if "nao_pode_conter" in exp:
            low = str(sess["resposta"]).lower()
            achados = [k for k in exp["nao_pode_conter"] if k.lower() in low]
            if achados:
                problemas.append(f"resposta contém proibido {achados}")
        if problemas:
            falhas += 1
            print(f"║ ❌ {c['id']}: " + "; ".join(problemas))
        else:
            print(f"║ ✅ {c['id']}")
    print(f"╚═ {falhas} cenário(s) fora do critério")
    return falhas


def compare(dir_a: str, dir_b: str) -> None:
    a, b = aggregate(load_dir(dir_a)), aggregate(load_dir(dir_b))
    print(f"\n╔═ Comparação  A={dir_a}  B={dir_b}")
    for k in a:
        va, vb = a[k], b[k]
        seta = "→" if va == vb else ("↑" if (isinstance(vb, (int, float)) and vb > va) else "↓")
        print(f"║ {k:<32} {va:>10}  {seta}  {vb:>10}")
    print("╚═ fim")


def main() -> int:
    ap = argparse.ArgumentParser(description="Análise offline de trajetórias do agente")
    ap.add_argument("--dir", help="diretório com *.jsonl de trajetória")
    ap.add_argument("--pull-from-device", action="store_true", help="baixa as trajetórias do aparelho via adb antes de analisar")
    ap.add_argument("--check-scenarios", action="store_true", help="aplica os critérios de bench/scenarios.json")
    ap.add_argument("--compare", nargs=2, metavar=("DIR_A", "DIR_B"), help="compara duas coletas")
    args = ap.parse_args()

    if args.compare:
        compare(*args.compare)
        return 0
    if not args.dir:
        ap.error("informe --dir (ou --compare A B)")
    if args.pull_from_device:
        pull_from_device(args.dir)

    sessions = load_dir(args.dir)
    if not sessions:
        print("nenhuma trajetória encontrada")
        return 1
    print_report(sessions, f"Trajetórias em {args.dir}")
    if args.check_scenarios:
        return 1 if check_scenarios(sessions) else 0
    return 0


if __name__ == "__main__":
    sys.exit(main())

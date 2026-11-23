#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
X0 / X-Zero (Linux Edition) — RuleEngine & Pre-Execution CUA Gatekeeper
Persists standing rules in data/user_rules.json (100% compatible with Android & Windows editions).
"""

import json
import os
import time
from typing import Dict, List, Optional, Set


class EnforcementMode:
    BLOCK_ALWAYS = "BLOCK_ALWAYS"       # Hard-blocks execution without even asking
    CONFIRM_ALWAYS = "CONFIRM_ALWAYS"   # Intercepts before execution and requires explicit confirmation
    BEHAVIOR_PROMPT = "BEHAVIOR_PROMPT" # Standing behavioral rule injected into system prompt


class RuleEngine:
    def __init__(self, data_dir: str):
        self.data_dir = data_dir
        os.makedirs(self.data_dir, exist_ok=True)
        self.rules_file = os.path.join(self.data_dir, "user_rules.json")
        self.rules: List[Dict] = []
        self.session_approved_keys: Set[str] = set()
        self.load()
        if not self.rules:
            self.seed_default_rules()

    def clear_session_approvals(self) -> None:
        self.session_approved_keys.clear()

    def grant_session_approval(self, key: str) -> None:
        self.session_approved_keys.add(key.lower().strip())

    def seed_default_rules(self) -> None:
        now_str = time.strftime("%Y-%m-%d %H:%M")
        self.rules = [
            {
                "id": "rule_no_whatsapp_delete",
                "title": "Nunca apagar nada no WhatsApp",
                "instruction": "PROIBIDO apagar mensagens, conversas, mídias ou clicar em 'Limpar' / 'Apagar' dentro do WhatsApp Desktop/Web.",
                "mode": EnforcementMode.BLOCK_ALWAYS,
                "targetPackages": ["whatsapp", "web.whatsapp.com", "zap"],
                "triggerKeywords": ["apagar", "excluir", "deletar", "limpar", "limpar tudo", "remover", "lixeira", "delete", "clear"],
                "enabled": True,
                "createdAt": now_str,
            },
            {
                "id": "rule_confirm_deletions",
                "title": "Confirmar antes de apagar/excluir arquivos ou comandos destrutivos",
                "instruction": "SEMPRE pedir confirmação explícita ao usuário antes de apagar, excluir, deletar, rodar rm/trash ou mover para a lixeira qualquer vídeo, foto, documento ou pasta, informando exatamente quais itens e tamanhos serão afetados.",
                "mode": EnforcementMode.CONFIRM_ALWAYS,
                "targetPackages": ["*"],
                "triggerKeywords": [
                    "excluir", "apagar", "deletar", "lixeira", "mover para a lixeira",
                    "esvaziar lixeira", "rm ", "rm -", "unlink", "shred", "gio trash",
                    "trash-put", "delete", "remove", "formatar"
                ],
                "enabled": True,
                "createdAt": now_str,
            },
            {
                "id": "rule_confirm_payments",
                "title": "Confirmar pagamentos, Pix e transações",
                "instruction": "SEMPRE pedir autorização explícita ao usuário antes de clicar em botões de pagar, transferir, confirmar Pix, comprar ou assinar qualquer serviço.",
                "mode": EnforcementMode.CONFIRM_ALWAYS,
                "targetPackages": ["*"],
                "triggerKeywords": ["pagar", "confirmar pagamento", "enviar pix", "transferir", "finalizar compra", "comprar agora"],
                "enabled": True,
                "createdAt": now_str,
            },
            {
                "id": "rule_natural_voice_no_name_spam",
                "title": "Fala natural sem repetir 'usuário'",
                "instruction": "Fale de forma natural, direta e fluida. NÃO repita a palavra 'usuário' em todas as frases e não termine toda resposta com 'O que temos para agora?'.",
                "mode": EnforcementMode.BEHAVIOR_PROMPT,
                "targetPackages": ["*"],
                "triggerKeywords": [],
                "enabled": True,
                "createdAt": now_str,
            },
            {
                "id": "rule_silent_third_party_conversations",
                "title": "Silêncio em conversas com outras pessoas",
                "instruction": "Quando o usuário estiver conversando com outras pessoas (ex: ContatoFamiliar, equipe, reuniões) e NÃO chamar 'X0'/'X-Zero', NUNCA interrompa falando em voz alta. Se captar um lembrete importante, use apenas post_notification com silent=true ou remember_fact.",
                "mode": EnforcementMode.BEHAVIOR_PROMPT,
                "targetPackages": ["*"],
                "triggerKeywords": [],
                "enabled": True,
                "createdAt": now_str,
            },
            {
                "id": "rule_wait_and_verify_backup_before_move",
                "title": "Em backups/mover arquivos: verificar destino antes de remover origem",
                "instruction": "Estilo CUA/ChatGPT: quando o pedido for mover vídeos/arquivos para o Google Drive ou nuvem, primeiro liste os arquivos exatos com tamanho (scan_storage_files), crie a pasta de destino, inicie o upload e NUNCA apague os arquivos originais antes que o upload esteja 100% confirmado e autorizado pelo usuário.",
                "mode": EnforcementMode.BEHAVIOR_PROMPT,
                "targetPackages": ["*"],
                "triggerKeywords": [],
                "enabled": True,
                "createdAt": now_str,
            },
        ]
        self.save()

    def get_all_rules(self) -> List[Dict]:
        return list(self.rules)

    def get_active_rules(self) -> List[Dict]:
        return [r for r in self.rules if r.get("enabled", True)]

    def add_or_update_rule(
        self,
        title: str,
        instruction: str,
        mode_str: str = "BEHAVIOR_PROMPT",
        target_pkg: str = "*",
        keywords_csv: str = "",
        rule_id: Optional[str] = None,
    ) -> str:
        clean_title = title.strip() or "Regra Personalizada"
        clean_inst = instruction.strip()
        if not clean_inst:
            return "erro: instrução da regra vazia"

        m_up = (mode_str or "").upper().strip()
        if "BLOCK" in m_up or "PROIB" in m_up or "NUNCA" in m_up:
            mode = EnforcementMode.BLOCK_ALWAYS
        elif "CONFIRM" in m_up or "PERGUNT" in m_up or "CRITIC" in m_up:
            mode = EnforcementMode.CONFIRM_ALWAYS
        else:
            inst_low = clean_inst.lower()
            if ("nunca apague" in inst_low or "proibido apagar" in inst_low) and "whatsapp" in inst_low:
                mode = EnforcementMode.BLOCK_ALWAYS
            elif any(k in inst_low for k in ["confirme", "confirmar", "perguntar antes", "apagar", "excluir", "deletar"]):
                mode = EnforcementMode.CONFIRM_ALWAYS
            else:
                mode = EnforcementMode.BEHAVIOR_PROMPT

        pkgs = [p.strip().lower() for p in (target_pkg or "*").split(",") if p.strip()] or ["*"]
        if "whatsapp" in clean_inst.lower() and pkgs == ["*"]:
            pkgs = ["whatsapp", "web.whatsapp.com"]

        kws = [k.strip().lower() for k in (keywords_csv or "").split(",") if k.strip()]
        if not kws and mode != EnforcementMode.BEHAVIOR_PROMPT:
            inferred = []
            low = f"{clean_title} {clean_inst}".lower()
            if any(x in low for x in ["apagar", "excluir", "deletar", "lixeira", "rm"]):
                inferred.extend(["apagar", "excluir", "deletar", "lixeira", "rm ", "trash"])
            if any(x in low for x in ["pagar", "pix", "transferir", "compra"]):
                inferred.extend(["pagar", "pix", "transferir", "comprar"])
            kws = list(dict.fromkeys(inferred))

        existing_idx = -1
        for idx, r in enumerate(self.rules):
            if (rule_id and r.get("id", "").lower() == rule_id.lower()) or r.get("title", "").lower() == clean_title.lower():
                existing_idx = idx
                break

        final_id = (
            self.rules[existing_idx]["id"]
            if existing_idx >= 0
            else (rule_id or f"rule_{int(time.time()) % 100000}")
        )
        new_rule = {
            "id": final_id,
            "title": clean_title,
            "instruction": clean_inst,
            "mode": mode,
            "targetPackages": pkgs,
            "triggerKeywords": kws,
            "enabled": True,
            "createdAt": time.strftime("%Y-%m-%d %H:%M"),
        }
        if existing_idx >= 0:
            self.rules[existing_idx] = new_rule
            self.save()
            return f"Regra atualizada com sucesso: [{final_id}] '{clean_title}' (modo={mode})"
        else:
            self.rules.append(new_rule)
            self.save()
            return f"Nova regra salva e ativada: [{final_id}] '{clean_title}' (modo={mode})"

    def toggle_rule(self, id_or_title: str, enabled: Optional[bool] = None) -> str:
        q = (id_or_title or "").strip().lower()
        for r in self.rules:
            if r["id"].lower() == q or q in r["title"].lower():
                r["enabled"] = enabled if enabled is not None else (not r.get("enabled", True))
                self.save()
                state_str = "ATIVADA" if r["enabled"] else "DESATIVADA"
                return f"Regra '{r['title']}' ({r['id']}) agora está {state_str}."
        return f"Regra '{id_or_title}' não encontrada."

    def remove_rule(self, id_or_title: str) -> str:
        q = (id_or_title or "").strip().lower()
        for idx, r in enumerate(self.rules):
            if r["id"].lower() == q or q in r["title"].lower():
                removed = self.rules.pop(idx)
                self.save()
                return f"Regra '{removed['title']}' removida com sucesso."
        return f"Regra '{id_or_title}' não encontrada."

    def evaluate_action_before_execution(
        self,
        action_name: str,
        target_description: str,
        current_app: str,
        current_task: str,
    ) -> Dict:
        desc_low = (target_description or "").lower()
        app_low = (current_app or "").lower()
        task_low = (current_task or "").lower()

        if any(safe_w in desc_low for safe_w in ["cancelar", "voltar", "fechar", "não", "nao", "manter"]):
            return {"allowed": True, "requiresConfirmation": False, "rule": None, "message": "ok"}

        for rule in self.get_active_rules():
            if rule["mode"] == EnforcementMode.BEHAVIOR_PROMPT:
                continue
            pkg_match = any(
                p == "*" or p in app_low or app_low in p
                for p in rule.get("targetPackages", ["*"])
            )
            if not pkg_match:
                continue

            matched_kw = next((kw for kw in rule.get("triggerKeywords", []) if kw and kw in desc_low), None)
            if not matched_kw:
                continue

            if rule["mode"] == EnforcementMode.BLOCK_ALWAYS:
                return {
                    "allowed": False,
                    "requiresConfirmation": False,
                    "rule": rule,
                    "message": f"🛑 BLOQUEADO PELA REGRA '{rule['title']}': {rule['instruction']} (Alvo interceptado: '{target_description}')",
                }

            if rule["mode"] == EnforcementMode.CONFIRM_ALWAYS:
                approval_key = f"{rule['id']}:{app_low}:{matched_kw}"
                explicitly_authorized = any(
                    phrase in task_low
                    for phrase in ["confirmo apagar", "pode apagar", "autorizo apagar", "confirmo excluir"]
                )
                if approval_key in self.session_approved_keys or explicitly_authorized:
                    return {"allowed": True, "requiresConfirmation": False, "rule": rule, "message": "aprovado"}
                return {
                    "allowed": False,
                    "requiresConfirmation": True,
                    "rule": rule,
                    "message": f"Confirmação de Segurança ({rule['title']}): Posso executar '{target_description}'? Responda 'sim' para confirmar ou 'não' para cancelar.",
                }

        return {"allowed": True, "requiresConfirmation": False, "rule": None, "message": "ok"}

    def get_prompt_rules_block(self) -> str:
        active = self.get_active_rules()
        if not active:
            return ""
        lines = ["REGRAS PERMANENTES E GUARDRAILS DO USUÁRIO (OBRIGATÓRIO SEGUIR COM PRIORIDADE MÁXIMA):"]
        for r in active:
            tag = {
                EnforcementMode.BLOCK_ALWAYS: "[BLOQUEIO ABSOLUTO]",
                EnforcementMode.CONFIRM_ALWAYS: "[CONFIRMAÇÃO OBRIGATÓRIA ANTES DE EXECUTAR]",
            }.get(r["mode"], "[COMPORTAMENTO]")
            lines.append(f"• {tag} {r['title']}: {r['instruction']}")
        return "\n".join(lines)

    def list_rules_summary(self) -> str:
        if not self.rules:
            return "Nenhuma regra configurada."
        lines = []
        for r in self.rules:
            st = "✅ ATIVA" if r.get("enabled", True) else "⏸️ DESATIVADA"
            m = {
                EnforcementMode.BLOCK_ALWAYS: "🛑 BLOQUEIO",
                EnforcementMode.CONFIRM_ALWAYS: "⚠️ CONFIRMAR",
            }.get(r["mode"], "🧠 COMPORTAMENTO")
            lines.append(f"• [{r['id']}] {st} ({m}) {r['title']}: {r['instruction']}")
        return "\n".join(lines)

    def save(self) -> None:
        try:
            with open(self.rules_file, "w", encoding="utf-8") as f:
                json.dump(self.rules, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    def load(self) -> None:
        if not os.path.exists(self.rules_file):
            return
        try:
            with open(self.rules_file, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, list):
                    self.rules = data
        except Exception:
            pass

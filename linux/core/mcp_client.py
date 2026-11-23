#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
X0 / X-Zero — McpClientManager (JSON-RPC 2.0 MCP Connector)
"""

import json
import os
import urllib.request
from typing import Dict, List


class McpClientManager:
    def __init__(self, data_dir: str):
        self.data_dir = data_dir
        os.makedirs(self.data_dir, exist_ok=True)
        self.mcp_file = os.path.join(self.data_dir, "mcp_servers.json")
        self.servers: List[Dict] = []
        self.load()
        if not self.servers:
            self.servers = [
                {
                    "name": "host-mcp",
                    "url": "http://127.0.0.1:3028/api/mcp-host",
                    "token": "",
                    "enabled": True,
                    "tools": ["host_status", "host_pm2_services"],
                }
            ]
            self.save()

    def add_or_update_server(self, name: str, url: str, token: str = "", enabled: bool = True) -> str:
        n = (name or "").strip()
        u = (url or "").strip()
        if not n or not u:
            return "erro: nome e URL do servidor MCP são obrigatórios"
        tools = self._discover_tools(u, token)
        entry = {"name": n, "url": u, "token": token, "enabled": enabled, "tools": tools}
        for i, s in enumerate(self.servers):
            if s.get("name", "").lower() == n.lower():
                self.servers[i] = entry
                self.save()
                return f"Servidor MCP '{n}' atualizado ({len(tools)} tools descobertas)"
        self.servers.append(entry)
        self.save()
        return f"Servidor MCP '{n}' conectado ({len(tools)} tools descobertas)"

    def remove_server(self, name: str) -> str:
        n = (name or "").strip().lower()
        before = len(self.servers)
        self.servers = [s for s in self.servers if s.get("name", "").lower() != n]
        self.save()
        return f"Servidor MCP '{name}' removido." if len(self.servers) < before else f"Servidor '{name}' não encontrado."

    def _discover_tools(self, url: str, token: str = "") -> List[str]:
        try:
            payload = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}}).encode("utf-8")
            headers = {"Content-Type": "application/json"}
            if token:
                headers["Authorization"] = f"Bearer {token}"
            req = urllib.request.Request(url, data=payload, headers=headers, method="POST")
            with urllib.request.urlopen(req, timeout=4) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                tools = data.get("result", {}).get("tools", [])
                return [t.get("name", "") for t in tools if t.get("name")]
        except Exception:
            return []

    def call_tool(self, server_name: str, tool_name: str, arguments: Dict) -> str:
        srv = next((s for s in self.servers if s.get("name", "").lower() == (server_name or "").lower()), None)
        if not srv and self.servers:
            srv = self.servers[0]
        if not srv:
            return "erro: nenhum servidor MCP configurado"
        try:
            payload = json.dumps(
                {
                    "jsonrpc": "2.0",
                    "id": 2,
                    "method": "tools/call",
                    "params": {"name": tool_name, "arguments": arguments or {}},
                }
            ).encode("utf-8")
            headers = {"Content-Type": "application/json"}
            if srv.get("token"):
                headers["Authorization"] = f"Bearer {srv['token']}"
            req = urllib.request.Request(srv["url"], data=payload, headers=headers, method="POST")
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                return json.dumps(data.get("result", data), ensure_ascii=False)
        except Exception as e:
            return f"erro MCP ({tool_name}): {e}"

    def get_prompt_context_summary(self) -> str:
        active = [s for s in self.servers if s.get("enabled", True)]
        if not active:
            return ""
        lines = ["SERVIDORES MCP CONECTADOS AO X0 / X-ZERO:"]
        for s in active:
            t_list = ", ".join(s.get("tools", [])) or "tools dinâmicas"
            lines.append(f"• {s['name']} ({s['url']}): [{t_list}]")
        return "\n".join(lines)

    def to_dict(self) -> Dict:
        return {"count": len(self.servers), "servers": self.servers}

    def save(self) -> None:
        try:
            with open(self.mcp_file, "w", encoding="utf-8") as f:
                json.dump(self.servers, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    def load(self) -> None:
        if not os.path.exists(self.mcp_file):
            return
        try:
            with open(self.mcp_file, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, list):
                    self.servers = data
        except Exception:
            pass

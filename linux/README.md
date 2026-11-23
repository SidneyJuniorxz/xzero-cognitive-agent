# ⚡ X0 / X-Zero — Linux Desktop Edition

Versão nativa para **Linux (X11 / Wayland / Headless)** do agente autônomo **X0 / X-Zero** (mascote **Xenon ✨**), compartilhando a mesma arquitetura cognitiva do Android e do Windows.

## 🧠 Arquitetura Incluída na Edição Linux (`linux/`)

- **`core/agent.py`**: Loop cognitivo autônomo com **GoalVerifier**, **RLHF** (reforço positivo/negativo por voz), pausa imediata do fluxo primário durante `/btw` e execução em lote (`batch`).
- **`core/rule_engine.py`**: Motor **Gatekeeper CUA Pré-Execução** (`data/user_rules.json`) com as 6 regras permanentes do usuário pré-carregadas (`BLOCK_ALWAYS`, `CONFIRM_ALWAYS`, `BEHAVIOR_PROMPT`), bloqueando exclusões no WhatsApp e exigindo confirmação explícita antes de apagar arquivos ou executar comandos destrutivos.
- **`core/linux_driver.py`**: Driver nativo Linux:
  - Telemetria em tempo real via `/proc/meminfo`, `/proc/uptime`, `/proc/loadavg`, `/sys/class/power_supply` e `/sys/class/thermal`.
  - Leitura de janelas e árvore UI (`wmctrl`, `xdotool`) + **Diff Semântico da Árvore (`compute_screen_diff`)**.
  - **`scan_storage_files`**: varredura direta estilo CUA/ChatGPT em `~/Videos`, `~/Downloads`, `~/Documents`, `~/Pictures` ordenando os maiores arquivos em milissegundos sem precisar abrir gerenciador gráfico.
  - Notificações nativas via `notify-send` e TTS pt-BR (`spd-say` / `espeak-ng`) com **Barge-In** real.
  - Captura de tela ao vivo (`scrot` / `import` / `grim`).
- **`core/knowledge_graph.py`**: Grafo Vetorial Híbrido 64D + Tabela `Q(s,a)` + Rotas Campeãs (`data/knowledge_graph_v2.json`), 100% compatível com o backup do celular Android.
- **`core/ambient_context.py`**: Escuta inteligente em 3 estados + aliases fonéticos (`"X0"`, `"X-Zero"`, `"XZERO"`, `"Gisele"`) + injeção paralela `/btw`.
- **`xenon_floating_pet.py`**: Widget flutuante Always-on-Top do Xenon para Linux Desktop.

## 🚀 Como Instalar e Rodar no Linux

```bash
cd linux
chmod +x install-linux.sh start-linux.sh
./install-linux.sh
./start-linux.sh
```

Acesse o **X0 / X-Zero Control Center (Linux)** em:
- `http://127.0.0.1:3031`

Para abrir também o mascote flutuante na área de trabalho:
```bash
python3 xenon_floating_pet.py
```

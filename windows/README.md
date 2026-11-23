# ⚡ X0 / X-Zero — Windows 10/11 Desktop Edition

Versão nativa para **Windows 10 / Windows 11** do agente autônomo **X0 / X-Zero** (mascote **Xenon ✨**), utilizando APIs nativas Win32 (`ctypes.windll.user32` / `kernel32`) e PowerShell `.NET` sem exigir compiladores externos.

## 🧠 Arquitetura Incluída na Edição Windows (`windows/`)

- **`core/agent.py`**: Loop cognitivo autônomo com **GoalVerifier**, **RLHF** (reforço positivo/negativo), pausa do fluxo primário durante `/btw` e execução em lote (`batch`).
- **`core/rule_engine.py`**: Motor **Gatekeeper CUA Pré-Execução** (`data\user_rules.json`) com as 6 regras permanentes do usuário pré-carregadas (`BLOCK_ALWAYS`, `CONFIRM_ALWAYS`, `BEHAVIOR_PROMPT`), bloqueando exclusões no WhatsApp e exigindo confirmação explícita antes de apagar arquivos ou mover para a Lixeira (`Recycle Bin`).
- **`core/windows_driver.py`**: Driver nativo Windows:
  - Telemetria em tempo real via Win32 `GlobalMemoryStatusEx`, `GetSystemPowerStatus` e `GetTickCount64` (Bateria %, AC Status, RAM Livre/Total, Uptime).
  - Leitura de janelas ativas via `EnumWindows` / `GetForegroundWindow` + **Diff Semântico da Árvore (`compute_screen_diff`)** e ativação de janelas via `SetForegroundWindow`.
  - Automação de teclado via `WScript.Shell.SendKeys`.
  - **`scan_storage_files`**: varredura direta estilo CUA/ChatGPT em `%USERPROFILE%\Videos`, `Downloads`, `Documents`, `Pictures` e `Desktop` ordenando os maiores arquivos em milissegundos.
  - Voz nativa Windows SAPI5 (`System.Speech.Synthesis`) com **Barge-In** real e notificações `NotifyIcon`.
  - Captura de tela ao vivo do Windows via `.NET System.Drawing.Bitmap.CopyFromScreen`.
- **`core/knowledge_graph.py`**: Grafo Vetorial Híbrido 64D + Tabela `Q(s,a)` + Rotas Campeãs (`data\knowledge_graph_v2.json`), 100% compatível com o backup do Android e do Linux.
- **`core/ambient_context.py`**: Escuta inteligente em 3 estados + aliases fonéticos (`"X0"`, `"X-Zero"`, `"XZERO"`, `"Gisele"`) + injeção paralela `/btw`.
- **`xenon_floating_pet.py`**: Widget flutuante Always-on-Top do Xenon para a área de trabalho do Windows.

## 🚀 Como Instalar e Rodar no Windows

No **PowerShell** ou **Prompt de Comando**:

```powershell
cd windows
powershell -ExecutionPolicy Bypass -File .\install-windows.ps1
.\start-windows.bat
```

O **X0 / X-Zero Control Center (Windows)** abrirá automaticamente em:
- `http://127.0.0.1:3032`

Para abrir também o mascote flutuante Always-on-Top na área de trabalho do Windows:
```powershell
python .\xenon_floating_pet.py
```

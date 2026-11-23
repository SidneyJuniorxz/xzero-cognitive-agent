#!/usr/bin/env bash
# ==============================================================================
# X0 / X-Zero (Linux Edition) — Inicializador Rápido
# ==============================================================================
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PORT="${PORT:-3031}"

echo "⚡ Iniciando X0 / X-Zero (Linux Desktop Edition) na porta $PORT..."
python3 "$DIR/xzero_linux_server.py"

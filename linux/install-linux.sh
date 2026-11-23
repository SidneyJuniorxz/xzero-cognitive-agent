#!/usr/bin/env bash
# ==============================================================================
# X0 / X-Zero (Linux Edition) — Instalador Automático
# ==============================================================================
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "🚀 Instalando X0 / X-Zero (Linux Desktop Edition) em: $DIR"

chmod +x "$DIR/xzero_linux_server.py" "$DIR/xenon_floating_pet.py" "$DIR/start-linux.sh" 2>/dev/null || true
mkdir -p "$DIR/data/trajectories"

# Cria serviço systemd --user opcional
SYSTEMD_USER_DIR="$HOME/.config/systemd/user"
mkdir -p "$SYSTEMD_USER_DIR"
cat > "$SYSTEMD_USER_DIR/xzero-linux.service" <<EOF
[Unit]
Description=X0 / X-Zero Autonomous Agent & Control Center (Linux Edition)
After=network.target

[Service]
Type=simple
WorkingDirectory=$DIR
ExecStart=/usr/bin/python3 $DIR/xzero_linux_server.py
Restart=always
RestartSec=3
Environment=PORT=3031

[Install]
WantedBy=default.target
EOF

echo "✅ Serviço systemd criado em: $SYSTEMD_USER_DIR/xzero-linux.service"
echo "👉 Para iniciar agora: ./start-linux.sh"
echo "👉 Painel Web Control Center: http://127.0.0.1:3031"

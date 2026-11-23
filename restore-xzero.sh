#!/usr/bin/env bash
# ==============================================================================
# XZERO v3 — One-Click Backup, Download, Install & Full Restore Script
# Repositório Oficial: https://github.com/<seu-usuario>/xzero-agent
# ==============================================================================
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_DIR="$PROJECT_DIR/backups"
APK_PATH="$PROJECT_DIR/releases/xzero-latest.apk"
ADB_BIN="${ADB_BIN:-$(command -v adb || echo adb)}"
PKG="com.xzero.agent"

mkdir -p "$BACKUP_DIR" "$PROJECT_DIR/releases"

echo "⚡====================================================================⚡"
echo "   XZERO v3 — Sistema de Backup, Restauração e Instalação Automática"
echo "⚡====================================================================⚡"

MODE="${1:---restore}"

# Detecta dispositivo conectado
DEVICE=$("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print $1; exit}')
if [ -z "$DEVICE" ]; then
    echo "❌ Nenhum smartphone Android detectado via ADB (USB ou Wi-Fi)."
    echo "💡 Conecte o cabo USB ou execute: $ADB_BIN connect <IP_DO_CELULAR>:5555"
    exit 1
fi
echo "📱 Dispositivo detectado: $DEVICE"

if [ "$MODE" = "--backup" ]; then
    echo "💾 Exportando backup cognitivo completo do aparelho $DEVICE..."
    "$ADB_BIN" -s "$DEVICE" shell "run-as $PKG cat files/knowledge_graph_v2.json" > "$BACKUP_DIR/knowledge_graph_v2.json" 2>/dev/null || true
    "$ADB_BIN" -s "$DEVICE" shell "run-as $PKG cat files/proactive_watches.json" > "$BACKUP_DIR/proactive_watches.json" 2>/dev/null || true
    "$ADB_BIN" -s "$DEVICE" shell "run-as $PKG cat files/mcp_servers.json" > "$BACKUP_DIR/mcp_servers.json" 2>/dev/null || true
    "$ADB_BIN" -s "$DEVICE" shell "run-as $PKG cat files/agent_memory.json" > "$BACKUP_DIR/agent_memory.json" 2>/dev/null || true
    if [ -f "$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk" ]; then
        cp -f "$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk" "$APK_PATH"
    fi
    echo "✅ Backup concluído em: $BACKUP_DIR"
    ls -lh "$BACKUP_DIR"
    exit 0
fi

# 1. Verifica ou compila o APK
if [ ! -f "$APK_PATH" ]; then
    if [ -f "$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk" ]; then
        cp -f "$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk" "$APK_PATH"
    else
        echo "🔨 Compilando APK a partir do código-fonte..."
        (cd "$PROJECT_DIR" && ./gradlew assembleDebug)
        cp -f "$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk" "$APK_PATH"
    fi
fi

# 2. Instala o APK no aparelho (ajustando limiar de espaço de armazenamento caso o celular esteja cheio)
echo "📲 Instalando APK ($APK_PATH) no dispositivo $DEVICE..."
"$ADB_BIN" -s "$DEVICE" shell "settings put global sys_storage_threshold_max_bytes 104857600 && settings put global sys_storage_threshold_percentage 1" 2>/dev/null || true
"$ADB_BIN" -s "$DEVICE" install -r "$APK_PATH"

# 3. Concede todas as permissões especiais e serviços do sistema
echo "🔐 Concedendo permissões especiais (Overlay, Microfone, Acessibilidade, Notificações)..."
"$ADB_BIN" -s "$DEVICE" shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null || true
"$ADB_BIN" -s "$DEVICE" shell pm grant "$PKG" android.permission.CAMERA 2>/dev/null || true
"$ADB_BIN" -s "$DEVICE" shell pm grant "$PKG" android.permission.READ_CONTACTS 2>/dev/null || true
"$ADB_BIN" -s "$DEVICE" shell pm grant "$PKG" android.permission.CALL_PHONE 2>/dev/null || true
"$ADB_BIN" -s "$DEVICE" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
"$ADB_BIN" -s "$DEVICE" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
"$ADB_BIN" -s "$DEVICE" shell appops set "$PKG" WRITE_SETTINGS allow 2>/dev/null || true
"$ADB_BIN" -s "$DEVICE" shell settings put secure enabled_accessibility_services "$PKG/$PKG.AgentAccessibilityService" 2>/dev/null || true
"$ADB_BIN" -s "$DEVICE" shell settings put secure accessibility_enabled 1 2>/dev/null || true
"$ADB_BIN" -s "$DEVICE" shell cmd notification allow_listener "$PKG/$PKG.NotifListener" 2>/dev/null || true

# 4. Inicia o app para garantir criação da pasta files/
"$ADB_BIN" -s "$DEVICE" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
sleep 2

# 5. Restaura Memória Vetorial, Política Q(s,a), Rotas Campeãs, Vigias e MCPs
for f in knowledge_graph_v2.json proactive_watches.json mcp_servers.json agent_memory.json; do
    if [ -s "$BACKUP_DIR/$f" ]; then
        echo "🧠 Restaurando $f no dispositivo..."
        cat "$BACKUP_DIR/$f" | "$ADB_BIN" -s "$DEVICE" shell "run-as $PKG sh -c 'cat > files/$f'"
    fi
done

# 6. Aciona healthcheck e reativa o Xenon na tela
"$ADB_BIN" -s "$DEVICE" shell "am broadcast -a $PKG.HEALTH -n $PKG/.AdbReceiver" >/dev/null 2>&1 || true

echo "✅====================================================================✅"
echo "   XZERO v3 instalado, permissões ativas e memória restaurada com sucesso!"
echo "✅====================================================================✅"

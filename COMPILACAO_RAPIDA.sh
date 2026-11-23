#!/bin/bash

echo "╔════════════════════════════════════════════════════════════╗"
echo "║   🔨 COMPILAÇÃO DO SISTEMA DE MONITORAMENTO              ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

cd .

echo "✅ PASSO 1: Limpeza"
./gradlew clean

echo ""
echo "✅ PASSO 2: Compilação"
./gradlew assembleDebug

echo ""
echo "✅ PASSO 3: Instalação"
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo ""
echo "✅ PASSO 4: Iniciar App"
adb shell am start -n com.xzero.agent/.MainActivity

echo ""
echo "✅ PASSO 5: Dashboard"
echo "Abra em seu navegador:"
echo "  http://localhost:3030/api/agent/metrics"
echo "ou"
echo "  file://./dashboard/monitoring.html"

echo ""
echo "✅ PASSO 6: Monitorar Logs (Terminal novo)"
echo "  adb logcat | grep -E 'ThinkingLogger|ErrorDetector|Metrics'"

echo ""
echo "╔════════════════════════════════════════════════════════════╗"
echo "║   ✅ COMPILAÇÃO CONCLUÍDA                                 ║"
echo "║   🔍 Acesse o dashboard para ver raciocínio em tempo real ║"
echo "╚════════════════════════════════════════════════════════════╝"

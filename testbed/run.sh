#!/bin/bash
# Banco de testes fora do celular. Uso: ./testbed/run.sh [--jvm] [--sim] [--quick]
#   (sem argumento) roda tudo
#   --jvm    só a suíte JVM sobre as fontes reais do app (Kotlin)
#   --sim    só os cenários do aparelho falso (gêmeo Linux)
set -u
cd "$(dirname "$0")/.."
ALVO="${1:---tudo}"
FALHAS=0

if [ "$ALVO" = "--tudo" ] || [ "$ALVO" = "--jvm" ]; then
  echo "╔══════════════════════════════════════════════════════════╗"
  echo "║  SUÍTE JVM — código real do app, sem aparelho            ║"
  echo "╚══════════════════════════════════════════════════════════╝"
  ./gradlew -p testbed/jvm test --console=plain || FALHAS=$((FALHAS+1))
fi

if [ "$ALVO" = "--tudo" ] || [ "$ALVO" = "--sim" ]; then
  echo
  echo "╔══════════════════════════════════════════════════════════╗"
  echo "║  CENÁRIOS — aparelho falso + gêmeo Linux                 ║"
  echo "╚══════════════════════════════════════════════════════════╝"
  python3 testbed/fakephone/run_scenario.py || FALHAS=$((FALHAS+1))
fi

echo
if [ "$FALHAS" -eq 0 ]; then
  echo "✅ testbed ok"
else
  echo "❌ $FALHAS etapa(s) com falha — veja testbed/README.md (há 1 falha esperada: bug aberto do app)"
fi
exit "$FALHAS"

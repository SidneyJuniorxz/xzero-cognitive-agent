# Xenon Lifelike — integração Android V1

## Status

**Atlas publicado e renderizador Android atualizado na branch `feat/xenon-lifelike-sprites`.** A branch ainda depende de build e teste em dispositivo antes de merge na `main`.

## Assets publicados

- `app/src/main/res/drawable/xenon_lifelike_spritesheet.png`: imagem lida pelo Android (`R.drawable.xenon_lifelike_spritesheet`).
- `assets/xenon-lifelike/xenon_lifelike_spritesheet.png`: cópia para reutilização por frontend/site.
- `assets/xenon-lifelike/manifest.json`: dimensões, ações e sequências.
- PNG com transparência, 768 × 832 px, grade de 4 × 4, 192 × 208 px por quadro, paleta otimizada de 256 cores.

## Mapeamento de estados (Android)

| Ação no `XenonSpriteView.kt` | Linha da nova folha | Animação |
| --- | --- | --- |
| `IDLE` | 0 | repouso, respiração e piscar |
| `THINK`, `DEEP_THINKING` | 1 | mão no queixo, reflexão |
| `WAITING_IMPATIENT`, `BORED_TAPPING`, `FOOT_TAPPING` | 2 | impaciência e batida do pé |
| `SUCCESS_HAPPY`, `SUCCESS_CONFIRMED` | 3 | reação de alegria/sucesso |

O código agora admite folhas com quantidades de colunas diferentes: CORE = 8 × 11, EMOTIONS = 8 × 6, WALL_DESCENT = 8 × 1, LIFELIKE = 4 × 4. Os demais estados continuam usando as folhas originais. **Sucesso confirmado permanece condicionado a evidência real, não apenas a resposta textual.**

## Checklist antes do merge

1. Sincronizar a branch, compilar o projeto Android e verificar que o recurso `xenon_lifelike_spritesheet` é carregado.
2. Conferir proporção, base dos pés e qualidade em aparelho de diferentes densidades.
3. Exercitar sequência `IDLE → THINK → EXECUTING_TASK → SUCCESS_CONFIRMED → IDLE` e `IDLE → WAITING_IMPATIENT → IDLE`.
4. Testar callbacks de fim, loops, transições, carga de GPU/memória e troca rápida de ações.
5. Validar também `RUN_RIGHT`, `RUN_LEFT` e `WALL_DESCENT` (ainda usam folhas anteriores).
6. Fazer merge somente depois do build e dos testes visuais.

## Observação sobre a arte

A versão do aplicativo usa PNG indexado otimizado para reduzir o download. A fonte RGBA e os 16 frames individuais permanecem no pacote de criação associado à conversa; não foram incluídos nesta branch. As cenas são uma primeira iteração: a naturalidade final depende de revisar alguns intermediários de movimento e calibrar timing em execução.

`main` não foi alterada.

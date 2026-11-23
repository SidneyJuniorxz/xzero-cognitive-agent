# Xenon Lifelike V2 — sprites para 36 ações Android

Branch alvo: `feat/xenon-lifelike-sprites`.

## Folhas (PNG com alpha)

- `xenon_lifelike_spritesheet.png` (4×4): idle, reflexão, impaciência, sucesso.
- `xenon_interactions_spritesheet.png` (4×4): aceno, explicação, apontar, escutar.
- `xenon_cognitive_spritesheet.png` (4×4): operação de painel holográfico, holograma, erro e dúvida.
- `xenon_motion_spritesheet.png` (8×6): corrida direita/esquerda corrigida, salto, protótipo do muro, caminhada frontal e caminhada de costas.

Todos os quadros têm `192×208 px` e fundo transparente; a corrida e as caminhadas usam escala de arte fixa por quadro, com referência de cabeça e pés ancorada na pose frontal. A corrida preserva variações de altura próprias da fase aérea, sem redimensionar o personagem. `manifest_v2.json` é o contrato completo dos 36 estados e deve ser mantido sincronizado com o enum `XenonSpriteView.Action`.

## Qualidade e limitações

- **Arte/índices validados:** dimensões, alfa, índices, loops, contagem de 36 estados.
- **Pendente:** compilar Android, percorrer cada estado, verificar GPU/memória e fluidez em dispositivo real.
- **WALL_DESCENT:** sequência temporária preserva a escala; ainda precisa de pose verdadeiramente sentada / pernas penduradas / pouso, desenhadas e revisadas.
- **RUN (corrigido):** usa uma nova sequência lateral com tronco inclinado, braços alternando e fase aérea; temporização 105 ms/quadro. Foi normalizada contra a escala do `IDLE` frontal.
- **WALK_FORWARD / WALK_BACKWARD:** linhas 4 e 5 da folha `MOTION`; 8 quadros a 145 ms/quadro. Movimento vertical positivo da bolha usa `WALK_FORWARD`, vertical negativo usa `WALK_BACKWARD`. `WALL_DESCENT` continua reservado ao comando específico do muro.
- **Voz:** comandos curtos como `anda pra frente`, `anda de costas` e `anda pra tras` acionam o movimento vertical correspondente.
- Os sprites novos devem ser aplicados somente no Android nesta etapa; dashboard/Windows/Linux exigem renderizadores próprios e estão fora deste commit.
- A `main` não deve receber merge antes da validação de arte, lógica e desempenho.

## Fontes

O atlas 4×4 V1 e a folha de interações foram baseados nos materiais visuais gerados para o Xenon; as demais folhas foram compostas em código para reutilizar o mesmo corpo, escala e identidade. `build_xenon_v2.py` reproduz a composição a partir das fontes de arte, que devem ser preservadas no pacote local de produção.
## Testes de locomoção V3

- Índices de `MOTION` preservados: linha 0 = `RUN_RIGHT`, 1 = `RUN_LEFT`, 2 = `JUMP`, 3 = `WALL_DESCENT`, 4 = `WALK_FORWARD`, 5 = `WALK_BACKWARD`.
- Folha ampliada para **1536 × 1248 px**, grade **8 × 6**, quadros **192 × 208 px**; arquivo espelhado em `assets/xenon-lifelike/`.
- O arquivo `assets/xenon-lifelike/manifest_v2.json` contém agora **38 estados**. Execute `python3 scripts/validate_xenon_sprites.py` após sincronizar a branch.
- Compilar e testar na tela real do Android antes de fazer merge: movimento lateral, frente/costas, transição para `IDLE`, animações one-shot do salto e muro e sobreposição do balão de fala.

A `main` permanece inalterada; nenhum build Android foi executado nesta entrega.

# Xenon — pixel art motion v2

## Diagnóstico do muro

`xenon_wall_descent_spritesheet.webp` tem 8 tiles de 96×104. O personagem opaco
ocupa somente 36–63 px de altura, enquanto a folha principal tem tiles de
192×208 e o personagem ocupa cerca de 198 px. Renderizar os dois tiles no mesmo
retângulo fazia o Xenon encolher cerca de 39% durante a cena do muro. Além disso,
o limite inferior do conteúdo varia entre os quadros 90 e 95, o que criava uma
oscilação de pés.

O asset permanece intacto. `XenonSpriteView` agora aplica, somente ao muro:

- escala uniforme compensatória de 1,65;
- recorte por tile correto de 96×104;
- pivô de pés por quadro (`95, 90, 93, 94, 92, 93, 95, 94`); e
- a mesma linha de chão visual usada pela folha principal.

## Pensamento

O quadro canônico é `CORE (0,5)`, a pose com a mão no queixo. Os estados são:

| Estado | Quadros | Uso |
| --- | --- | --- |
| `THINK_ENTER` | `(0,0) → (0,5)` | Início de `AgentPhase.THINKING` |
| `THINK_HOLD` | `(0,5)` | Raciocínio em curso |
| `THINK_EXIT` | `(0,5) → (0,0)` | Antes de escuta, ação, pesquisa, erro ou repouso |

Os eventos de `AgentPhase` são a fonte da animação. `EmotionEngine` continua a
definir aura e prosódia, mas não pode trocar a pose durante atividade real.

## Movimento livre

- Arrastar deixa o Xenon exatamente no ponto escolhido; não há mais snap para a borda.
- Pedidos curtos aceitam passos e destinos: direita, esquerda, cima, baixo, centro,
  canto superior direito, canto inferior esquerdo e diagonais.
- Todo deslocamento usa caminhada lateral coerente com a direção horizontal; para
  trajetos verticais, mantém a última orientação. A cena do muro só ocorre ao pedir
  explicitamente “muro”.
- O ciclo de vida ocioso só pode animar quando não há tarefa, escuta, fala, resposta
  pendente ou reconhecimento de voz ativo.

## Limitações conhecidas

A validação visual de escala é feita por inspeção dos assets e preview estático.
A reprodução final no overlay requer um aparelho Android com a permissão de sobreposição
ativa; a build pode validar compilação, mas não substitui esse teste em dispositivo.

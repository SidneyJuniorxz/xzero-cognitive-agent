# Xenon / XZero — contrato de estados visuais

O Xenon deve representar o estado real do agente e não executar gestos aleatórios durante tarefas ativas.

**Branch experimental:** `feat/xenon-lifelike-sprites`.
O Android mapeia **36 ações** para 4 folhas da identidade Lifelike V2. As folhas antigas são mantidas no repositório para retorno seguro; o dashboard/site e as edições Linux/Windows ainda precisam de atualização própria.

| Folha de sprites | Grade | Quadro | Ações |
| --- | --- | --- | --- |
| `xenon_lifelike_spritesheet.png` | 4 × 4 | 192 × 208 px | idle, pensar, impaciência e sucesso |
| `xenon_interactions_spritesheet.png` | 4 × 4 | 192 × 208 px | acenar, explicar, apontar, escutar/observar |
| `xenon_cognitive_spritesheet.png` | 4 × 4 | 192 × 208 px | holograma, execução, falha e dúvida |
| `xenon_motion_spritesheet.png` | 8 × 4 | 192 × 208 px | deslocamento, salto e protótipo de descida do muro |
| `xenon_spritesheet.png` (legado) | 8 × 11 | 192 × 208 px | fallback e consumidores antigos |
| `xenon_emotions_spritesheet.webp` (legado) | 8 × 6 | 96 × 104 px | fallback e consumidores antigos |

**Referência técnica:** `assets/xenon-lifelike/manifest_v2.json` registra cada uma das 36 ações, linha, sequência, cadência e loop. `scripts/validate_xenon_sprites.py` verifica dimensões, transparência e alinhamento com o enum Android.

**Limitadores visuais conhecidos:** corrida baseada em passos laterais acelerados e descida do muro ainda precisa de poses sentada/pendurada/aterrissagem. A substituição não deve ir para `main` sem revisão visual e build no Android.

## Estados funcionais

| Estado visível | Sequência | Fato que o autoriza |
| --- | --- | --- |
| Escutando atentamente | `LISTENING_ATTENTIVELY` | `SpeechRecognizer.onReadyForSpeech` abriu a captura atual |
| Pensando profundamente | `DEEP_THINKING` | início de um passo de raciocínio |
| Pesquisando na internet | `RESEARCHING_WEB` | ação `web_search` em execução |
| Executando uma tarefa | `EXECUTING_TASK` | ferramenta/ação já foi iniciada |
| Pedindo ajuda naturalmente | `ASKING_HELP` | `AgentUi.ask()` abriu uma pergunta ao usuário |
| Recuperando-se de um erro | `RECOVERING_ERROR` | falha, estagnação ou ciclo detectados |
| Confirmando sucesso | `SUCCESS_CONFIRMED` | `GoalVerifier.verified == true` |
| Aprendendo algo novo | `LEARNING_NEW` | memória/regra ou reforço humano foi registrado |

`ATTENTIVE_LISTENING` é rebaixado para repouso ou pensamento enquanto o reconhecedor ainda não estiver capturando áudio. Da mesma forma, uma resposta textual do agente não chama `SUCCESS_CONFIRMED`: ela só acontece quando o retorno do `GoalVerifier` confirma o objetivo.

## Ações espontâneas

O loop ocioso só roda sem tarefa ativa, sem escuta manual e no estado `IDLE_RELAXED`. Ele alterna `LOOKING_AROUND`, `RESTING`, `PLAYING_HOLOGRAM`, `FOOT_TAPPING`, `CURIOUS`, `WAVING_SPONTANEOUSLY`, `DISCOVERY_REACTION` e `DEEP_THINKING`.

O conjunto atual não tem uma pose sentada desenhada. `RESTING` é uma pausa em pé com os quadros de tédio/descanso; uma sequência literalmente sentada exige trocar ou redesenhar uma das linhas da folha de emoções, mantendo a mesma grade 8 × 6.

## Integração com o site da Host

O site expõe `window.SVSMascot.setState(state, facts)` e também aceita o evento `host:xenon-state`. Para não sinalizar algo falso, `listening` requer `{ micActive: true }` e `success` requer `{ verified: true }`:

```js
document.dispatchEvent(new CustomEvent('host:xenon-state', {
  detail: { state: 'success', verified: true, message: 'Resultado validado.' }
}));
```

A resposta comum do chat agora usa explicação, não comemoração; entregar texto não prova que uma tarefa externa foi concluída.

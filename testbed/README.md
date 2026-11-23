# Banco de testes fora do celular (testbed)

Ambiente para exercitar o agente **sem aparelho conectado, sem rede e sem gastar token**.

Regra deste diretório: **nada em `app/` é alterado** — nem o `build.gradle.kts`. O testbed
é um projeto Gradle independente que compila as *fontes reais* do app (`app/src/main/java`)
contra o `android.jar` da plataforma e as roda na JVM com o Robolectric. Arquivo novo que
aparecer no app entra na compilação sozinho, sem configuração.

## Como rodar

```bash
./testbed/run.sh
```

Partes isoladas:

```bash
./gradlew -p testbed/jvm test                      # só a suíte Kotlin
./gradlew -p testbed/jvm test -PshowLogs=true      # mostrando os logs do agente
./gradlew -p testbed/jvm test --tests GoalVerifierTest
python3 testbed/fakephone/run_scenario.py -v       # só os cenários, com log
```

Pré-requisitos: JDK 17, Python 3.10+ e o Android SDK com a plataforma 35 (só o
`android.jar`, nada de emulador). O SDK é localizado nesta ordem: `-PandroidSdk=...`,
`ANDROID_HOME`, `ANDROID_SDK_ROOT`, `sdk.dir` do `local.properties` da raiz, caminhos
usuais. Como `local.properties` é ignorado pelo git, cada checkout/worktree precisa do seu:

```bash
echo "sdk.dir=/caminho/do/android-sdk" > local.properties
```

## O que tem aqui

```
testbed/
├── jvm/                      projeto Gradle que roda o código real do app na JVM
│   ├── build.gradle.kts      compila ../../app/src/main/java + android.jar + Robolectric
│   └── src/test/kotlin/
│       ├── support/
│       │   ├── RecordingUi.kt   AgentUi de teste (grava fala/pergunta/fase/conclusão)
│       │   ├── AgentProbe.kt    acesso por reflexão às decisões privadas do Agent
│       │   └── FakeLlm.kt       LLM falso: troca o handler https da JVM e serve roteiro
│       ├── DecisoesLocaisTest.kt    atalhos locais, intenção, evidência visual, falha
│       ├── ConfirmacaoFaladaTest.kt portão de confirmação de ação irreversível
│       ├── GoalVerifierTest.kt      quando o agente pode dizer "pronto"
│       ├── ContextoAmbienteTest.kt  wake word, /btw, pontuação de intenção, hesitação
│       ├── MotorDeRegrasTest.kt     guardrails de bloqueio/confirmação
│       ├── LoopDoAgenteTest.kt      loop de ponta a ponta com LLM de roteiro
│       └── BugsConhecidosTest.kt    defeitos reais do app, ainda abertos (falham de propósito)
├── fakephone/                aparelho falso em Python
│   ├── fake_phone.py         telas (formato do dumpScreen), driver falso, LLM de roteiro
│   ├── run_scenario.py       roda cenários pelo loop real do gêmeo Linux (linux/core)
│   ├── mock_mcp.py           servidor MCP falso — serve até para o aparelho de verdade
│   ├── screens/*.json        fixtures de tela (relógio, WhatsApp)
│   └── scenarios/*.json      cenários com expectativas
├── replay/
│   └── replay_trajectory.py  análise offline das trajetórias (o bench sem celular na mão)
└── run.sh
```

## As três camadas

**1. Suíte JVM (código real do app).** 56 testes, ~5 s, nenhum aparelho. Cobre o que mais
quebra em silêncio: classificadores locais que decidem o turno antes do LLM, o portão de
confirmação falada, o GoalVerifier, o motor de regras, o motor de contexto ambiente e o
loop de decisão inteiro (JSON inválido, conversão `say`→`done`, escalonamento de modelo,
queda de provedor, bloqueio de `done` prematuro).

O LLM é substituído uma camada abaixo do app: `FakeLlm` troca o handler de `https` da JVM
(por reflexão, com `--add-opens=java.base/java.net=ALL-UNNAMED`), responde às chamadas de
Gemini/Claude/OpenAI com um roteiro, **recusa qualquer outro host** (teste nenhum toca a
rede) e guarda os requests — o que permite afirmar sobre o prompt realmente enviado.
Chamadas de *embedding* têm resposta própria, derivada do texto, para não consumir o
roteiro de decisões e tornar o teste não-determinístico.

**2. Aparelho falso + gêmeo Linux (cenários de UI).** As fixtures de tela são escritas no
mesmo formato que o `AgentAccessibilityService.dumpScreen()` produz no Android
(`App em foco: pkg` + `[id] Classe "texto" {flags} @x,y`), e cada tela declara transições
(`"tap:0": "clock_list"`). Isso roda o loop do gêmeo Python (`linux/core`), que recebe
`driver` e `llm` injetados — então um alarme criado em 4 toques leva 13 ms e zero token.
Escopo honesto: isto valida a implementação **Python**, não o Kotlin. Serve para desenhar
fluxo, prompt, guardrail e fixture de forma barata; a verificação do Kotlin é a camada 1.

**3. Replay offline de trajetórias.** O app já grava tudo que o bench mede
(`files/trajectories/*.jsonl`). Depois de coletadas uma vez, avaliação e comparação entre
versões acontecem no PC:

```bash
python3 testbed/replay/replay_trajectory.py --pull-from-device --dir /tmp/traj
python3 testbed/replay/replay_trajectory.py --dir /tmp/traj --check-scenarios
python3 testbed/replay/replay_trajectory.py --compare /tmp/traj_antes /tmp/traj_depois
```

**Bônus que funciona contra o aparelho real, sem alterar nada:** `mock_mcp.py`. O
`McpClientManager` lê a URL do servidor de disco, então basta apontar o app para o PC:

```bash
python3 testbed/fakephone/mock_mcp.py --port 8765
adb shell am broadcast -a com.xzero.agent.MCP \
    -n com.xzero.agent/.AdbReceiver \
    --es op add --es name testbed --es url http://SEU_IP:8765/mcp
```

Tem modos de falha (`--latency-ms`, `--fail-rate`, `--down`) para exercitar o cooldown e o
isolamento de servidor lento do cliente MCP.

## Achados do testbed

### 1. BUG ABERTO — exceção na auto-cura derruba a tarefa (`BugsConhecidosTest` falha)

`HealthMonitor.recordHealAttempt` chama `calculateBackoffMs(accessibilityDisableCountInWindow - 1)`.
Com o contador em 0, o argumento vira `-1` e `1 shl minOf(-1, 7)` desloca 31 bits (o shift
é mascarado em 5 bits na JVM) → `Int.MIN_VALUE`:

```
exponentialBaseMs = -2.147.483.648.000
cappedMs          = -2.147.483.648.000     (minOf com 120_000 escolhe o negativo)
random.nextLong(0, cappedMs / 2)           -> IllegalArgumentException
```

E o contador fica em 0 com frequência: `clearOldCounters()` roda no início de todo
`checkAndHeal` e o zera sempre que a última desativação registrada é mais velha que a
janela de 5 min (inclusive quando nunca houve uma). `checkAndHeal(heal = true)` roda a
**cada passo** do agente (`Agent.kt:891`), então com a acessibilidade desligada e a
reativação não confirmando na hora — o caso comum, porque o serviço não reconecta em
poucos ms — a exceção sobe e mata a tarefa, em vez de o agente seguir pelo caminho
degradado ("só ações por intent funcionam") que o próprio código prevê. O caminho de
auto-cura é justamente o que derruba a execução.

O teste exige o comportamento correto (não estourar) e fica verde quando o app for
corrigido. É a única falha esperada da suíte.

### 2. Observação — um JSON torto custa uma chamada extra de modelo forte

Quando o modelo responde fora do formato, o agente coloca a reclamação em `lastResult`
começando com `"erro:"`. No passo seguinte, o GoalVerifier lê esse prefixo como "a última
ação falhou" e barra o `done` — mesmo em tarefa conversacional, onde ação nenhuma foi
executada. Resultado: mais um passo, já escalonado para o modelo forte. Está documentado
em `LoopDoAgenteTest.json_invalido_uma_vez_se_recupera_no_passo_seguinte`, que exige a
recuperação em no máximo 3 chamadas e segue verde se o comportamento melhorar.

## O que este ambiente ainda NÃO cobre

- **Tocar em elemento `[id]` de tela no Kotlin.** `Agent` alcança a tela pelo singleton
  `AgentAccessibilityService.instance` (14 usos só em `Agent.kt`), e `dumpScreen()` não é
  `open`, então não há como injetar tela falsa sem mexer no app. Sem o serviço, o agente vê
  `"(serviço de acessibilidade desativado)"` — o que ainda exercita todo o resto do loop.
  Para fechar essa lacuna: extrair uma interface `DeviceDriver` (dumpScreen/tap/type/scroll/
  global/describeNode/captureScreenBase64/computeScreenDiff) com a implementação real
  delegando ao singleton, e recebê-la no construtor com default. As fixtures de
  `fakephone/screens/` já estão no formato certo para serem usadas no mesmo dia.
- **LLM mockado no aparelho/emulador.** As URLs dos provedores estão fixas no `Llm.kt` e
  `Prefs` não tem base URL. Um `prefs.llmBaseUrl` opcional (só debug) liberaria rodar a
  suíte inteira contra um emulador apontando para `http://10.0.2.2:porta`.
- **Camadas de hardware**: voz/TTS, sensores, câmera, overlay da bolha. Isso continua sendo
  teste em aparelho (ou emulador) — veja `tools/bench.sh`.

## Manutenção

- **Stub do `R`**: o AGP geraria o `R` a partir de `res/`; aqui uma task do Gradle gera um
  `R.kt` mínimo a partir das referências `R.<tipo>.<nome>` encontradas nas fontes. É
  automático; nenhum teste infla layout nem carrega bitmap.
- **Reflexão no `AgentProbe`**: as funções de decisão são `private` no app e o combinado é
  não alterar `app/`. Se um dia virarem `internal`, basta trocar o corpo do `AgentProbe`
  por chamadas diretas — nenhuma suíte precisa ser reescrita. Se uma função for renomeada,
  o probe falha com mensagem explícita dizendo qual nome sumiu.
- **AARs**: Robolectric precisa de `androidx.test` (`.aar`) e o app do Sherpa-ONNX; um
  projeto Kotlin/JVM puro não desempacota `.aar`, então uma task extrai o `classes.jar` de
  cada um.
- **Adicionar cenário**: copie um `fakephone/scenarios/*.json`, aponte para uma fixture de
  `screens/` e liste as decisões do modelo. Expectativas disponíveis:
  `resposta_contem_algum`, `nao_pode_conter`, `max_palavras_resposta`, `max_chamadas_llm`,
  `tela_final`, `telas_visitadas`, `texto_digitado_contem`, `apps_abertos`, `log_contem`,
  `toques_proibidos`.

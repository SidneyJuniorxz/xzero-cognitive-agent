import android.content.Context
import com.xzero.agent.McpClientManager
import com.xzero.agent.RealtimeMonitoringAPI
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Telemetria que mente é pior que telemetria ausente.
 *
 * Caso real medido no celular Android (40 sessões, 19/11): a ação `done` aparecia no painel
 * com 28,6% de acerto (25 "falhas" em 35) porque a heurística antiga era
 * `result.contains("erro")` — e o texto de SUCESSO do verificador de meta é
 * "Sequência de N etapa(s) verificada **sem erros**". Pior: o motor de insights usava esse
 * número para apontar a ação mais saudável do sistema como a mais problemática.
 *
 * Cada caso aqui é o contrato: sucesso não pode contar como falha, e falha real não pode
 * passar batido.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class TelemetriaVerdadeiraTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    // ---------- classificação de passos ----------

    @Test fun sem_erros_no_texto_de_sucesso_nao_conta_como_falha() {
        // String literal colhida de files/trajectories/ no aparelho.
        val res = "done (score=21.5, goal_verified=true, epistemic=OBSERVED, " +
            "reason=Sequência de 17 etapa(s) verificada sem erros)"
        assertFalse(
            RealtimeMonitoringAPI.classifyStepFailure("done", res),
            "'verificada sem erros' é sucesso — era o bug que derrubava o done para 28,6%"
        )
    }

    @Test fun goal_verified_false_conta_como_falha() {
        val res = "done (score=-1.0, goal_verified=false, reason=nada foi executado)"
        assertTrue(RealtimeMonitoringAPI.classifyStepFailure("done", res))
    }

    @Test fun goal_verified_vence_texto_livre_ambiguo() {
        // O sinal estruturado é a fonte da verdade; não pode ser derrubado por prosa.
        val res = "done (score=3.2, goal_verified=true, reason=nenhum erro encontrado na rota)"
        assertFalse(RealtimeMonitoringAPI.classifyStepFailure("done", res))
    }

    @Test fun erro_real_de_ferramenta_continua_sendo_falha() {
        assertTrue(
            RealtimeMonitoringAPI.classifyStepFailure(
                "mcp_call",
                "MCP [whatsapp/whatsapp_send]: Erro ao enviar (400): {\"error\":\"jid e text obrigatórios\"}"
            )
        )
        assertTrue(
            RealtimeMonitoringAPI.classifyStepFailure("open_app", "app 'Neolog' não encontrado")
        )
        assertTrue(
            RealtimeMonitoringAPI.classifyStepFailure("type", "falhou ao digitar")
        )
        assertTrue(
            RealtimeMonitoringAPI.classifyStepFailure(
                "tap",
                "erro: id 24 não existe na tela atual (total=23 elementos)"
            )
        )
    }

    @Test fun score_negativo_e_falha_mas_score_positivo_nao() {
        assertTrue(RealtimeMonitoringAPI.classifyStepFailure("tap", "ok [score=-1.5]"))
        assertFalse(RealtimeMonitoringAPI.classifyStepFailure("tap", "ok [score=0.8]"))
    }

    @Test fun acoes_terminais_degeneradas_sao_sempre_falha() {
        assertTrue(RealtimeMonitoringAPI.classifyStepFailure("unconfirmed_timeout", "Sem resposta"))
        assertTrue(RealtimeMonitoringAPI.classifyStepFailure("done_blocked", "bloqueado"))
    }

    @Test fun passo_simples_bem_sucedido_nao_e_falha() {
        assertFalse(RealtimeMonitoringAPI.classifyStepFailure("home", "ok"))
        assertFalse(RealtimeMonitoringAPI.classifyStepFailure("tap", "ok | tela mudou"))
    }

    // ---------- validação de argumentos MCP ----------

    @Test fun argumento_obrigatorio_ausente_e_pego_antes_da_rede() {
        val tool = McpClientManager.McpToolInfo(
            name = "whatsapp_send",
            description = "envia",
            requiredArgs = listOf("jid", "text"),
            acceptedArgs = listOf("jid", "text", "quoted_id")
        )
        // Exatamente o que o agente mandou 3x seguidas no aparelho.
        val erro = tool.validationError(JSONObject().put("recipient", "ColegaEquipe").put("text", "oi"))
        assertNotNull(erro, "chamada sem 'jid' tinha de ser barrada localmente")
        assertTrue(erro.contains("jid"), "a mensagem precisa dizer QUAL argumento falta: $erro")
        assertTrue(erro.contains("recipient"), "e qual argumento inválido foi enviado: $erro")
    }

    @Test fun chamada_valida_passa_sem_atrito() {
        val tool = McpClientManager.McpToolInfo(
            name = "whatsapp_send",
            description = "envia",
            requiredArgs = listOf("jid", "text"),
            acceptedArgs = listOf("jid", "text")
        )
        assertNull(tool.validationError(JSONObject().put("jid", "55...@c.us").put("text", "oi")))
    }

    @Test fun string_vazia_conta_como_argumento_ausente() {
        val tool = McpClientManager.McpToolInfo(
            name = "whatsapp_send",
            description = "envia",
            requiredArgs = listOf("jid"),
            acceptedArgs = listOf("jid", "text")
        )
        assertNotNull(tool.validationError(JSONObject().put("jid", "   ")))
    }

    @Test fun servidor_sem_schema_declarado_nao_e_engessado() {
        // Sem `required` no inputSchema, nada é bloqueado: servidor novo continua utilizável.
        val tool = McpClientManager.McpToolInfo(name = "qualquer", description = "x")
        assertNull(
            tool.validationError(JSONObject().put("foo", "bar")),
            "sem schema declarado a validação não pode inventar obrigatoriedade"
        )
    }

    @Test fun validacao_sobrevive_ao_restart_do_app() {
        // requiredArgs precisa ser persistido em mcp_servers.json, senão a proteção
        // desaparece no primeiro reinício e só volta depois de um tools/list.
        val mgr = McpClientManager(ctx)
        mgr.addOrUpdateServer("teste-mcp", "https://exemplo.invalido/mcp")
        val json = mgr.toJson()
        assertTrue(json.has("servers"), "toJson deve expor servers: $json")
    }
}

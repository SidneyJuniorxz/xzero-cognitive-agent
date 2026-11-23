#!/usr/bin/env node
/**
 * X0 / X-Zero — Unified MCP Server & Machine-to-Phone MCP Bridge
 *
 * Dual Mode:
 * 1) Stdio MCP Server (Default, for ChatGPT / Codex / Claude / Gemini Antigravity):
 *      node mcp/xzero_mcp_server.js
 *    Exposes complete tools (`xzero_*`) to inspect metrics, logs, trajectories, screen UI,
 *    and configure every internal setting, rule, watch, knowledge graph, and MCP of X0.
 *
 * 2) HTTP JSON-RPC 2.0 MCP Hub & Phone Bridge (`--http 3029`):
 *      node mcp/xzero_mcp_server.js --http 3029
 *    - Exposes `/mcp` (X0 Phone MCP over HTTP JSON-RPC 2.0)
 *    - Exposes `/mcp/host-cloud` and `/api/mcp-demo` (Cloud Backup / `upload_files` / Server Health for X0 on the phone)
 *    - Exposes `/mcp/whatsapp` (WhatsApp Bridge WhatsApp Web API MCP for X0 on the phone)
 *    - Exposes `/mcp/host-brain` (Postgres `sua_base` + Second Brain + Machine MCPs for X0 on the phone)
 *    - Automatically maintains `adb reverse tcp:3029 tcp:3029` and syncs X0's MCPs & Knowledge Graph when USB/Wi-Fi is connected.
 */

const http = require('http');
const { execFile } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const url = require('url');

// Load @modelcontextprotocol/sdk and zod from mcp/node_modules (npm install inside mcp/)
const { createRequire } = require('module');
const sdkRequire = createRequire(path.join(__dirname, 'package.json'));
const { McpServer } = sdkRequire('@modelcontextprotocol/sdk/server/mcp.js');
const { StdioServerTransport } = sdkRequire('@modelcontextprotocol/sdk/server/stdio.js');
const { z } = sdkRequire('zod');

const PKG = 'com.xzero.agent';
const ADB_CANDIDATES = [process.env.ADB_BIN, 'adb'].filter(Boolean);
const ADB_BIN = ADB_CANDIDATES.find((p) => p === 'adb' || fs.existsSync(p)) || 'adb';

const BACKUP_ROOT = process.env.XZERO_DATA_DIR || path.join(__dirname, '..', 'backups');
const DRIVE_SYNC_DIR = path.join(BACKUP_ROOT, 'drive_sync');
const CHANNEL_FILE = path.join(BACKUP_ROOT, 'gpt_xzero_channel.json');
const EVIDENCE_DIR = path.join(BACKUP_ROOT, 'evidences');

function loadChannelState() {
  try {
    if (fs.existsSync(CHANNEL_FILE)) {
      const parsed = JSON.parse(fs.readFileSync(CHANNEL_FILE, 'utf8'));
      return {
        tasks: Array.isArray(parsed.tasks) ? parsed.tasks : [],
        inbox: Array.isArray(parsed.inbox) ? parsed.inbox : []
      };
    }
  } catch (_) {}
  return { tasks: [], inbox: [] };
}

function saveChannelState(state) {
  try {
    fs.mkdirSync(BACKUP_ROOT, { recursive: true });
    state.tasks = state.tasks.slice(-100);
    state.inbox = state.inbox.slice(-150);
    fs.writeFileSync(CHANNEL_FILE, JSON.stringify(state, null, 2), { mode: 0o600 });
  } catch (_) {}
}

function publishChannelRecord({ task_id = '', state = 'completed', result = '', error = '', message = '', session_id = '', screenshot_base64 = '' } = {}) {
  const ch = loadChannelState();
  const now = new Date().toISOString();
  const tid = String(task_id || `msg_${Date.now()}_${Math.floor(Math.random() * 1000)}`).trim();
  const evidences = [];

  if (screenshot_base64 && typeof screenshot_base64 === 'string' && screenshot_base64.length > 100) {
    try {
      fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
      const cleanB64 = screenshot_base64.replace(/^data:image\/\w+;base64,/, '');
      const fname = `${tid}_${Date.now()}.jpg`;
      const fpath = path.join(EVIDENCE_DIR, fname);
      fs.writeFileSync(fpath, Buffer.from(cleanB64, 'base64'), { mode: 0o600 });
      evidences.push({ type: 'screenshot', path: fpath, created_at: now });
    } catch (_) {}
  }

  let existing = ch.tasks.find((t) => t.task_id === tid);
  if (existing) {
    existing.state = state || existing.state;
    if (result) existing.result = result;
    if (error) existing.error = error;
    if (session_id) existing.session_id = session_id;
    if (evidences.length) existing.evidences = [...(existing.evidences || []), ...evidences];
    existing.updated_at = now;
  } else {
    existing = {
      task_id: tid,
      state: state || 'completed',
      task: message || '',
      result: result || message || '',
      error: error || '',
      session_id: session_id || '',
      evidences,
      created_at: now,
      updated_at: now
    };
    ch.tasks.push(existing);
  }

  ch.inbox.push({
    id: `in_${Date.now()}_${Math.floor(Math.random() * 1000)}`,
    task_id: tid,
    state: existing.state,
    message: message || result || error || '',
    session_id: existing.session_id,
    evidences,
    read: false,
    created_at: now
  });

  saveChannelState(ch);
  return existing;
}
const STATE_CACHE_FILE = path.join(BACKUP_ROOT, 'last_phone_state.json');

const WHATSAPP_BRIDGE_URL = process.env.WHATSAPP_BRIDGE_URL || 'http://127.0.0.1:3026';
const WHATSAPP_BRIDGE_KEY = process.env.WHATSAPP_BRIDGE_KEY || '';
const WHATSAPP_BRIDGE_PORT = Number(process.env.WHATSAPP_BRIDGE_PORT || 3026);
const HTTP_PORT = Number(process.env.XZERO_MCP_PORT || 3029);
const PUBLIC_MCP_BASE_URL = (process.env.XZERO_MCP_PUBLIC_URL || 'https://xzero-mcp.example.com').replace(/\/$/, '');
const TOKEN_FILE = path.join(os.homedir(), '.cloudflared', 'xzero_mcp_token');
const crypto = require('crypto');

function loadOrCreateMcpToken() {
  if (process.env.XZERO_MCP_TOKEN) return process.env.XZERO_MCP_TOKEN.trim();
  try {
    if (fs.existsSync(TOKEN_FILE)) {
      const existing = fs.readFileSync(TOKEN_FILE, 'utf8').trim();
      if (existing.length >= 24) return existing;
    }
    const generated = crypto.randomBytes(24).toString('hex');
    fs.mkdirSync(path.dirname(TOKEN_FILE), { recursive: true });
    fs.writeFileSync(TOKEN_FILE, generated + '\n', { mode: 0o600 });
    return generated;
  } catch (_) {
    return '';
  }
}

const MCP_AUTH_TOKEN = loadOrCreateMcpToken();

fs.mkdirSync(DRIVE_SYNC_DIR, { recursive: true });

// ============================================================================
// Low-level ADB & Broadcast Helpers
// ============================================================================

function execAdb(args, options = {}) {
  return new Promise((resolve, reject) => {
    execFile(
      ADB_BIN,
      args,
      {
        timeout: options.timeout || 15000,
        maxBuffer: options.maxBuffer || 20 * 1024 * 1024,
        encoding: options.encoding || 'utf8'
      },
      (err, stdout, stderr) => {
        if (err) return reject(new Error((stderr || err.message || '').trim()));
        resolve(stdout);
      }
    );
  });
}

function execCmd(bin, args, options = {}) {
  return new Promise((resolve, reject) => {
    execFile(
      bin,
      args,
      { timeout: options.timeout || 15000, maxBuffer: 10 * 1024 * 1024, encoding: 'utf8' },
      (err, stdout, stderr) => {
        if (err) return reject(new Error((stderr || err.message || '').trim()));
        resolve(stdout);
      }
    );
  });
}

async function getActiveDevice() {
  try {
    const out = await execAdb(['devices', '-l'], { timeout: 4000 });
    const lines = out
      .split('\n')
      .map((l) => l.trim())
      .filter((l) => l && !l.startsWith('List of devices'));
    const online = lines.filter((l) => /\bdevice\b/.test(l));
    if (online.length === 0) {
      return { connected: false, serial: null, transport: 'offline', raw: lines };
    }
    const usb = online.find((l) => !l.includes(':'));
    const chosen = usb || online[0];
    const serial = chosen.split(/\s+/)[0];
    const modelMatch = chosen.match(/model:(\S+)/);
    return {
      connected: true,
      serial,
      transport: serial.includes(':') ? 'wifi' : 'usb',
      model: modelMatch ? modelMatch[1].replace(/_/g, ' ') : 'celular Android'
    };
  } catch (e) {
    return { connected: false, serial: null, transport: 'error', error: e.message };
  }
}

function adbShell(serial, shellCmd, timeout = 12000) {
  const args = serial ? ['-s', serial, 'shell', shellCmd] : ['shell', shellCmd];
  return execAdb(args, { timeout });
}

function sq(val) {
  return `'${String(val).replace(/'/g, `'\\''`)}'`;
}

async function runBroadcast(serial, actionSuffix, extras = {}, timeout = 20000) {
  let cmd = `am broadcast -a ${PKG}.${actionSuffix} -n ${PKG}/.AdbReceiver`;
  for (const [k, v] of Object.entries(extras)) {
    if (v === undefined || v === null) continue;
    if (typeof v === 'boolean') {
      cmd += ` --ez ${k} ${v ? 'true' : 'false'}`;
    } else if (typeof v === 'number' && Number.isInteger(v)) {
      cmd += ` --ei ${k} ${v}`;
    } else {
      cmd += ` --es ${k} ${sq(String(v))}`;
    }
  }
  const out = await adbShell(serial, cmd, timeout);
  const m = out.match(/data="([\s\S]*)"\s*$/);
  return { raw: out.trim(), data: m ? m[1] : null };
}

async function readAppJsonFile(serial, relPath, fallback = {}) {
  try {
    const out = await adbShell(serial, `run-as ${PKG} cat ${relPath} 2>/dev/null`);
    if (!out || !out.trim()) return fallback;
    return JSON.parse(out.trim());
  } catch (_) {
    return fallback;
  }
}

function loadStateCache() {
  try {
    if (fs.existsSync(STATE_CACHE_FILE)) {
      return JSON.parse(fs.readFileSync(STATE_CACHE_FILE, 'utf8'));
    }
  } catch (_) {}
  return {};
}

function updateStateCache(patch) {
  try {
    const cur = loadStateCache();
    const next = { ...cur, ...patch, updatedAtIso: new Date().toISOString() };
    fs.writeFileSync(STATE_CACHE_FILE, JSON.stringify(next, null, 2));
  } catch (_) {}
}

// ============================================================================
// WhatsApp Bridge WhatsApp API Client (Bridges to http://127.0.0.1:3026)
// ============================================================================

async function callWhatsappBridge(method, apiPath, body) {
  if (!WHATSAPP_BRIDGE_KEY) {
    return { status: 503, data: { error: 'WhatsApp bridge nao configurado (defina WHATSAPP_BRIDGE_URL e WHATSAPP_BRIDGE_KEY). Veja docs/WHATSAPP_BRIDGE.md' } };
  }
  const headers = { 'x-api-key': WHATSAPP_BRIDGE_KEY };
  if (body !== undefined) headers['content-type'] = 'application/json';
  const res = await fetch(`${WHATSAPP_BRIDGE_URL}${apiPath}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(30000)
  });
  const raw = await res.text();
  let data;
  try { data = JSON.parse(raw); } catch (_) { data = { raw }; }
  return { status: res.status, data };
}

// A formatação de mensagem de WhatsApp vivia aqui também, numa segunda versão que
// divergia da do MCP (fuso em UTC, sem status de transcrição). Agora o texto chega
// pronto da ponte /api/agent-tools, com fuso de São Paulo e cabeçalho de frescor.

// ============================================================================
// Machine MCP Tools Exposed TO X0 on the Phone (`/mcp/host-cloud`, `/mcp/whatsapp`, `/mcp/host-brain`)
// ============================================================================

// Catálogo de ferramentas de WhatsApp servido pelo MCP do WhatsApp Bridge (/api/agent-tools).
// Fica em memória para o hub poder responder tools/list na hora; é atualizado no boot e
// de hora em hora. O fallback existe só para o X0 não ficar sem nada se a API estiver
// fora do ar no momento da partida.
const ADAZAP_FALLBACK_TOOLS = [
  {
    name: 'whatsapp_status',
    description: 'Estado da conexão do WhatsApp e saúde da sincronização (catálogo completo indisponível no momento).',
    inputSchema: { type: 'object', properties: {} }
  }
];

let whatsappTools = ADAZAP_FALLBACK_TOOLS;

async function refreshWhatsApp BridgeTools() {
  try {
    const r = await callWhatsappBridge('GET', '/api/agent-tools');
    const tools = r.status === 200 && Array.isArray(r.data?.tools) ? r.data.tools : null;
    if (!tools || !tools.length) throw new Error(`catalogo indisponivel (HTTP ${r.status})`);
    whatsappTools = tools.map(t => ({
      name: t.name,
      description: t.description,
      inputSchema: t.inputSchema || { type: 'object', properties: {} }
    }));
    console.log(`[XZERO-MCP-HUB] catalogo whatsapp carregado: ${whatsappTools.length} ferramentas`);
  } catch (e) {
    console.error('[XZERO-MCP-HUB] falha ao carregar catalogo whatsapp:', e.message);
  }
  return whatsappTools;
}

const PHONE_FACING_SERVERS = {
  'host-cloud': {
    tools: [
      {
        name: 'upload_files',
        description: 'Transfere e faz backup de vídeos ou arquivos grandes do celular (ex: DCIM/Camera/PXL_...mp4) direto para o servidor/Google Drive da sua organização via ponte ADB/Cloud.',
        inputSchema: {
          type: 'object',
          properties: {
            files: {
              type: 'array',
              items: { type: 'string' },
              description: 'Lista de caminhos dos arquivos no celular (ex: ["DCIM/Camera/PXL_20261110.mp4"])'
            },
            destination_folder: {
              type: 'string',
              description: 'Nome da pasta de destino no backup/Drive (padrão: Videos_Camera_Backup)'
            }
          },
          required: ['files']
        }
      },
      {
        name: 'list_cloud_backups',
        description: 'Lista todos os vídeos e arquivos do celular já salvos no backup/Drive do servidor Host com tamanho em MB/GB.',
        inputSchema: {
          type: 'object',
          properties: {
            folder: { type: 'string', description: 'Subpasta opcional (ex: Videos_Camera_Backup)' }
          }
        }
      },
      {
        name: 'host_server_health',
        description: 'Retorna status completo do servidor Linux da sua organização (CPU, RAM, disco livre, uptime e túneis ativos).',
        inputSchema: {
          type: 'object',
          properties: { detail: { type: 'string' } }
        }
      },
      {
        name: 'host_quick_note',
        description: 'Salva uma nota rápida, lembrete ou registro de auditoria no servidor da sua organização.',
        inputSchema: {
          type: 'object',
          properties: {
            title: { type: 'string', description: 'Título da nota' },
            content: { type: 'string', description: 'Conteúdo detalhado' }
          },
          required: ['title', 'content']
        }
      },
      {
        name: 'xzero_publish_result',
        description: 'Publica resultado estruturado, mensagem ou screenshot de evidência na fila MCP do ChatGPT (sem abrir o app do ChatGPT na tela).',
        inputSchema: {
          type: 'object',
          properties: {
            task_id: { type: 'string', description: 'ID da tarefa recebida (opcional se for mensagem espontânea)' },
            state: { type: 'string', description: 'completed | failed | running' },
            result: { type: 'string', description: 'Resultado final ou resumo da execução' },
            error: { type: 'string', description: 'Erro ocorrido, se houver' },
            message: { type: 'string', description: 'Mensagem direta para a fila do ChatGPT' },
            session_id: { type: 'string', description: 'ID da trajetória no aparelho' },
            screenshot_base64: { type: 'string', description: 'Imagem JPEG em base64 opcional como evidência' }
          }
        }
      }
    ],
    async callTool(name, args = {}) {
      if (name === 'xzero_publish_result') {
        const rec = publishChannelRecord(args || {});
        return `Publicado no canal MCP ChatGPT<->XZero: task_id=${rec.task_id}, state=${rec.state}, evidences=${(rec.evidences || []).length}`;
      }
      if (name === 'upload_files') {
        const dev = await getActiveDevice();
        if (!dev.connected) {
          return 'erro: celular não está conectado via ADB no momento para transferir os arquivos.';
        }
        const rawFiles = Array.isArray(args.files)
          ? args.files
          : typeof args.files === 'string'
          ? args.files.split(',').map((s) => s.trim()).filter(Boolean)
          : typeof args.file === 'string'
          ? [args.file]
          : [];
        if (rawFiles.length === 0) {
          return 'erro: informe o parâmetro "files" com a lista de caminhos (ex: ["DCIM/Camera/arquivo.mp4"]).';
        }
        const folderName = String(args.destination_folder || args.folder || 'Videos_Camera_Backup').replace(/[^a-zA-Z0-9_.-]/g, '_');
        const targetDir = path.join(DRIVE_SYNC_DIR, folderName);
        fs.mkdirSync(targetDir, { recursive: true });

        const transferred = [];
        let totalBytes = 0;
        for (const f of rawFiles) {
          const cleanRel = String(f).replace(/^\/sdcard\//, '').replace(/^\/storage\/emulated\/0\//, '').replace(/^\/+/, '');
          const remotePath = `/sdcard/${cleanRel}`;
          const baseName = path.basename(cleanRel);
          const localDest = path.join(targetDir, baseName);
          try {
            await execAdb(['-s', dev.serial, 'pull', remotePath, localDest], { timeout: 120000 });
            const st = fs.statSync(localDest);
            totalBytes += st.size;
            const mb = (st.size / (1024 * 1024)).toFixed(1);
            transferred.push(`✅ ${baseName} (${mb} MB) -> ${localDest}`);
          } catch (e) {
            transferred.push(`❌ Falha em ${remotePath}: ${e.message}`);
          }
        }
        const totalMb = (totalBytes / (1024 * 1024)).toFixed(1);
        return `Upload/Backup concluído na pasta '${folderName}' (${totalMb} MB transferidos):\n` + transferred.join('\n');
      }

      if (name === 'list_cloud_backups') {
        const sub = args.folder ? path.join(DRIVE_SYNC_DIR, String(args.folder).replace(/[^a-zA-Z0-9_.-]/g, '_')) : DRIVE_SYNC_DIR;
        if (!fs.existsSync(sub)) return 'Nenhum arquivo de backup encontrado ainda.';
        const items = [];
        function walk(dir) {
          for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
            const full = path.join(dir, entry.name);
            if (entry.isDirectory()) walk(full);
            else {
              const st = fs.statSync(full);
              const mb = (st.size / (1024 * 1024)).toFixed(1);
              items.push(`- ${path.relative(DRIVE_SYNC_DIR, full)} (${mb} MB)`);
            }
          }
        }
        walk(sub);
        return items.length ? `Arquivos no backup Host (${items.length}):\n${items.join('\n')}` : 'Pasta de backup vazia.';
      }

      if (name === 'host_server_health') {
        const freeMb = Math.round(os.freemem() / 1024 / 1024);
        const totalMb = Math.round(os.totalmem() / 1024 / 1024);
        const upHrs = (os.uptime() / 3600).toFixed(1);
        const load = os.loadavg().map((n) => n.toFixed(2)).join(', ');
        return `Servidor sua organização Online | Host=${os.hostname()} | Load=${load} | Uptime=${upHrs}h | RAM Livre=${freeMb}MB/${totalMb}MB | Backups=${DRIVE_SYNC_DIR}`;
      }

      if (name === 'host_quick_note') {
        const notesFile = path.join(BACKUP_ROOT, 'host_mcp_notes.jsonl');
        const entry = { ts: new Date().toISOString(), title: args.title || 'Nota X0', content: args.content || '' };
        fs.appendFileSync(notesFile, JSON.stringify(entry) + '\n');
        return `Nota '${entry.title}' salva com sucesso em ${notesFile}.`;
      }

      return `Ferramenta desconhecida em host-cloud-mcp: ${name}`;
    }
  },

  // O WhatsApp do X0 é repasse puro para o catálogo do MCP do WhatsApp Bridge.
  // Antes as ferramentas eram reescritas à mão aqui, e o hub ficou parado em 6 enquanto
  // o MCP já servia 14 — Claude e ChatGPT viam uma coisa e o celular outra. Agora a lista
  // vem de /api/agent-tools e a execução vai para /api/agent-tools/<nome>: uma ferramenta
  // nova no MCP aparece no X0 sem tocar neste arquivo.
  'whatsapp': {
    get tools() {
      return whatsappTools;
    },
    async callTool(name, args = {}) {
      const r = await callWhatsappBridge('POST', `/api/agent-tools/${encodeURIComponent(name)}`, args || {});
      // A ponte já devolve o texto pronto; só caímos no JSON cru se algo fugir do contrato.
      if (typeof r.data?.text === 'string' && r.data.text.trim()) return r.data.text;
      if (r.status !== 200) return `Erro WhatsApp Bridge (${r.status}): ${JSON.stringify(r.data)}`;
      return JSON.stringify(r.data);
    }
  },

  'host-brain': {
    tools: [
      {
        name: 'postgres_query',
        description: 'Executa consulta SQL SELECT (somente leitura) no banco PostgreSQL sua_base da máquina Host.',
        inputSchema: {
          type: 'object',
          properties: {
            sql: { type: 'string', description: 'Query SQL SELECT (ex: SELECT table_name FROM information_schema.tables WHERE table_schema=\'public\')' }
          },
          required: ['sql']
        }
      },
      {
        name: 'second_brain_search',
        description: 'Pesquisa notas, projetos e memórias técnicas no Second Brain (Grafo multi-hop + RAG + notas locais).',
        inputSchema: {
          type: 'object',
          properties: {
            query: { type: 'string', description: 'Termo ou pergunta para pesquisar no Second Brain' }
          },
          required: ['query']
        }
      },
      {
        name: 'second_brain_remember',
        description: 'Envia e grava uma nova memória, fato, entidade ou relação estruturada no grafo do Second Brain (porta 8001).',
        inputSchema: {
          type: 'object',
          properties: {
            subject: { type: 'string', description: 'Sujeito/entidade principal (ex: "ContatoFamiliar", "X-Zero", "sua organização")' },
            relation: { type: 'string', description: 'Relação (ex: "gosta_de", "usa_tecnologia", "decidiu", "relacionado_a")' },
            target: { type: 'string', description: 'Alvo ou valor do fato (ex: "pizza de calabresa", "Cloudflare Tunnel")' },
            description: { type: 'string', description: 'Contexto adicional ou detalhes da memória' },
            subject_type: { type: 'string', description: 'Tipo: projeto|pessoa|ideia|empresa|tecnologia|conceito' },
            target_type: { type: 'string', description: 'Tipo do alvo: projeto|pessoa|ideia|empresa|tecnologia|conceito' }
          },
          required: ['subject', 'target']
        }
      },
      {
        name: 'second_brain_analyze_xenon',
        description: 'Envia telemetria/contexto do Xenon/ADA para o Second Brain analisar padrões, gargalos e sugerir melhorias com base na memória externa.',
        inputSchema: {
          type: 'object',
          properties: {
            focus: { type: 'string', description: 'Foco da análise (ex: voz/VAD, fluxo de ações, WhatsApp, memória)' },
            telemetry: { type: 'string', description: 'Resumo opcional de métricas ou logs recentes do Xenon' }
          },
          required: ['focus']
        }
      },
      {
        name: 'second_brain_proactive_assist',
        description: 'Co-piloto proativo no Second Brain: sugere continuação de tarefas (ex: caminhos de arquivos grandes ao limpar armazenamento), pesquisa tópicos para trazer à tela e verifica logs críticos.',
        inputSchema: {
          type: 'object',
          properties: {
            activity_type: { type: 'string', description: 'storage_cleanup | research | critical_log | workflow_continuation' },
            app_package: { type: 'string', description: 'Pacote Android em foco (opcional)' },
            user_action_or_query: { type: 'string', description: 'Ação iniciada pelo usuário, dúvida ou log crítico detectado' },
            large_files_summary: { type: 'string', description: 'Resumo de arquivos grandes do celular (opcional)' },
            device_metrics: { type: 'string', description: 'Telemetria do celular (opcional)' }
          },
          required: ['activity_type']
        }
      },
      {
        name: 'svs_critical_logs_check',
        description: 'Verifica logs críticos do PM2/servidor, uso de disco/RAM e lista caminhos dos maiores arquivos no servidor.',
        inputSchema: {
          type: 'object',
          properties: {
            min_mb: { type: 'number', description: 'Tamanho mínimo em MB para listar arquivos grandes (padrão: 50)' }
          }
        }
      },
      {
        name: 'machine_mcp_catalog',
        description: 'Lista todos os MCPs e integrações configurados na máquina Linux do usuário e exemplos reais que funcionaram.',
        inputSchema: { type: 'object', properties: {} }
      }
    ],
    async callTool(name, args = {}) {
      if (name === 'postgres_query') {
        const sql = String(args.sql || '').trim();
        if (!/^select\b/i.test(sql) && !/^with\b/i.test(sql)) {
          return 'erro: apenas consultas SELECT (somente leitura) são permitidas via postgres_query.';
        }
        const connUrl = process.env.XZERO_PG_URL || '';
        if (!connUrl) return 'erro: defina XZERO_PG_URL (usuario somente-leitura!) para habilitar postgres_query.';
        try {
          const out = await execCmd('psql', [connUrl, '-c', sql], { timeout: 10000 });
          return out.trim().slice(0, 2500);
        } catch (e) {
          return `Erro SQL: ${e.message}`;
        }
      }
      if (name === 'second_brain_remember') {
        const subject = String(args.subject || '').trim();
        const target = String(args.target || '').trim();
        const relation = String(args.relation || 'relacionado_a').trim();
        const description = String(args.description || '').trim();
        if (!subject || !target) return 'erro: informe subject e target para gravar no Second Brain.';
        try {
          const resp = await fetch('http://127.0.0.1:8001/api/memory/remember', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              subject,
              relation,
              target,
              description,
              subject_type: args.subject_type || 'conceito',
              target_type: args.target_type || 'conceito',
              origin: 'xzero'
            }),
            signal: AbortSignal.timeout(5000)
          });
          const data = await resp.json();
          if (!resp.ok) return `erro Second Brain HTTP ${resp.status}: ${JSON.stringify(data)}`;
          const notesFile = path.join(BACKUP_ROOT, 'host_mcp_notes.jsonl');
          fs.mkdirSync(BACKUP_ROOT, { recursive: true });
          fs.appendFileSync(notesFile, JSON.stringify({ ts: new Date().toISOString(), source: 'second_brain_remember', subject, relation, target, description }) + '\n');
          return `Memória gravada no Second Brain: ${data.triple || `${subject} -> ${target}`}`;
        } catch (e) {
          return `Erro ao gravar no Second Brain: ${e.message}`;
        }
      }
      if (name === 'second_brain_search') {
        const q = String(args.query || '').trim();
        if (!q) return 'Informe query para buscar.';
        const sections = [];
        try {
          const gsResp = await fetch(`http://127.0.0.1:8001/api/memory/graph-search?q=${encodeURIComponent(q)}&hops=2&limit=10`, { signal: AbortSignal.timeout(4000) });
          if (gsResp.ok) {
            const gs = await gsResp.json();
            if (Array.isArray(gs.entities) && gs.entities.length) {
              sections.push('Entidades Second Brain:\n' + gs.entities.slice(0, 8).map((e) => `- ${e.name} (${e.entity_type}): ${e.description || ''}`).join('\n'));
            }
            if (Array.isArray(gs.triples) && gs.triples.length) {
              sections.push('Conexões no Grafo (multi-hop):\n' + gs.triples.slice(0, 12).map((t) => `- ${t.source} --[${t.relation}]--> ${t.target}`).join('\n'));
            }
          }
          const chatResp = await fetch('http://127.0.0.1:8001/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ session_id: 'xzero-brain-bridge', content: `Busque na memória do Second Brain e responda objetivamente: ${q}` }),
            signal: AbortSignal.timeout(12000)
          });
          if (chatResp.ok) {
            const data = await chatResp.json();
            const reply = data.response || data.reply || data.answer || data.content || JSON.stringify(data);
            sections.push(`Second Brain RAG:\n${String(reply).slice(0, 1500)}`);
          }
        } catch (e) {
          sections.push(`(Aviso Second Brain API: ${e.message})`);
        }
        const notesFile = path.join(BACKUP_ROOT, 'host_mcp_notes.jsonl');
        if (fs.existsSync(notesFile)) {
          const matches = fs.readFileSync(notesFile, 'utf8').split('\n').filter(Boolean).filter((l) => l.toLowerCase().includes(q.toLowerCase()));
          if (matches.length) sections.push(`Notas locais (${matches.length}):\n${matches.slice(-5).join('\n')}`);
        }
        return sections.length ? sections.join('\n\n') : `Nenhuma informação encontrada para '${q}'.`;
      }
      if (name === 'second_brain_analyze_xenon') {
        const focus = String(args.focus || 'geral').trim();
        const telemetry = String(args.telemetry || '').trim();
        try {
          const prompt = `Analise o agente autônomo Xenon/ADA (Android) com foco em: ${focus}. ` +
            (telemetry ? `Telemetria/Logs atuais: ${telemetry.slice(0, 1500)}. ` : '') +
            'Use o contexto dos projetos do usuário no Second Brain para sugerir melhorias práticas e objetivas.';
          const chatResp = await fetch('http://127.0.0.1:8001/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ session_id: 'xenon-brain-analyzer', content: prompt }),
            signal: AbortSignal.timeout(30000)
          });
          if (!chatResp.ok) return `Erro Second Brain HTTP ${chatResp.status}`;
          const data = await chatResp.json();
          const reply = data.response || data.reply || data.answer || data.content || JSON.stringify(data);
          return String(reply).slice(0, 2200);
        } catch (e) {
          return `Erro ao consultar Second Brain: ${e.message}`;
        }
      }
      if (name === 'second_brain_proactive_assist') {
        try {
          const resp = await fetch('http://127.0.0.1:8001/api/memory/proactive-assist', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              activity_type: args.activity_type || 'workflow_continuation',
              app_package: args.app_package || '',
              user_action_or_query: args.user_action_or_query || '',
              large_files_summary: args.large_files_summary || '',
              device_metrics: args.device_metrics || ''
            }),
            signal: AbortSignal.timeout(10000)
          });
          if (!resp.ok) return `Erro Second Brain proactive-assist HTTP ${resp.status}`;
          const data = await resp.json();
          const lines = [data.screen_suggestion || ''];
          if (Array.isArray(data.recommendations) && data.recommendations.length > 1) {
            lines.push('Sugestões:\n' + data.recommendations.map((r) => `- ${r}`).join('\n'));
          }
          return lines.filter(Boolean).join('\n\n');
        } catch (e) {
          return `Erro no co-piloto proativo Second Brain: ${e.message}`;
        }
      }
      if (name === 'svs_critical_logs_check') {
        try {
          const minMb = Number(args.min_mb || 50);
          const resp = await fetch(`http://127.0.0.1:8001/api/health/critical-logs?min_mb=${minMb}`, {
            signal: AbortSignal.timeout(8000)
          });
          if (!resp.ok) return `Erro ao checar logs críticos HTTP ${resp.status}`;
          const data = await resp.json();
          const sys = data.system || {};
          const parts = [
            `Sistema: Disco Livre=${sys.disk_free_gb}GB/${sys.disk_total_gb}GB (${sys.disk_used_percent}% usado) | RAM=${sys.ram_percent}% (${sys.ram_avail_mb}MB livres) | CPU=${sys.cpu_percent}%`
          ];
          if (Array.isArray(sys.critical_alerts) && sys.critical_alerts.length) {
            parts.push('Alertas Críticos:\n' + sys.critical_alerts.map((a) => `⚠️ ${a}`).join('\n'));
          }
          if (Array.isArray(data.critical_logs) && data.critical_logs.length) {
            parts.push('Logs Críticos PM2:\n' + data.critical_logs.map((l) => `- [${l.service}] ${l.excerpt}`).join('\n'));
          }
          if (Array.isArray(data.largest_server_files) && data.largest_server_files.length) {
            parts.push('Maiores Arquivos no Servidor:\n' + data.largest_server_files.map((f) => `- ${f.path} (${f.size_mb} MB)`).join('\n'));
          }
          return parts.join('\n\n');
        } catch (e) {
          return `Erro ao verificar logs críticos: ${e.message}`;
        }
      }
      if (name === 'machine_mcp_catalog') {
        return [
          'MCPs Ativos na Máquina Linux do usuário e Conectados ao X0:',
          '1. host-cloud-mcp: upload_files (transfere vídeos/arquivos grandes do celular via ADB para backups/drive_sync), list_cloud_backups, host_server_health, host_quick_note.',
          // Lista gerada do catálogo real, para não envelhecer quando o MCP ganhar ferramenta.
          `2. whatsapp-bridge-mcp (API direta na porta 3026, sem precisar abrir a tela): ${whatsappTools.map(t => t.name).join(', ')}.`,
          '3. host-brain-mcp: postgres_query (banco sua_base na porta 5432), second_brain_search, second_brain_proactive_assist, svs_critical_logs_check, machine_mcp_catalog.'
        ].join('\n');
      }
      return `Ferramenta desconhecida em host-brain-mcp: ${name}`;
    }
  }
};

// Unified dispatcher when X0 calls `/api/mcp-demo` or `/mcp/all`
const ALL_PHONE_TOOLS = [
  ...PHONE_FACING_SERVERS['host-cloud'].tools,
  ...PHONE_FACING_SERVERS['whatsapp'].tools,
  ...PHONE_FACING_SERVERS['host-brain'].tools
];

async function callAnyPhoneFacingTool(toolName, args) {
  for (const srv of Object.values(PHONE_FACING_SERVERS)) {
    if (srv.tools.some((t) => t.name === toolName)) {
      return await srv.callTool(toolName, args);
    }
  }
  return `Ferramenta '${toolName}' não encontrada nos servidores MCP da máquina.`;
}

// ============================================================================
// Auto-Configuration & Teaching of X0 on the Phone
// ============================================================================

let lastConfiguredSerial = null;
let lastConfiguredAt = 0;

async function configureAndTeachPhoneMcp(force = false) {
  const dev = await getActiveDevice();
  if (!dev.connected) return { ok: false, error: 'Celular desconectado do ADB no momento' };

  const now = Date.now();
  if (!force && lastConfiguredSerial === dev.serial && (now - lastConfiguredAt) < 60000) {
    return { ok: true, serial: dev.serial, cached: true };
  }

  const results = [];
  // 1. Setup adb reverse for ports 3029 (MCP Hub), 3028 (Dashboard), and 3026 (WhatsApp Bridge)
  for (const port of [HTTP_PORT, 3028, WHATSAPP_BRIDGE_PORT]) {
    try {
      await execAdb(['-s', dev.serial, 'reverse', `tcp:${port}`, `tcp:${port}`], { timeout: 5000 });
      results.push(`adb reverse tcp:${port} ok`);
    } catch (e) {
      results.push(`adb reverse tcp:${port} warn: ${e.message}`);
    }
  }

  // 2. Register and sync the 3 MCP servers inside X0's McpClientManager via secure Cloudflare HTTPS + Bearer Token
  const useCloudflare = Boolean(PUBLIC_MCP_BASE_URL && MCP_AUTH_TOKEN);
  const baseUrl = useCloudflare ? PUBLIC_MCP_BASE_URL : `http://127.0.0.1:${HTTP_PORT}`;
  const mcpConfigs = [
    { name: 'host-cloud-mcp', url: `${baseUrl}/mcp/host-cloud`, token: useCloudflare ? MCP_AUTH_TOKEN : '' },
    { name: 'whatsapp-bridge-mcp', url: `${baseUrl}/mcp/whatsapp`, token: useCloudflare ? MCP_AUTH_TOKEN : '' },
    { name: 'host-brain-mcp', url: `${baseUrl}/mcp/host-brain`, token: useCloudflare ? MCP_AUTH_TOKEN : '' }
  ];

  for (const cfg of mcpConfigs) {
    try {
      const r = await runBroadcast(dev.serial, 'MCP', { op: 'add', name: cfg.name, url: cfg.url, token: cfg.token }, 20000);
      results.push(`${cfg.name}: ${r.data || r.raw}`);
    } catch (e) {
      results.push(`${cfg.name} erro: ${e.message}`);
    }
  }

  // 3. Ensure rule_74018 doesn't disable sensors and teach MCP rules & champion routes in KnowledgeGraphMemory
  try {
    await runBroadcast(dev.serial, 'RULES', {
      op: 'update',
      id: 'rule_74018',
      title: 'Ajuste de Sensibilidade de Voz sem Desligar Sensores',
      instruction: 'Quando o usuário pedir pontuação menor para ativar a voz, use configure_voice_live (ex: auto_intent_threshold=3.5) e NUNCA desligue os sensores físicos. Se pedir silêncio, use post_notification com silent=true.',
      mode: 'BEHAVIOR_PROMPT',
      package: '*',
      keywords: 'pontuação,sensibilidade,ativar'
    });
  } catch (_) {}

  const factsToTeach = [
    {
      subject: 'host-cloud-mcp',
      relation: 'como_usar_upload_backup',
      target: 'Para fazer backup ou enviar vídeos/arquivos grandes para a nuvem/Drive: 1) scan_storage_files, 2) ask_user confirmando, 3) mcp_call server="host-cloud-mcp" tool="upload_files" arguments={"files":["DCIM/Camera/..."],"destination_folder":"Videos_Camera_Backup"}',
      boost: '3.0'
    },
    {
      subject: 'whatsapp-bridge-mcp',
      relation: 'como_usar_whatsapp_api',
      target: 'Para WhatsApp sem abrir a tela use mcp_call server="whatsapp-bridge-mcp". Comece por whatsapp_recent (o que chegou agora, até 7 dias) ou whatsapp_status (checa se a sincronização está saudável antes de afirmar que algo é recente). Também há whatsapp_list_chats, whatsapp_read_chat, whatsapp_search, whatsapp_pending, whatsapp_send, whatsapp_send_media (anexo por url/base64), whatsapp_get_media (abre anexo e devolve URL temporária), whatsapp_transcribe_audio, whatsapp_mark_read e whatsapp_group_summary',
      boost: '3.0'
    },
    {
      subject: 'host-brain-mcp',
      relation: 'como_usar_banco_e_notas',
      target: 'Para consultar o banco PostgreSQL sua_base ou pesquisar notas do Second Brain: use mcp_call server="host-brain-mcp" com tool="postgres_query" ou tool="second_brain_search"',
      boost: '2.8'
    }
  ];

  for (const f of factsToTeach) {
    try {
      await runBroadcast(dev.serial, 'GRAPH', { op: 'upsert_fact', ...f });
    } catch (_) {}
  }

  const championRoutes = [
    {
      intent: 'fazer backup ou transferir vídeos grandes do celular para a nuvem Google Drive PC',
      package: 'global',
      steps: 'scan_storage_files(2.5) -> ask_user(1.5) -> mcp_call[host-cloud-mcp/upload_files](3.0) -> post_notification(1.8) -> done(3.0)',
      score: '9.6'
    },
    {
      intent: 'ver mensagens pendentes ou buscar conversa no WhatsApp rápido via MCP',
      package: 'global',
      steps: 'mcp_call[whatsapp-bridge-mcp/whatsapp_pending](2.8) -> done(3.0)',
      score: '9.5'
    },
    {
      intent: 'diminuir pontuação ou ajustar sensibilidade de ativação por voz em modo live',
      package: 'global',
      steps: 'configure_voice_live(2.5) -> done(3.0)',
      score: '9.5'
    }
  ];

  for (const rt of championRoutes) {
    try {
      await runBroadcast(dev.serial, 'GRAPH', { op: 'add_route', ...rt });
    } catch (_) {}
  }

  lastConfiguredSerial = dev.serial;
  lastConfiguredAt = now;
  return { ok: true, serial: dev.serial, results };
}

// ============================================================================
// Core Implementation of X0 Phone MCP Tools (For ChatGPT / Codex / Gemini / Claude)
// ============================================================================

async function getFullMetricsAndTelemetry(heal = false) {
  const dev = await getActiveDevice();
  if (!dev.connected) {
    const cached = loadStateCache();
    return {
      connected: false,
      note: 'Celular desconectado do ADB neste instante. Retornando último estado em cache.',
      cachedState: cached
    };
  }
  const [healthRes, cfgRes] = await Promise.all([
    runBroadcast(dev.serial, 'HEALTH', { heal: Boolean(heal) }).catch((e) => ({ error: e.message })),
    runBroadcast(dev.serial, 'GET_CONFIG').catch((e) => ({ error: e.message }))
  ]);
  let health = null;
  let config = null;
  try { if (healthRes.data) health = JSON.parse(healthRes.data); } catch (_) { health = healthRes.data; }
  try { if (cfgRes.data) config = JSON.parse(cfgRes.data); } catch (_) { config = cfgRes.data; }

  const payload = {
    connected: true,
    adb: dev,
    health,
    config
  };
  updateStateCache({ lastMetrics: payload });
  return payload;
}

async function getLogsAndTrajectories({ mode = 'both', log_limit = 100, session_id = '' } = {}) {
  const dev = await getActiveDevice();
  if (!dev.connected) {
    const cached = loadStateCache();
    return {
      connected: false,
      note: 'Celular desconectado do ADB neste instante. Retornando último cache.',
      cachedLogs: cached.lastLogs || null
    };
  }
  const result = { connected: true, serial: dev.serial };

  if (session_id) {
    const sid = path.basename(session_id).replace(/\.jsonl$/, '');
    const raw = await adbShell(dev.serial, `run-as ${PKG} cat files/trajectories/${sid}.jsonl 2>/dev/null`);
    result.sessionId = sid;
    result.events = raw
      .split('\n')
      .filter((l) => l.trim())
      .map((l) => { try { return JSON.parse(l); } catch (_) { return { raw: l }; } });
    return result;
  }

  if (mode === 'logs' || mode === 'both') {
    const limit = Math.min(400, Math.max(10, Number(log_limit) || 100));
    const out = await execAdb(['-s', dev.serial, 'logcat', '-d', '-s', 'AgentePhone:V'], { timeout: 8000 });
    result.logs = out
      .split('\n')
      .map((l) => l.trim())
      .filter((l) => l && !l.startsWith('--------- beginning of'))
      .slice(-limit);
  }

  if (mode === 'trajectories' || mode === 'both') {
    const tr = await runBroadcast(dev.serial, 'TRAJECTORY');
    result.trajectoriesSummary = tr.data || tr.raw;
  }

  updateStateCache({ lastLogs: result });
  return result;
}

function createXzeroMcpServer() {
  const server = new McpServer(
    { name: 'xzero-phone', version: '3.2.0' },
    {
      instructions:
        'Controle total do agente autônomo X0 / X-Zero (celular Android do usuário). ' +
        'Permite inspecionar métricas, sensores, memória curta de voz, logs, trajetórias RL, árvore de tela UI, ' +
        'além de modificar todas as configurações internas, regras permanentes (RuleEngine), vigias proativas, ' +
        'Grafo de Conhecimento, servidores MCP do celular e executar qualquer ferramenta ou comando do X0.'
    }
  );

  const jsonText = (obj) => ({
    content: [{ type: 'text', text: typeof obj === 'string' ? obj : JSON.stringify(obj, null, 2) }]
  });

  // 1. Metrics, Sensors, Ambient Voice & Health
  server.registerTool(
    'xzero_get_metrics',
    {
      description:
        'Retorna métricas completas em tempo real do X0 no celular: bateria, temperatura, RAM, armazenamento, sensores físicos (lux, movimento, proximidade, giroscópio, passos, postura de voz), memória curta de voz Live (score, limiar, janela, chunks), servidores MCP e diagnóstico Healthcheck.',
      inputSchema: {
        heal: z.boolean().optional().describe('Se true, executa auto-recuperação (heal) durante a leitura')
      }
    },
    async ({ heal = false }) => jsonText(await getFullMetricsAndTelemetry(heal))
  );

  // 2. Logs & Trajectories
  server.registerTool(
    'xzero_get_logs',
    {
      description:
        'Lê os logs em tempo real do X0 (logcat AgentePhone), a lista de sessões de trajetória (sess_*.jsonl com scores RL e GoalVerifier) ou os eventos completos de uma sessão específica.',
      inputSchema: {
        mode: z.enum(['both', 'logs', 'trajectories']).optional().describe('O que buscar: both (padrão), logs ou trajectories'),
        log_limit: z.number().int().min(10).max(400).optional().describe('Quantidade de linhas do logcat (padrão 100)'),
        session_id: z.string().optional().describe('Se informado (ex: sess_20261112_220517_9993), retorna o JSONL completo dessa sessão')
      }
    },
    async (args) => jsonText(await getLogsAndTrajectories(args))
  );

  // 3. Get & Set Internal Configuration
  server.registerTool(
    'xzero_get_config',
    {
      description:
        'Lê todas as configurações internas do X0: provedor LLM primário, modelos (Gemini, Claude, OpenAI), Wake Word / Modo Live, TTS (speak), som sutil, limiar de ativação por voz (auto_intent_threshold), confirmação falada pré-execução e configuração de todos os sensores físicos.',
      inputSchema: {}
    },
    async () => {
      const dev = await getActiveDevice();
      if (!dev.connected) return jsonText({ connected: false, cached: loadStateCache().lastMetrics?.config || null });
      const r = await runBroadcast(dev.serial, 'GET_CONFIG');
      try { return jsonText(JSON.parse(r.data)); } catch (_) { return jsonText(r.data || r.raw); }
    }
  );

  server.registerTool(
    'xzero_set_config',
    {
      description:
        'Altera por dentro qualquer configuração do X0 em tempo real: provedor LLM (primary), modelos, modo Live (wake_word), voz TTS (speak), som sutil (subtle_sound), sensibilidade/pontuação de ativação de voz (auto_intent_threshold, ex: 3.5 a 5.0), confirmação antes de executar (pre_exec_confirm) e modo/sensores físicos.',
      inputSchema: {
        primary: z.enum(['gemini', 'claude', 'openai']).optional().describe('Provedor LLM primário'),
        gemini_model: z.string().optional().describe('Modelo Gemini (ex: gemini-3.1-flash-lite)'),
        claude_model: z.string().optional().describe('Modelo Claude'),
        openai_model: z.string().optional().describe('Modelo OpenAI'),
        wake_word: z.boolean().optional().describe('Ativa/desativa escuta contínua Modo Live'),
        speak: z.boolean().optional().describe('Ativa/desativa resposta por voz TTS'),
        subtle_sound: z.boolean().optional().describe('Ativa/desativa efeitos sonoros sutis'),
        auto_intent_threshold: z.number().min(2.0).max(8.5).optional().describe('Pontuação de intenção para ativação automática por voz (ex: 3.5 = mais sensível, 4.8 = mais restrito)'),
        pre_exec_confirm: z.boolean().optional().describe('Exigir confirmação falada antes de executar ações em apps'),
        sensor_mode: z.enum(['normal', 'auto', 'high_alert', 'eco']).optional().describe('Modo dos sensores físicos'),
        light: z.boolean().optional().describe('Ativar sensor de luminosidade (lux)'),
        accelerometer: z.boolean().optional().describe('Ativar acelerômetro (movimento, shake, pick-up)'),
        proximity: z.boolean().optional().describe('Ativar sensor de proximidade (bolso/ouvido)'),
        gyroscope: z.boolean().optional().describe('Ativar giroscópio (postura de voz)'),
        steps: z.boolean().optional().describe('Ativar contador de passos'),
        auto_silent: z.boolean().optional().describe('Modo silencioso automático no escuro (<8 lux) ou bolso')
      }
    },
    async (args) => {
      const dev = await getActiveDevice();
      if (!dev.connected) return jsonText({ ok: false, error: 'Celular desconectado do ADB' });
      const extras = {};
      for (const [k, v] of Object.entries(args)) {
        if (v !== undefined) extras[k] = String(v);
      }
      const r = await runBroadcast(dev.serial, 'CONFIG', extras);
      const after = await runBroadcast(dev.serial, 'GET_CONFIG').catch(() => ({ data: null }));
      let parsedAfter = null;
      try { if (after.data) parsedAfter = JSON.parse(after.data); } catch (_) {}
      return jsonText({ ok: true, result: r.data || r.raw, updatedConfig: parsedAfter });
    }
  );

  // 4. Manage Permanent Rules & Guardrails (RuleEngine)
  server.registerTool(
    'xzero_manage_rules',
    {
      description:
        'Lista, cria, altera, ativa/desativa ou remove Regras Permanentes e Guardrails do X0 (RuleEngine: BLOCK_ALWAYS, CONFIRM_ALWAYS, BEHAVIOR_PROMPT).',
      inputSchema: {
        op: z.enum(['list', 'json', 'add', 'update', 'toggle', 'remove']).describe('Operação nas regras'),
        id: z.string().optional().describe('ID da regra (para update, toggle ou remove, ex: rule_confirm_deletions)'),
        title: z.string().optional().describe('Título da regra'),
        instruction: z.string().optional().describe('Instrução detalhada da regra'),
        mode: z.enum(['BLOCK_ALWAYS', 'CONFIRM_ALWAYS', 'BEHAVIOR_PROMPT']).optional().describe('Modo de aplicação da regra'),
        package: z.string().optional().describe('Pacote alvo (ex: com.whatsapp ou * para todos)'),
        keywords: z.string().optional().describe('Palavras-chave separadas por vírgula que disparam o guardrail')
      }
    },
    async (args) => {
      const dev = await getActiveDevice();
      if (!dev.connected) return jsonText({ ok: false, error: 'Celular desconectado do ADB' });
      const r = await runBroadcast(dev.serial, 'RULES', args);
      if (args.op === 'json') {
        try { return jsonText(JSON.parse(r.data)); } catch (_) {}
      }
      return jsonText(r.data || r.raw);
    }
  );

  // 5. Manage Proactive Notification Watches (ProactiveWatcher)
  server.registerTool(
    'xzero_manage_watches',
    {
      description:
        'Lista, adiciona ou remove Vigias Proativas de notificações no X0 (ex: vigiar mensagens de contatos específicos no WhatsApp, e-mails, alertas do Claude/Codex).',
      inputSchema: {
        op: z.enum(['list', 'json', 'add', 'remove']).describe('Operação nas vigias'),
        id: z.string().optional().describe('ID ou filtro da vigia para remover'),
        source: z.string().optional().describe('Fonte: whatsapp, email, claude, codex ou any'),
        filter: z.string().optional().describe('Contato, palavra-chave ou assunto a monitorar'),
        instruction: z.string().optional().describe('O que o X0 deve fazer quando a notificação chegar'),
        auto_open: z.boolean().optional().describe('Se deve abrir o app automaticamente')
      }
    },
    async (args) => {
      const dev = await getActiveDevice();
      if (!dev.connected) return jsonText({ ok: false, error: 'Celular desconectado do ADB' });
      const r = await runBroadcast(dev.serial, 'WATCHES', args);
      if (args.op === 'json') {
        try { return jsonText(JSON.parse(r.data)); } catch (_) {}
      }
      return jsonText(r.data || r.raw);
    }
  );

  // 6. Manage Knowledge Graph, Champion Routes & RLHF
  server.registerTool(
    'xzero_manage_knowledge_graph',
    {
      description:
        'Acessa e edita o Grafo Vetorial de Conhecimento, Rotas Campeãs e Tabela Q(s,a) do X0. Permite consultar fatos, ensinar novos fatos (upsert_fact), injetar rotas campeãs validadas (add_route) ou aplicar reforço RLHF (+2.5 / -2.5).',
      inputSchema: {
        op: z.enum(['summary', 'json', 'query', 'upsert_fact', 'add_route', 'rlhf', 'reload']).describe('Operação no Grafo'),
        query: z.string().optional().describe('Consulta semântica para op=query'),
        subject: z.string().optional().describe('Sujeito do fato (para op=upsert_fact)'),
        relation: z.string().optional().describe('Relação do fato (para op=upsert_fact)'),
        target: z.string().optional().describe('Valor/alvo do fato (para op=upsert_fact)'),
        boost: z.number().optional().describe('Peso do fato (0.5 a 5.0)'),
        intent: z.string().optional().describe('Descrição da tarefa para injetar Rota Campeã (op=add_route)'),
        package: z.string().optional().describe('Pacote do app para op=add_route (padrão global)'),
        steps: z.string().optional().describe('Resumo dos passos campeões (ex: scan_storage_files(2.5) -> mcp_call(3.0) -> done(3.0))'),
        score: z.number().optional().describe('Score da rota campeã (padrão 9.5)'),
        positive: z.boolean().optional().describe('Se true, aplica reforço positivo (+2.5); se false, penalidade (-2.5) em op=rlhf'),
        feedback: z.string().optional().describe('Texto do feedback ou regra de correção para op=rlhf')
      }
    },
    async (args) => {
      const dev = await getActiveDevice();
      if (!dev.connected) return jsonText({ ok: false, error: 'Celular desconectado do ADB' });
      const r = await runBroadcast(dev.serial, 'GRAPH', args);
      if (args.op === 'json') {
        try { return jsonText(JSON.parse(r.data)); } catch (_) {}
      }
      return jsonText(r.data || r.raw);
    }
  );

  // 7. Manage Phone MCP Servers (McpClientManager inside X0)
  server.registerTool(
    'xzero_manage_phone_mcps',
    {
      description:
        'Gerencia os servidores MCP conectados dentro do próprio X0 no celular (McpClientManager): lista, adiciona, sincroniza tools/list, testa chamada de tool ou configura automaticamente todos os MCPs da máquina Linux (setup_machine_mcps).',
      inputSchema: {
        op: z.enum(['list', 'json', 'add', 'remove', 'sync', 'call', 'setup_machine_mcps']).describe('Operação MCP no celular'),
        name: z.string().optional().describe('Nome do servidor MCP (para add/remove)'),
        url: z.string().optional().describe('URL JSON-RPC do servidor MCP (para add)'),
        token: z.string().optional().describe('Token Bearer opcional'),
        server: z.string().optional().describe('Servidor alvo para op=call'),
        tool: z.string().optional().describe('Ferramenta alvo para op=call'),
        args_json: z.string().optional().describe('Argumentos JSON para op=call')
      }
    },
    async (args) => {
      if (args.op === 'setup_machine_mcps') {
        return jsonText(await configureAndTeachPhoneMcp(true));
      }
      const dev = await getActiveDevice();
      if (!dev.connected) return jsonText({ ok: false, error: 'Celular desconectado do ADB' });
      const r = await runBroadcast(dev.serial, 'MCP', args, 25000);
      if (args.op === 'json') {
        try { return jsonText(JSON.parse(r.data)); } catch (_) {}
      }
      return jsonText(r.data || r.raw);
    }
  );

  // 8. Send High-Level Command / Interaction to X0
  server.registerTool(
    'xzero_send_command',
    {
      description:
        'Envia um comando direto ao X0 no celular: ask (executa uma tarefa completa), btw (injeta contexto paralelo /btw), wake (simula fala na escuta passiva), listen (abre o microfone), barge_in (interrompe a fala TTS na hora), shake (simula agitar), pickup (simula pegar da mesa), animate (anima o mascote Xenon), health (roda healthcheck) ou recover (destrava UI).',
      inputSchema: {
        type: z.enum(['ask', 'btw', 'wake', 'listen', 'barge_in', 'shake', 'pickup', 'animate', 'health', 'recover']).describe('Tipo de comando'),
        text: z.string().optional().describe('Texto da tarefa (para ask, btw ou wake)'),
        anim: z.string().optional().describe('Nome da animação para animate (WAVE, JUMP, RUN, THINK, DANCE, CELEBRATE)'),
        speech: z.string().optional().describe('Balão de fala opcional para animate')
      }
    },
    async ({ type, text = '', anim = 'WAVE', speech = '' }) => {
      const dev = await getActiveDevice();
      if (!dev.connected) return jsonText({ ok: false, error: 'Celular desconectado do ADB' });
      const mapAction = {
        ask: ['ASK', { text }],
        btw: ['BTW', { text }],
        wake: ['WAKE', { text }],
        listen: ['LISTEN', {}],
        barge_in: ['BARGE_IN', {}],
        shake: ['SHAKE', {}],
        pickup: ['PICKUP', {}],
        animate: ['ANIMATE', { anim, speech }],
        health: ['HEALTH', { heal: true }],
        recover: ['RECOVER', {}]
      };
      const [act, extras] = mapAction[type];
      const r = await runBroadcast(dev.serial, act, extras);
      return jsonText({ ok: true, command: type, response: r.data || r.raw });
    }
  );

  // 9. Execute Any Single Internal X0 Tool Directly (Deterministic Tool Execution)
  server.registerTool(
    'xzero_exec_tool',
    {
      description:
        'Executa deterministicamente qualquer ferramenta interna do X0 no celular e retorna o resultado imediato (ex: device_status, scan_storage_files, configure_voice_live, configure_sensors, post_notification, read_notifications, reply_notification, open_app, tap, type, scroll, back, home, media_control, set_alarm, mcp_list_servers, mcp_call).',
      inputSchema: {
        action: z.string().describe('Nome da ferramenta interna do X0 (ex: scan_storage_files, device_status, post_notification, open_app)'),
        arguments: z.record(z.any()).optional().describe('Objeto de argumentos da ferramenta (ex: {"min_mb": 50, "type": "video"})')
      }
    },
    async ({ action, arguments: toolArgs = {} }) => {
      const dev = await getActiveDevice();
      if (!dev.connected) return jsonText({ ok: false, error: 'Celular desconectado do ADB' });
      const r = await runBroadcast(dev.serial, 'EXEC_TOOL', {
        action,
        args_json: JSON.stringify(toolArgs || {})
      }, 35000);
      return jsonText({ ok: true, action, result: r.data || r.raw });
    }
  );

  // 10. Inspect Live Phone Screen Accessibility Tree
  server.registerTool(
    'xzero_inspect_screen',
    {
      description:
        'Lê a árvore de acessibilidade da tela atual do celular em tempo real (pacote em foco e todos os elementos interativos numerados [id]), permitindo ver exatamente o que está na tela do celular Android.',
      inputSchema: {}
    },
    async () => {
      const dev = await getActiveDevice();
      if (!dev.connected) return jsonText({ ok: false, error: 'Celular desconectado do ADB' });
      const r = await runBroadcast(dev.serial, 'SCREEN_TREE');
      try { return jsonText(JSON.parse(r.data)); } catch (_) { return jsonText(r.data || r.raw); }
    }
  );

  // 11. Structured Channel: Dispatch Task with task_id & Persistent Queue
  server.registerTool(
    'xzero_dispatch_task',
    {
      description:
        'Envia uma missão estruturada ao XZero com task_id persistente (sem digitar na UI do ChatGPT). O XZero publica resultado e evidências na fila ao concluir.',
      inputSchema: {
        task: z.string().describe('Missão/objetivo completo para o XZero executar no celular'),
        wait_ms: z.number().optional().describe('Tempo opcional (ms, máx 15000) para aguardar resposta imediata na fila')
      }
    },
    async ({ task, wait_ms = 0 }) => {
      const tid = `task_${Date.now()}_${Math.floor(100 + Math.random() * 900)}`;
      const now = new Date().toISOString();
      const ch = loadChannelState();
      const entry = {
        task_id: tid,
        state: 'queued',
        task,
        result: '',
        error: '',
        session_id: '',
        evidences: [],
        created_at: now,
        updated_at: now
      };
      ch.tasks.push(entry);
      saveChannelState(ch);

      const dev = await getActiveDevice();
      if (!dev.connected) {
        return jsonText({ ok: true, queued: true, task_id: tid, state: 'queued', note: 'Celular desconectado do ADB no momento; missão salva na fila persistente.' });
      }

      const r = await runBroadcast(dev.serial, 'ASK', { text: `[TASK_ID:${tid}] ${task}` });
      const ch2 = loadChannelState();
      const tObj = ch2.tasks.find((x) => x.task_id === tid);
      if (tObj && tObj.state === 'queued') {
        tObj.state = 'running';
        tObj.updated_at = new Date().toISOString();
        saveChannelState(ch2);
      }

      const waitCap = Math.min(Math.max(Number(wait_ms) || 0, 0), 15000);
      if (waitCap > 0) {
        const deadline = Date.now() + waitCap;
        while (Date.now() < deadline) {
          await new Promise((res) => setTimeout(res, 750));
          const latest = loadChannelState().tasks.find((x) => x.task_id === tid);
          if (latest && (latest.state === 'completed' || latest.state === 'failed')) {
            return jsonText({ ok: true, ...latest });
          }
        }
      }
      return jsonText({ ok: true, task_id: tid, state: 'running', dispatch: r.data || r.raw });
    }
  );

  // 12. Structured Channel: Query Task Status
  server.registerTool(
    'xzero_task_status',
    {
      description: 'Consulta o estado padronizado (task_id, state, result, error, evidences) de uma missão ou lista as últimas missões do canal.',
      inputSchema: {
        task_id: z.string().optional().describe('ID da tarefa para consultar (se omitido, lista as últimas missões)'),
        limit: z.number().optional().describe('Quantidade máxima de tarefas ao listar (padrão 10)')
      }
    },
    async ({ task_id = '', limit = 10 } = {}) => {
      const ch = loadChannelState();
      if (task_id) {
        const found = ch.tasks.find((t) => t.task_id === task_id);
        return jsonText(found ? { ok: true, ...found } : { ok: false, error: `task_id '${task_id}' não encontrado` });
      }
      const list = ch.tasks.slice(-Math.min(Math.max(Number(limit) || 10, 1), 50)).reverse();
      return jsonText({ ok: true, count: list.length, tasks: list });
    }
  );

  // 13. Structured Channel: Poll Inbox Messages Published by XZero
  server.registerTool(
    'xzero_channel_poll',
    {
      description: 'Consulta mensagens, resultados e evidências publicados pelo XZero na fila MCP (para apresentar no chat sem abrir o app ChatGPT).',
      inputSchema: {
        limit: z.number().optional().describe('Máximo de mensagens (padrão 15)'),
        only_unread: z.boolean().optional().describe('Se true, retorna apenas não lidas e marca como lidas')
      }
    },
    async ({ limit = 15, only_unread = false } = {}) => {
      const ch = loadChannelState();
      let items = only_unread ? ch.inbox.filter((m) => !m.read) : ch.inbox;
      items = items.slice(-Math.min(Math.max(Number(limit) || 15, 1), 50)).reverse();
      if (only_unread && items.length > 0) {
        const ids = new Set(items.map((i) => i.id));
        ch.inbox.forEach((m) => { if (ids.has(m.id)) m.read = true; });
        saveChannelState(ch);
      }
      return jsonText({ ok: true, count: items.length, messages: items });
    }
  );

  // 14. Structured Channel: Inspect Execution Evidence & Logs
  server.registerTool(
    'xzero_get_evidence',
    {
      description: 'Inspeciona evidências de execução (screenshots salvos, trajetória da sessão e árvore atual da tela) de uma tarefa.',
      inputSchema: {
        task_id: z.string().optional().describe('ID da tarefa'),
        include_screen_tree: z.boolean().optional().describe('Se true, captura também a árvore UI atual do celular')
      }
    },
    async ({ task_id = '', include_screen_tree = false } = {}) => {
      const ch = loadChannelState();
      const taskObj = task_id ? ch.tasks.find((t) => t.task_id === task_id) : ch.tasks[ch.tasks.length - 1];
      let trajectory = null;
      if (taskObj?.session_id) {
        trajectory = await getLogsAndTrajectories({ mode: 'trajectories', session_id: taskObj.session_id }).catch(() => null);
      }
      let screenTree = null;
      if (include_screen_tree) {
        const dev = await getActiveDevice();
        if (dev.connected) {
          const r = await runBroadcast(dev.serial, 'SCREEN_TREE').catch(() => null);
          try { screenTree = JSON.parse(r?.data || '{}'); } catch (_) { screenTree = r?.data || null; }
        }
      }
      return jsonText({ ok: true, task: taskObj || null, trajectory, screenTree });
    }
  );

  return server;
}

// ============================================================================
// HTTP JSON-RPC 2.0 Server Mode (`--http 3029`)
// ============================================================================

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization'
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => {
      data += c;
      if (data.length > 2 * 1024 * 1024) req.destroy();
    });
    req.on('end', () => {
      if (!data) return resolve({});
      try { resolve(JSON.parse(data)); } catch (e) { reject(e); }
    });
    req.on('error', reject);
  });
}

async function handleJsonRpcMcpRequest(req, res, serverType) {
  const body = await readBody(req);
  const method = body.method || '';
  const id = body.id !== undefined ? body.id : 1;

  if (method === 'initialize') {
    return sendJson(res, 200, {
      jsonrpc: '2.0',
      id,
      result: {
        protocolVersion: '2024-11-05',
        capabilities: { tools: {} },
        serverInfo: { name: `xzero-${serverType}`, version: '3.2.0' }
      }
    });
  }

  const targetSrv = PHONE_FACING_SERVERS[serverType];
  const toolsList = targetSrv ? targetSrv.tools : ALL_PHONE_TOOLS;

  if (method === 'tools/list') {
    return sendJson(res, 200, {
      jsonrpc: '2.0',
      id,
      result: { tools: toolsList }
    });
  }

  if (method === 'tools/call') {
    const toolName = (body.params && body.params.name) || '';
    const args = (body.params && body.params.arguments) || {};
    try {
      const output = targetSrv
        ? await targetSrv.callTool(toolName, args)
        : await callAnyPhoneFacingTool(toolName, args);
      return sendJson(res, 200, {
        jsonrpc: '2.0',
        id,
        result: {
          content: [{ type: 'text', text: String(output) }]
        }
      });
    } catch (e) {
      return sendJson(res, 200, {
        jsonrpc: '2.0',
        id,
        error: { code: -32000, message: e.message }
      });
    }
  }

  return sendJson(res, 200, { jsonrpc: '2.0', id, result: { ok: true } });
}

function isAuthorizedRequest(req) {
  if (!MCP_AUTH_TOKEN) return true;
  const authHeader = String(req.headers.authorization || '');
  const bearer = authHeader.startsWith('Bearer ') ? authHeader.slice(7).trim() : '';
  const xToken = String(req.headers['x-mcp-token'] || '').trim();
  const provided = bearer || xToken;
  if (!provided || provided.length !== MCP_AUTH_TOKEN.length) return false;
  return crypto.timingSafeEqual(Buffer.from(provided), Buffer.from(MCP_AUTH_TOKEN));
}

function startHttpDaemon(port = HTTP_PORT) {
  const httpServer = http.createServer(async (req, res) => {
    try {
      if (req.method === 'OPTIONS') return sendJson(res, 200, { ok: true });
      const parsed = url.parse(req.url, true);
      const pathname = parsed.pathname || '/';

      if (pathname === '/health' && req.method === 'GET') {
        const isPublic = Boolean(req.headers['cf-connecting-ip'] || req.headers['x-forwarded-for']);
        if (isPublic) return sendJson(res, 200, { ok: true, auth: Boolean(MCP_AUTH_TOKEN) });
        const dev = await getActiveDevice();
        return sendJson(res, 200, { ok: true, port, auth: Boolean(MCP_AUTH_TOKEN), adb: dev, servers: Object.keys(PHONE_FACING_SERVERS) });
      }

      if (!isAuthorizedRequest(req)) {
        return sendJson(res, 401, { error: 'Unauthorized: Bearer token inválido ou ausente' });
      }

      if (pathname === '/setup-phone' && req.method === 'POST') {
        const r = await configureAndTeachPhoneMcp(true);
        return sendJson(res, 200, r);
      }

      if (pathname === '/mcp/host-cloud' && req.method === 'POST') {
        return await handleJsonRpcMcpRequest(req, res, 'host-cloud');
      }
      if (pathname === '/mcp/whatsapp' && req.method === 'POST') {
        return await handleJsonRpcMcpRequest(req, res, 'whatsapp');
      }
      if (pathname === '/mcp/host-brain' && req.method === 'POST') {
        return await handleJsonRpcMcpRequest(req, res, 'host-brain');
      }
      if ((pathname === '/api/mcp-demo' || pathname === '/mcp' || pathname === '/mcp/all') && req.method === 'POST') {
        return await handleJsonRpcMcpRequest(req, res, 'all');
      }

      return sendJson(res, 404, { error: 'Endpoint MCP não encontrado' });
    } catch (e) {
      return sendJson(res, 500, { error: e.message });
    }
  });

  httpServer.listen(port, '0.0.0.0', () => {
    console.log(`[XZERO-MCP-HUB] Listening on http://0.0.0.0:${port} (/mcp/host-cloud, /mcp/whatsapp, /mcp/host-brain, /api/mcp-demo)`);
    // Carrega o catálogo de WhatsApp antes de ensinar o celular, para o X0 já receber
    // a lista completa na primeira sincronização e não a lista de fallback.
    refreshWhatsApp BridgeTools().then(() => configureAndTeachPhoneMcp(true)).then((r) => {
      console.log('[XZERO-MCP-HUB] Initial phone sync:', JSON.stringify(r));
    }).catch(() => {});
  });

  // O catálogo muda quando o MCP do WhatsApp Bridge ganha ferramenta; reler de hora em hora
  // basta, e cobre o caso de a API ter subido depois do hub.
  setInterval(() => { refreshWhatsApp BridgeTools().catch(() => {}); }, 60 * 60 * 1000);

  // Auto-sync loop whenever phone reconnects on USB/Wi-Fi
  setInterval(async () => {
    try {
      const dev = await getActiveDevice();
      if (dev.connected) {
        await configureAndTeachPhoneMcp(false);
        await getFullMetricsAndTelemetry(false);
      } else {
        lastConfiguredSerial = null;
      }
    } catch (_) {}
  }, 8000);
}

async function main() {
  const args = process.argv.slice(2);
  if (args.includes('--http')) {
    const idx = args.indexOf('--http');
    const port = Number(args[idx + 1]) || HTTP_PORT;
    startHttpDaemon(port);
    return;
  }
  if (args.includes('--setup-phone')) {
    const r = await configureAndTeachPhoneMcp(true);
    console.log(JSON.stringify(r, null, 2));
    process.exit(0);
  }
  // Default: Stdio MCP server for ChatGPT / Codex / Claude / Gemini
  const mcpServer = createXzeroMcpServer();
  await mcpServer.connect(new StdioServerTransport());
}

module.exports = {
  createXzeroMcpServer,
  PHONE_FACING_SERVERS,
  ALL_PHONE_TOOLS,
  callAnyPhoneFacingTool,
  configureAndTeachPhoneMcp,
  getFullMetricsAndTelemetry,
  getLogsAndTrajectories
};

if (require.main === module) {
  main().catch((err) => {
    console.error('[xzero-mcp] fatal error:', err);
    process.exit(1);
  });
}

#!/usr/bin/env node
/**
 * XZERO v2 — Web Control Panel & Observability Dashboard Backend
 * Port: 3028 (Default)
 * Bridges HTTP/REST requests from the browser (or Cloudflare Tunnel) to the XZERO Android Agent via ADB.
 */

const http = require('http');
const crypto = require('crypto');
const { execFile, spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const url = require('url');

function loadDashboardAuthConfig() {
  let allowedEmail = 'owner@example.com';
  let ownerPass = '';
  let bearerToken = '';
  let accessTeamDomain = '';   // ex.: usuariojuniorxz.cloudflareaccess.com
  let accessAud = '';          // AUD da aplicação Access; vazio = caminho Access desligado
  const envPath = path.join(__dirname, '..', '.env.mcp');
  try {
    if (fs.existsSync(envPath)) {
      for (const line of fs.readFileSync(envPath, 'utf8').split(/\r?\n/)) {
        const t = line.trim();
        if (!t || t.startsWith('#') || !t.includes('=')) continue;
        const [k, ...rest] = t.split('=');
        const v = rest.join('=').trim();
        if (k.trim() === 'XZERO_ALLOWED_EMAIL' && v) allowedEmail = v.toLowerCase();
        if (k.trim() === 'XZERO_OWNER_PASSWORD' && v) ownerPass = v;
        if (k.trim() === 'CF_ACCESS_TEAM_DOMAIN' && v) accessTeamDomain = v;
        if (k.trim() === 'CF_ACCESS_AUD' && v) accessAud = v;
      }
    }
  } catch (_) {}
  try {
    const tokPath = path.join(os.homedir(), '.cloudflared', 'xzero_mcp_token');
    if (fs.existsSync(tokPath)) bearerToken = fs.readFileSync(tokPath, 'utf8').trim();
  } catch (_) {}
  return { allowedEmail, ownerPass, bearerToken, accessTeamDomain, accessAud };
}

const DASH_AUTH = loadDashboardAuthConfig();

// ATENÇÃO DE SEGURANÇA
// Este painel aciona o celular por adb. Ele é publicado em xzero.example.com.
// A versão anterior decodificava o JWT do Cloudflare Access SEM verificar a assinatura e
// confiava no e-mail de dentro — qualquer pessoa montava um token `alg:none` com o e-mail
// certo e entrava (confirmado: HTTP 200 com token forjado). Um header/cookie que o cliente
// controla NUNCA é prova de identidade sozinho.
//
// Agora o caminho Access só vale com assinatura verificada contra as chaves públicas do
// Cloudflare (JWKS) E com o `aud` da aplicação conferido. Enquanto a aplicação Access não
// existir (sem CF_ACCESS_AUD), este caminho fica desligado e só o token/senha autenticam —
// falha fechado, nunca aberto.

const b64urlToJson = seg => {
  const b64 = String(seg || '').replace(/-/g, '+').replace(/_/g, '/');
  const pad = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
  return JSON.parse(Buffer.from(pad, 'base64').toString('utf8'));
};
const b64urlToBuf = seg => {
  const b64 = String(seg || '').replace(/-/g, '+').replace(/_/g, '/');
  const pad = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
  return Buffer.from(pad, 'base64');
};

// Cache das chaves públicas do Access (JWKS), renovado de hora em hora.
let _jwks = { keys: [], fetchedAt: 0 };
async function getAccessKeys() {
  if (!DASH_AUTH.accessTeamDomain) return [];
  if (Date.now() - _jwks.fetchedAt < 60 * 60 * 1000 && _jwks.keys.length) return _jwks.keys;
  try {
    const res = await fetch(`https://${DASH_AUTH.accessTeamDomain}/cdn-cgi/access/certs`, {
      signal: AbortSignal.timeout(8000),
    });
    const data = await res.json();
    _jwks = { keys: data.keys || [], fetchedAt: Date.now() };
  } catch (e) {
    console.error('[dashboard] falha ao buscar JWKS do Access:', e.message);
  }
  return _jwks.keys;
}

// Verifica um JWT RS256 do Cloudflare Access: assinatura, expiração e audiência.
// Devolve o e-mail só quando TUDO confere; caso contrário, string vazia.
async function verifiedAccessEmail(jwt) {
  if (!jwt || !DASH_AUTH.accessAud || !DASH_AUTH.accessTeamDomain) return '';
  const parts = String(jwt).split('.');
  if (parts.length !== 3) return '';
  let header, payload;
  try { header = b64urlToJson(parts[0]); payload = b64urlToJson(parts[1]); } catch (_) { return ''; }
  if (header.alg !== 'RS256') return '';                       // 'none' e HMAC não entram
  if (!payload.exp || Date.now() / 1000 > payload.exp) return '';
  const auds = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
  if (!auds.includes(DASH_AUTH.accessAud)) return '';
  if (payload.iss && payload.iss !== `https://${DASH_AUTH.accessTeamDomain}`) return '';

  const keys = await getAccessKeys();
  const jwk = keys.find(k => k.kid === header.kid);
  if (!jwk) return '';
  try {
    const pub = crypto.createPublicKey({ key: jwk, format: 'jwk' });
    const ok = crypto.verify(
      'RSA-SHA256',
      Buffer.from(`${parts[0]}.${parts[1]}`),
      pub,
      b64urlToBuf(parts[2]),
    );
    if (!ok) return '';
  } catch (_) {
    return '';
  }
  return String(payload.email || '').trim().toLowerCase();
}

function extractCookieValue(cookieHeader, name) {
  const m = String(cookieHeader || '').match(new RegExp(`(?:^|;\\s*)${name}=([^;]+)`));
  return m ? decodeURIComponent(m[1]) : '';
}

async function isDashboardAuthorized(req) {
  const isPublic = Boolean(req.headers['cf-connecting-ip'] || req.headers['x-forwarded-for']);
  const remote = String(req.socket?.remoteAddress || '');
  const isLocal = !isPublic && (remote === '127.0.0.1' || remote === '::1' || remote === '::ffff:127.0.0.1');
  if (isLocal) return true;

  // 1. Cloudflare Access — SÓ com JWT de assinatura verificada e audiência conferida.
  // O e-mail só é aceito quando sai de um token que o Cloudflare realmente assinou; o header
  // `cf-access-authenticated-user-email`, sozinho, é dado do cliente e não vale como prova.
  const cfJwt = String(req.headers['cf-access-jwt-assertion'] || extractCookieValue(req.headers.cookie, 'CF_Authorization') || '').trim();
  if (cfJwt) {
    const email = await verifiedAccessEmail(cfJwt);
    if (email && email === DASH_AUTH.allowedEmail) return true;
  }

  // 2. Fallback Bearer / Basic Auth (enquanto Access App não estiver ativa no painel CF)
  const auth = String(req.headers.authorization || '');
  if (auth.startsWith('Bearer ') && DASH_AUTH.bearerToken) {
    const got = auth.slice(7).trim();
    if (got.length === DASH_AUTH.bearerToken.length && crypto.timingSafeEqual(Buffer.from(got), Buffer.from(DASH_AUTH.bearerToken))) {
      return true;
    }
  }
  if (auth.startsWith('Basic ') && DASH_AUTH.ownerPass) {
    try {
      const decoded = Buffer.from(auth.slice(6).trim(), 'base64').toString('utf8');
      const idx = decoded.indexOf(':');
      if (idx >= 0) {
        const user = decoded.slice(0, idx).trim().toLowerCase();
        const pass = decoded.slice(idx + 1);
        const userOk = !user || user === DASH_AUTH.allowedEmail || user === 'admin' || user === 'usuario';
        if (userOk && pass.length === DASH_AUTH.ownerPass.length && crypto.timingSafeEqual(Buffer.from(pass), Buffer.from(DASH_AUTH.ownerPass))) {
          return true;
        }
      }
    } catch (_) {}
  }
  return false;
}

const PORT = Number(process.env.PORT || 3028);
const ADB_CANDIDATES = [process.env.ADB_BIN, 'adb'].filter(Boolean);
const ADB_BIN = ADB_CANDIDATES.find((p) => p === 'adb' || fs.existsSync(p)) || 'adb';
const PKG = 'com.xzero.agent';
const PUBLIC_DIR = path.join(__dirname, 'public');
const SPRITESHEET_FALLBACK = path.join(PUBLIC_DIR, 'xenon-spritesheet.png');

function execAdb(args, options = {}) {
  selfStat.adbSpawns += 1;
  return new Promise((resolve, reject) => {
    execFile(
      ADB_BIN,
      args,
      { timeout: options.timeout || 12000, maxBuffer: options.maxBuffer || 15 * 1024 * 1024, encoding: options.encoding || 'utf8' },
      (err, stdout, stderr) => {
        if (err) {
          return reject(new Error(stderr || err.message));
        }
        resolve(stdout);
      }
    );
  });
}

/*
 * Cache curto + coalescência de `adb devices -l`.
 *
 * Problema medido: `getActiveDevice()` é chamado no começo de 19 endpoints, e cada chamada
 * faz um `execFile` novo de `adb`. O painel faz poll de /api/overview a cada 4,5s, e a
 * resposta de overview por si já dispara 4 shells em paralelo. Com duas abas abertas isso
 * virava ~90 processos `adb` por minuto conversando com um aparelho em 6% de bateria.
 *
 * O TTL é deliberadamente menor que o intervalo de poll, então "conectado/desconectado"
 * continua reagindo dentro de um ciclo — não se perde frescor, só se perde desperdício.
 */
const DEVICE_CACHE_TTL_MS = 2500;
let deviceCache = { at: 0, value: null };
let deviceInFlight = null;

async function getActiveDevice() {
  const now = Date.now();
  if (deviceCache.value && now - deviceCache.at < DEVICE_CACHE_TTL_MS) {
    return deviceCache.value;
  }
  if (deviceInFlight) return deviceInFlight;
  deviceInFlight = probeActiveDevice()
    .then((value) => {
      deviceCache = { at: Date.now(), value };
      return value;
    })
    .finally(() => {
      deviceInFlight = null;
    });
  return deviceInFlight;
}

/** Invalida o cache quando algo que muda a topologia de conexão acabou de rodar. */
function invalidateDeviceCache() {
  deviceCache = { at: 0, value: null };
}

let lastMdnsReconnectAt = 0;

async function tryReconnectMdnsWifi() {
  const now = Date.now();
  if (now - lastMdnsReconnectAt < 12000) return false;
  lastMdnsReconnectAt = now;
  try {
    const mdns = await execAdb(['mdns', 'services'], { timeout: 3500 });
    const addrs = [];
    for (const line of mdns.split('\n')) {
      if (line.includes('_adb-tls-connect._tcp')) {
        const m = line.match(/(\d+\.\d+\.\d+\.\d+:\d+)/);
        if (m) addrs.unshift(m[1]);
      } else if (line.includes('_adb._tcp')) {
        const m = line.match(/(\d+\.\d+\.\d+\.\d+:\d+)/);
        if (m) addrs.push(m[1]);
      }
    }
    for (const addr of addrs.slice(0, 2)) {
      try {
        const res = await execAdb(['connect', addr], { timeout: 3500 });
        if (res.includes('connected to') || res.includes('already connected')) return true;
      } catch (_) {}
    }
  } catch (_) {}
  return false;
}

async function probeActiveDevice() {
  try {
    let out = await execAdb(['devices', '-l'], { timeout: 4000 });
    let lines = out
      .split('\n')
      .map((l) => l.trim())
      .filter((l) => l && !l.startsWith('List of devices'));
    let online = lines.filter((l) => /\bdevice\b/.test(l));
    if (online.length === 0) {
      const reconnected = await tryReconnectMdnsWifi();
      if (reconnected) {
        out = await execAdb(['devices', '-l'], { timeout: 4000 });
        lines = out.split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('List of devices'));
        online = lines.filter((l) => /\bdevice\b/.test(l));
      }
    }
    if (online.length === 0) {
      return { connected: false, serial: null, transport: 'offline', raw: lines };
    }
    // Prefer USB serial first, then Wi-Fi
    const usb = online.find((l) => !l.includes(':'));
    const chosen = usb || online[0];
    const serial = chosen.split(/\s+/)[0];
    const modelMatch = chosen.match(/model:(\S+)/);
    return {
      connected: true,
      serial,
      transport: serial.includes(':') ? 'wifi' : 'usb',
      model: modelMatch ? modelMatch[1].replace(/_/g, ' ') : 'celular Android',
      allDevices: online.map((l) => l.split(/\s+/)[0])
    };
  } catch (e) {
    return { connected: false, serial: null, transport: 'error', error: e.message };
  }
}

async function adbDeviceShell(serial, shellCmd, timeout = 10000) {
  const args = serial ? ['-s', serial, 'shell', shellCmd] : ['shell', shellCmd];
  return execAdb(args, { timeout });
}

function parseBroadcastResultData(stdout) {
  const match = stdout.match(/data="([\s\S]*?)"(?:\r?\n|$)/);
  if (!match) return null;
  return match[1];
}

async function runBroadcast(serial, action, extras = {}) {
  const extraParts = [];
  for (const [k, v] of Object.entries(extras)) {
    if (v === undefined || v === null) continue;
    if (typeof v === 'boolean') {
      extraParts.push(`--ez ${k} ${v ? 'true' : 'false'}`);
    } else {
      const escaped = String(v).replace(/'/g, "'\\''");
      extraParts.push(`--es ${k} '${escaped}'`);
    }
  }
  const cmd = `am broadcast -a ${PKG}.${action} -n ${PKG}/.AdbReceiver ${extraParts.join(' ')}`;
  const out = await adbDeviceShell(serial, cmd, 12000);
  return { raw: out, data: parseBroadcastResultData(out) };
}

async function readAppJsonFile(serial, relPath, fallback) {
  try {
    const out = await adbDeviceShell(serial, `run-as ${PKG} cat ${relPath} 2>/dev/null`);
    if (!out || !out.trim()) return fallback;
    return JSON.parse(out.trim());
  } catch (_) {
    return fallback;
  }
}

async function writeAppJsonFile(serial, relPath, dataObj) {
  const b64 = Buffer.from(JSON.stringify(dataObj), 'utf8').toString('base64');
  const cmd = `run-as ${PKG} sh -c "echo '${b64}' | base64 -d > ${relPath}"`;
  await adbDeviceShell(serial, cmd, 8000);
}

const TOOLS_CATALOG = [
  {
    name: 'tap',
    category: 'Interação & UI',
    scoreBase: '+0.8',
    description: 'Toca nas coordenadas (x, y) da tela atual com base nos elementos numerados da árvore de Acessibilidade.',
    params: { x: 540, y: 1200 },
    samplePrompt: 'Toque no centro da tela'
  },
  {
    name: 'tap_text',
    category: 'Interação & UI',
    scoreBase: '+1.0',
    description: 'Localiza na árvore de Acessibilidade um elemento contendo o texto informado e clica nele (ou no ancestral clicável).',
    params: { text: 'Curtidas' },
    samplePrompt: 'Clique onde está escrito Curtidas'
  },
  {
    name: 'long_press',
    category: 'Interação & UI',
    scoreBase: '+0.8',
    description: 'Pressiona e segura nas coordenadas (x, y) por duration_ms milissegundos.',
    params: { x: 540, y: 1200, duration_ms: 800 },
    samplePrompt: 'Segure pressionado no item central'
  },
  {
    name: 'swipe',
    category: 'Interação & UI',
    scoreBase: '+0.8',
    description: 'Desliza o dedo de (x1, y1) até (x2, y2) na duração especificada.',
    params: { x1: 540, y1: 1800, x2: 540, y2: 600, duration_ms: 350 },
    samplePrompt: 'Role a página para baixo'
  },
  {
    name: 'scroll',
    category: 'Interação & UI',
    scoreBase: '+0.8',
    description: 'Rola a tela na direção indicada (down, up, left, right).',
    params: { direction: 'down' },
    samplePrompt: 'Role a tela para baixo'
  },
  {
    name: 'type',
    category: 'Interação & UI',
    scoreBase: '+1.0',
    description: 'Digita texto diretamente no campo editável em foco (ou primeiro EditText visível).',
    params: { text: 'Olá usuário' },
    samplePrompt: 'Digite Olá usuário no campo atual'
  },
  {
    name: 'open_app',
    category: 'Comunicação & Apps',
    scoreBase: '+1.3',
    description: 'Abre diretamente qualquer aplicativo instalado pelo nome amigável ou package name (ex: Spotify, WhatsApp, Chrome).',
    params: { name: 'Spotify' },
    samplePrompt: 'Abra o aplicativo Spotify'
  },
  {
    name: 'send_whatsapp',
    category: 'Comunicação & Apps',
    scoreBase: '+1.8',
    description: 'Envia mensagem de WhatsApp diretamente para um contato salvo ou número e clica automaticamente no botão Enviar (dispatch=true).',
    params: { contact: 'ContatoFamiliar', text: 'Oi amor, já estou indo!', dispatch: true },
    samplePrompt: 'Mande um WhatsApp para a ContatoFamiliar dizendo que já estou chegando'
  },
  {
    name: 'call',
    category: 'Comunicação & Apps',
    scoreBase: '+1.5',
    description: 'Inicia chamada telefônica direta para um nome da agenda ou número de telefone.',
    params: { target: 'ContatoFamiliar' },
    samplePrompt: 'Ligue para a ContatoFamiliar'
  },
  {
    name: 'open_url',
    category: 'Comunicação & Apps',
    scoreBase: '+1.2',
    description: 'Abre uma URL diretamente no navegador padrão do Android.',
    params: { url: 'https://example.com' },
    samplePrompt: 'Abra o site example.com'
  },
  {
    name: 'web_search',
    category: 'Cognição, Grafo & Vigias',
    scoreBase: '+1.6',
    description: 'Pesquisa silenciosamente na web em background (DuckDuckGo Instant Answer API) e retorna resumo sem sair do app atual.',
    params: { query: 'clima São Paulo hoje' },
    samplePrompt: 'Pesquise em background a cotação do dólar e me fale'
  },
  {
    name: 'device_status',
    category: 'Sensores, Câmera & Áudio',
    scoreBase: '+1.6',
    description: 'Lê telemetria completa do aparelho em 1 passo: Bateria, Temperatura, RAM livre/total, Rede, Luminosidade (lux), Estado de Movimento (acelerômetro), Proximidade, Giroscópio, Passos, Gravador e Vigias.',
    params: {},
    samplePrompt: 'Me diga o status completo da bateria, temperatura, sensores de luz, proximidade, giroscópio e passos'
  },
  {
    name: 'configure_sensors',
    category: 'Sensores, Câmera & Áudio',
    scoreBase: '+1.6',
    description: 'Permite ao XZERO ativar/desativar ou mudar a taxa de amostragem de todos os sensores (modo eco, normal, high_alert, auto) e auto-silêncio no escuro/bolso.',
    params: { mode: 'high_alert', light: true, accelerometer: true, proximity: true, gyroscope: true, steps: true, auto_silent: true },
    samplePrompt: 'Ajuste os sensores com configure_sensors para o modo high_alert com todos os sensores ativos'
  },
  {
    name: 'post_notification',
    category: 'Comunicação & Apps',
    scoreBase: '+1.6',
    description: 'Cria uma notificação nativa do XZERO na barra de status do Android para deixar mensagem/relatório sem falar em voz alta (suprime TTS quando silent=true).',
    params: { title: 'Relatório XZERO', text: 'Sensores em modo de vigia e auditoria concluída.', silent: true },
    samplePrompt: 'Deixe uma notificação silenciosa sem falar em voz alta dizendo que o monitoramento noturno está ativo'
  },
  {
    name: 'capture_camera',
    category: 'Sensores, Câmera & Áudio',
    scoreBase: '+1.6',
    description: 'Captura silenciosamente um frame da câmera traseira (back) ou frontal (front) via Camera2 API em segundo plano para análise visual multimodal.',
    params: { lens: 'back' },
    samplePrompt: 'Use a câmera traseira para ver o que está na minha frente e descreva'
  },
  {
    name: 'start_recording',
    category: 'Sensores, Câmera & Áudio',
    scoreBase: '+1.5',
    description: 'Inicia gravação de áudio ambiente em segundo plano (.m4a AAC 128kbps) sem abrir tela.',
    params: { label: 'reuniao_importante' },
    samplePrompt: 'Comece a gravar o áudio ambiente com o rótulo reuniao_importante'
  },
  {
    name: 'stop_recording',
    category: 'Sensores, Câmera & Áudio',
    scoreBase: '+1.5',
    description: 'Finaliza a gravação de áudio ambiente em andamento e salva o arquivo em files/recordings/.',
    params: {},
    samplePrompt: 'Pare a gravação de áudio agora'
  },
  {
    name: 'media_control',
    category: 'Sensores, Câmera & Áudio',
    scoreBase: '+1.5',
    description: 'Controla reprodução de mídia do sistema (play_pause, next, previous, play, pause, stop) via AudioManager.',
    params: { command: 'play_pause' },
    samplePrompt: 'Pause ou dê play na música atual'
  },
  {
    name: 'add_watch',
    category: 'Cognição, Grafo & Vigias',
    scoreBase: '+1.5',
    description: 'Cria uma vigia proativa contínua para monitorar notificações (whatsapp, email, claude, codex ou qualquer app) com filtro e ação proativa.',
    params: { source: 'whatsapp', filter: 'ColegaEquipe', instruction: 'Me avise imediatamente e sugira resposta', auto_open: false },
    samplePrompt: 'Fique de vigia no WhatsApp do ColegaEquipe e me avise quando ele mandar mensagem'
  },
  {
    name: 'list_watches',
    category: 'Cognição, Grafo & Vigias',
    scoreBase: '+1.2',
    description: 'Lista todas as vigias proativas ativas no momento.',
    params: {},
    samplePrompt: 'Quais vigias proativas estão ativas agora?'
  },
  {
    name: 'remove_watch',
    category: 'Cognição, Grafo & Vigias',
    scoreBase: '+1.2',
    description: 'Remove uma vigia proativa pelo seu ID ou filtro.',
    params: { id_or_filter: 'ColegaEquipe' },
    samplePrompt: 'Remova a vigia do ColegaEquipe'
  },
  {
    name: 'remember',
    category: 'Cognição, Grafo & Vigias',
    scoreBase: '+1.2',
    description: 'Salva um fato na memória persistente e cria uma aresta ponderada (sujeito -> relação -> objeto) no Grafo Vetorial.',
    params: { fact: 'ColegaEquipe é parceiro técnico de projetos', subject: 'ColegaEquipe', relation: 'é_parceiro_de', target: 'usuário' },
    samplePrompt: 'Lembre-se que o ColegaEquipe é parceiro técnico do usuário'
  },
  {
    name: 'ask_user',
    category: 'Cognição, Grafo & Vigias',
    scoreBase: '+1.0',
    description: 'Fala uma pergunta em voz alta (TTS + balão do Xenon) e escuta a resposta do usuário para tirar dúvida ou corrigir a rota.',
    params: { question: 'Qual contato você prefere?' },
    samplePrompt: 'Me pergunte para qual contato devo enviar o relatório'
  },
  {
    name: 'speak',
    category: 'Sensores, Câmera & Áudio',
    scoreBase: '+1.0',
    description: 'Fala um texto em voz alta usando a voz neural pt-BR e exibe no balão flutuante do Xenon sem encerrar a tarefa.',
    params: { text: 'Estou verificando os seus dados agora.' },
    samplePrompt: 'Diga em voz alta: Olá usuário, sistemas operacionais cem por cento'
  },
  {
    name: 'read_notifications',
    category: 'Comunicação & Apps',
    scoreBase: '+1.2',
    description: 'Lê todas as notificações ativas da barra de status capturadas pelo NotifListener.',
    params: {},
    samplePrompt: 'Leia minhas últimas notificações e resuma o que há de importante'
  },
  {
    name: 'reply_notification',
    category: 'Comunicação & Apps',
    scoreBase: '+1.8',
    description: 'Responde diretamente a uma notificação em segundo plano via RemoteInput (sem precisar abrir o aplicativo).',
    params: { match: 'ColegaEquipe', text: 'Recebido, já vou olhar!' },
    samplePrompt: 'Responda à notificação do ColegaEquipe dizendo Recebido'
  },
  {
    name: 'set_volume',
    category: 'Sistema & Controle',
    scoreBase: '+1.0',
    description: 'Ajusta o volume de media, ring, notification ou alarm de 0 a 100%.',
    params: { stream: 'media', percent: 60 },
    samplePrompt: 'Coloque o volume de mídia em 60%'
  },
  {
    name: 'set_brightness',
    category: 'Sistema & Controle',
    scoreBase: '+1.0',
    description: 'Ajusta o brilho da tela de 0 a 100%.',
    params: { percent: 75 },
    samplePrompt: 'Ajuste o brilho da tela para 75%'
  },
  {
    name: 'back',
    category: 'Sistema & Controle',
    scoreBase: '+0.8',
    description: 'Executa a ação global Voltar do Android.',
    params: {},
    samplePrompt: 'Volte para a tela anterior'
  },
  {
    name: 'home',
    category: 'Sistema & Controle',
    scoreBase: '+0.8',
    description: 'Vai para a tela inicial (Home) do Android.',
    params: {},
    samplePrompt: 'Vá para a tela inicial do celular'
  },
  {
    name: 'mcp_list_servers',
    category: 'Conectores MCP',
    scoreBase: '+1.6',
    description: 'Lista todos os servidores MCP (Model Context Protocol) conectados ao XZERO e suas ferramentas descobertas.',
    params: {},
    samplePrompt: 'Liste os servidores MCP conectados com mcp_list_servers'
  },
  {
    name: 'mcp_add_server',
    category: 'Conectores MCP',
    scoreBase: '+1.6',
    description: 'Conecta um novo servidor MCP externo via JSON-RPC/HTTP e descobre automaticamente suas tools via tools/list.',
    params: { name: 'host-mcp', url: 'https://xzero.example.com/api/mcp-demo', token: '' },
    samplePrompt: 'Conecte o servidor MCP host-mcp na URL https://xzero.example.com/api/mcp-demo'
  },
  {
    name: 'mcp_call',
    category: 'Conectores MCP',
    scoreBase: '+1.6',
    description: 'Executa uma ferramenta (tools/call) em qualquer servidor MCP conectado ao XZERO.',
    params: { server: 'host-mcp', tool: 'svs_system_ping', arguments: { check: 'all' } },
    samplePrompt: 'Chame a ferramenta svs_system_ping no servidor MCP host-mcp'
  }
];

/*
 * Coalescência de leituras caras.
 *
 * /api/overview dispara 4 shells ADB em paralelo. Com duas abas do painel abertas (ou o
 * celular + o navegador do desktop), cada ciclo de poll multiplicava essa rajada pelo
 * número de clientes, contra um único aparelho via um único daemon ADB. Aqui, requisições
 * simultâneas do MESMO endpoint compartilham uma única ida ao aparelho.
 *
 * Só leituras entram: nada de POST/DELETE, porque compartilhar uma escrita entre dois
 * clientes mudaria a semântica da ação.
 */
const readInFlight = new Map();

/** Endpoints de leitura pura que valem compartilhar entre clientes simultâneos. */
const COALESCIBLE_READS = new Set([
  '/api/overview',
  '/api/logs',
  '/api/trajectories',
  '/api/memory',
  '/api/watches',
  '/api/energy',
  '/api/ambient',
  '/api/monitoring3030'
]);

/**
 * Captura o que `handleApi` escreveria, para que a mesma resposta sirva vários clientes.
 * Imita só a fatia de ServerResponse que os handlers realmente usam (sendJson).
 */
function createResponseBuffer() {
  const captured = { status: 200, payload: null };
  return {
    captured,
    setHeader() {},
    writeHead(status) {
      captured.status = status;
      return this;
    },
    end(body) {
      try {
        captured.payload = body ? JSON.parse(body) : null;
      } catch (_) {
        captured.payload = { raw: String(body || '') };
      }
      return this;
    }
  };
}

function coalesceRead(key, producer) {
  const pending = readInFlight.get(key);
  // `hit` tem de ser decidido AGORA, não depois do await: quando a promessa resolve a
  // entrada já saiu do mapa, e a métrica sairia sempre como "miss".
  if (pending) return pending.then((value) => ({ value, hit: true }));
  const task = Promise.resolve()
    .then(producer)
    .finally(() => readInFlight.delete(key));
  readInFlight.set(key, task);
  return task.then((value) => ({ value, hit: false }));
}

function sendJson(res, status, payload, timings) {
  if (timings) {
    // Server-Timing deixa o custo real de cada endpoint visível no DevTools do navegador,
    // sem precisar instrumentar o front: dá para ver na hora se a lentidão é ADB ou rede.
    res.setHeader('Server-Timing', timings);
  }
  return sendJsonRaw(res, status, payload);
}

function sendJsonRaw(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type'
  });
  res.end(body);
}

function readRequestBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (chunk) => {
      data += chunk;
      if (data.length > 1024 * 1024) req.destroy();
    });
    req.on('end', () => {
      if (!data) return resolve({});
      try {
        resolve(JSON.parse(data));
      } catch (e) {
        reject(new Error('Invalid JSON body'));
      }
    });
    req.on('error', reject);
  });
}

async function handleApi(req, res, parsedUrl) {
  const pathname = parsedUrl.pathname;

  if (req.method === 'OPTIONS') {
    return sendJson(res, 200, { ok: true });
  }

  // 1. Overview / Telemetry / Health
  if (pathname === '/api/overview' && req.method === 'GET') {
    const dev = await getActiveDevice();
    if (!dev.connected) {
      return sendJson(res, 200, {
        connected: false,
        adb: dev,
        health: null,
        memoryStats: { edges: 0, routes: 0, facts: 0 },
        watches: []
      });
    }

    const heal = parsedUrl.query.heal !== 'false';
    const [healthBroadcast, kg, mem, watches] = await Promise.all([
      runBroadcast(dev.serial, 'HEALTH', { heal }).catch((e) => ({ error: e.message })),
      readAppJsonFile(dev.serial, 'files/knowledge_graph_v2.json', { edges: [], routes: [] }),
      readAppJsonFile(dev.serial, 'files/agent_memory.json', { facts: [] }),
      readAppJsonFile(dev.serial, 'files/proactive_watches.json', [])
    ]);

    let health = null;
    if (healthBroadcast && healthBroadcast.data) {
      try {
        health = JSON.parse(healthBroadcast.data);
      } catch (_) {
        health = { raw: healthBroadcast.data };
      }
    }

    return sendJson(res, 200, {
      connected: true,
      timestamp: Date.now(),
      adb: dev,
      health,
      memoryStats: {
        edges: Array.isArray(kg.edges) ? kg.edges.length : 0,
        routes: Array.isArray(kg.routes) ? kg.routes.length : 0,
        facts: Array.isArray(mem.facts) ? mem.facts.length : 0
      },
      watches: Array.isArray(watches) ? watches : []
    });
  }

  // 2. Live Logcat Viewer
  if (pathname === '/api/logs' && req.method === 'GET') {
    const dev = await getActiveDevice();
    if (!dev.connected) return sendJson(res, 200, { connected: false, logs: [] });
    const limit = Math.min(500, Math.max(20, Number(parsedUrl.query.limit) || 160));
    try {
      const out = await execAdb(['-s', dev.serial, 'logcat', '-d', '-t', String(limit), 'AgentePhone:I', '*:S'], { timeout: 6000 });
      const lines = out
        .split('\n')
        .map((l) => l.trim())
        .filter((l) => l && !l.startsWith('--------- beginning of'));
      const parsed = lines.map((line, idx) => {
        const m = line.match(/^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+AgentePhone:\s*(.*)$/);
        if (!m) return { id: idx, time: '', level: 'I', message: line, score: null };
        const msg = m[5];
        const scoreMatch = msg.match(/score=([+-]?\d+(?:\.\d+)?)/i) || msg.match(/⭐\s*([+-]?\d+(?:\.\d+)?)/);
        return {
          id: idx,
          time: m[1],
          pid: m[2],
          level: m[4],
          message: msg,
          score: scoreMatch ? Number(scoreMatch[1]) : null
        };
      });
      return sendJson(res, 200, { connected: true, count: parsed.length, logs: parsed.reverse() });
    } catch (e) {
      return sendJson(res, 500, { error: e.message });
    }
  }

  // 3. Trajectories List
  if (pathname === '/api/trajectories' && req.method === 'GET') {
    const dev = await getActiveDevice();
    if (!dev.connected) return sendJson(res, 200, { connected: false, sessions: [] });
    try {
      const script = `run-as ${PKG} sh -c 'for f in $(ls -1t files/trajectories/*.jsonl 2>/dev/null | head -n 30); do echo "===FILE:$f"; cat "$f"; done'`;
      const raw = await adbDeviceShell(dev.serial, script, 10000);
      const chunks = raw.split('===FILE:').filter((c) => c.trim());
      const sessions = chunks.map((chunk) => {
        const lines = chunk.split('\n').filter((l) => l.trim());
        const filePath = lines.shift() || '';
        const sessionId = path.basename(filePath, '.jsonl');
        let task = '';
        let startedAt = 0;
        let endedAt = 0;
        let outcome = 'running';
        let answer = '';
        let stepsCount = 0;
        let totalScore = 0;
        let provider = '';
        const routeSteps = [];

        for (const line of lines) {
          try {
            const obj = JSON.parse(line);
            if (obj.type === 'session_start' && obj.data) {
              task = obj.data.task || '';
              startedAt = obj.data.started_at || 0;
            } else if (obj.type === 'agent_step' && obj.data) {
              stepsCount++;
              provider = obj.data.provider || provider;
              const act = obj.data.action || 'step';
              const resStr = String(obj.data.result || '');
              const sm = resStr.match(/score=([+-]?\d+(?:\.\d+)?)/);
              const sc = sm ? Number(sm[1]) : null;
              if (sc !== null && act !== 'done') totalScore += sc;
              if (act === 'done' && sc !== null) totalScore = sc;
              routeSteps.push({ action: act, score: sc });
            } else if (obj.type === 'session_end' && obj.data) {
              outcome = obj.data.outcome || 'done';
              answer = obj.data.answer || '';
              endedAt = obj.data.ended_at || 0;
            }
          } catch (_) {}
        }
        return {
          sessionId,
          task: task || sessionId,
          startedAt,
          endedAt,
          durationSec: startedAt && endedAt ? Math.max(1, Math.round((endedAt - startedAt) / 1000)) : null,
          outcome,
          answer,
          stepsCount,
          totalScore: Number(totalScore.toFixed(1)),
          provider,
          routeSummary: routeSteps.map((s) => (s.score !== null ? `${s.action}(${s.score >= 0 ? '+' : ''}${s.score})` : s.action)).join(' → ')
        };
      });
      return sendJson(res, 200, { connected: true, sessions });
    } catch (e) {
      return sendJson(res, 500, { error: e.message });
    }
  }

  // 4. Trajectory Detail
  if (pathname.startsWith('/api/trajectories/') && req.method === 'GET') {
    const dev = await getActiveDevice();
    if (!dev.connected) return sendJson(res, 400, { error: 'Dispositivo desconectado' });
    const sid = path.basename(pathname.replace('/api/trajectories/', '')).replace(/[^a-zA-Z0-9_.-]/g, '');
    try {
      const raw = await adbDeviceShell(dev.serial, `run-as ${PKG} cat files/trajectories/${sid}.jsonl 2>/dev/null`);
      const events = raw
        .split('\n')
        .filter((l) => l.trim())
        .map((l) => {
          try {
            return JSON.parse(l);
          } catch (_) {
            return { raw: l };
          }
        });
      return sendJson(res, 200, { sessionId: sid, events });
    } catch (e) {
      return sendJson(res, 500, { error: e.message });
    }
  }

  // 5. Knowledge Graph & Hybrid Vector Memory
  if (pathname === '/api/memory' && req.method === 'GET') {
    const dev = await getActiveDevice();
    if (!dev.connected) return sendJson(res, 200, { connected: false, edges: [], routes: [], qPolicy: [], facts: [] });
    const [kg, mem] = await Promise.all([
      readAppJsonFile(dev.serial, 'files/knowledge_graph_v2.json', { edges: [], routes: [], q_policy: [] }),
      readAppJsonFile(dev.serial, 'files/agent_memory.json', { facts: [] })
    ]);
    return sendJson(res, 200, {
      connected: true,
      edges: Array.isArray(kg.edges) ? kg.edges : [],
      routes: Array.isArray(kg.routes) ? kg.routes : [],
      qPolicy: Array.isArray(kg.q_policy) ? kg.q_policy : [],
      facts: Array.isArray(mem.facts) ? mem.facts : []
    });
  }

  if (pathname === '/api/memory/edge' && req.method === 'POST') {
    const dev = await getActiveDevice();
    if (!dev.connected) return sendJson(res, 400, { error: 'Celular desconectado do ADB' });
    const body = await readRequestBody(req);
    const s = String(body.subject || '').trim();
    const r = String(body.relation || '').trim();
    const t = String(body.target || '').trim();
    const w = Number(body.weight || 1.8);
    if (!s || !r || !t) return sendJson(res, 400, { error: 'Informe subject, relation e target' });

    const kg = await readAppJsonFile(dev.serial, 'files/knowledge_graph_v2.json', { edges: [], routes: [] });
    const edges = Array.isArray(kg.edges) ? kg.edges : [];
    const idx = edges.findIndex((e) => e.s.toLowerCase() === s.toLowerCase() && e.r.toLowerCase() === r.toLowerCase() && e.t.toLowerCase() === t.toLowerCase());
    if (idx >= 0) {
      edges[idx].w = w;
      edges[idx].u = Date.now();
    } else {
      edges.unshift({ s, r, t, w, u: Date.now() });
    }
    kg.edges = edges;
    await writeAppJsonFile(dev.serial, 'files/knowledge_graph_v2.json', kg);
    return sendJson(res, 200, { ok: true, edges: kg.edges });
  }

  if (pathname === '/api/memory/edge' && req.method === 'DELETE') {
    const dev = await getActiveDevice();
    if (!dev.connected) return sendJson(res, 400, { error: 'Celular desconectado do ADB' });
    const body = await readRequestBody(req);
    const kg = await readAppJsonFile(dev.serial, 'files/knowledge_graph_v2.json', { edges: [], routes: [] });
    kg.edges = (kg.edges || []).filter((e) => !(e.s === body.s && e.r === body.r && e.t === body.t));
    await writeAppJsonFile(dev.serial, 'files/knowledge_graph_v2.json', kg);
    return sendJson(res, 200, { ok: true, edges: kg.edges });
  }

  // 6. Proactive Watches (synced live via WATCHES broadcast + recent watch triggers from trajectories)
  if (pathname === '/api/watches' && req.method === 'GET') {
    const dev = await getActiveDevice();
    if (!dev.connected) return sendJson(res, 200, { connected: false, watches: [], recentEvents: [] });
    let watches = [];
    try {
      const live = await runBroadcast(dev.serial, 'WATCHES', { op: 'json' });
      const parsed = JSON.parse(live?.data || '[]');
      if (Array.isArray(parsed)) watches = parsed;
    } catch (_) {
      const fileW = await readAppJsonFile(dev.serial, 'files/proactive_watches.json', []);
      watches = Array.isArray(fileW) ? fileW : [];
    }

    const recentEvents = [];
    try {
      const script = `run-as ${PKG} sh -c 'for f in $(ls -1t files/trajectories/*.jsonl 2>/dev/null | head -n 40); do grep -q "EVENTO DE VIGIA" "$f" 2>/dev/null && echo "===FILE:$f" && cat "$f"; done'`;
      const rawAll = await adbDeviceShell(dev.serial, script, 10000);
      const chunks = String(rawAll || '').split('===FILE:').slice(1);
      for (const ch of chunks.slice(0, 15)) {
        const lines = ch.split('\n').slice(1).filter(Boolean);
        let task = '';
        let startedAt = 0;
        let outcome = 'in_progress';
        let answer = '';
        let sid = '';
        for (const l of lines) {
          try {
            const ev = JSON.parse(l);
            if (!sid && ev.session_id) sid = ev.session_id;
            if (ev.type === 'session_start') {
              task = ev.data?.task || '';
              startedAt = ev.data?.started_at || 0;
            } else if (ev.type === 'session_end') {
              outcome = ev.data?.outcome || outcome;
              answer = ev.data?.answer || '';
            }
          } catch (_) {}
        }
        if (task) recentEvents.push({ session_id: sid, task, started_at: startedAt, outcome, answer });
      }
    } catch (_) {}

    return sendJson(res, 200, { connected: true, watches, recentEvents });
  }

  if (pathname === '/api/watches' && req.method === 'POST') {
    const dev = await getActiveDevice();
    if (!dev.connected) return sendJson(res, 400, { error: 'Celular desconectado do ADB' });
    const body = await readRequestBody(req);
    const source = String(body.source || 'whatsapp').trim();
    const filter = String(body.filter || '').trim();
    const instruction = String(body.instruction || 'Notificar usuário e sugerir ação').trim();
    const autoOpen = Boolean(body.autoOpen);
    if (!filter && source !== 'unlock') return sendJson(res, 400, { error: 'Informe o filtro (contato, palavra-chave ou assunto)' });

    const effectiveFilter = filter || (source === 'unlock' ? 'desbloqueio' : 'any');
    await runBroadcast(dev.serial, 'WATCHES', {
      op: 'add',
      source,
      filter: effectiveFilter,
      instruction,
      auto_open: String(autoOpen)
    });
    const live = await runBroadcast(dev.serial, 'WATCHES', { op: 'json' }).catch(() => null);
    let list = [];
    try { list = JSON.parse(live?.data || '[]'); } catch (_) {}
    if (!Array.isArray(list)) list = [];
    return sendJson(res, 200, { ok: true, watches: list });
  }

  if (pathname.startsWith('/api/watches/') && req.method === 'DELETE') {
    const dev = await getActiveDevice();
    if (!dev.connected) return sendJson(res, 400, { error: 'Celular desconectado do ADB' });
    const targetId = decodeURIComponent(pathname.replace('/api/watches/', '')).trim();
    if (!targetId) return sendJson(res, 400, { error: 'ID inválido' });
    await runBroadcast(dev.serial, 'WATCHES', { op: 'remove', id: targetId });
    const live = await runBroadcast(dev.serial, 'WATCHES', { op: 'json' }).catch(() => null);
    let next = [];
    try { next = JSON.parse(live?.data || '[]'); } catch (_) {}
    if (!Array.isArray(next)) next = [];
    return sendJson(res, 200, { ok: true, watches: next });
  }

  // 7. Tools Catalog
  if (pathname === '/api/tools' && req.method === 'GET') {
    return sendJson(res, 200, { count: TOOLS_CATALOG.length, tools: TOOLS_CATALOG });
  }

  // 7b. Energy & Battery Telemetry (by subsystem, app UID, hardware sensors, and X0 tools)
  if (pathname === '/api/energy' && req.method === 'GET') {
    const dev = await getActiveDevice();
    if (!dev.connected) {
      return sendJson(res, 200, { connected: false, battery: {}, subsystems: [], apps: [], activeSensors: [], tools: [], sensorConfig: {} });
    }

    const [batRaw, bstatsRaw, pkgRaw, sensorRaw, ambientRes, trajRaw] = await Promise.all([
      adbDeviceShell(dev.serial, 'dumpsys battery', 5000).catch(() => ''),
      adbDeviceShell(dev.serial, "dumpsys batterystats | sed -n '/Estimated power use/,/^  Per-app/p' | head -n 60", 8000).catch(() => ''),
      adbDeviceShell(dev.serial, 'pm list packages -U', 5000).catch(() => ''),
      adbDeviceShell(dev.serial, "dumpsys sensorservice | grep -E 'Connection Number:|status: active|sampling_period'", 5000).catch(() => ''),
      runBroadcast(dev.serial, 'AMBIENT_STATUS').catch(() => ({ data: null })),
      adbDeviceShell(dev.serial, `run-as ${PKG} sh -c 'for f in $(ls -1t files/trajectories/*.jsonl 2>/dev/null | head -n 35); do cat "$f"; done'`, 8000).catch(() => '')
    ]);

    // 1) Battery status
    const battery = { level: null, temperatureC: null, voltageMv: null, source: 'Bateria', charging: false };
    for (const line of String(batRaw).split('\n')) {
      const t = line.trim();
      if (t.startsWith('level:')) battery.level = Number(t.split(':')[1].trim());
      else if (t.startsWith('temperature:')) battery.temperatureC = Number(t.split(':')[1].trim()) / 10;
      else if (t.startsWith('voltage:')) battery.voltageMv = Number(t.split(':')[1].trim());
      else if (t === 'USB powered: true') { battery.source = 'USB'; battery.charging = true; }
      else if (t === 'AC powered: true') { battery.source = 'AC'; battery.charging = true; }
      else if (t === 'Wireless powered: true') { battery.source = 'Wireless'; battery.charging = true; }
    }

    // 2) Map UID -> package name
    const uidMap = { '0': 'root (kernel)', '1000': 'Android System', '1041': 'audioserver', '10001': 'system' };
    for (const line of String(pkgRaw).split('\n')) {
      const m = line.match(/package:([^\s]+)\s+uid:(\d+)/);
      if (m) {
        const pkg = m[1];
        const uidNum = Number(m[2]);
        const key = uidNum >= 10000 ? `u0a${uidNum - 10000}` : String(uidNum);
        uidMap[key] = uidMap[key] ? `${uidMap[key]}, ${pkg}` : pkg;
      }
    }

    // 3) Parse batterystats Estimated power use
    let capacityMah = null;
    let computedDrainMah = null;
    let dischargePct = null;
    const subsystems = [];
    const apps = [];
    let inGlobal = false;

    for (const line of String(bstatsRaw).split('\n')) {
      const capM = line.match(/Capacity:\s*([\d.]+),\s*Computed drain:\s*([\d.]+),.*discharge percentage:\s*(\d+)/);
      if (capM) {
        capacityMah = Number(capM[1]);
        computedDrainMah = Number(capM[2]);
        dischargePct = Number(capM[3]);
        continue;
      }
      if (line.trim() === 'Global') { inGlobal = true; continue; }
      if (inGlobal && line.trim().startsWith('UID ')) { inGlobal = false; }

      if (inGlobal) {
        const gm = line.match(/^\s{6}([a-z_]+):\s*([\d.]+)(?:.*duration:\s*([^\n]+))?/);
        if (gm) {
          subsystems.push({
            name: gm[1],
            mah: Number(gm[2]),
            duration: gm[3] ? gm[3].trim() : ''
          });
        }
      }

      const um = line.match(/^\s*UID\s+([a-z0-9]+):\s*([\d.]+)\s*(.*)$/);
      if (um) {
        const uid = um[1];
        const totalMah = Number(um[2]);
        const detail = um[3] || '';
        const pick = (k) => {
          const r = detail.match(new RegExp(`\\b${k}=([\\d.]+)`));
          return r ? Number(r[1]) : 0;
        };
        const sensDurM = detail.match(/sensors=[\d.]+\s*\(([^)]+)\)/);
        apps.push({
          uid,
          packageName: uidMap[uid] || uid,
          isX0: (uidMap[uid] || '').includes('com.xzero.agent'),
          totalMah,
          screenMah: pick('screen'),
          cpuMah: pick('cpu'),
          sensorsMah: pick('sensors'),
          sensorsDuration: sensDurM ? sensDurM[1] : '',
          cameraMah: pick('camera'),
          wifiMah: pick('wifi'),
          mobileMah: pick('mobile_radio'),
          gnssMah: pick('gnss'),
          wakelockMah: pick('wakelock')
        });
      }
    }

    // 4) Parse active hardware sensors from sensorservice
    const activeSensors = [];
    const sLines = String(sensorRaw).split('\n');
    for (let i = 0; i < sLines.length; i++) {
      const l = sLines[i].trim();
      if (l.includes('| status: active')) {
        activeSensors.push(l.split('|')[0].trim());
      }
    }

    // 5) Sensor config from AMBIENT_STATUS
    let sensorConfig = {};
    try {
      const amb = JSON.parse(ambientRes?.data || '{}');
      sensorConfig = amb.sensor_config || amb.sensors || {};
    } catch (_) {}
    if (!Array.isArray(sensorConfig.sensors_list) || sensorConfig.sensors_list.length === 0) {
      sensorConfig.sensors_list = [
        { key: 'accelerometer', label: 'Acelerômetro', enabled: sensorConfig.accelerometer !== false, critical: true, purpose: 'Shake /btw e pegar o celular da mesa' },
        { key: 'proximity', label: 'Proximidade', enabled: sensorConfig.proximity !== false, critical: true, purpose: 'Detectar bolso/rosto e evitar toque acidental' },
        { key: 'light', label: 'Luz Ambiente', enabled: sensorConfig.light !== false, critical: false, purpose: 'Auto-silêncio em ambiente escuro' },
        { key: 'gyroscope', label: 'Giroscópio', enabled: sensorConfig.gyroscope !== false, critical: false, purpose: 'Rotação fina / movimento' },
        { key: 'steps', label: 'Contador de Passos', enabled: sensorConfig.steps !== false, critical: false, purpose: 'Atividade física / caminhada' }
      ];
    }

    // 6) Tool power impact aggregation from recent trajectories
    const toolStats = {};
    const IMPACT = {
      capture_camera: { level: 'Alto', weight: 4.5, reason: 'Câmera + processamento visual' },
      get_location: { level: 'Alto', weight: 4.0, reason: 'GNSS / GPS ativo' },
      inspect_screen_visual: { level: 'Médio-Alto', weight: 3.2, reason: 'Captura de tela + LLM multimodal' },
      open_app: { level: 'Médio', weight: 2.0, reason: 'Tela ligada + inicialização de app' },
      tap: { level: 'Médio', weight: 1.5, reason: 'Interação UI + dump XML' },
      type: { level: 'Médio', weight: 1.5, reason: 'Teclado + acessibilidade' },
      web_search: { level: 'Médio', weight: 1.8, reason: 'Rádio Wi-Fi/4G + HTTP' },
      mcp_call: { level: 'Médio', weight: 1.4, reason: 'Rede HTTPS Cloudflare / API' },
      send_whatsapp: { level: 'Médio', weight: 1.8, reason: 'App WhatsApp + rede' },
      device_status: { level: 'Baixo', weight: 0.4, reason: 'Leitura pontual de sensores' },
      done: { level: 'Baixo', weight: 0.5, reason: 'TTS + resposta final' }
    };
    for (const line of String(trajRaw).split('\n')) {
      if (!line.trim()) continue;
      try {
        const ev = JSON.parse(line);
        if (ev.type === 'agent_step' && ev.data?.action) {
          const act = ev.data.action;
          if (!toolStats[act]) {
            const meta = IMPACT[act] || { level: 'Baixo', weight: 0.8, reason: 'Execução local/LLM' };
            toolStats[act] = { action: act, calls: 0, level: meta.level, weight: meta.weight, reason: meta.reason, score: 0 };
          }
          toolStats[act].calls += 1;
          toolStats[act].score = Number((toolStats[act].calls * toolStats[act].weight).toFixed(1));
        }
      } catch (_) {}
    }
    const tools = Object.values(toolStats).sort((a, b) => b.score - a.score);

    return sendJson(res, 200, {
      connected: true,
      battery: { ...battery, capacityMah, computedDrainMah, dischargePct },
      subsystems: subsystems.sort((a, b) => b.mah - a.mah),
      apps: apps.sort((a, b) => b.totalMah - a.totalMah).slice(0, 15),
      activeSensors: [...new Set(activeSensors)],
      sensorConfig,
      tools
    });
  }

  if (pathname === '/api/energy/sensors' && req.method === 'POST') {
    const dev = await getActiveDevice();
    if (!dev.connected) return sendJson(res, 400, { error: 'Celular desconectado do ADB' });
    const body = await readRequestBody(req);
    const r = await runBroadcast(dev.serial, 'EXEC_TOOL', {
      action: 'configure_sensors',
      args_json: JSON.stringify(body || {})
    });
    return sendJson(res, 200, { ok: true, result: r.data || r.raw });
  }

  if (pathname === '/monitoring' && req.method === 'GET') {
    const monPath = path.join(__dirname, 'monitoring.html');
    if (fs.existsSync(monPath)) {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      fs.createReadStream(monPath).pipe(res);
      return;
    }
  }

  // 7c. Bridge unificada da porta :3030 do celular (Raciocínio, Erros, Harness Insights, Áudio/VAD)
  if (pathname === '/api/monitoring3030' && req.method === 'GET') {
    const dev = await getActiveDevice();
    if (!dev.connected) {
      return sendJson(res, 200, { connected: false, thinking: {}, errors: {}, harness: {}, audio: {}, metrics: {} });
    }
    try {
      await execAdb(['-s', dev.serial, 'forward', 'tcp:3031', 'tcp:3030'], 3000);
    } catch (_) {}

    const fetchLocal = (ep) => new Promise((resolve) => {
      const r = http.get(`http://127.0.0.1:3031${ep}`, { timeout: 2500 }, (resp) => {
        let data = '';
        resp.on('data', (c) => data += c);
        resp.on('end', () => {
          try { resolve(JSON.parse(data)); } catch (_) { resolve({}); }
        });
      });
      r.on('error', () => resolve({}));
      r.on('timeout', () => { r.destroy(); resolve({}); });
    });

    const [thinking, errors, harness, audio, metrics] = await Promise.all([
      fetchLocal('/api/agent/thinking'),
      fetchLocal('/api/agent/errors'),
      fetchLocal('/api/agent/harness-insights'),
      fetchLocal('/api/agent/audio'),
      fetchLocal('/api/agent/metrics')
    ]);

    return sendJson(res, 200, { connected: true, thinking, errors, harness, audio, metrics });
  }

  // 8. Dispatch Action / Command to XZERO
  if (pathname === '/api/action' && req.method === 'POST') {
    const dev = await getActiveDevice();
    if (!dev.connected) return sendJson(res, 400, { error: 'Celular desconectado do ADB' });
    const body = await readRequestBody(req);
    const type = String(body.type || 'ask').toLowerCase();

    try {
      if (type === 'ask') {
        const text = String(body.text || '').trim();
        if (!text) return sendJson(res, 400, { error: 'Digite um comando ou objetivo para o XZERO' });
        const r = await runBroadcast(dev.serial, 'ASK', { text });
        return sendJson(res, 200, { ok: true, action: 'ASK', response: r.data || r.raw });
      }
      if (type === 'wake') {
        const text = String(body.text || 'Ok agente').trim();
        const r = await runBroadcast(dev.serial, 'WAKE', { text });
        return sendJson(res, 200, { ok: true, action: 'WAKE', response: r.data || r.raw });
      }
      if (type === 'listen') {
        const r = await runBroadcast(dev.serial, 'LISTEN');
        return sendJson(res, 200, { ok: true, action: 'LISTEN', response: r.data || r.raw });
      }
      if (type === 'health') {
        const r = await runBroadcast(dev.serial, 'HEALTH', { heal: true });
        return sendJson(res, 200, { ok: true, action: 'HEALTH', response: r.data ? JSON.parse(r.data) : r.raw });
      }
      if (type === 'recover') {
        const r = await runBroadcast(dev.serial, 'RECOVER');
        return sendJson(res, 200, { ok: true, action: 'RECOVER', response: r.data || r.raw });
      }
      if (type === 'adb_wifi') {
        await execAdb(['-s', dev.serial, 'tcpip', '5555']);
        const ipOut = await adbDeviceShell(dev.serial, "ip route | awk '/wlan0/ {print $9}' | head -n 1");
        const ip = (ipOut || '').trim() || '';
        const connOut = await execAdb(['connect', `${ip}:5555`]).catch((e) => e.message);
        // A topologia de conexão acabou de mudar: o cache de 2,5s ficaria mentindo.
        invalidateDeviceCache();
        return sendJson(res, 200, { ok: true, action: 'ADB_WIFI', ip: `${ip}:5555`, response: connOut.trim() });
      }
      if (type === 'btw') {
        const text = String(body.text || '').trim();
        if (!text) return sendJson(res, 400, { error: 'Informe o contexto paralelo (/btw)' });
        const r = await runBroadcast(dev.serial, 'BTW', { text });
        return sendJson(res, 200, { ok: true, action: 'BTW', response: r.data || r.raw });
      }
      return sendJson(res, 400, { error: `Tipo de ação desconhecido: ${type}` });
    } catch (e) {
      return sendJson(res, 500, { error: e.message });
    }
  }

  // 8b. Ambient Short-Term Voice Memory + MCP Status + Built-in Demo MCP Server
  if (pathname === '/api/ambient' && req.method === 'GET') {
    const dev = await getActiveDevice();
    if (!dev.connected) return sendJson(res, 200, { connected: false, ambient: null, mcp: { servers: [] } });
    try {
      const [ambBroadcast, mcpFile] = await Promise.all([
        runBroadcast(dev.serial, 'AMBIENT_STATUS').catch(() => ({ data: null })),
        readAppJsonFile(dev.serial, 'files/mcp_servers.json', { servers: [] })
      ]);
      let parsed = {};
      if (ambBroadcast && ambBroadcast.data) {
        try { parsed = JSON.parse(ambBroadcast.data); } catch (_) {}
      }
      return sendJson(res, 200, {
        connected: true,
        ambient: parsed.ambient || null,
        mcp: parsed.mcp || mcpFile
      });
    } catch (e) {
      return sendJson(res, 500, { error: e.message });
    }
  }

  if (pathname === '/api/mcp' && req.method === 'POST') {
    const dev = await getActiveDevice();
    if (!dev.connected) return sendJson(res, 400, { error: 'Celular desconectado do ADB' });
    const body = await readRequestBody(req);
    const op = String(body.op || 'add').toLowerCase();
    const r = await runBroadcast(dev.serial, 'MCP', {
      op,
      name: String(body.name || '').trim(),
      url: String(body.url || '').trim(),
      token: String(body.token || '').trim()
    });
    return sendJson(res, 200, { ok: true, response: r.data || r.raw });
  }

  // Built-in JSON-RPC 2.0 MCP Server endpoint so XZERO can test & use Host Host MCP tools immediately!
  if ((pathname === '/api/mcp-demo' || pathname.startsWith('/api/mcp/')) && req.method === 'POST') {
    const body = await readRequestBody(req);
    const method = body.method || '';
    const id = body.id || 1;
    const mcpBridge = require('../mcp/xzero_mcp_server.js');
    const subKey = pathname.startsWith('/api/mcp/') ? pathname.replace('/api/mcp/', '') : 'all';
    const srv = mcpBridge.PHONE_FACING_SERVERS[subKey];
    const tools = srv ? srv.tools : mcpBridge.ALL_PHONE_TOOLS;
    if (method === 'tools/list') {
      return sendJson(res, 200, {
        jsonrpc: '2.0',
        id,
        result: { tools }
      });
    }
    if (method === 'tools/call') {
      const toolName = (body.params && body.params.name) || '';
      const args = (body.params && body.params.arguments) || {};
      const outText = srv
        ? await srv.callTool(toolName, args)
        : await mcpBridge.callAnyPhoneFacingTool(toolName, args);
      return sendJson(res, 200, {
        jsonrpc: '2.0',
        id,
        result: {
          content: [{ type: 'text', text: String(outText) }]
        }
      });
    }
    return sendJson(res, 200, { jsonrpc: '2.0', id, result: { ok: true } });
  }

  // 8c. One-Click Backup & Restore / Reinstall Endpoints
  if ((pathname === '/api/backup' || pathname === '/api/restore') && req.method === 'POST') {
    const modeFlag = pathname === '/api/backup' ? '--backup' : '--restore';
    const scriptPath = path.join(__dirname, '..', 'restore-xzero.sh');
    return new Promise((resolve) => {
      execFile('bash', [scriptPath, modeFlag], { timeout: 60000 }, (err, stdout, stderr) => {
        if (err) {
          sendJson(res, 500, { ok: false, error: stderr || err.message, output: stdout });
        } else {
          sendJson(res, 200, { ok: true, mode: modeFlag, output: stdout });
        }
        resolve();
      });
    });
  }

  if (pathname === '/api/download-apk' && req.method === 'GET') {
    const relApk = path.join(__dirname, '..', 'releases', 'xzero-latest.apk');
    const buildApk = path.join(__dirname, '..', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
    const targetApk = fs.existsSync(relApk) ? relApk : buildApk;
    if (!fs.existsSync(targetApk)) {
      return sendJson(res, 404, { error: 'APK ainda não compilado' });
    }
    const stat = fs.statSync(targetApk);
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Disposition': 'attachment; filename="xzero-v3-latest.apk"',
      'Content-Length': stat.size
    });
    fs.createReadStream(targetApk).pipe(res);
    return;
  }

  // 9. Update LLM & Agent Configuration
  if (pathname === '/api/config' && req.method === 'POST') {
    const dev = await getActiveDevice();
    if (!dev.connected) return sendJson(res, 400, { error: 'Celular desconectado do ADB' });
    const body = await readRequestBody(req);
    const extras = {};
    if (body.gemini_key && !body.gemini_key.includes('••')) extras.gemini_key = body.gemini_key.trim();
    if (body.gemini_model) extras.gemini_model = body.gemini_model.trim();
    if (body.claude_key && !body.claude_key.includes('••')) extras.claude_key = body.claude_key.trim();
    if (body.claude_model) extras.claude_model = body.claude_model.trim();
    if (body.openai_key && !body.openai_key.includes('••')) extras.openai_key = body.openai_key.trim();
    if (body.openai_model) extras.openai_model = body.openai_model.trim();
    if (body.primary) extras.primary = body.primary.trim();
    if (body.wake_word !== undefined) extras.wake_word = String(Boolean(body.wake_word));
    if (body.speak !== undefined) extras.speak = String(Boolean(body.speak));
    if (body.subtle_sound !== undefined) extras.subtle_sound = String(Boolean(body.subtle_sound));

    try {
      const r = await runBroadcast(dev.serial, 'CONFIG', extras);
      return sendJson(res, 200, { ok: true, response: r.data || r.raw });
    } catch (e) {
      return sendJson(res, 500, { error: e.message });
    }
  }

  // 10. Live Phone Screen Capture
  if (pathname === '/api/screen' && req.method === 'GET') {
    const dev = await getActiveDevice();
    if (!dev.connected) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      return res.end('Device offline');
    }
    const child = spawn(ADB_BIN, ['-s', dev.serial, 'exec-out', 'screencap', '-p']);
    const chunks = [];
    child.stdout.on('data', (c) => chunks.push(c));
    child.on('close', (code) => {
      if (code !== 0 || chunks.length === 0) {
        res.writeHead(500, { 'Content-Type': 'text/plain' });
        return res.end('Failed to capture screen');
      }
      const buf = Buffer.concat(chunks);
      res.writeHead(200, {
        'Content-Type': 'image/png',
        'Cache-Control': 'no-store'
      });
      res.end(buf);
    });
    return;
  }

  return sendJson(res, 404, { error: 'Endpoint não encontrado' });
}

/*
 * Contador de chamadas por endpoint, exposto em /api/_selfstat.
 *
 * Observabilidade do próprio painel: antes não havia como saber quantas idas ao aparelho
 * o painel estava gerando — só dava para inferir pelo consumo de bateria. Com isto, a
 * pergunta "o painel está pesando no celular?" tem resposta numérica.
 */
const selfStat = { since: Date.now(), byPath: {}, adbSpawns: 0, coalescedHits: 0 };

const SNAPSHOT_FILE = path.join(__dirname, '..', 'backups', 'dashboard_snapshot.json');
let snapshotStore = {};
try {
  if (fs.existsSync(SNAPSHOT_FILE)) {
    snapshotStore = JSON.parse(fs.readFileSync(SNAPSHOT_FILE, 'utf8')) || {};
  }
} catch (_) {
  snapshotStore = {};
}

function applyDashboardSnapshot(pathname, payload) {
  if (!payload || typeof payload !== 'object') return payload;
  if (payload.connected === true) {
    snapshotStore[pathname] = { savedAt: Date.now(), data: payload };
    try {
      fs.mkdirSync(path.dirname(SNAPSHOT_FILE), { recursive: true });
      fs.writeFileSync(SNAPSHOT_FILE, JSON.stringify(snapshotStore), { mode: 0o600 });
    } catch (_) {}
    return payload;
  }
  if (payload.connected === false && snapshotStore[pathname]?.data) {
    const saved = snapshotStore[pathname];
    return {
      ...saved.data,
      connected: false,
      cached: true,
      last_sync_at: saved.savedAt,
      adb: payload.adb || { connected: false, serial: null, transport: 'offline' }
    };
  }
  return payload;
}

const server = http.createServer(async (req, res) => {
  try {
    if (!(await isDashboardAuthorized(req))) {
      res.writeHead(401, {
        'Content-Type': 'application/json; charset=utf-8',
        'WWW-Authenticate': 'Basic realm="X-Zero Dashboard"'
      });
      return res.end(JSON.stringify({ error: 'Unauthorized: requer Cloudflare Access (xzero) ou login de dono' }));
    }
    const parsedUrl = url.parse(req.url, true);
    const pathname = parsedUrl.pathname || '/';

    if (pathname === '/api/_selfstat' && req.method === 'GET') {
      const upMs = Date.now() - selfStat.since;
      return sendJsonRaw(res, 200, {
        uptime_sec: Math.round(upMs / 1000),
        adb_spawns: selfStat.adbSpawns,
        adb_spawns_per_min: Number((selfStat.adbSpawns / (upMs / 60000)).toFixed(1)),
        coalesced_hits: selfStat.coalescedHits,
        requests_by_path: selfStat.byPath
      });
    }

    /*
     * /monitoring é tratado dentro de handleApi, mas só /api/* chegava lá — o botão
     * "🌐 Abrir /monitoring" que o próprio painel renderiza devolvia 404 em produção.
     * Servido aqui, no mesmo lugar dos outros arquivos estáticos, que é onde ele mora.
     */
    if (pathname === '/monitoring' && req.method === 'GET') {
      const monPath = path.join(__dirname, 'monitoring.html');
      if (fs.existsSync(monPath)) {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
        return fs.createReadStream(monPath).pipe(res);
      }
    }

    // Proxy transparente de /harness e /api/agent/* para a porta :3030 do celular (via adb forward :3031)
    if (pathname === '/harness' || pathname.startsWith('/api/agent/')) {
      const dev = await getActiveDevice();
      if (dev.connected) {
        await execAdb(['-s', dev.serial, 'forward', 'tcp:3031', 'tcp:3030'], { timeout: 2500 }).catch(() => {});
      }
      const targetReq = http.request(
        {
          hostname: '127.0.0.1',
          port: 3031,
          path: req.url,
          method: req.method,
          headers: { ...req.headers, host: '127.0.0.1:3031' },
          timeout: pathname === '/api/agent/stream' ? 0 : 8000
        },
        (targetRes) => {
          res.writeHead(targetRes.statusCode || 200, targetRes.headers);
          targetRes.pipe(res);
        }
      );
      targetReq.on('error', () => {
        if (!res.headersSent) {
          sendJsonRaw(res, 502, { error: 'Monitor :3030 do celular indisponível no momento' });
        }
      });
      req.pipe(targetReq);
      return;
    }

    if (pathname.startsWith('/api/')) {
      const t0 = Date.now();
      selfStat.byPath[pathname] = (selfStat.byPath[pathname] || 0) + 1;
      // Só GET de leitura entra na coalescência; escrita sempre vai sozinha ao aparelho.
      const coalescible = req.method === 'GET' && COALESCIBLE_READS.has(pathname);
      if (coalescible) {
        const key = `${pathname}?${parsedUrl.search || ''}`;
        const { value: captured, hit } = await coalesceRead(key, async () => {
          const buffer = createResponseBuffer();
          await handleApi(req, buffer, parsedUrl);
          return buffer.captured;
        });
        if (hit) selfStat.coalescedHits += 1;
        res.setHeader('Server-Timing', `api;dur=${Date.now() - t0}, coalesced;desc="${hit ? 'hit' : 'miss'}"`);
        const finalPayload = applyDashboardSnapshot(pathname, captured.payload);
        return sendJsonRaw(res, captured.status, finalPayload);
      }
      await handleApi(req, res, parsedUrl);
      return;
    }

    if (pathname === '/xenon-spritesheet.png') {
      const localSprite = path.join(PUBLIC_DIR, 'xenon-spritesheet.png');
      const targetSprite = fs.existsSync(localSprite) ? localSprite : SPRITESHEET_FALLBACK;
      if (fs.existsSync(targetSprite)) {
        res.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'public, max-age=3600' });
        return fs.createReadStream(targetSprite).pipe(res);
      }
    }

    if (pathname === '/install.sh' || pathname === '/restore-xzero.sh') {
      const scriptPath = path.join(__dirname, '..', 'restore-xzero.sh');
      if (fs.existsSync(scriptPath)) {
        res.writeHead(200, { 'Content-Type': 'text/x-shellscript; charset=utf-8', 'Cache-Control': 'no-store' });
        return fs.createReadStream(scriptPath).pipe(res);
      }
    }

    const indexPath = path.join(PUBLIC_DIR, 'index.html');
    if (pathname === '/' || pathname === '/index.html') {
      const html = fs.readFileSync(indexPath, 'utf8');
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      return res.end(html);
    }

    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Not found');
  } catch (err) {
    sendJson(res, 500, { error: err.message });
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[XZERO-DASHBOARD] Control Panel & Observability running on http://127.0.0.1:${PORT}`);
});

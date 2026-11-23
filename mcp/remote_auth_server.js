#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { createRequire } = require('module');

function loadEnvFile(envPath) {
  if (!fs.existsSync(envPath)) return;
  const raw = fs.readFileSync(envPath, 'utf8');
  for (const line of raw.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const eq = trimmed.indexOf('=');
    if (eq <= 0) continue;
    const key = trimmed.slice(0, eq).trim();
    const val = trimmed.slice(eq + 1).trim();
    if (process.env[key] === undefined) {
      process.env[key] = val;
    }
  }
}

loadEnvFile(path.join(__dirname, '..', '.env.mcp'));

function requireFromPaths(id) {
  const roots = [
    __dirname,
    path.join(__dirname, '..'),
  ];
  for (const root of roots) {
    try {
      const r = createRequire(path.join(root, 'package.json'));
      return r(id);
    } catch {
      // try next root
    }
  }
  return require(id);
}

const express = requireFromPaths('express');
const cors = requireFromPaths('cors');
const Database = requireFromPaths('better-sqlite3');
const { StreamableHTTPServerTransport } = requireFromPaths('@modelcontextprotocol/sdk/server/streamableHttp.js');
const {
  mcpAuthRouter,
  getOAuthProtectedResourceMetadataUrl,
} = requireFromPaths('@modelcontextprotocol/sdk/server/auth/router.js');
const { requireBearerAuth } = requireFromPaths('@modelcontextprotocol/sdk/server/auth/middleware/bearerAuth.js');
const {
  InvalidTokenError,
  InvalidGrantError,
  AccessDeniedError,
} = requireFromPaths('@modelcontextprotocol/sdk/server/auth/errors.js');
const { isInitializeRequest } = requireFromPaths('@modelcontextprotocol/sdk/types.js');

let josePromise = null;
async function getJose() {
  if (!josePromise) {
    josePromise = Promise.resolve(requireFromPaths('jose'));
  }
  return josePromise;
}

const { createXzeroMcpServer } = require('./xzero_mcp_server.js');

const PORT = Number(process.env.XZERO_MCP_PORT || 3033);
const HOST = process.env.XZERO_MCP_HOST || '127.0.0.1';
if (!process.env.XZERO_MCP_PUBLIC_URL || !process.env.XZERO_ALLOWED_EMAIL || !process.env.XZERO_OWNER_PASSWORD) {
  console.error('[xzero-mcp-remote] Defina XZERO_MCP_PUBLIC_URL, XZERO_ALLOWED_EMAIL e XZERO_OWNER_PASSWORD em .env.mcp (veja .env.mcp.example).');
  process.exit(1);
}
const PUBLIC_URL_STR = process.env.XZERO_MCP_PUBLIC_URL.replace(/\/+$/, '');
const PUBLIC_URL = new URL(`${PUBLIC_URL_STR}/`);
const MCP_RESOURCE_URL = new URL(`${PUBLIC_URL_STR}/mcp`);
const ALLOWED_EMAIL = process.env.XZERO_ALLOWED_EMAIL.trim().toLowerCase();
const STATIC_BEARER_TOKEN = (process.env.XZERO_MCP_BEARER_TOKEN || '').trim();
const OWNER_PASSWORD = (process.env.XZERO_OWNER_PASSWORD || '').trim();
const CF_ACCESS_TEAM_DOMAIN = (process.env.CF_ACCESS_TEAM_DOMAIN || '').trim();
const CF_ACCESS_AUD = (process.env.CF_ACCESS_AUD || '').trim();
const GOOGLE_OAUTH_ENABLED = process.env.XZERO_GOOGLE_OAUTH_ENABLED === '1'
  && Boolean(process.env.GOOGLE_CLIENT_ID)
  && Boolean(process.env.GOOGLE_CLIENT_SECRET);
const GOOGLE_CLIENT_ID = (process.env.GOOGLE_CLIENT_ID || '').trim();
const GOOGLE_CLIENT_SECRET = (process.env.GOOGLE_CLIENT_SECRET || '').trim();
const GOOGLE_REDIRECT_URI = `${PUBLIC_URL_STR}/mcp/google/callback`;

const DATA_DIR = path.join(__dirname, 'data');
fs.mkdirSync(DATA_DIR, { recursive: true, mode: 0o700 });
const DB_PATH = path.join(DATA_DIR, 'xzero-oauth.db');
const db = new Database(DB_PATH);
try { fs.chmodSync(DB_PATH, 0o600); } catch {}
db.pragma('journal_mode = WAL');

db.exec(`
  CREATE TABLE IF NOT EXISTS oauth_clients (
    client_id TEXT PRIMARY KEY,
    data TEXT NOT NULL,
    created_at INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS oauth_pending (
    id TEXT PRIMARY KEY,
    client_id TEXT NOT NULL,
    redirect_uri TEXT NOT NULL,
    state TEXT,
    code_challenge TEXT NOT NULL,
    scopes TEXT,
    resource TEXT,
    csrf_token TEXT NOT NULL,
    verified_email TEXT,
    auth_method TEXT,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS oauth_codes (
    code TEXT PRIMARY KEY,
    client_id TEXT NOT NULL,
    redirect_uri TEXT NOT NULL,
    code_challenge TEXT NOT NULL,
    scopes TEXT NOT NULL,
    resource TEXT,
    user_email TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS oauth_tokens (
    token TEXT PRIMARY KEY,
    token_type TEXT NOT NULL,
    client_id TEXT NOT NULL,
    scopes TEXT NOT NULL,
    resource TEXT,
    user_email TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts INTEGER NOT NULL,
    event TEXT NOT NULL,
    ip TEXT,
    detail TEXT
  );
`);

function audit(event, ip, detail) {
  try {
    db.prepare('INSERT INTO audit_log (ts, event, ip, detail) VALUES (?, ?, ?, ?)')
      .run(Math.floor(Date.now() / 1000), String(event), String(ip || ''), String(detail || '').slice(0, 500));
  } catch {}
}

function timingSafeEqualStr(a, b) {
  if (!a || !b) return false;
  const ha = crypto.createHash('sha256').update(String(a), 'utf8').digest();
  const hb = crypto.createHash('sha256').update(String(b), 'utf8').digest();
  return crypto.timingSafeEqual(ha, hb);
}

function getClientIp(req) {
  const cf = req.headers['cf-connecting-ip'];
  if (cf) return String(cf);
  return req.ip || req.socket?.remoteAddress || 'unknown';
}

const loginRateMap = new Map();
function checkLoginRateLimit(ip) {
  const now = Date.now();
  const windowMs = 15 * 60 * 1000;
  const entry = loginRateMap.get(ip) || { count: 0, resetAt: now + windowMs };
  if (now > entry.resetAt) {
    entry.count = 0;
    entry.resetAt = now + windowMs;
  }
  loginRateMap.set(ip, entry);
  return entry;
}

function cleanupExpired() {
  const now = Math.floor(Date.now() / 1000);
  db.prepare('DELETE FROM oauth_pending WHERE expires_at < ?').run(now);
  db.prepare('DELETE FROM oauth_codes WHERE expires_at < ?').run(now);
  db.prepare('DELETE FROM oauth_tokens WHERE expires_at < ?').run(now);
}
setInterval(cleanupExpired, 5 * 60 * 1000).unref();

let cfJwksCache = null;
async function verifyCloudflareAccessJwt(req) {
  if (!CF_ACCESS_TEAM_DOMAIN) return null;
  let token = req.headers['cf-access-jwt-assertion'];
  if (!token && req.headers.cookie) {
    const match = String(req.headers.cookie).match(/(?:^|;\s*)CF_Authorization=([^;]+)/);
    if (match) token = decodeURIComponent(match[1]);
  }
  if (!token || typeof token !== 'string') return null;

  try {
    const jose = await getJose();
    const issuer = `https://${CF_ACCESS_TEAM_DOMAIN.replace(/^https?:\/\//, '').replace(/\/+$/, '')}`;
    if (!cfJwksCache) {
      cfJwksCache = jose.createRemoteJWKSet(new URL(`${issuer}/cdn-cgi/access/certs`));
    }
    const opts = { issuer };
    if (CF_ACCESS_AUD) opts.audience = CF_ACCESS_AUD;
    const { payload } = await jose.jwtVerify(token, cfJwksCache, opts);
    const email = String(payload?.email || '').trim().toLowerCase();
    if (!email || email !== ALLOWED_EMAIL) {
      return { ok: false, email, reason: 'email_not_allowed' };
    }
    return { ok: true, email, sub: payload.sub || email };
  } catch (err) {
    return { ok: false, reason: err?.message || 'cf_jwt_invalid' };
  }
}

function escapeHtml(s) {
  return String(s || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function securityHeaders(res) {
  res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, private');
  res.setHeader('Pragma', 'no-cache');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Robots-Tag', 'noindex, nofollow');
  res.setHeader('Referrer-Policy', 'no-referrer');
}

function renderLoginHtml({ pending, client, cfResult, errorMsg, googleEnabled }) {
  const clientName = client?.client_name || client?.client_id || 'Cliente MCP';
  const redirectHost = (() => {
    try { return new URL(pending.redirect_uri).host; } catch { return pending.redirect_uri; }
  })();
  const scopes = (pending.scopes || 'mcp:tools').split(/\s+/).filter(Boolean).join(', ');
  const cfVerified = Boolean(cfResult?.ok && cfResult.email === ALLOWED_EMAIL)
    || (pending.verified_email === ALLOWED_EMAIL);
  const verifiedBadgeText = cfResult?.ok
    ? `Cloudflare Access verificado: ${ALLOWED_EMAIL}`
    : (pending.verified_email === ALLOWED_EMAIL
      ? `${pending.auth_method || 'Google'} verificado: ${ALLOWED_EMAIL}`
      : '');

  return `<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>X0 Phone MCP — Autorização Segura</title>
  <style>
    :root { color-scheme: dark; }
    body {
      margin: 0; font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
      background: #0b0f17; color: #e5e7eb; min-height: 100vh;
      display: flex; align-items: center; justify-content: center; padding: 20px;
    }
    .card {
      width: 100%; max-width: 440px; background: #121826;
      border: 1px solid #243047; border-radius: 16px; padding: 26px;
      box-shadow: 0 20px 60px rgba(0,0,0,.55);
    }
    .badge {
      display: inline-block; font-size: 12px; font-weight: 600;
      padding: 4px 10px; border-radius: 999px;
      background: rgba(56,189,248,.12); color: #38bdf8; border: 1px solid rgba(56,189,248,.28);
    }
    h1 { font-size: 20px; margin: 12px 0 6px; color: #f8fafc; }
    p { font-size: 14px; color: #94a3b8; line-height: 1.5; margin: 0 0 16px; }
    .meta {
      background: #0d1320; border: 1px solid #1e293b; border-radius: 10px;
      padding: 12px 14px; margin-bottom: 16px; font-size: 13px;
    }
    .meta div { margin: 4px 0; }
    .meta strong { color: #cbd5e1; }
    .ok-box {
      background: rgba(34,197,94,.12); border: 1px solid rgba(34,197,94,.35);
      color: #86efac; padding: 12px 14px; border-radius: 10px; margin-bottom: 16px; font-size: 13px;
    }
    .err-box {
      background: rgba(239,68,68,.12); border: 1px solid rgba(239,68,68,.35);
      color: #fca5a5; padding: 12px 14px; border-radius: 10px; margin-bottom: 16px; font-size: 13px;
    }
    label { display: block; font-size: 13px; font-weight: 600; color: #cbd5e1; margin: 10px 0 6px; }
    input[type=email], input[type=password] {
      width: 100%; box-sizing: border-box; padding: 11px 12px; border-radius: 10px;
      border: 1px solid #334155; background: #0b1120; color: #f8fafc; font-size: 14px;
    }
    .btn {
      width: 100%; margin-top: 14px; padding: 12px 16px; border: 0; border-radius: 10px;
      font-size: 14px; font-weight: 600; cursor: pointer;
    }
    .btn-primary { background: #2563eb; color: #fff; }
    .btn-primary:hover { background: #1d4ed8; }
    .btn-google {
      display: block; text-align: center; text-decoration: none; box-sizing: border-box;
      background: #1e293b; color: #f8fafc; border: 1px solid #334155;
    }
    .btn-danger {
      background: transparent; color: #94a3b8; border: 1px solid #334155; margin-top: 10px;
    }
    .owner-lock {
      margin-top: 14px; font-size: 12px; color: #64748b; text-align: center;
    }
  </style>
</head>
<body>
  <div class="card">
    <span class="badge">X0 Phone MCP · Acesso Exclusivo</span>
    <h1>Autorizar Conexão Segura</h1>
    <p>Este servidor controla seu celular Android via ADB/MCP e permite acesso somente à conta autorizada.</p>

    <div class="meta">
      <div><strong>Aplicativo:</strong> ${escapeHtml(clientName)}</div>
      <div><strong>Destino OAuth:</strong> ${escapeHtml(redirectHost)}</div>
      <div><strong>Escopos:</strong> ${escapeHtml(scopes)}</div>
      <div><strong>Conta permitida:</strong> ${escapeHtml(ALLOWED_EMAIL)}</div>
    </div>

    ${errorMsg ? `<div class="err-box">${escapeHtml(errorMsg)}</div>` : ''}

    ${cfVerified ? `
      <div class="ok-box">${escapeHtml(verifiedBadgeText)}</div>
      <form method="POST" action="/mcp/approve">
        <input type="hidden" name="pending_id" value="${escapeHtml(pending.id)}" />
        <input type="hidden" name="csrf" value="${escapeHtml(pending.csrf_token)}" />
        <input type="hidden" name="action" value="allow" />
        <button class="btn btn-primary" type="submit">Autorizar ${escapeHtml(clientName)}</button>
      </form>
    ` : `
      ${googleEnabled ? `
        <a class="btn btn-google" href="/mcp/google/start?pending=${encodeURIComponent(pending.id)}">
          Entrar com Google (${escapeHtml(ALLOWED_EMAIL)})
        </a>
      ` : ''}
      <form method="POST" action="/mcp/approve">
        <input type="hidden" name="pending_id" value="${escapeHtml(pending.id)}" />
        <input type="hidden" name="csrf" value="${escapeHtml(pending.csrf_token)}" />
        <input type="hidden" name="action" value="allow" />
        <label for="email">E-mail autorizado</label>
        <input id="email" name="email" type="email" autocomplete="email" placeholder="${escapeHtml(ALLOWED_EMAIL)}" required />
        <label for="password">Chave do proprietário</label>
        <input id="password" name="password" type="password" autocomplete="current-password" placeholder="Senha mestra configurada em .env.mcp" required />
        <button class="btn btn-primary" type="submit">Verificar e Autorizar</button>
      </form>
    `}

    <form method="POST" action="/mcp/approve">
      <input type="hidden" name="pending_id" value="${escapeHtml(pending.id)}" />
      <input type="hidden" name="csrf" value="${escapeHtml(pending.csrf_token)}" />
      <input type="hidden" name="action" value="deny" />
      <button class="btn btn-danger" type="submit">Recusar</button>
    </form>

    <div class="owner-lock">Protegido por OAuth 2.1 + PKCE · Zero Trust</div>
  </div>
</body>
</html>`;
}

const clientsStore = {
  getClient(clientId) {
    const row = db.prepare('SELECT data FROM oauth_clients WHERE client_id = ?').get(clientId);
    if (!row) return undefined;
    try { return JSON.parse(row.data); } catch { return undefined; }
  },
  registerClient(client) {
    const clientId = client.client_id || crypto.randomUUID();
    const record = {
      ...client,
      client_id: clientId,
      client_id_issued_at: client.client_id_issued_at || Math.floor(Date.now() / 1000),
    };
    db.prepare('INSERT OR REPLACE INTO oauth_clients (client_id, data, created_at) VALUES (?, ?, ?)')
      .run(clientId, JSON.stringify(record), Math.floor(Date.now() / 1000));
    audit('client_registered', '', `${clientId} (${client.client_name || 'unnamed'})`);
    return record;
  },
};

const oauthProvider = {
  clientsStore,

  async authorize(client, params, res) {
    const pendingId = crypto.randomBytes(24).toString('hex');
    const csrfToken = crypto.randomBytes(24).toString('hex');
    const now = Math.floor(Date.now() / 1000);
    const scopes = Array.isArray(params.scopes) && params.scopes.length
      ? params.scopes.join(' ')
      : 'mcp:tools';

    db.prepare(`
      INSERT INTO oauth_pending (
        id, client_id, redirect_uri, state, code_challenge, scopes, resource, csrf_token, created_at, expires_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run(
      pendingId,
      client.client_id,
      params.redirectUri,
      params.state || null,
      params.codeChallenge,
      scopes,
      params.resource ? params.resource.toString() : null,
      csrfToken,
      now,
      now + 900,
    );

    res.redirect(`/mcp/login?pending=${encodeURIComponent(pendingId)}`);
  },

  async challengeForAuthorizationCode(client, authorizationCode) {
    const row = db.prepare('SELECT * FROM oauth_codes WHERE code = ?').get(authorizationCode);
    if (!row || row.client_id !== client.client_id) {
      throw new InvalidGrantError('Invalid authorization code');
    }
    if (row.expires_at < Math.floor(Date.now() / 1000)) {
      db.prepare('DELETE FROM oauth_codes WHERE code = ?').run(authorizationCode);
      throw new InvalidGrantError('Expired authorization code');
    }
    return row.code_challenge;
  },

  async exchangeAuthorizationCode(client, authorizationCode, _codeVerifier, redirectUri) {
    const row = db.prepare('SELECT * FROM oauth_codes WHERE code = ?').get(authorizationCode);
    if (!row || row.client_id !== client.client_id) {
      throw new InvalidGrantError('Invalid authorization code');
    }
    db.prepare('DELETE FROM oauth_codes WHERE code = ?').run(authorizationCode);

    const now = Math.floor(Date.now() / 1000);
    if (row.expires_at < now) {
      throw new InvalidGrantError('Expired authorization code');
    }
    if (redirectUri && row.redirect_uri !== redirectUri) {
      throw new InvalidGrantError('Redirect URI mismatch');
    }
    if (row.user_email !== ALLOWED_EMAIL) {
      throw new AccessDeniedError('Unauthorized owner email');
    }

    const accessToken = `xz_at_${crypto.randomBytes(32).toString('hex')}`;
    const refreshToken = `xz_rt_${crypto.randomBytes(32).toString('hex')}`;
    const accessExpiresIn = 30 * 24 * 3600;
    const refreshExpiresIn = 90 * 24 * 3600;

    db.prepare(`
      INSERT INTO oauth_tokens (token, token_type, client_id, scopes, resource, user_email, created_at, expires_at)
      VALUES (?, 'access', ?, ?, ?, ?, ?, ?)
    `).run(accessToken, client.client_id, row.scopes, row.resource, row.user_email, now, now + accessExpiresIn);

    db.prepare(`
      INSERT INTO oauth_tokens (token, token_type, client_id, scopes, resource, user_email, created_at, expires_at)
      VALUES (?, 'refresh', ?, ?, ?, ?, ?, ?)
    `).run(refreshToken, client.client_id, row.scopes, row.resource, row.user_email, now, now + refreshExpiresIn);

    audit('token_issued', '', `client=${client.client_id} user=${row.user_email}`);

    return {
      access_token: accessToken,
      token_type: 'bearer',
      expires_in: accessExpiresIn,
      refresh_token: refreshToken,
      scope: row.scopes,
    };
  },

  async exchangeRefreshToken(client, refreshToken, scopes) {
    const row = db.prepare('SELECT * FROM oauth_tokens WHERE token = ? AND token_type = ?').get(refreshToken, 'refresh');
    if (!row || row.client_id !== client.client_id) {
      throw new InvalidGrantError('Invalid refresh token');
    }
    const now = Math.floor(Date.now() / 1000);
    if (row.expires_at < now || row.user_email !== ALLOWED_EMAIL) {
      db.prepare('DELETE FROM oauth_tokens WHERE token = ?').run(refreshToken);
      throw new InvalidGrantError('Expired or unauthorized refresh token');
    }

    const finalScopes = Array.isArray(scopes) && scopes.length ? scopes.join(' ') : row.scopes;
    const newAccessToken = `xz_at_${crypto.randomBytes(32).toString('hex')}`;
    const accessExpiresIn = 30 * 24 * 3600;

    db.prepare(`
      INSERT INTO oauth_tokens (token, token_type, client_id, scopes, resource, user_email, created_at, expires_at)
      VALUES (?, 'access', ?, ?, ?, ?, ?, ?)
    `).run(newAccessToken, client.client_id, finalScopes, row.resource, row.user_email, now, now + accessExpiresIn);

    return {
      access_token: newAccessToken,
      token_type: 'bearer',
      expires_in: accessExpiresIn,
      refresh_token: refreshToken,
      scope: finalScopes,
    };
  },

  async verifyAccessToken(token) {
    if (STATIC_BEARER_TOKEN && timingSafeEqualStr(token, STATIC_BEARER_TOKEN)) {
      return {
        token,
        clientId: 'xzero-owner-bearer',
        scopes: ['mcp:tools'],
        expiresAt: Math.floor(Date.now() / 1000) + 3600,
        resource: MCP_RESOURCE_URL,
        extra: { email: ALLOWED_EMAIL, method: 'static_bearer' },
      };
    }

    const row = db.prepare('SELECT * FROM oauth_tokens WHERE token = ? AND token_type = ?').get(token, 'access');
    if (!row) {
      throw new InvalidTokenError('Invalid access token');
    }
    if (row.expires_at < Math.floor(Date.now() / 1000)) {
      db.prepare('DELETE FROM oauth_tokens WHERE token = ?').run(token);
      throw new InvalidTokenError('Expired access token');
    }
    if (row.user_email !== ALLOWED_EMAIL) {
      throw new AccessDeniedError('Forbidden user');
    }

    return {
      token,
      clientId: row.client_id,
      scopes: String(row.scopes || 'mcp:tools').split(/\s+/).filter(Boolean),
      expiresAt: row.expires_at,
      resource: row.resource ? new URL(row.resource) : MCP_RESOURCE_URL,
      extra: { email: row.user_email, method: 'oauth2.1' },
    };
  },

  async revokeToken(client, request) {
    db.prepare('DELETE FROM oauth_tokens WHERE token = ? AND client_id = ?').run(request.token, client.client_id);
  },
};

const app = express();
app.disable('x-powered-by');
app.set('trust proxy', 1);

app.use(cors({
  origin: true,
  credentials: true,
  methods: ['GET', 'POST', 'DELETE', 'OPTIONS'],
  allowedHeaders: [
    'Content-Type',
    'Authorization',
    'Mcp-Session-Id',
    'MCP-Protocol-Version',
    'Last-Event-ID',
    'Cf-Access-Jwt-Assertion',
  ],
  exposedHeaders: ['Mcp-Session-Id', 'WWW-Authenticate', 'MCP-Protocol-Version'],
}));

app.get('/health', (_req, res) => {
  res.json({
    ok: true,
    service: 'xzero-phone-mcp-remote',
    publicUrl: `${PUBLIC_URL_STR}/mcp`,
    ownerEmail: ALLOWED_EMAIL,
    authModes: {
      oauth21: true,
      bearerToken: Boolean(STATIC_BEARER_TOKEN),
      cloudflareAccessJwt: Boolean(CF_ACCESS_TEAM_DOMAIN),
      googleOAuth: GOOGLE_OAUTH_ENABLED,
      ownerPasskey: Boolean(OWNER_PASSWORD),
    },
    ts: new Date().toISOString(),
  });
});

app.use(mcpAuthRouter({
  provider: oauthProvider,
  issuerUrl: PUBLIC_URL,
  baseUrl: PUBLIC_URL,
  resourceServerUrl: MCP_RESOURCE_URL,
  scopesSupported: ['mcp:tools'],
  resourceName: 'X0 Phone MCP',
}));

app.get('/mcp/login', async (req, res) => {
  securityHeaders(res);
  const pendingId = String(req.query.pending || '').trim();
  const pending = db.prepare('SELECT * FROM oauth_pending WHERE id = ?').get(pendingId);
  if (!pending || pending.expires_at < Math.floor(Date.now() / 1000)) {
    return res.status(400).send('Solicitação OAuth inválida ou expirada. Inicie a conexão novamente pelo cliente MCP.');
  }
  const client = clientsStore.getClient(pending.client_id);
  const cfResult = await verifyCloudflareAccessJwt(req);
  if (cfResult && !cfResult.ok && cfResult.email) {
    audit('cf_access_denied', getClientIp(req), `denied email=${cfResult.email}`);
    return res.status(403).send(`Acesso negado para ${escapeHtml(cfResult.email)}. Somente ${escapeHtml(ALLOWED_EMAIL)} pode autorizar.`);
  }
  return res.type('html').send(renderLoginHtml({
    pending,
    client,
    cfResult,
    errorMsg: req.query.err ? String(req.query.err) : '',
    googleEnabled: GOOGLE_OAUTH_ENABLED,
  }));
});

app.get('/mcp/google/start', (req, res) => {
  securityHeaders(res);
  if (!GOOGLE_OAUTH_ENABLED) {
    return res.status(404).send('Google OAuth callback não habilitado.');
  }
  const pendingId = String(req.query.pending || '').trim();
  const pending = db.prepare('SELECT * FROM oauth_pending WHERE id = ?').get(pendingId);
  if (!pending || pending.expires_at < Math.floor(Date.now() / 1000)) {
    return res.status(400).send('Solicitação OAuth expirada.');
  }
  const authUrl = new URL('https://accounts.google.com/o/oauth2/v2/auth');
  authUrl.searchParams.set('client_id', GOOGLE_CLIENT_ID);
  authUrl.searchParams.set('redirect_uri', GOOGLE_REDIRECT_URI);
  authUrl.searchParams.set('response_type', 'code');
  authUrl.searchParams.set('scope', 'openid email profile');
  authUrl.searchParams.set('state', `${pending.id}.${pending.csrf_token}`);
  authUrl.searchParams.set('login_hint', ALLOWED_EMAIL);
  authUrl.searchParams.set('prompt', 'select_account');
  return res.redirect(authUrl.toString());
});

app.get('/mcp/google/callback', async (req, res) => {
  securityHeaders(res);
  if (!GOOGLE_OAUTH_ENABLED) return res.status(404).send('Google OAuth desabilitado.');
  const code = String(req.query.code || '');
  const state = String(req.query.state || '');
  const [pendingId, csrf] = state.split('.');
  const pending = db.prepare('SELECT * FROM oauth_pending WHERE id = ?').get(pendingId || '');
  if (!pending || !csrf || !timingSafeEqualStr(csrf, pending.csrf_token) || pending.expires_at < Math.floor(Date.now() / 1000)) {
    return res.status(400).send('State OAuth inválido ou expirado.');
  }
  try {
    const tokenRes = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        code,
        client_id: GOOGLE_CLIENT_ID,
        client_secret: GOOGLE_CLIENT_SECRET,
        redirect_uri: GOOGLE_REDIRECT_URI,
        grant_type: 'authorization_code',
      }),
    });
    const tokenJson = await tokenRes.json();
    if (!tokenRes.ok || !tokenJson.access_token) {
      throw new Error(tokenJson.error_description || tokenJson.error || 'google_token_error');
    }
    const uiRes = await fetch('https://openidconnect.googleapis.com/v1/userinfo', {
      headers: { Authorization: `Bearer ${tokenJson.access_token}` },
    });
    const ui = await uiRes.json();
    const email = String(ui.email || '').trim().toLowerCase();
    if (!ui.email_verified || email !== ALLOWED_EMAIL) {
      audit('google_login_denied', getClientIp(req), `email=${email}`);
      return res.status(403).send(`Acesso negado para ${escapeHtml(email)}. Somente ${escapeHtml(ALLOWED_EMAIL)} pode autorizar.`);
    }
    db.prepare('UPDATE oauth_pending SET verified_email = ?, auth_method = ? WHERE id = ?')
      .run(email, 'Google OAuth', pending.id);
    return res.redirect(`/mcp/login?pending=${encodeURIComponent(pending.id)}`);
  } catch (err) {
    audit('google_login_error', getClientIp(req), err?.message || 'error');
    return res.redirect(`/mcp/login?pending=${encodeURIComponent(pending.id)}&err=${encodeURIComponent('Falha no login Google')}`);
  }
});

app.post('/mcp/approve', express.urlencoded({ extended: false }), async (req, res) => {
  securityHeaders(res);
  const ip = getClientIp(req);
  const pendingId = String(req.body?.pending_id || '').trim();
  const csrf = String(req.body?.csrf || '').trim();
  const action = String(req.body?.action || '').trim();

  const pending = db.prepare('SELECT * FROM oauth_pending WHERE id = ?').get(pendingId);
  if (!pending || pending.expires_at < Math.floor(Date.now() / 1000)) {
    return res.status(400).send('Solicitação OAuth inválida ou expirada.');
  }
  if (!timingSafeEqualStr(csrf, pending.csrf_token)) {
    audit('csrf_mismatch', ip, `pending=${pendingId}`);
    return res.status(403).send('CSRF inválido.');
  }

  if (action === 'deny') {
    db.prepare('DELETE FROM oauth_pending WHERE id = ?').run(pendingId);
    const redirect = new URL(pending.redirect_uri);
    redirect.searchParams.set('error', 'access_denied');
    redirect.searchParams.set('error_description', 'Owner denied access');
    if (pending.state) redirect.searchParams.set('state', pending.state);
    return res.redirect(redirect.toString());
  }

  const rl = checkLoginRateLimit(ip);
  if (rl.count >= 8) {
    audit('rate_limited', ip, `pending=${pendingId}`);
    return res.status(429).send('Muitas tentativas. Aguarde 15 minutos.');
  }

  let verifiedEmail = null;
  let method = '';

  const cfResult = await verifyCloudflareAccessJwt(req);
  if (cfResult?.ok && cfResult.email === ALLOWED_EMAIL) {
    verifiedEmail = cfResult.email;
    method = 'cloudflare_access';
  } else if (pending.verified_email === ALLOWED_EMAIL) {
    verifiedEmail = pending.verified_email;
    method = pending.auth_method || 'google_oauth';
  } else {
    const email = String(req.body?.email || '').trim().toLowerCase();
    const password = String(req.body?.password || '');
    if (email !== ALLOWED_EMAIL || !OWNER_PASSWORD || !timingSafeEqualStr(password, OWNER_PASSWORD)) {
      rl.count += 1;
      audit('approve_failed', ip, `email=${email || 'empty'}`);
      const client = clientsStore.getClient(pending.client_id);
      return res.status(403).type('html').send(renderLoginHtml({
        pending,
        client,
        cfResult: null,
        errorMsg: 'Credenciais inválidas ou e-mail não autorizado.',
        googleEnabled: GOOGLE_OAUTH_ENABLED,
      }));
    }
    verifiedEmail = ALLOWED_EMAIL;
    method = 'owner_password';
  }

  rl.count = 0;
  db.prepare('DELETE FROM oauth_pending WHERE id = ?').run(pendingId);

  const code = `xz_code_${crypto.randomBytes(24).toString('hex')}`;
  const now = Math.floor(Date.now() / 1000);
  db.prepare(`
    INSERT INTO oauth_codes (
      code, client_id, redirect_uri, code_challenge, scopes, resource, user_email, created_at, expires_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    code,
    pending.client_id,
    pending.redirect_uri,
    pending.code_challenge,
    pending.scopes || 'mcp:tools',
    pending.resource,
    verifiedEmail,
    now,
    now + 300,
  );

  audit('oauth_code_issued', ip, `client=${pending.client_id} method=${method} user=${verifiedEmail}`);

  const redirect = new URL(pending.redirect_uri);
  redirect.searchParams.set('code', code);
  if (pending.state) redirect.searchParams.set('state', pending.state);
  return res.redirect(redirect.toString());
});

const bearerAuth = requireBearerAuth({
  verifier: oauthProvider,
  resourceMetadataUrl: getOAuthProtectedResourceMetadataUrl(MCP_RESOURCE_URL),
});

const sessions = new Map();
setInterval(() => {
  const cutoff = Date.now() - 60 * 60 * 1000;
  for (const [sid, entry] of sessions.entries()) {
    if (entry.lastActivity < cutoff) {
      try { entry.transport.close(); } catch {}
      sessions.delete(sid);
    }
  }
}, 10 * 60 * 1000).unref();

app.all('/mcp', express.json({ limit: '4mb' }), bearerAuth, async (req, res) => {
  const ip = getClientIp(req);
  const rawSid = req.headers['mcp-session-id'];
  const sessionId = Array.isArray(rawSid) ? rawSid[0] : rawSid;

  try {
    if (sessionId) {
      const existing = sessions.get(sessionId);
      if (!existing) {
        return res.status(404).json({
          jsonrpc: '2.0',
          error: { code: -32001, message: 'Session not found or expired' },
          id: null,
        });
      }
      existing.lastActivity = Date.now();
      await existing.transport.handleRequest(req, res, req.body);
      return;
    }

    if (req.method === 'POST' && isInitializeRequest(req.body)) {
      let createdTransport = null;
      let server = null;
      const transport = new StreamableHTTPServerTransport({
        sessionIdGenerator: () => crypto.randomUUID(),
        onsessioninitialized: (newSid) => {
          sessions.set(newSid, {
            transport: createdTransport,
            server,
            lastActivity: Date.now(),
          });
          audit('mcp_session_open', ip, `sid=${newSid} auth=${req.auth?.extra?.method || 'token'}`);
        },
      });
      createdTransport = transport;
      server = createXzeroMcpServer();
      transport.onclose = () => {
        if (transport.sessionId) {
          sessions.delete(transport.sessionId);
        }
      };
      await server.connect(transport);
      await transport.handleRequest(req, res, req.body);
      return;
    }

    return res.status(400).json({
      jsonrpc: '2.0',
      error: { code: -32000, message: 'Bad Request: No valid Mcp-Session-Id header' },
      id: null,
    });
  } catch (err) {
    console.error('[xzero-mcp-remote] error:', err);
    if (!res.headersSent) {
      return res.status(500).json({
        jsonrpc: '2.0',
        error: { code: -32603, message: err?.message || 'Internal server error' },
        id: null,
      });
    }
  }
});

app.listen(PORT, HOST, () => {
  console.log(`[xzero-mcp-remote] Listening on http://${HOST}:${PORT}`);
  console.log(`[xzero-mcp-remote] Public MCP URL: ${PUBLIC_URL_STR}/mcp`);
  console.log(`[xzero-mcp-remote] Allowed Owner: ${ALLOWED_EMAIL}`);
});

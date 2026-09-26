/* mcp.js — MCP servers window (the / menu → MCP servers, and /mcp). */

/* ===================== MCP servers =====================
   The VS Code extension's panel, screen for screen: a list grouped by scope, a detail
   view per server (reconnect, enable/disable, sign in, tools), and an Add server form.

   What the conversation sees is asked of the tab's own process — _mcp sends one of
   mcp_status / mcp_toggle / mcp_reconnect / mcp_authenticate / mcp_clear_auth under a
   token, and the CLI's reply comes back through window.onMcp with that token. Adding and
   removing a server is the CLI's own `claude mcp add|remove` (_mcpConfig), answered on
   the same channel. So the list is exactly what this conversation has: a server added
   here reaches it in the next conversation, as it does in the extension. */

const MCP_DOCS_URL = 'https://code.claude.com/docs/en/mcp';
/* A process that has died never answers, so every request gives up eventually. */
const MCP_TIMEOUT_MS = 45000;
/* While a sign-in is being completed in the browser, the list is re-read this often. */
const MCP_AUTH_POLL_MS = 2000;
/* A process that was only just started reports servers as still connecting. Re-read
   while any is, a bounded number of times, so the window settles by itself. */
const MCP_PENDING_POLL_MS = 1500;
const MCP_PENDING_POLLS = 20;
/* Our own server, and the browser: both are run by the plugin, not configured by the
   user, and switching either off here would leave the rest of the view out of step. */
const MCP_HIDDEN = new Set(['eclipse', 'claude-in-chrome']);
const MCP_SCOPE_ORDER = ['project', 'local', 'user', 'claudeai', 'managed', 'enterprise'];
const MCP_SCOPE_LABELS = { project: 'Project', local: 'Local', user: 'User', claudeai: 'claude.ai',
                           managed: 'Managed', enterprise: 'Enterprise' };
const MCP_STATUS = {
  connected:    ['✓', 'Connected'],
  failed:       ['✗', 'Failed'],
  'needs-auth': ['⚠', 'Needs Auth'],
  pending:      ['◐', 'Connecting…'],
  disabled:     ['○', 'Disabled'],
};
const MCP_TRANSPORTS = [
  ['stdio', 'Local command (stdio)', 'Runs a command on your machine'],
  ['http',  'HTTP (remote)',         'Connects to a server by URL'],
  ['sse',   'SSE (remote, legacy)',  'Older remote protocol; prefer HTTP'],
];
const MCP_SCOPES = [
  ['local',   'Local',   'Private to you in this project'],
  ['user',    'User',    'Available in all your projects'],
  ['project', 'Project', 'Shared via .mcp.json in this project'],
];

/* ---- requests ---- */
const mcpPending = new Map();   // token -> { resolve, reject, timer }
let mcpSeq = 0;
function mcpToken() { return 'w' + Date.now().toString(36) + '-' + (++mcpSeq); }

/** Settles a pending request for a token. Unknown tokens (a timed-out request's late
    answer, or one for a window since closed) are dropped. */
window.onMcp = function(tabId, json) {
  let r; try { r = JSON.parse(json); } catch (e) { return; }
  const p = r && mcpPending.get(r.token);
  if (!p) return;
  mcpPending.delete(r.token);
  clearTimeout(p.timer);
  if (r.ok) p.resolve(r.response || {});
  else p.reject(new Error(r.error || 'Unknown error'));
};
function mcpAwait(token) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      mcpPending.delete(token);
      reject(new Error('Claude Code did not answer in time.'));
    }, MCP_TIMEOUT_MS);
    mcpPending.set(token, { resolve, reject, timer });
  });
}
/** One control request to the tab's process; resolves with the CLI's response body. */
function mcpCall(t, request) {
  if (!window._mcp) return Promise.reject(new Error('Not supported by this build.'));
  const token = mcpToken(), done = mcpAwait(token);
  // Launch settings ride along because this may have to START the tab's process,
  // exactly as Remote Control's toggle does.
  _mcp(t.id, token, JSON.stringify(request), t.sessionId || '', t.permMode || permMode,
       effort, curModel, thinkingOn ? '1' : '0', rootPathOf(t));
  return done;
}
/** `claude mcp add|remove`, run in the tab's folder. */
function mcpEditConfig(t, op) {
  if (!window._mcpConfig) return Promise.reject(new Error('Not supported by this build.'));
  const token = mcpToken(), done = mcpAwait(token);
  _mcpConfig(t.id, token, JSON.stringify(op), rootPathOf(t));
  return done;
}

/* ---- state ---- */
/* view: 'list' | 'detail' | 'add'. busy: {server, action} while one detail action runs.
   awaiting: the server whose sign-in is being completed in the browser. */
let mcpState = null;

function openMcpServers(initialServer) {
  closeMenus(); closeSlash();
  const t = activeTab(); if (!t) return;
  if (mcpState) closeMcpServers(true);
  mcpState = {
    tab: t, view: initialServer ? 'detail' : 'list', selected: initialServer || null,
    servers: [], loading: true, retrying: false, loadError: null, filter: '',
    success: null, failure: null, busy: null, awaiting: null, authUrl: null,
    showTools: false, confirmRemove: false, adding: false, addError: null,
    form: { name: '', transport: 'stdio', command: '', args: '', env: '', url: '', headers: '', scope: 'local' },
    pendingPolls: 0, pollTimer: null, authTimer: null, seq: 0, applied: 0,
  };
  document.getElementById('mcp-win').dataset.view = '';   // no skeleton from a closed window
  document.getElementById('mcp-overlay').classList.add('open');
  document.addEventListener('keydown', mcpKey, true);
  registerOverlayCancel(mcpCancel, false);   // not tab-owned — no visibility guard
  renderMcp();
  mcpRefresh();
}
/** Closing is held off while a config edit is running, as in the extension — the
    result would otherwise land nowhere. force: replaced by a fresh window.
    @returns {boolean} whether the window closed */
function closeMcpServers(force) {
  const st = mcpState; if (!st) return true;
  if (!force && (st.adding || (st.busy && st.busy.action === 'remove'))) return false;
  clearTimeout(st.pollTimer); clearInterval(st.authTimer);
  mcpState = null;
  document.getElementById('mcp-win').dataset.view = '';
  document.getElementById('mcp-overlay').classList.remove('open');
  document.removeEventListener('keydown', mcpKey, true);
  unregisterOverlayCancel(mcpCancel);
  return true;
}
/** The dismiss key: backs out of a pending remove first, then closes. */
function mcpEscape() {
  const st = mcpState; if (!st) return;
  if (st.confirmRemove && !st.busy) { st.confirmRemove = false; renderMcp(); return; }
  closeMcpServers(false);
}
/* Eclipse's binding (Esc, or Ctrl+G under Emacs) pops this entry before calling it, so
   a window still open afterwards has to put it back. */
function mcpCancel() {
  mcpEscape();
  if (mcpState) registerOverlayCancel(mcpCancel, false);
}
document.getElementById('mcp-overlay').addEventListener('click', (e) => {
  if (e.target.id === 'mcp-overlay') closeMcpServers(false);
});
/* Only Escape is ours: every other key belongs to the filter box and the form. */
function mcpKey(e) {
  if (e.key !== 'Escape' || !mcpState) return;
  e.preventDefault(); e.stopPropagation();
  mcpEscape();
}

/** Re-reads the server list. Overlapping reads are ordered: an older answer arriving
    after a newer one is ignored rather than rolling the window back. */
function mcpRefresh() {
  const st = mcpState; if (!st) return Promise.resolve();
  const seq = ++st.seq;
  return mcpCall(st.tab, { subtype: 'mcp_status' }).then((res) => {
    if (mcpState !== st || seq < st.applied) return;
    st.applied = seq;
    st.loadError = null;
    st.servers = ((res && res.mcpServers) || []).filter(s => s && s.config && !MCP_HIDDEN.has(s.name));
    const cur = st.awaiting && st.servers.find(s => s.name === st.awaiting);
    if (cur && cur.status === 'connected') st.awaiting = null;
    // A server that has gone (removed elsewhere, or never there) cannot stay open.
    if (st.selected && !st.servers.some(s => s.name === st.selected)) {
      st.selected = null; st.awaiting = null;
      if (st.view === 'detail') st.view = 'list';
    }
  }, (err) => {
    if (mcpState !== st || seq < st.applied) return;
    st.applied = seq;
    st.loadError = (err && err.message) || 'Failed to load MCP servers';
  }).then(() => {
    if (mcpState !== st || seq !== st.applied) return;
    st.loading = false; st.retrying = false;
    mcpSchedulePendingPoll(st);
    renderMcp();
  });
}
function mcpSchedulePendingPoll(st) {
  clearTimeout(st.pollTimer);
  if (!st.servers.some(s => s.status === 'pending') || st.pendingPolls >= MCP_PENDING_POLLS) return;
  st.pendingPolls++;
  st.pollTimer = setTimeout(() => { if (mcpState === st) mcpRefresh(); }, MCP_PENDING_POLL_MS);
}
function mcpSetAwaiting(st, name, url) {
  st.awaiting = name; st.authUrl = url || null;
  clearInterval(st.authTimer);
  st.authTimer = setInterval(() => {
    if (mcpState !== st || !st.awaiting) { clearInterval(st.authTimer); return; }
    mcpRefresh();
  }, MCP_AUTH_POLL_MS);
}
function mcpClearMessages(st) { st.success = null; st.failure = null; }

/* ---- detail actions ---- */
/** Runs one detail action: marks it busy, reports the outcome, re-reads the list. */
function mcpAction(name, action, run, successText, after) {
  const st = mcpState; if (!st) return;
  mcpClearMessages(st);
  st.busy = { server: name, action };
  renderMcp();
  return Promise.resolve().then(run).then((res) => {
    if (mcpState !== st) return;
    if (successText) st.success = successText;
    if (after) after(res);
  }, (err) => {
    if (mcpState !== st) return;
    st.failure = (err && err.message) || ('Failed to ' + action);
  }).then(() => {
    if (mcpState !== st) return;
    st.busy = null;
    // A fresh budget: a reconnect or enable can put the server back to connecting.
    st.pendingPolls = 0;
    renderMcp();
    return mcpRefresh();
  });
}
function mcpReconnect(name) {
  const st = mcpState; if (!st) return;
  st.awaiting = null; clearInterval(st.authTimer);
  mcpAction(name, 'reconnect', () => mcpCall(st.tab, { subtype: 'mcp_reconnect', serverName: name }),
            'Reconnected to ' + name);
}
function mcpSetEnabled(name, enabled) {
  const st = mcpState; if (!st) return;
  mcpAction(name, enabled ? 'enable' : 'disable',
            () => mcpCall(st.tab, { subtype: 'mcp_toggle', serverName: name, enabled }),
            (enabled ? 'Enabled ' : 'Disabled ') + name,
            () => { if (!enabled) { st.selected = null; st.view = 'list'; st.awaiting = null; } });
}
/** Sign-in opens in the browser. For a claude.ai connector, and for an OAuth server,
    the CLI hands back the page to open and finishes on its own once the user is done
    there; the window watches for the server to connect meanwhile. */
function mcpAuthenticate(name) {
  const st = mcpState; if (!st) return;
  mcpAction(name, 'authenticate', () => mcpCall(st.tab, { subtype: 'mcp_authenticate', serverName: name }),
            null, (res) => {
    res = res || {};
    if (res.authUrl && window._openExternal) _openExternal(res.authUrl);
    if (res.requiresUserAction) mcpSetAwaiting(st, name, res.authUrl);
    else st.success = 'Authenticated successfully';
  });
}
function mcpClearAuth(name) {
  const st = mcpState; if (!st) return;
  mcpAction(name, 'clearAuth', () => mcpCall(st.tab, { subtype: 'mcp_clear_auth', serverName: name }),
            'Cleared authentication for ' + name);
}
function mcpRemove(name, scope) {
  const st = mcpState; if (!st) return;
  st.confirmRemove = false;
  mcpAction(name, 'remove', () => mcpEditConfig(st.tab, { op: 'remove', name, scope }),
            'Removed ' + name + ' from ' + scope + ' config. Running sessions keep it until restarted.',
            () => { st.selected = null; st.view = 'list'; st.awaiting = null; });
}

/* ---- add form ---- */
/* Checked here to word the errors as the extension does; the core checks again. */
function mcpValidName(n) {
  if (n === '') return 'Server name is required.';
  if (/[^a-zA-Z0-9_-]/.test(n)) return 'Invalid name ' + n + '. Names can only contain letters, numbers, hyphens, and underscores.';
  if (n === 'eclipse') return 'eclipse is reserved for the plugin itself.';
  return null;
}
function mcpLines(s) { return s.split('\n').map(l => l.trim()).filter(l => l !== ''); }
function mcpEnvLines(s) {
  const out = [], ls = s.split('\n');
  for (let i = 0; i < ls.length; i++) {
    const l = ls[i].trim(); if (l === '') continue;
    const eq = l.indexOf('='), key = eq >= 0 ? l.slice(0, eq).trim() : '';
    if (key === '') return { error: 'Environment variables must be KEY=value (line ' + (i + 1) + ').' };
    out.push(key + '=' + l.slice(eq + 1).trim());
  }
  return { values: out };
}
function mcpHeaderLines(s) {
  const out = [], ls = s.split('\n');
  for (let i = 0; i < ls.length; i++) {
    const l = ls[i].trim(); if (l === '') continue;
    if (l.indexOf(':') <= 0) return { error: 'Headers must be "Header-Name: value" (line ' + (i + 1) + ').' };
    out.push(l);
  }
  return { values: out };
}
function mcpSubmitAdd() {
  const st = mcpState; if (!st || st.adding) return;
  const f = st.form, name = f.name.trim();
  // The button is at the foot of a form that has usually been scrolled, and the error
  // goes above the fields, so it is brought into view.
  const fail = (msg) => {
    st.addError = msg; renderMcp();
    const e = document.querySelector('#mcp-win .mcp-add-error');
    if (e) e.scrollIntoView({ block: 'nearest' });
  };
  const bad = mcpValidName(name); if (bad) return fail(bad);
  let config;
  if (f.transport === 'stdio') {
    const command = f.command.trim(); if (command === '') return fail('Command is required.');
    const env = mcpEnvLines(f.env); if (env.error) return fail(env.error);
    config = { transport: 'stdio', command, args: mcpLines(f.args), env: env.values };
  } else {
    const url = f.url.trim(); if (url === '') return fail('URL is required.');
    const headers = mcpHeaderLines(f.headers); if (headers.error) return fail(headers.error);
    config = { transport: f.transport, url, headers: headers.values };
  }
  st.addError = null; st.adding = true;
  renderMcp();
  mcpEditConfig(st.tab, { op: 'add', name, scope: f.scope, config }).then(() => {
    if (mcpState !== st) return;
    st.adding = false;
    mcpClearMessages(st);
    st.success = 'Added ' + name + ' to ' + f.scope + ' config. It will be available in new sessions.';
    st.form = { name: '', transport: 'stdio', command: '', args: '', env: '', url: '', headers: '', scope: 'local' };
    st.view = 'list';
    renderMcp();
    mcpRefresh();
  }, (err) => {
    if (mcpState !== st) return;
    st.adding = false;
    fail((err && err.message) || 'Failed to add server');
  });
}

/* ---- navigation ---- */
function mcpShowList() {
  const st = mcpState; if (!st) return;
  mcpClearMessages(st);
  st.view = 'list'; st.selected = null; st.awaiting = null; st.confirmRemove = false;
  clearInterval(st.authTimer);
  renderMcp();
}
function mcpShowDetail(name) {
  const st = mcpState; if (!st) return;
  mcpClearMessages(st);
  st.view = 'detail'; st.selected = name; st.showTools = false; st.confirmRemove = false;
  renderMcp();
}
function mcpShowAdd() {
  const st = mcpState; if (!st) return;
  mcpClearMessages(st);
  st.view = 'add'; st.addError = null;
  renderMcp();
}
function mcpRetry() {
  const st = mcpState; if (!st) return;
  st.retrying = true; st.pendingPolls = 0;
  renderMcp();
  mcpRefresh();
}

/* ---- rendering ---- */
function mcpEl(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}
function mcpButton(label, onClick, opts) {
  opts = opts || {};
  const b = mcpEl('button', 'mcp-btn' + (opts.primary ? ' primary' : '') + (opts.danger ? ' danger' : ''), label);
  b.type = 'button';
  b.disabled = !!opts.disabled;
  b.onclick = onClick;
  return b;
}
function mcpBadge(status) {
  const [icon, label] = MCP_STATUS[status] || ['?', status];
  const b = mcpEl('span', 'mcp-badge ' + status);
  b.appendChild(mcpEl('span', 'mcp-badge-ic', icon));
  b.appendChild(document.createTextNode(label));
  return b;
}
function mcpMessages(parent, st) {
  if (st.success) parent.appendChild(mcpEl('div', 'mcp-msg ok', st.success));
  if (st.failure) parent.appendChild(mcpEl('div', 'mcp-msg err', st.failure));
}
/** A server's error as one readable line: tags stripped, first line, capped. */
function mcpErrorLine(e) {
  let s = String(e || ''), prev = '';
  while (prev !== s) { prev = s; s = s.replace(/<[^>]+>/g, ''); }
  s = s.split('\n')[0] || s;
  return s.length > 200 ? s.slice(0, 200) + '…' : s;
}
function mcpGroups(servers, filter) {
  const q = filter.trim().toLowerCase(), by = new Map();
  servers.forEach(s => {
    if (q && !s.name.toLowerCase().includes(q)) return;
    const k = s.scope || 'other';
    if (!by.has(k)) by.set(k, []);
    by.get(k).push(s);
  });
  by.forEach(list => list.sort((a, b) => a.name.localeCompare(b.name)));
  const out = [];
  MCP_SCOPE_ORDER.forEach(k => { if (by.has(k)) out.push([k, by.get(k)]); });
  by.forEach((list, k) => { if (!MCP_SCOPE_ORDER.includes(k)) out.push([k, list]); });
  return out;
}

function renderMcp() {
  const st = mcpState; if (!st) return;
  const win = document.getElementById('mcp-win');
  // The list and the form keep their skeleton while they are on screen, so typing in
  // the filter or a field never loses the caret to a repaint.
  if (st.view === 'list' && win.dataset.view === 'list') { mcpFillList(win, st); return; }
  if (st.view === 'add' && win.dataset.view === 'add') { mcpFillAdd(win, st); return; }
  win.innerHTML = '';
  win.dataset.view = st.view;
  const head = mcpEl('div', 'mcp-head');
  head.appendChild(mcpEl('span', 'mcp-title', 'MCP servers'));
  const x = mcpEl('span', 'mcp-x'); x.innerHTML = ICONS.X; x.title = 'Close';
  x.onclick = () => closeMcpServers(false);
  head.appendChild(x);
  win.appendChild(head);
  const body = mcpEl('div', 'mcp-body');
  win.appendChild(body);
  if (st.view === 'list') mcpBuildList(body, st);
  else if (st.view === 'add') mcpBuildAdd(body, st);
  else mcpBuildDetail(body, st);
  const foot = mcpEl('div', 'mcp-foot');
  // A real http(s) href: ui.js hands every such click to the system browser already.
  const a = mcpEl('a', 'mcp-link', 'Learn more about MCP'); a.href = MCP_DOCS_URL;
  foot.appendChild(a);
  win.appendChild(foot);
  if (st.view === 'list') mcpFillList(win, st);
  else if (st.view === 'add') mcpFillAdd(win, st);
}

/* List: status line, filter, groups, Add server. */
function mcpBuildList(body, st) {
  body.appendChild(mcpEl('div', 'mcp-status'));
  const filter = mcpEl('input', 'mcp-input mcp-filter');
  filter.type = 'text'; filter.placeholder = 'Filter servers…';
  filter.setAttribute('aria-label', 'Filter servers…');
  filter.value = st.filter;
  filter.oninput = () => { st.filter = filter.value; mcpFillList(document.getElementById('mcp-win'), st); };
  body.appendChild(filter);
  body.appendChild(mcpEl('div', 'mcp-list'));
  body.appendChild(mcpButton('Add server', mcpShowAdd));
  setTimeout(() => { if (mcpState === st && st.view === 'list') filter.focus(); }, 0);
}
function mcpFillList(win, st) {
  const status = win.querySelector('.mcp-status'), filter = win.querySelector('.mcp-filter');
  const list = win.querySelector('.mcp-list'), add = win.querySelector('.mcp-body > .mcp-btn');
  status.innerHTML = ''; list.innerHTML = '';
  const loaded = !st.loading && !st.retrying;
  if (st.loading) status.appendChild(mcpEl('div', 'mcp-dim', 'Loading MCP servers…'));
  else if (st.retrying) status.appendChild(mcpEl('div', 'mcp-dim', 'Retrying…'));
  if (loaded) mcpMessages(status, st);
  if (loaded && st.loadError) {
    const e = mcpEl('div', 'mcp-msg err', 'Failed to load servers: ' + st.loadError);
    e.appendChild(document.createElement('br'));
    const r = mcpEl('a', 'mcp-retry', 'Retry'); r.href = '#';
    r.onclick = (ev) => { ev.preventDefault(); mcpRetry(); };
    e.appendChild(r);
    status.appendChild(e);
  }
  const has = st.servers.length > 0;
  if (loaded && !has && !st.loadError) status.appendChild(mcpEl('div', 'mcp-empty', 'No MCP servers configured.'));
  filter.style.display = loaded && has ? '' : 'none';
  add.style.display = loaded ? '' : 'none';
  if (!loaded || !has) return;
  const groups = mcpGroups(st.servers, st.filter);
  if (!groups.length) { list.appendChild(mcpEl('div', 'mcp-empty', 'No matching servers.')); return; }
  groups.forEach(([scope, servers]) => {
    list.appendChild(mcpEl('div', 'mcp-scope', (MCP_SCOPE_LABELS[scope] || scope) + ' (' + servers.length + ')'));
    servers.forEach(s => {
      const row = mcpEl('div', 'mcp-row' + (s.status === 'disabled' ? ' disabled' : ''));
      row.tabIndex = 0; row.setAttribute('role', 'button');
      row.appendChild(mcpEl('span', 'mcp-name', s.name));
      row.appendChild(mcpBadge(s.status));
      row.onclick = () => mcpShowDetail(s.name);
      row.onkeydown = (e) => {
        if ((e.key === 'Enter' || e.key === ' ') && !e.repeat) { e.preventDefault(); mcpShowDetail(s.name); }
      };
      list.appendChild(row);
    });
  });
}

/* Detail: back, messages, title + status, the actions this status allows, sign-in
   progress, tools. */
function mcpBuildDetail(body, st) {
  const s = st.servers.find(v => v.name === st.selected);
  const back = mcpEl('a', 'mcp-back', '← Back to list'); back.href = '#';
  back.onclick = (e) => { e.preventDefault(); mcpShowList(); };
  body.appendChild(back);
  if (!s) { body.appendChild(mcpEl('div', 'mcp-dim', 'Loading MCP servers…')); return; }
  mcpMessages(body, st);
  if (!st.failure && s.error) body.appendChild(mcpEl('div', 'mcp-msg err', mcpErrorLine(s.error)));
  const hd = mcpEl('div', 'mcp-detail-head');
  hd.appendChild(mcpEl('span', 'mcp-detail-title', s.name));
  hd.appendChild(mcpBadge(s.status));
  body.appendChild(hd);

  const type = s.config && s.config.type;
  const canAuth = type === 'sse' || type === 'http' || type === 'claudeai-proxy';
  const busy = st.busy, mine = busy && busy.server === s.name;
  const label = (action, idle, active) => (mine && busy.action === action ? active : idle);
  const acts = mcpEl('div', 'mcp-actions');
  const reconnect = () => mcpButton(label('reconnect', 'Reconnect', 'Reconnecting…'), () => mcpReconnect(s.name), { disabled: mine });
  const disable = () => mcpButton(label('disable', 'Disable', 'Disabling…'), () => mcpSetEnabled(s.name, false), { disabled: mine });
  const authenticate = () => mcpButton(label('authenticate', 'Authenticate', 'Authenticating…'), () => mcpAuthenticate(s.name),
                                       { primary: true, disabled: !!busy });
  const awaiting = st.awaiting === s.name;
  if (s.status === 'connected') {
    acts.appendChild(reconnect());
    if (canAuth && type !== 'claudeai-proxy') {
      acts.appendChild(mcpButton(label('clearAuth', 'Clear authentication', 'Clearing…'), () => mcpClearAuth(s.name),
                                 { danger: true, disabled: !!busy }));
    }
    acts.appendChild(disable());
  } else if (s.status === 'needs-auth' && !awaiting) {
    if (canAuth) acts.appendChild(authenticate());
    acts.appendChild(disable());
  } else if (s.status === 'failed' && !awaiting) {
    if (canAuth) acts.appendChild(authenticate());
    acts.appendChild(reconnect());
    acts.appendChild(disable());
  } else if (s.status === 'disabled') {
    acts.appendChild(mcpButton(label('enable', 'Enable', 'Enabling…'), () => mcpSetEnabled(s.name, true),
                               { primary: true, disabled: mine }));
  }
  // Only what a config file holds can be removed from one.
  if (s.scope === 'local' || s.scope === 'user' || s.scope === 'project') {
    if (!st.confirmRemove) {
      acts.appendChild(mcpButton('Remove', () => { st.confirmRemove = true; renderMcp(); }, { danger: true, disabled: !!busy }));
    } else {
      const row = mcpEl('div', 'mcp-confirm');
      row.appendChild(mcpEl('span', 'mcp-confirm-text', 'Remove ' + s.name + ' from ' + s.scope + ' config?'));
      row.appendChild(mcpButton(label('remove', 'Confirm remove', 'Removing…'), () => mcpRemove(s.name, s.scope),
                                { danger: true, disabled: !!busy }));
      row.appendChild(mcpButton('Cancel', () => { st.confirmRemove = false; renderMcp(); }, { disabled: !!busy }));
      acts.appendChild(row);
    }
  }
  body.appendChild(acts);

  if (awaiting && s.status !== 'connected') {
    const w = mcpEl('div', 'mcp-awaiting');
    w.appendChild(mcpEl('span', 'mcp-dim', 'Completing authentication in browser…'));
    w.appendChild(mcpButton('Check connection', () => mcpReconnect(s.name)));
    if (st.authUrl) {
      const again = mcpEl('a', 'mcp-link', 'Re-open authentication page'); again.href = '#';
      again.onclick = (e) => { e.preventDefault(); if (window._openExternal) _openExternal(st.authUrl); };
      w.appendChild(again);
    }
    body.appendChild(w);
  }

  const tools = s.tools || [];
  if (s.status === 'connected' && tools.length) {
    const toggle = mcpEl('a', 'mcp-tools-toggle',
                         st.showTools ? 'Hide tools ▴' : 'View tools (' + tools.length + ') ▾');
    toggle.href = '#';
    toggle.onclick = (e) => { e.preventDefault(); st.showTools = !st.showTools; renderMcp(); };
    body.appendChild(toggle);
    if (st.showTools) {
      const list = mcpEl('div', 'mcp-tools');
      tools.forEach(tool => {
        const it = mcpEl('div', 'mcp-tool');
        it.appendChild(mcpEl('span', 'mcp-tool-name', tool.name));
        const an = tool.annotations || {};
        if (an.readOnly) it.appendChild(mcpEl('span', 'mcp-tag ro', 'read-only'));
        if (an.destructive) it.appendChild(mcpEl('span', 'mcp-tag destructive', 'destructive'));
        list.appendChild(it);
      });
      body.appendChild(list);
    }
  }
}

/* Add server form. */
function mcpOptions(label, options, get, set) {
  const f = mcpEl('div', 'mcp-field');
  f.appendChild(mcpEl('span', 'mcp-label', label));
  const g = mcpEl('div', 'mcp-options'); g.setAttribute('role', 'group'); g.setAttribute('aria-label', label);
  options.forEach(([value, lbl, desc]) => {
    const b = mcpEl('button', 'mcp-option'); b.type = 'button'; b.dataset.option = value;
    b.appendChild(mcpEl('span', 'mcp-option-label', lbl));
    b.appendChild(mcpEl('span', 'mcp-option-desc', desc));
    b.onclick = () => { set(value); renderMcp(); };
    g.appendChild(b);
  });
  f.appendChild(g);
  f._get = get;
  return f;
}
function mcpField(label, key, st, opts) {
  opts = opts || {};
  const f = mcpEl('div', 'mcp-field' + (opts.cls ? ' ' + opts.cls : ''));
  const id = 'mcp-add-' + key;
  const l = mcpEl('label', 'mcp-label', label); l.htmlFor = id;
  const inp = mcpEl(opts.multi ? 'textarea' : 'input', 'mcp-input' + (opts.mono ? ' mono' : ''));
  inp.id = id;
  if (!opts.multi) inp.type = 'text';
  if (opts.placeholder) inp.placeholder = opts.placeholder;
  inp.value = st.form[key];
  inp.oninput = () => { st.form[key] = inp.value; };
  f.appendChild(l); f.appendChild(inp);
  return f;
}
function mcpBuildAdd(body, st) {
  const back = mcpEl('a', 'mcp-back', '← Back to list'); back.href = '#';
  back.onclick = (e) => { e.preventDefault(); if (!st.adding) mcpShowList(); };
  body.appendChild(back);
  body.appendChild(mcpEl('div', 'mcp-form-title', 'Add MCP server'));
  body.appendChild(mcpEl('div', 'mcp-add-error'));
  body.appendChild(mcpField('Name', 'name', st, { placeholder: 'example-tools', mono: true }));
  body.appendChild(mcpOptions('Transport', MCP_TRANSPORTS, () => st.form.transport, v => { st.form.transport = v; }));
  body.appendChild(mcpField('Command', 'command', st, { placeholder: 'npx', mono: true, cls: 'stdio-only' }));
  body.appendChild(mcpField('Arguments (one per line)', 'args', st, { multi: true, cls: 'stdio-only' }));
  body.appendChild(mcpField('Environment variables (KEY=value, one per line)', 'env', st, { multi: true, cls: 'stdio-only' }));
  body.appendChild(mcpField('URL', 'url', st, { placeholder: 'https://example.com/mcp', mono: true, cls: 'remote-only' }));
  body.appendChild(mcpField('Headers (Header-Name: value, one per line)', 'headers', st, { multi: true, cls: 'remote-only' }));
  const scope = mcpOptions('Scope', MCP_SCOPES, () => st.form.scope, v => { st.form.scope = v; });
  scope.appendChild(mcpEl('div', 'mcp-scope-warning',
    'Saved to .mcp.json and shared with everyone who opens this project. In IDE sessions, project ' +
    'servers connect without a separate approval step.'));
  body.appendChild(scope);
  const act = mcpEl('div', 'mcp-form-actions');
  act.appendChild(mcpButton('Add server', mcpSubmitAdd, { primary: true }));
  body.appendChild(act);
  setTimeout(() => { const n = document.getElementById('mcp-add-name'); if (n && mcpState === st) n.focus(); }, 0);
}
function mcpFillAdd(win, st) {
  const stdio = st.form.transport === 'stdio';
  win.querySelectorAll('.stdio-only').forEach(e => { e.style.display = stdio ? '' : 'none'; });
  win.querySelectorAll('.remote-only').forEach(e => { e.style.display = stdio ? 'none' : ''; });
  win.querySelectorAll('.mcp-field').forEach(f => {
    if (!f._get) return;
    const v = f._get();
    f.querySelectorAll('.mcp-option').forEach(b => {
      const on = b.dataset.option === v;
      b.classList.toggle('selected', on);
      b.setAttribute('aria-pressed', on ? 'true' : 'false');
      b.disabled = st.adding;
    });
  });
  win.querySelector('.mcp-scope-warning').style.display = st.form.scope === 'project' ? '' : 'none';
  const err = win.querySelector('.mcp-add-error');
  err.innerHTML = '';
  if (st.addError) err.appendChild(mcpEl('div', 'mcp-msg err', st.addError));
  const submit = win.querySelector('.mcp-form-actions .mcp-btn');
  submit.textContent = st.adding ? 'Adding…' : 'Add server';
  submit.disabled = st.adding;
  win.querySelectorAll('.mcp-input').forEach(i => { i.disabled = st.adding; });
}

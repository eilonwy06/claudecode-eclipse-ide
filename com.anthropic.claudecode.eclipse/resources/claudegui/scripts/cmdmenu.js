/* cmdmenu.js — the command menu: one menu with two ways in (the / button, and typing /
   in the composer), its rows, its filter, and the list of slash commands a folder offers. */

/* ===================== What the CLI offers in a folder =====================
   The slash commands (built-in, skills, plug-in and the user's own), the models and the
   output styles are asked of a short-lived CLI that is sent `initialize` and nothing else
   (_commands, cli_ask.rs), per root folder. The answer is kept here and in localStorage,
   so a later opening, a later tab and a later run of Eclipse have the list at once and
   the fresh one replaces it quietly. */
const cliOffers = new Map();        // root -> the answer, with `at` = when it was fetched
const cliOffersAsked = new Map();   // root -> when the ask now in flight was sent
const OFFERS_REFRESH_MS = 30000;    // an open menu asks again no sooner than this
const OFFERS_WAIT_MS = 12000;       // an ask unanswered this long is given up on

function offersKey(root) { return 'claude.offers.' + (root || ''); }
/** What the tab's folder offers, as last heard; null when nothing has ever been heard. */
function offersFor(t) {
  const root = t ? rootPathOf(t) : '';
  let o = cliOffers.get(root);
  if (!o) {
    try {
      const raw = localStorage.getItem(offersKey(root));
      if (raw) { o = JSON.parse(raw); o.at = 0; cliOffers.set(root, o); }
    } catch (e) { o = null; }
  }
  return o || null;
}
/** Whether an ask for the tab's folder is in flight. */
function offersLoading(t) {
  const at = cliOffersAsked.get(t ? rootPathOf(t) : '');
  return !!at && Date.now() - at < OFFERS_WAIT_MS;
}
/** Asks for the tab's folder, unless an ask is in flight or the answer is fresh. */
function requestOffers(t, force) {
  if (!t || !window._commands) return;
  const root = rootPathOf(t), now = Date.now();
  const asked = cliOffersAsked.get(root);
  if (asked && now - asked < OFFERS_WAIT_MS) return;
  const o = cliOffers.get(root);
  if (!force && o && o.at && now - o.at < OFFERS_REFRESH_MS) return;
  cliOffersAsked.set(root, now);
  try { _commands(t.id, root); } catch (e) { cliOffersAsked.delete(root); return; }
  // It always ends: an ask that is never answered stops counting as one.
  setTimeout(() => {
    if (cliOffersAsked.get(root) !== now) return;
    cliOffersAsked.delete(root);
    offersChanged(root);
  }, OFFERS_WAIT_MS);
}
window.onCommands = function(tabId, root, json) {
  const askedAt = cliOffersAsked.get(root);
  cliOffersAsked.delete(root);
  let r = null; try { r = JSON.parse(json); } catch (e) {}
  if (r && r.ok && Array.isArray(r.commands)) {
    r.at = Date.now();
    cliOffers.set(root, r);
    try { localStorage.setItem(offersKey(root), JSON.stringify(r)); } catch (e) {}
    if (typeof flaggedSwitchHeard === 'function') flaggedSwitchHeard(askedAt);
  }
  offersChanged(root);
};
/** An answer came in, or an ask was given up on. A list on screen is only touched where
    it was showing the loader: rows somebody may be reaching for do not move. */
function offersChanged(root) {
  const t = activeTab();
  if (!t || rootPathOf(t) !== root) return;
  if (cmdMenu.loaderShown && cmdMenuIsOpen()) cmdMenuRender(true);
  if (typeof slashDialogOffersChanged === 'function') slashDialogOffersChanged();
}

/** Whether a command of this name is the user's own (a skill, a plug-in's, a file of
    theirs), which then runs in place of anything of ours by the same name. */
function cliOwnsCommand(name) {
  const o = offersFor(activeTab());
  return !!(o && (o.commands || []).some(c => c && c.name === name && c.builtin === false));
}
/** Every slash command the tab can run, ours and the CLI's, one entry a name, by name. */
function allSlashCommands(t) {
  const out = new Map();
  SLASH_COMMANDS.forEach(c => out.set(c.cmd.slice(1), {
    name: c.cmd.slice(1), description: c.desc, argumentHint: c.hint || '', aliases: c.aliases || [] }));
  const o = offersFor(t);
  ((o && o.commands) || []).forEach(c => {
    if (!c || !c.name) return;
    const mine = out.get(c.name);
    if (mine && c.builtin !== false) {
      if (!mine.argumentHint) mine.argumentHint = c.argumentHint || '';
      if (!mine.aliases.length) mine.aliases = c.aliases || [];
      return;
    }
    out.set(c.name, { name: c.name, description: c.description || '',
                      argumentHint: c.argumentHint || '', aliases: c.aliases || [] });
  });
  return [...out.values()].sort((a, b) => a.name.localeCompare(b.name));
}

/* ===================== Fast mode =====================
   The CLI says whether it is on when a conversation's process starts and after every
   turn (window.onFastMode). While it is, a bolt sits on the composer's corner. */
window.onFastMode = function(tabId, json) {
  let r = null; try { r = JSON.parse(json); } catch (e) {}
  const t = tabs.find(x => x.id === tabId);
  if (!t || !r) return;
  t.fastMode = r.state === 'on';
  refreshFastBolt();
};
function refreshFastBolt() {
  let bolt = document.getElementById('fast-bolt');
  const wrap = document.getElementById('input-wrap');
  if (!bolt && wrap) {
    bolt = document.createElement('span');
    bolt.id = 'fast-bolt';
    bolt.innerHTML = ICONS.BOLT;
    bolt.title = 'Fast mode is on';
    wrap.appendChild(bolt);
  }
  const t = activeTab();
  if (bolt) bolt.classList.toggle('on', !!(t && t.fastMode));
}
/** Whether the tab's model can run in fast mode; yes when the CLI has not said. */
function modelSupportsFast(t) {
  const o = offersFor(t);
  const models = (o && o.models) || [];
  if (!models.length) return true;
  const m = models.find(x => x && (x.value === curModel || x.resolvedModel === curModel
                                   || (!curModel && x.value === 'default')));
  return m ? m.supportsFastMode !== false : true;
}

/* ===================== The rows ===================== */
const CMD_SECTIONS = ['Context', 'Model', 'Customize', 'Settings', 'Appearance', 'Support'];
const HELP_DOCS_URL = 'https://code.claude.com/docs/en/overview';

/** Whether this Eclipse has the Marketplace client. Asked once: it does not come and go. */
let marketplaceKnown = null;
function hasMarketplace() {
  if (marketplaceKnown === null) {
    try { marketplaceKnown = !!window._ide && _ide('hasMarketplace', '', '') === 'true'; } catch (e) { marketplaceKnown = false; }
  }
  return marketplaceKnown;
}
/** The menu's rows as they stand now. A row is an action, a switch (`toggle`, which
    leaves the menu open), or a part built in the page and moved in (`node`). */
function cmdMenuRows() {
  const t = activeTab();
  const ide = (what) => { try { return window._ide ? _ide(what) : ''; } catch (e) { return ''; } };
  const rows = [
    { id: 'attach', sec: 'Context', label: 'Attach file…', icon: 'UPLOAD',
      tip: 'Attach files from your computer', run: () => pickFilesFromComputer() },
    { id: 'mention', sec: 'Context', label: 'Mention file from this project…', icon: 'FILEICON',
      tip: 'Reference a project file with @mention', run: () => insertMentionTrigger('@') },
    { id: 'clear', sec: 'Context', label: 'Clear conversation', icon: 'NEWCHAT',
      tip: 'Start a new conversation', run: () => applySlash('/clear') },
    { id: 'rewind', sec: 'Context', label: 'Rewind', icon: 'REWIND', run: () => openRewindDialog() },
    { id: 'bookmarks', sec: 'Context', label: 'Bookmarks', icon: 'BOOKMARK', run: () => toggleBookmarksPanel() },
    { id: 'export', sec: 'Context', label: 'Export conversation', icon: 'EXPORT', run: () => openExportDialog() },

    { id: 'model', sec: 'Model', label: 'Switch model', icon: 'SPARK', tip: 'Change the AI model',
      curModel: true, trail: ICONS.CHEVRON, run: (e) => openModelChooser(e) },
    { id: 'effort', sec: 'Model', label: 'Effort', node: document.getElementById('row-effort') },
    { id: 'thinking', sec: 'Model', label: 'Thinking', icon: 'BRAIN', toggle: true, swId: 'think-check',
      on: () => thinkingOn, run: (e) => toggleThinking(e) },
    { id: 'ultracode', sec: 'Model', label: 'Ultracode', icon: 'BOLT', toggle: true,
      tip: 'Orchestrate background subagents more aggressively',
      on: () => ultracodeOn, run: (e) => toggleUltracode(e) },
    { id: 'flagswitch', sec: 'Model', label: 'Switch models when a message is flagged', icon: 'FLAG', toggle: true,
      tip: 'When safeguards flag a message, automatically switch to a different model to keep chatting. '
        + 'When off, your session will pause instead.',
      on: () => flaggedSwitchOn(t), run: () => setFlaggedSwitch(t, !flaggedSwitchOn(t)) },
    { id: 'account', sec: 'Model', label: 'Account & usage…', icon: 'USER',
      tip: 'View account info and usage', run: () => openAccount() },
    { id: 'fast', sec: 'Model', label: 'Toggle fast mode', icon: 'BOLT',
      tip: 'Toggle fast mode for faster responses (Opus only)',
      show: () => modelSupportsFast(t), run: () => sendSlashToCli('/fast') },

    { id: 'styles', sec: 'Customize', label: 'Output styles', icon: 'LINES', run: () => openOutputStyles() },
    { id: 'mcp', sec: 'Customize', label: 'MCP servers', icon: 'PLUG', run: () => openMcpServers() },
    { id: 'hooks', sec: 'Customize', label: 'Hooks', icon: 'HOOK', run: () => openHooksDialog() },
    { id: 'permissions', sec: 'Customize', label: 'Permissions', icon: 'SHIELD', run: () => openPermissionsDialog() },
    { id: 'status', sec: 'Customize', label: 'Status', icon: 'INFO', run: () => openStatusDialog() },
    { id: 'sandbox', sec: 'Customize', label: 'Sandbox', icon: 'CUBE', run: () => openSandboxDialog() },
    { id: 'slashcmds', sec: 'Customize', label: 'Slash commands', icon: 'CMD', run: () => openSlashCommandsDialog() },
    { id: 'memory', sec: 'Customize', label: 'Memory', icon: 'NOTE', run: () => openMemoryDialog('memory') },
    { id: 'instructions', sec: 'Customize', label: 'Instructions', icon: 'PENCIL',
      tip: 'Edit CLAUDE.md files', run: () => openMemoryDialog('instructions') },
    { id: 'marketplace', sec: 'Customize', label: 'Eclipse Marketplace', icon: 'BAG',
      show: () => hasMarketplace(), run: () => ide('marketplace') },
    // The tab's own conversation moves to the Terminal (tabs.js, openTabInTerminal). No row
    // for a tab under another folder: the Terminal runs in the workspace folder, where
    // that conversation is not to be found.
    { id: 'terminal', sec: 'Customize', label: 'Open Claude in Terminal', icon: 'TERMINAL',
      tip: (t && t.sessionId) ? 'Open this session in the Terminal' : 'Open a new Claude instance in the Terminal',
      show: () => tabInWorkspaceFolder(t), run: () => openTabInTerminal(t) },
    { id: 'design', sec: 'Customize', label: 'Claude Design', icon: 'PALETTE',
      tip: 'Authorize design-system access with your claude.ai account', run: () => openDesignDialog() },
    { id: 'chrome', sec: 'Customize', label: 'Claude in Chrome', icon: 'GLOBE',
      show: () => !window._browserSupported || !!_browserSupported(), run: () => openChromeDialog() },

    { id: 'switchaccount', sec: 'Settings', label: 'Switch account', icon: 'SWAP',
      tip: 'Log in with a different account', run: () => openSignInDialog(true) },
    { id: 'signout', sec: 'Settings', label: 'Sign out', icon: 'SIGNOUT',
      tip: 'Sign out of Claude on this computer', run: () => openSignOutDialog() },
    { id: 'config', sec: 'Settings', label: 'General config…', icon: 'GEAR',
      show: () => !!window._ide, run: () => ide('prefs') },
    { id: 'focus', sec: 'Settings', label: 'Focus view', icon: 'FOCUS', toggle: true,
      tip: "Show only your prompts and Claude's responses", on: () => focusView, run: () => setFocusView(!focusView) },

    { id: 'docs', sec: 'Support', label: 'View help docs', icon: 'HELP',
      run: () => { if (window._openExternal) _openExternal(HELP_DOCS_URL); } },

    { id: 'zoom', sec: 'Appearance', label: 'Zoom level', node: document.getElementById('row-zoom') },
  ];
  return rows.filter(r => (!r.show || r.show()) && (r.node !== null));
}

/* ===================== The menu ===================== */
/* typed: opened by typing / in the composer, which is then the filter (the box hides).
   query: what is being filtered by, without a leading slash. sel: the row the keyboard is
   on, as an index into the selectable rows, or -1. loaderShown: the last drawing had the
   loader row in it. */
const cmdMenu = { typed: false, query: '', slash: false, sel: -1, loaderShown: false };
const slashState = { open: false };   // the composer's key handler asks whether the typed menu is up

function cmdMenuEl() { return document.getElementById('actions-menu'); }
function cmdMenuIsOpen() { const m = cmdMenuEl(); return !!m && m.classList.contains('open'); }
function cmdMenuItems() { return [...document.querySelectorAll('#actions-rows .item')]; }

/** How well `q` matches: lower is better, -1 not at all. Loose on purpose, as the
    extension's is: a label is matched anywhere, by its letters in order, by an alias,
    and a longer query by what the row says it does. */
function cmdMatch(q, label, aliases, desc) {
  const l = String(label || '').toLowerCase().replace(/^\//, '');
  if (l === q) return 0;
  if (l.startsWith(q)) return 1;
  if (l.includes(q)) return 2;
  const al = (aliases || []).map(a => String(a).toLowerCase());
  if (al.some(a => a.startsWith(q))) return 3;
  if (al.some(a => a.includes(q))) return 4;
  let i = 0;
  for (const ch of l) { if (ch === q[i]) i++; if (i === q.length) break; }
  if (i === q.length && q.length > 1) return 5;
  if (q.length >= 3 && String(desc || '').toLowerCase().includes(q)) return 6;
  return -1;
}

function cmdRowEl(r) {
  if (r.node) return r.node;
  const it = document.createElement('div');
  it.className = 'item' + (r.cmd ? ' cmd' : '');
  it.dataset.id = r.id;
  if (r.tip) it.title = r.tip;
  if (r.cmd) {
    it.innerHTML = '<div class="txt"><span class="lbl"></span><span class="d"></span></div>';
    it.querySelector('.lbl').textContent = '/' + r.name;
    if (r.argumentHint) {
      const hint = document.createElement('span'); hint.className = 'hint';
      hint.textContent = r.argumentHint;
      it.querySelector('.lbl').appendChild(hint);
    }
    it.querySelector('.d').textContent = r.description || '';
    if (r.description) it.title = r.description;
  } else {
    it.innerHTML = '<span class="ic">' + (ICONS[r.icon] || '') + '</span><div class="txt"><span class="lbl"></span></div>';
    const lbl = it.querySelector('.lbl');
    lbl.textContent = r.label;
    if (r.curModel) {
      const cur = document.createElement('span');
      cur.id = 'cur-model'; cur.style.color = 'var(--fg-dim)';
      lbl.appendChild(cur);
    }
    if (r.toggle) {
      const sw = document.createElement('span');
      sw.className = 'sw' + (r.on() ? ' on' : '');
      if (r.swId) sw.id = r.swId;
      const trail = document.createElement('span'); trail.className = 'trail'; trail.appendChild(sw);
      it.appendChild(trail);
    } else if (r.trail) {
      const trail = document.createElement('span'); trail.className = 'trail'; trail.innerHTML = r.trail;
      it.appendChild(trail);
    }
  }
  // The press must not take the caret out of the composer or the filter box.
  it.onmousedown = (e) => e.preventDefault();
  it.onclick = (e) => cmdMenuActivate(r, e, false);
  it._row = r;
  return it;
}
function cmdHead(text) {
  const h = document.createElement('div'); h.className = 'head';
  h.innerHTML = '<span class="h"></span>'; h.firstChild.textContent = text;
  return h;
}
function cmdSep() { const s = document.createElement('div'); s.className = 'sep'; return s; }

/** Draws the menu for the query in hand. keep: an answer came in while the loader was up,
    so the row the keyboard is on and the scroll position are held where they were. */
function cmdMenuRender(keep) {
  const host = document.getElementById('actions-rows');
  const parts = document.getElementById('actions-parts');
  if (!host) return;
  const scroller = host.parentElement, scrollTop = scroller ? scroller.scrollTop : 0;
  const selId = keep && cmdMenu.sel >= 0 && cmdMenuItems()[cmdMenu.sel] ? cmdMenuItems()[cmdMenu.sel].dataset.id : null;
  // The two slider rows are moved, never rebuilt: park them before the list is emptied.
  ['row-effort', 'row-zoom'].forEach(id => { const n = document.getElementById(id); if (n && parts) parts.appendChild(n); });
  host.innerHTML = '';

  const t = activeTab();
  const q = cmdMenu.query.toLowerCase();
  const rows = cmdMenuRows();
  const groups = [];   // [title, [row…], loader?]
  cmdMenu.loaderShown = false;

  if (!q) {
    CMD_SECTIONS.forEach(sec => {
      const mine = rows.filter(r => r.sec === sec);
      if (mine.length) groups.push([sec, mine, false]);
    });
  } else {
    const cmds = allSlashCommands(t)
      .map(c => [cmdMatch(q, c.name, c.aliases, c.description), c])
      .filter(x => x[0] >= 0)
      .sort((a, b) => a[0] - b[0] || a[1].name.localeCompare(b[1].name))
      .map(x => Object.assign({ id: 'cmd:' + x[1].name, cmd: true }, x[1]));
    const loading = !offersFor(t) && offersLoading(t);
    const cmdGroup = (cmds.length || loading) ? ['Slash Commands', cmds, loading] : null;
    const secGroups = [];
    CMD_SECTIONS.forEach(sec => {
      const mine = rows.filter(r => r.sec === sec && !r.node && cmdMatch(q, r.label, null, r.tip) >= 0);
      if (mine.length) secGroups.push([sec, mine, false]);
    });
    // Typed after a slash, the commands come first; in the filter box they sit where the
    // extension lists them, after Customize.
    if (cmdGroup && cmdMenu.slash) groups.push(cmdGroup);
    secGroups.forEach(g => {
      groups.push(g);
      if (cmdGroup && !cmdMenu.slash && g[0] === 'Customize') groups.push(cmdGroup);
    });
    if (cmdGroup && !cmdMenu.slash && !groups.includes(cmdGroup)) {
      const at = groups.findIndex(g => CMD_SECTIONS.indexOf(g[0]) > CMD_SECTIONS.indexOf('Customize'));
      if (at < 0) groups.push(cmdGroup); else groups.splice(at, 0, cmdGroup);
    }
  }

  if (!groups.length) {
    host.appendChild(cmdHead('No matching commands'));
  }
  groups.forEach((g, gi) => {
    if (gi) host.appendChild(cmdSep());
    host.appendChild(cmdHead(g[0]));
    g[1].forEach(r => host.appendChild(cmdRowEl(r)));
    if (g[2]) {
      const row = document.createElement('div'); row.className = 'loading-row';
      row.innerHTML = '<span class="spin-ring"></span><span></span>';
      row.lastChild.textContent = 'Loading…';
      host.appendChild(row);
      cmdMenu.loaderShown = true;
    }
  });

  // What the parts moved back in show is theirs to keep right.
  if (typeof updateModelLabel === 'function') updateModelLabel();
  if (typeof updateThinkingCheck === 'function') updateThinkingCheck();

  const items = cmdMenuItems();
  if (keep) {
    cmdMenu.sel = selId ? items.findIndex(it => it.dataset.id === selId) : -1;
    if (scroller) scroller.scrollTop = scrollTop;
  } else {
    cmdMenu.sel = q && items.length ? 0 : -1;
    if (scroller) scroller.scrollTop = 0;
  }
  cmdMenuPaintSel(false);
}
function cmdMenuPaintSel(reveal) {
  cmdMenuItems().forEach((it, i) => {
    const on = i === cmdMenu.sel;
    it.classList.toggle('sel', on);
    if (on && reveal && it.scrollIntoView) it.scrollIntoView({ block: 'nearest' });
  });
}
function cmdMenuMove(by) {
  const n = cmdMenuItems().length;
  if (!n) return;
  cmdMenu.sel = cmdMenu.sel < 0 ? (by > 0 ? 0 : n - 1) : (cmdMenu.sel + by + n) % n;
  cmdMenuPaintSel(true);
}

/** A row was picked, by click or by key. viaTab: with the Tab key, which for a slash
    command only completes it in the composer so its arguments can be typed. */
function cmdMenuActivate(r, e, viaTab) {
  if (r.toggle) {
    r.run(e);
    const it = cmdMenuItems().find(x => x._row === r);
    const sw = it && it.querySelector('.sw');
    if (sw) sw.classList.toggle('on', !!r.on());
    return;
  }
  const typed = cmdMenu.typed;
  if (r.cmd && viaTab) {
    closeMenus();
    input.value = '/' + r.name + ' ';
    input.focus();
    input.dispatchEvent(new Event('input'));
    return;
  }
  if (r.cmd) { closeMenus(); applySlash('/' + r.name); return; }
  closeMenus();
  if (typed) {
    // The "/…" in the composer was the filter, not a message.
    input.value = ''; input.style.height = 'auto';
    const t = activeTab(); if (t) t.draft = '';
    input.dispatchEvent(new Event('input'));
  }
  r.run(e);
}

/** A key pressed while the menu is up, from the composer (typed) or the filter box.
    @returns {boolean} whether the menu took it */
function cmdMenuKey(e) {
  if (!cmdMenuIsOpen()) return false;
  if (e.key === 'ArrowDown') { e.preventDefault(); cmdMenuMove(1); return true; }
  if (e.key === 'ArrowUp') { e.preventDefault(); cmdMenuMove(-1); return true; }
  if (e.key === 'Enter' || e.key === 'Tab') {
    const it = cmdMenuItems()[cmdMenu.sel];
    // Nothing chosen: Tab is left alone; Enter is swallowed while only "/" is typed, so
    // a bare slash is never sent as a message.
    if (!it) { if (e.key === 'Enter' && cmdMenu.typed) { e.preventDefault(); return true; } return false; }
    e.preventDefault();
    cmdMenuActivate(it._row, e, e.key === 'Tab');
    return true;
  }
  if (e.key === 'Escape') { e.preventDefault(); closeMenus(); return true; }
  return false;
}

/* ---- the two ways in ---- */
/** The filter box's text changed, or the menu was just opened by its button. */
function filterActionsSlash(query) {
  const raw = String(query || '').trim();
  cmdMenu.typed = false;
  cmdMenu.slash = raw.startsWith('/');
  cmdMenu.query = raw.replace(/^\//, '');
  cmdMenuEl().classList.remove('typed');
  cmdMenuRender(false);
  requestOffers(activeTab());
}
function buildActionsSlash(query) { filterActionsSlash(query); }

/** The composer's text changed: a lone "/word" opens the menu filtered by the word. */
function updateSlashMenu() {
  const m = /^\/(\S*)$/.exec(input.value);
  if (!m || (typeof composerShowing === 'function' && !composerShowing())) { closeSlash(); return; }
  const menu = cmdMenuEl();
  if (!slashState.open) {
    closeMenus();
    menu.classList.add('typed', 'open');
    positionMenu(menu, document.getElementById('slash-btn'));
    slashState.open = true;
    requestOffers(activeTab());
  }
  cmdMenu.typed = true; cmdMenu.slash = true; cmdMenu.query = m[1];
  cmdMenuRender(false);
}
function handleSlashKey(e) { return cmdMenuKey(e); }
/** Closes the menu when it was opened by typing; the button's opening is closeMenus's. */
function closeSlash() {
  if (!slashState.open) return;
  slashState.open = false;
  cmdMenu.typed = false;
  cmdMenuEl().classList.remove('open', 'typed');
}

document.getElementById('actions-slash-filter').addEventListener('keydown', (e) => {
  if (e.key === 'Escape') return;   // ui.js closes whichever menu is open
  cmdMenuKey(e);
});

/** Once, when the page is ready: the first drawing, and the first ask. */
function cmdMenuInit() {
  cmdMenu.typed = false; cmdMenu.query = ''; cmdMenu.slash = false;
  cmdMenuRender(false);
  requestOffers(activeTab());
  refreshFastBolt();
  applyFocusView();
  // The bolt follows the tab in front; tabs change in too many places to hook each one.
  setInterval(refreshFastBolt, 700);
}

/* ===================== Focus view =====================
   Shows only your prompts and Claude's responses: each run of tool calls in a reply folds
   into one line that says how many there were and opens on a click, and the thinking
   markers are put away. Kept across restarts. */
let focusView = false;
try { focusView = localStorage.getItem('claude.focusView') === '1'; } catch (e) {}
let focusFoldSeq = 0;

function setFocusView(on) {
  focusView = !!on;
  try { localStorage.setItem('claude.focusView', focusView ? '1' : '0'); } catch (e) {}
  applyFocusView();
}
/** Brings the transcript in line with the setting: one fold line before each run of tool
    calls while it is on, none while it is off. Runs again whenever the transcript changes. */
function applyFocusView() {
  const app = document.getElementById('app');
  if (!app) return;
  app.classList.toggle('focus-view', focusView);
  focusObserver.disconnect();
  try {
    document.querySelectorAll('.turn').forEach(turn => {
      let run = [];
      const close = () => {
        if (!run.length) return;
        const first = run[0];
        let fold = first.previousElementSibling;
        if (!fold || !fold.classList.contains('tool-fold')) {
          fold = document.createElement('div');
          fold.className = 'a-item tool-fold';
          fold.innerHTML = '<span class="dot"></span><span class="tf-label"></span><span class="tf-chev">' + ICONS.CHEVRONDOWN + '</span>';
          fold.dataset.fold = String(++focusFoldSeq);
          fold.onclick = () => {
            const open = !fold.classList.contains('open');
            fold.classList.toggle('open', open);
            let n = fold.nextElementSibling;
            while (n && (n.classList.contains('tool-line') || n.classList.contains('think'))) {
              if (n.classList.contains('tool-line')) n.classList.toggle('fold-open', open);
              n = n.nextElementSibling;
            }
            // Cards drawn under the closed fold had no size to be cut to (chat.js).
            if (open) measureRevealed(turn);
          };
          turn.insertBefore(fold, first);
        }
        const lastDot = run[run.length - 1].querySelector('.dot');
        fold.querySelector('.dot').className = lastDot ? lastDot.className : 'dot gray';
        fold.querySelector('.tf-label').textContent = run.length + (run.length === 1 ? ' tool call' : ' tool calls');
        const open = fold.classList.contains('open');
        run.forEach(line => line.classList.toggle('fold-open', open));
        run = [];
      };
      [...turn.children].forEach(child => {
        if (child.classList.contains('tool-fold')) {
          // A fold whose run has gone (or the setting is off) goes too.
          const next = child.nextElementSibling;
          if (!focusView || !next || !next.classList.contains('tool-line')) child.remove();
          return;
        }
        if (!focusView) { child.classList.remove('fold-open'); return; }
        if (child.classList.contains('tool-line')) run.push(child);
        else if (!child.classList.contains('think')) close();
      });
      if (focusView) close();
    });
    // Lines that show now and did not before: Focus view switched off, or a line that
    // joined a run whose fold is open. In here, so the observer does not see it.
    measureRevealed(document.getElementById('messages'));
  } finally {
    const host = document.getElementById('messages');
    if (host) focusObserver.observe(host, { childList: true, subtree: true });
  }
}
let focusTimer = null;
const focusObserver = new MutationObserver(() => {
  if (!focusView) return;
  clearTimeout(focusTimer);
  focusTimer = setTimeout(applyFocusView, 120);
});

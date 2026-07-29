/* models.js — Model chooser (dynamic /v1/models catalog), switch dividers, per-session
   prefs, thinking toggle, account window, actions-menu slash list builder. */

/* ---- model chooser (claude --model) ---- */
/* Fallback list until the dynamic /v1/models list arrives (onAvailableModels).
   The real chooser is built from the account's actually-available models. */
let MODELS = [
  { id: '',           label: 'Default (recommended)', desc: 'Latest model · efficient for routine tasks' },
  { id: 'fable',      label: 'Fable',                 desc: 'Latest flagship model' },
  { id: 'opus',       label: 'Opus',                  desc: 'Most capable — best for complex tasks' },
  { id: 'sonnet',     label: 'Sonnet',                desc: 'Balanced capability and speed' },
  { id: 'sonnet[1m]', label: 'Sonnet (1M context)',   desc: '1M-token context · draws from usage credits' },
  { id: 'haiku',      label: 'Haiku',                 desc: 'Fastest for quick answers' }
];
/* Java pushes the account's real, curated model list here. */
window.onAvailableModels = function(json) {
  let list;
  try { list = JSON.parse(json); } catch (e) { return; }
  if (!Array.isArray(list) || !list.length) return;
  MODELS = list;
  applyCliModelLimits();   // this list is the ACCOUNT's; clamp it to the binary's
  // Re-add a custom --model from plugin prefs if it isn't already offered.
  if (customModel && !MODELS.some(m => m.id === customModel)) {
    MODELS.splice(1, 0, { id: customModel, label: prettyModelId(customModel), desc: 'From your plugin settings' });
  }
  // If the current selection vanished, fall back to Default (keep the tab in sync).
  if (curModel && !MODELS.some(m => m.id === curModel) && !/^claude-/.test(curModel)) {
    curModel = ''; const t = activeTab(); if (t) t.model = '';
  }
  updateModelLabel();
  const menu = document.getElementById('model-menu');
  if (menu && menu.classList.contains('open')) renderModelList();
  if (typeof notifyStatusSelection === 'function') notifyStatusSelection();
};
let curModel = '';      // '' = Default
let customModel = '';
function initModelConfig() {
  try { const c = JSON.parse(window._modelConfig ? window._modelConfig() : '{}'); customModel = (c && c.customModel) || ''; } catch (e) {}
  if (customModel) {
    // surface a --model from the plugin's preference args, selected by default
    MODELS.splice(1, 0, { id: customModel, label: prettyModelId(customModel), desc: 'From your plugin settings' });
    curModel = customModel;
  }
  updateModelLabel();
}
/* Reliable async push of the configured --model (from prefs), sent by Java alongside
   the model list — a safety net in case the page-load _modelConfig() didn't land. The
   configured model IS the default here (matching the Claude Terminal), so adopt it for
   any tab that's still on the untouched account default. */
window.onCustomModel = function(id) {
  if (!id || customModel === id) return;
  customModel = id;
  if (!MODELS.some(m => m.id === id))
    MODELS.splice(1, 0, { id: id, label: prettyModelId(id), desc: 'From your plugin settings' });
  tabs.forEach(t => { if (t && !t.model) t.model = id; });
  if (!curModel) curModel = id;
  updateModelLabel();
  if (typeof notifyStatusSelection === 'function') notifyStatusSelection();
};
function modelLabelFor(id) {
  const m = MODELS.find(x => x.id === id);
  if (m) return m.label.replace(' (recommended)', '');
  // A resumed session may carry a full model id (e.g. claude-opus-4-8) with no
  // chooser entry — show it prettified rather than the raw id.
  if (id && /^claude-/.test(id)) return prettyModelId(id);
  return id || 'Default';
}
function prettyModelId(id) {
  const s = String(id).replace(/^claude-/, '').replace(/-\d{8}$/, '');
  const p = s.split('-'); if (!p.length || !p[0]) return id;
  const fam = p[0].charAt(0).toUpperCase() + p[0].slice(1);
  return p.length > 1 ? fam + ' ' + p.slice(1).join('.') : fam;
}
function updateModelLabel() {
  const el = document.getElementById('cur-model');
  if (el) el.textContent = '(' + modelLabelFor(curModel) + ')';
}
function openModelChooser(e) {
  if (e) e.stopPropagation();
  closeMenus();
  renderModelList();
  const menu = document.getElementById('model-menu');
  const anchor = document.getElementById('slash-btn');
  menu.classList.add('open');
  positionMenu(menu, anchor);
  openMenuEl = menu; openAnchor = anchor;
}
function renderModelList() {
  const list = document.getElementById('model-list');
  list.innerHTML = '';
  MODELS.forEach(m => {
    const it = document.createElement('div');
    it.className = 'item' + (m.disabled ? ' disabled' : '');
    it.innerHTML = '<div class="m-txt"><span class="m-title"></span><span class="m-desc"></span></div>'
      + (m.id === curModel && !m.disabled ? '<span class="check">' + ICONS.CHECK + '</span>' : '');
    it.querySelector('.m-title').textContent = m.label;
    it.querySelector('.m-desc').textContent = m.desc;
    if (!m.disabled) it.onclick = () => selectModel(m.id);
    list.appendChild(it);
  });
}
/** @param {string} id @param {{noDivider?: boolean}} [opts] /model prints its own
 *  confirmation line, so it suppresses the divider rather than saying it twice. */
function selectModel(id, opts) {
  const prev = curModel;
  curModel = id; updateModelLabel(); closeMenus();
  const t = activeTab(); if (t) t.model = id;
  // Mark the switch in the transcript (VSCode behavior) — only mid-conversation.
  if (t && id !== prev && !(opts && opts.noDivider) && !t.pane.querySelector('.welcome')) {
    addModelSwitchDivider(id);
  }
  // A gated model may make the current effort/thinking pair illegal — correct it
  // here rather than blocking the switch (the user asked for this model).
  enforceThinkingGate();
  persistTabPrefs(t); notifyStatusSelection();
}
/* "〰 Switched to <model> 〰" divider element. */
function makeSwitchDivider(name) {
  const turn = document.createElement('div'); turn.className = 'turn';
  const d = document.createElement('div'); d.className = 'switch-divider';
  d.innerHTML = '<span class="sw-line"></span><span class="sw-text"></span><span class="sw-line"></span>';
  d.querySelector('.sw-text').textContent = 'Switched to ' + name;
  turn.appendChild(d);
  return turn;
}
/* Inserted live when the model changes mid-conversation (concrete id via fullId). */
function addModelSwitchDivider(id) {
  const pane = activeTab() ? activeTab().pane : messagesEl;
  if (!pane) return;
  const entry = MODELS.find(m => m.id === id);
  const name = (entry && entry.fullId) ? entry.fullId
             : (/^claude-/.test(id) ? id : modelLabelFor(id));
  pane.appendChild(makeSwitchDivider(name));
  scrollBottom();
}

/* Java tells us the actual model a conversation ran on (resolves "Default").
   A tab with no explicit selection adopts it so the model persists across
   tab switches instead of snapping back to "Default". */
window.onResolvedModel = function(tabId, id) {
  if (!id) return;
  const t = tabById(tabId);
  if (!t) return;
  warnIfDowngraded(t, id);                // check BEFORE the early-out below
  if (t.model) return;                    // only fill in an unset (Default) tab
  t.model = id;
  if (t === activeTab()) { curModel = id; updateModelLabel(); notifyStatusSelection(); }
};

/* Strip the "claude-" prefix and any -YYYYMMDD suffix so claude-opus-4-5 and
   claude-opus-4-5-20251101 compare equal. */
function normalizeModelId(id) {
  return String(id || '').replace(/^claude-/, '').replace(/-\d{8}$/, '');
}

/* ---- what the INSTALLED binary can actually run ----
   {opus:'claude-opus-4-8', …}, read from the model ids compiled into claude.exe
   and pushed by Java. The account list (/v1/models) says what you're entitled to;
   this says what the binary in front of you will really do with an alias. Only the
   families the scanner looks for are represented, so absence is only meaningful
   for those. */
const CLI_SCANNED_FAMILIES = ['fable', 'opus', 'sonnet', 'haiku'];
let cliModels = null;      // newest per family — drives the alias labels
let cliModelIds = null;    // EVERY id in the binary — validates a pinned --model

window.onCliModels = function(json) {
  let d;
  try { d = JSON.parse(json); } catch (e) { return; }
  if (!d) return;
  // Tolerate the older flat {family: id} shape: this panel's JS reloads on its
  // own, so it can be newer than the Java build feeding it.
  const latest = d.latest || d;
  if (!latest || !Object.keys(latest).length) return;   // scan failed → leave the chooser alone
  cliModels = latest;
  if (Array.isArray(d.all)) {
    cliModelIds = {};
    d.all.forEach(id => { cliModelIds[id] = 1; cliModelIds[normalizeModelId(id)] = 1; });
  }
  applyCliModelLimits();
  updateModelLabel();
  const menu = document.getElementById('model-menu');
  if (menu && menu.classList.contains('open')) renderModelList();
  if (typeof notifyStatusSelection === 'function') notifyStatusSelection();
};

/* Rewrites each alias entry to the model the CLI will REALLY run. Your account
   may offer Opus 5 while the installed binary tops out at Opus 4.8 — the CLI
   resolves "opus" itself and silently gives you 4.8, so listing "Opus 5" is a
   promise the binary can't keep. Relabelling makes the chooser honest up front
   instead of surprising you mid-conversation. */
function applyCliModelLimits() {
  if (!cliModels) return;
  MODELS.forEach(m => {
    if (!m.id) return;                            // Default — the CLI's own pick
    if (/^claude-/.test(m.id)) {                  // a concrete id (pinned --model)
      // Nothing to relabel: you named one exact model. But the CLI won't
      // substitute for a model it doesn't have — it just fails on send — so flag
      // it here instead of after you've written a message.
      if (!pinSupported(m.id)) {
        m.disabled = true;
        m.desc = 'Not supported by your installed Claude Code';
      }
      return;
    }
    const family = m.id.replace(/\[1m\]$/, '');
    if (CLI_SCANNED_FAMILIES.indexOf(family) < 0) return;   // not scanned for → can't judge
    const supported = cliModels[family];
    if (!supported) {
      // The binary has never heard of this family; selecting it would just fail.
      m.disabled = true;
      m.desc = 'Not supported by your installed Claude Code';
      return;
    }
    if (!m.fullId) { m.fullId = supported; return; }
    if (normalizeModelId(m.fullId) === normalizeModelId(supported)) return;   // in step
    // Behind the account: show the truth, and what updating would unlock.
    m.accountLatest = m.fullId;
    m.label = prettyModelId(supported) + (/\[1m\]$/.test(m.id) ? ' (1M context)' : '');
    m.desc = 'Newest ' + family + ' your Claude Code supports · update for '
           + prettyModelId(m.accountLatest);
    // fullId now matches what will actually run, so warnIfDowngraded stays quiet —
    // the label already tells the truth. It still fires when this scan is
    // unavailable, which is exactly when the surprise could still happen.
    m.fullId = supported;
  });
}

/** @returns {boolean} whether a concrete model id exists in the installed binary.
 *  Answers true whenever we can't actually tell — no id list yet, or a family the
 *  scanner doesn't look for — so an unknown never disables a working choice. */
function pinSupported(id) {
  if (!cliModelIds) return true;
  const family = String(id).replace(/^claude-/, '').split('-')[0];
  if (CLI_SCANNED_FAMILIES.indexOf(family) < 0) return true;
  return !!(cliModelIds[id] || cliModelIds[normalizeModelId(id)]);
}

/* SILENT DOWNGRADE GUARD.
   Model aliases ("opus"/"sonnet") are resolved by the CLI, not by us — so an
   installed CLI older than a model release does NOT error on `--model opus`, it
   quietly resolves the alias to the newest model IT knows. You pick Opus, you
   get an older Opus, and nothing anywhere says so. (This is what a 2.1.17 CLI
   did once Opus 5 / Sonnet 5 shipped.)

   The catalog gives us `fullId` — the concrete id the ACCOUNT's latest for that
   family is — and Java reports the concrete id the CLI ACTUALLY ran. When those
   disagree for an explicitly chosen alias, the binary is behind: say so once per
   tab, and point at the update banner. */
function warnIfDowngraded(t, resolvedId) {
  const picked = t.model || (t === activeTab() ? curModel : '');
  if (!picked) return;                       // "Default" — the CLI's choice is not a downgrade
  if (/^claude-/.test(picked)) return;       // an explicit concrete id ran as asked
  const entry = MODELS.find(m => m.id === picked);
  if (!entry || !entry.fullId) return;       // no catalog id to compare (offline fallback list)
  const want = normalizeModelId(entry.fullId);
  const got = normalizeModelId(resolvedId);
  if (!want || !got || want === got) return;
  if (!t.downgradeWarned) t.downgradeWarned = {};
  if (t.downgradeWarned[picked + '>' + got]) return;   // once per tab per mismatch
  t.downgradeWarned[picked + '>' + got] = true;
  const verInfo = (typeof cliVersion !== 'undefined' && cliVersion && cliVersion.installed)
    ? ' Your installed Claude Code is ' + cliVersion.installed
      + ((cliVersion.updateAvailable && cliVersion.latest)
          ? ' — update to ' + cliVersion.latest + ' to use ' + prettyModelId(entry.fullId) + '.'
          : '.')
    : '';
  addSystemTo(t, '⚠ You selected ' + entry.label + ', but this conversation is running '
    + prettyModelId(resolvedId) + '. Your Claude Code CLI resolves "' + picked
    + '" to the newest model it knows, which is older than ' + prettyModelId(entry.fullId) + '.' + verInfo);
}

/* Persist this conversation's composer settings (effort/model/thinking) so a
   resumed session restores them — the sidecar VSCode-equivalent. No-op until the
   tab has a session id (a fresh conversation persists on onSessionId). */
/** Persists a tab's effort/model/thinking/permission-mode to the per-session sidecar
 *  (needs t.sessionId). @param {Tab} t */
function persistTabPrefs(t) {
  if (!t || !t.sessionId) return;
  try { if (window._saveSessionPrefs) window._saveSessionPrefs(t.sessionId, String(t.effortIdx), t.model || '', t.thinking ? '1' : '0', t.permMode || DEFAULT_PERM_MODE); }
  catch (e) {}
}

/* Push the current model/effort/thinking selection to the Java status bar so it
   updates immediately (rather than waiting for the next response). */
function notifyStatusSelection() {
  try { if (window._statusSelection) window._statusSelection(modelLabelFor(curModel), effort, thinkingOn ? '1' : '0'); }
  catch (e) {}
}

/* ---- thinking toggle (off => MAX_THINKING_TOKENS=0) ---- */
let thinkingOn = true;

/* THINKING/EFFORT COMPATIBILITY GATE.
   Claude 5 models reject high effort with thinking disabled — the API answers
   400 "output_config.effort 'xhigh' is not supported when thinking is disabled
   on this model" (verified on opus-5 through our own spawn path; opus-4-8 and
   older accept the same combination happily). Rather than let the user compose
   an illegal state and eat an error card after they've already sent, we block
   it in the controls: the offending choice is simply not selectable.

   Three things can change — model, effort, thinking — so the illegal state is
   reachable from three directions. Effort and thinking lock each other out;
   the MODEL switch instead auto-corrects (see enforceThinkingGate), because
   locking that too would strand the user with no way out. */
const EFFORT_REQUIRES_THINKING = ['xhigh', 'max'];

/** @returns {boolean} whether the given model id is a Claude 5 family model —
 *  the only family that enforces the effort/thinking pairing. Aliases resolve
 *  through the catalog's fullId (what the CLI will REALLY run), so picking
 *  "opus" on a binary that tops out at 4.8 correctly reports false. */
function isThinkingGatedModel(id) {
  const entry = MODELS.find(m => m.id === id);
  const concrete = (entry && entry.fullId) ? entry.fullId : id;
  // normalizeModelId gives "opus-5" / "opus-4-8" / "haiku-4-5". Only the MAJOR
  // version counts: match the version right after the family name, so opus-5 and
  // sonnet-5 gate while opus-4-5 and haiku-4-5 (major 4) do not.
  const m = /^[a-z]+-(\d+)/.exec(normalizeModelId(concrete));
  return !!m && parseInt(m[1], 10) >= 5;
}

/** @returns {boolean} whether thinking is currently MANDATORY (model is gated
 *  and effort is at a stop that requires it). While true the thinking toggle is
 *  locked on and the xhigh/max stops stay available. */
function thinkingRequired() {
  return isThinkingGatedModel(curModel)
      && EFFORT_REQUIRES_THINKING.indexOf(effort) >= 0;
}

/** Display name for gate messages. Prefers the concrete id the CLI will really
 *  run ("Opus 5") over a bare alias label ("Opus"), since the whole point of the
 *  message is which model generation imposes the rule. */
function gateModelName(id) {
  const entry = MODELS.find(m => m.id === id);
  return (entry && entry.fullId) ? prettyModelId(entry.fullId) : modelLabelFor(id);
}

/** @returns {boolean} whether the xhigh/max effort stops are currently
 *  selectable. Reciprocal of thinkingRequired(): with thinking off on a gated
 *  model they'd produce a 400, so the slider stops short of them. */
function highEffortAllowed() {
  return thinkingOn || !isThinkingGatedModel(curModel);
}

/** Highest selectable effort index for the current model/thinking combination. */
function maxEffortIdx() {
  if (highEffortAllowed()) return EFFORTS.length - 1;
  let i = EFFORTS.length - 1;
  while (i > 0 && EFFORT_REQUIRES_THINKING.indexOf(EFFORTS[i]) >= 0) i--;
  return i;
}

/* Re-applies the gate after ANY of model/effort/thinking changes. Refreshes the
   locked/dimmed affordances, and — when a model switch has created an illegal
   state — turns thinking back on rather than blocking the switch. */
function enforceThinkingGate(opts) {
  if (thinkingRequired() && !thinkingOn) {
    // Only reachable by switching TO a gated model while sitting at xhigh/max
    // with thinking off. Auto-correct: the deliberate action wins.
    thinkingOn = true;
    const t = activeTab(); if (t) t.thinking = true;
    if (!(opts && opts.silent) && typeof addSystem === 'function') {
      addSystem('Thinking enabled — required at ' + EFFORT_LABELS[effortIdx]
        + ' effort on ' + gateModelName(curModel) + '.');
    }
  }
  updateThinkingCheck();
  updateEffortGate();
}

function updateThinkingCheck() {
  const chk = document.getElementById('think-check');
  if (chk) chk.style.visibility = thinkingOn ? '' : 'hidden';
  const row = chk ? chk.closest('.item') : null;
  if (!row) return;
  const locked = thinkingRequired();
  row.classList.toggle('locked', locked);
  row.title = locked
    ? 'Required at ' + EFFORT_LABELS[effortIdx] + ' effort on ' + gateModelName(curModel)
    : '';
  // Explain the lock inline so it doesn't just look broken.
  let note = row.querySelector('.gate-note');
  if (locked && !note) {
    note = document.createElement('span');
    note.className = 'desc gate-note';
    row.querySelector('.txt').appendChild(note);
  }
  if (note) {
    note.textContent = locked ? 'Required at this effort level on Claude 5 models' : '';
    note.style.display = locked ? '' : 'none';
  }
}

/* Dims the unreachable end of the effort sliders and explains why. The slider is
   a bare span (no native disabled state), so the block lives in effortFromX —
   this only makes the limit visible. */
function updateEffortGate() {
  const capped = !highEffortAllowed();
  const maxPct = (maxEffortIdx() / (EFFORTS.length - 1)) * 100;
  document.querySelectorAll('.eff-slider').forEach(sl => {
    sl.classList.toggle('capped', capped);
    sl.style.setProperty('--eff-cap', maxPct + '%');
    sl.title = capped
      ? 'Turn Thinking on to use X-High or Max on ' + gateModelName(curModel)
      : 'Effort';
  });
}

function toggleThinking(e) {
  if (e) e.stopPropagation();
  // Locked on: at xhigh/max on a Claude 5 model, thinking cannot be disabled.
  if (thinkingOn && thinkingRequired()) return;
  thinkingOn = !thinkingOn;
  const t = activeTab(); if (t) t.thinking = thinkingOn;
  // Turning thinking OFF closes the xhigh/max stops — pull the slider back so
  // the state stays legal instead of silently going illegal.
  if (!thinkingOn && effortIdx > maxEffortIdx()) setEffort(maxEffortIdx());
  enforceThinkingGate();
  persistTabPrefs(t); notifyStatusSelection();
}

/* ---- Account & usage window ---- */
function openAccount() {
  closeMenus();
  let acc = {};
  try { acc = JSON.parse(window._accountInfo ? window._accountInfo() : '{}'); } catch (e) {}
  const win = document.getElementById('account-win');
  function row(k, v) { return v ? '<div class="aw-row"><span class="k">' + k + '</span><span class="v">' + escapeHtml(v) + '</span></div>' : ''; }
  let h = '<div class="aw-head"><span class="t">Account &amp; Usage</span><span class="x" onclick="closeAccount()">' + ICONS.X + '</span></div>';
  h += '<div class="aw-sec">Account</div>';
  h += row('Auth method', acc.authMethod);
  h += row('Email', acc.email);
  h += row('Organization', acc.organization);
  h += row('Plan', acc.plan);
  if (!acc.email) h += '<div class="aw-note">Not signed in, or account info is unavailable.</div>';
  h += '<div class="aw-sec">Usage</div>';
  h += '<div class="aw-note">Usage limits live on your Claude account — the CLI doesn\'t expose the percentages here. <a href="https://claude.ai/settings/usage" target="_blank">Manage usage on claude.ai</a></div>';
  win.innerHTML = h;
  document.getElementById('account-overlay').classList.add('open');
}
function closeAccount() { document.getElementById('account-overlay').classList.remove('open'); }

/* ---- slash commands inside the actions menu ---- */
function buildActionsSlash() {
  const c = document.getElementById('actions-slash');
  if (!c) return;
  c.innerHTML = '';
  SLASH_COMMANDS.forEach(cmd => {
    const it = document.createElement('div'); it.className = 'item';
    it.innerHTML = '<span class="cmd"></span><span class="d"></span>';
    it.querySelector('.cmd').textContent = cmd.cmd;
    it.querySelector('.d').textContent = cmd.desc;
    it.onclick = () => { closeMenus(); applySlash(cmd.cmd); };
    c.appendChild(it);
  });
}

/* Initialise: model config FIRST (so the first tab's default model is the user's
   configured one), then the first tab + context chip + slash list. */

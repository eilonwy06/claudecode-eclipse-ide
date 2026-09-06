/* slash.js — Slash command menu + handlers (/clear /compact /model /rewind /help),
   sendCompact. */

/* ===================== Slash commands ===================== */
const SLASH_COMMANDS = [
  { cmd: '/advisor', desc: 'Set up an advisor model' },
  { cmd: '/clear',   desc: 'Start a new session (tab)' },
  { cmd: '/compact', desc: 'Clear conversation history but keep a summary in context' },
  { cmd: '/model',   desc: 'Switch model' },
  { cmd: '/resume',  desc: 'Open session history' },
  { cmd: '/rewind',  desc: 'Restore code and fork from an earlier message' },
  { cmd: '/help',    desc: 'Show available commands' },
];
const slashState = { open: false, sel: 0, items: [] };
const slashEl = document.getElementById('slash-menu');

function updateSlashMenu() {
  const v = input.value;
  const m = /^\/(\S*)$/.exec(v); // only a leading "/word" with no space yet
  if (!m) { closeSlash(); return; }
  const q = m[1].toLowerCase();
  slashState.items = SLASH_COMMANDS.filter(c => c.cmd.slice(1).startsWith(q));
  if (!slashState.items.length) { closeSlash(); return; }
  slashState.sel = 0;
  renderSlash();
  positionSlash();
  slashState.open = true;
  slashEl.classList.add('open');
}
function renderSlash() {
  slashEl.innerHTML = '<div class="head"><span class="h">Commands</span></div>';
  slashState.items.forEach((c, i) => {
    const it = document.createElement('div');
    it.className = 'item' + (i === slashState.sel ? ' sel' : '');
    it.innerHTML = '<span class="cmd"></span><span class="d"></span>';
    it.querySelector('.cmd').textContent = c.cmd;
    it.querySelector('.d').textContent = c.desc;
    it.onmousedown = (e) => { e.preventDefault(); applySlash(c.cmd); };
    slashEl.appendChild(it);
  });
}
function positionSlash() {
  const r = document.getElementById('composer').getBoundingClientRect();
  slashEl.style.left = (r.left + 12) + 'px';
  slashEl.classList.add('open');
  slashEl.style.top = (r.top - slashEl.offsetHeight - 4) + 'px';
}
function handleSlashKey(e) {
  if (e.key === 'ArrowDown') { slashState.sel = (slashState.sel + 1) % slashState.items.length; renderSlash(); positionSlash(); return true; }
  if (e.key === 'ArrowUp')   { slashState.sel = (slashState.sel - 1 + slashState.items.length) % slashState.items.length; renderSlash(); positionSlash(); return true; }
  if (e.key === 'Enter' || e.key === 'Tab') { e.preventDefault(); applySlash(slashState.items[slashState.sel].cmd); return true; }
  if (e.key === 'Escape') { closeSlash(); return true; }
  return false;
}
/* Runs a command picked from a menu. The composer draft is PRESERVED — picking
   "/clear" while half-way through a message must not throw that message away.
   The one exception is the inline "/" menu, where the draft IS the command being
   typed ("/rew…"), so it's consumed. */
function applySlash(cmd) {
  const typingCommand = /^\/\S*$/.test(input.value.trim());
  closeSlash();
  if (typingCommand) {
    input.value = '';
    input.style.height = 'auto';
    const t = activeTab(); if (t) t.draft = '';
  }
  // Executed directly rather than through doSend(), which would send whatever is
  // in the composer — the whole point is to leave that untouched.
  if (!handleSlashCommand(cmd)) sendSlashToCli(cmd);
}
function closeSlash() { slashState.open = false; slashEl.classList.remove('open'); }
function openSlashFromButton() {
  closeMenus();
  input.focus();
  if (!input.value.startsWith('/')) input.value = '/';
  updateSlashMenu();
}
/**
 * Every slash command echoes itself into the transcript, like VSCode. Commands
 * that open a follow-up choice defer that echo until the choice is actually made,
 * so cancelling leaves no trace.
 * @param {string} text the raw composer text
 * @returns {boolean} true = handled locally, false = pass through to the CLI
 */
function handleSlashCommand(text) {
  const cmd = text.split(/\s+/)[0].toLowerCase();
  // Clears the conversation IN THE CURRENT TAB, then echoes the command so it's
  // the only message left in the fresh session (joebiden7). Deliberately not
  // newSession() — VSCode stays on the tab /clear was invoked from.
  if (cmd === '/clear') { clearSession(); addUserMessage(text, null, null, null, nowIso()); return true; }
  if (cmd === '/compact') { sendCompact(); return true; }          // echoes itself
  // Echo is deferred to the card's confirm() — cancel/Esc adds nothing.
  if (cmd === '/advisor') { openAdvisorCard(text); return true; }
  if (cmd === '/model') { handleModelCommand(text); return true; }
  if (cmd === '/rewind') { openRewindDialog(); return true; }      // deliberately unchanged
  // Opens the SAME history panel the toolbar's Session History button does, but via
  // openHistoryForResume (not openHistoryFromToolbar) — picking a session here loads
  // it into the CURRENT tab in place, matching the CLI's own /resume typed at an
  // existing Claude Terminal prompt (see history.js's historyResumeInPlace). The CLI's
  // real /resume is an interactive picker (local-jsx) that only exists in its terminal
  // TUI; this headless process (-p --input-format stream-json) has no such thing to
  // send it to, same reason /model is reproduced locally above rather than forwarded.
  // No echo: nothing was actually sent, just a panel opened, like /rewind.
  if (cmd === '/resume') { openHistoryForResume(); return true; }
  if (cmd === '/help') {
    const ht = activeTab();
    addUserMessage(text, null, null, null, nowIso());
    addSystemTo(ht, 'Commands: /advisor — set up an advisor model · /clear — new conversation · /compact — compact the conversation into a summary · /model — switch model · /rewind — restore code and fork from an earlier message · /help — this list. Type / to see them.');
    return true;
  }
  return false; // unknown slash: let it pass through to Claude
}

/* ---- /model ----
   Handled HERE, not sent to the CLI. The CLI only implements /model in its
   interactive TUI; over `-p --input-format stream-json` (how this panel runs it)
   it answers "/model isn't available in this environment". So we reproduce its
   replies locally (joebiden6). Doing it locally is also what keeps the tab's own
   selection correct — a CLI-side switch would live inside that one process only,
   and the tab would silently disagree with it. */
function handleModelCommand(text) {
  // addSystem() follows the RENDER tab (streamPane), which isn't necessarily the
  // one you typed in — a reply to a command you just ran belongs in the tab you
  // ran it in, so target it explicitly.
  const t = activeTab();
  addUserMessage(text, null, null, null, nowIso());
  const arg = text.slice('/model'.length).trim();
  if (!arg) {
    addSystemTo(t, 'Current model: ' + modelLabelFor(curModel) + ' (effort: ' + effort + ')\n'
      + 'Usage: /model <name>. Available: ' + availableModelNames().join(', ')
      + ', or a full model ID.');
    return;
  }
  const id = resolveModelArg(arg);
  if (id === null) { addSystemTo(t, "Model '" + arg + "' not found"); return; }
  // noDivider: the "Set model to …" line below already reports the switch, so the
  // "〰 Switched to X 〰" divider would just say it twice.
  selectModel(id, { noDivider: true });
  addSystemTo(t, 'Set model to ' + modelLabelFor(id) + ' for this session only');
}

/** Names accepted by /model, in chooser order (disabled ones are omitted). */
function availableModelNames() {
  const names = MODELS.filter(m => m.id && !m.disabled).map(m => m.id);
  names.push('default');
  return names;
}

/** @returns {string|null} the model id for a /model argument, or null if unknown. */
function resolveModelArg(arg) {
  const a = String(arg).trim();
  if (/^default$/i.test(a)) return '';
  const exact = MODELS.find(m => m.id && m.id.toLowerCase() === a.toLowerCase() && !m.disabled);
  if (exact) return exact.id;
  // A full model ID is accepted when the installed CLI actually has it — the
  // same check the chooser uses for a pinned --model.
  if (/^claude-/i.test(a) && (typeof pinSupported !== 'function' || pinSupported(a.toLowerCase()))) {
    return a.toLowerCase();
  }
  return null;
}

/**
 * Sends a slash command the CLI owns down the same persistent process a normal
 * message uses, echoing it in the transcript so the CLI's reply reads as a turn.
 * @param {string} text the full command line (may carry arguments)
 */
function sendSlashToCli(text) {
  const t = activeTab(); if (!t) return;
  t.cancelled = false;
  loadRender(t);
  const queueing = !!t.streaming;
  addUserMessage(text, null, null, null, nowIso());
  closeSlash();
  if (!queueing) { setStreaming(true); showWorking(); }
  else if (!workingEl) showWorking();
  if (window._sendToJava) window._sendToJava(text, false, t.sessionId || '', t.permMode || permMode, effort, curModel, thinkingOn ? '1' : '0', t.id);
  persistTabPrefs(t);
}

/* /compact goes to the CLI like a normal send (same persistent process), but with
   the working gerund pinned to "Compacting…" and no tab titling — the CLI answers
   with system compact events (window.onCompact) or, on failure, a plain text turn
   ("Not enough messages to compact."). */
function sendCompact() {
  const t = activeTab(); if (!t) return;
  t.cancelled = false;
  loadRender(t);
  const queueing = !!t.streaming;
  addUserMessage('/compact', null, null, null, nowIso());
  input.value = ''; input.style.height = 'auto'; t.draft = ''; closeSlash();
  t.compacting = true;
  if (!queueing) { setStreaming(true); showWorking(); }
  else if (!workingEl) showWorking();
  if (window._sendToJava) window._sendToJava('/compact', false, t.sessionId || '', t.permMode || permMode, effort, curModel, thinkingOn ? '1' : '0', t.id);
  persistTabPrefs(t);
}


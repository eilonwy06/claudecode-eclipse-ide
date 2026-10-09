/* slash.js — Slash command menu + handlers (/clear /compact /model /rewind /help),
   sendCompact. */

/* ===================== Slash commands ===================== */
const SLASH_COMMANDS = [
  { cmd: '/advisor', desc: 'Set up an advisor model' },
  { cmd: '/clear',   desc: 'Start a new session (tab)' },
  { cmd: '/compact', desc: 'Clear conversation history but keep a summary in context' },
  { cmd: '/mcp',     desc: 'Manage MCP servers' },
  { cmd: '/model',   desc: 'Switch model' },
  { cmd: '/resume',  desc: 'Open session history', aliases: ['continue'] },
  { cmd: '/context', desc: 'Show context window usage for this conversation', dialog: true },
  { cmd: '/usage',   desc: 'Show account and plan usage', dialog: true },
  { cmd: '/remote-control', desc: 'Continue this conversation on the web or your phone', aliases: ['rc'] },
  { cmd: '/rewind',  desc: 'Restore code and fork from an earlier message', aliases: ['checkpoint', 'undo'] },
  { cmd: '/help',    desc: 'Show available commands' },
  // The dialogs the command menu opens (clidialogs.js), by the names the CLI gives them.
  // Typed bare they open the dialog; with arguments they go to the CLI, as in the extension.
  { cmd: '/status',  desc: 'Show version, model, account and connectivity', dialog: true },
  { cmd: '/memory',  desc: 'Edit Claude memory files', dialog: true },
  { cmd: '/permissions', desc: 'Manage allow & deny tool permission rules', aliases: ['allowed-tools'], dialog: true },
  { cmd: '/hooks',   desc: 'View hook configurations for tool events', dialog: true },
  { cmd: '/export',  desc: 'Export the current conversation to a file or clipboard', dialog: true },
  { cmd: '/skills',  desc: 'List available skills', dialog: true },
  { cmd: '/config',  desc: 'Open settings', aliases: ['settings'], dialog: true },
  { cmd: '/sandbox', desc: 'Sandbox settings', dialog: true },
  { cmd: '/chrome',  desc: 'Claude in Chrome settings', dialog: true },
  { cmd: '/design-login', desc: 'Authorize design-system access with your claude.ai account', dialog: true },
  { cmd: '/feedback', desc: 'Send feedback to Anthropic or report a bug', hint: '[report]', aliases: ['bug'], dialog: true },
  { cmd: '/login',   desc: 'Log in with a different account', dialog: true },
  { cmd: '/logout',  desc: 'Sign out of Claude on this computer', dialog: true },
];
/* The menu these are listed in, and the keys that drive it, are cmdmenu.js: one menu for
   the / button and for a / typed in the composer. */

/* Runs a command picked from a menu. The composer draft is PRESERVED — picking
   "/clear" while half-way through a message must not throw that message away.
   The one exception is a command being typed ("/rew…"), where the draft IS the
   command, so it is consumed. */
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
/**
 * Every slash command echoes itself into the transcript, like VSCode. Commands
 * that open a follow-up choice defer that echo until the choice is actually made,
 * so cancelling leaves no trace.
 * @param {string} text the raw composer text
 * @returns {boolean} true = handled locally, false = pass through to the CLI
 */
function handleSlashCommand(text) {
  let cmd = text.split(/\s+/)[0].toLowerCase();
  // An alias is the command it stands for.
  const aliased = SLASH_COMMANDS.find(c => (c.aliases || []).includes(cmd.slice(1)));
  if (aliased) cmd = aliased.cmd;
  // A command of the user's own (a skill, a plug-in's, a file of theirs) by one of these
  // names runs in place of ours, as in the extension.
  if (typeof cliOwnsCommand === 'function' && cliOwnsCommand(cmd.slice(1))) return false;
  // What is typed after /feedback is the report's first words.
  if (cmd === '/feedback') { openFeedbackDialog(text.trim().replace(/^\S+\s*/, '')); return true; }
  // A dialog opens for the bare command only; with arguments the command is the CLI's.
  if (text.trim().split(/\s+/).length === 1) {
    const dialogs = { '/status': openStatusDialog, '/memory': () => openMemoryDialog('memory'),
      '/permissions': openPermissionsDialog, '/hooks': openHooksDialog, '/export': openExportDialog,
      '/skills': openSlashCommandsDialog, '/sandbox': openSandboxDialog, '/chrome': openChromeDialog,
      '/config': () => { if (window._ide) _ide('prefs', '', ''); },
      '/usage': openAccount, '/design-login': openDesignDialog, '/login': () => openSignInDialog(true), '/logout': openSignOutDialog };
    if (dialogs[cmd]) { dialogs[cmd](); return true; }
  }
  // Clears the conversation IN THE CURRENT TAB, then echoes the command so it's
  // the only message left in the fresh session (joebiden7). Deliberately not
  // newSession() — VSCode stays on the tab /clear was invoked from.
  if (cmd === '/clear') { clearSession(); addUserMessage(text, null, null, null, nowIso()); return true; }
  if (cmd === '/compact') { sendCompact(); return true; }          // echoes itself
  // Echo is deferred to the card's confirm() — cancel/Esc adds nothing.
  if (cmd === '/advisor') { openAdvisorCard(text); return true; }
  if (cmd === '/model') { handleModelCommand(text); return true; }
  // Same "interactive-only, refuses over stream-json" story as /model and /resume — but
  // get_context_usage answers it anyway. openContextDialog (tabs.js) drives it instead of
  // sending this text anywhere. No echo: a dialog opened, nothing was sent.
  if (cmd === '/context') { openContextDialog(); return true; }
  if (cmd === '/rewind') { openRewindDialog(); return true; }      // deliberately unchanged
  // No echo: the CLI answers asynchronously and writes its own line, so echoing
  // the command here would put it above a result that has not happened yet.
  if (cmd === '/remote-control') { toggleRemoteControl(); return true; }
  // Opens the SAME history panel the toolbar's Session History button does, but via
  // openHistoryForResume (not openHistoryFromToolbar) — picking a session here loads
  // it into the CURRENT tab in place, matching the CLI's own /resume typed at an
  // existing Claude Terminal prompt (see history.js's historyResumeInPlace). The CLI's
  // real /resume is an interactive picker (local-jsx) that only exists in its terminal
  // TUI; this headless process (-p --input-format stream-json) has no such thing to
  // send it to, same reason /model is reproduced locally above rather than forwarded.
  // No echo: nothing was actually sent, just a panel opened, like /rewind.
  if (cmd === '/resume') { openHistoryForResume(); return true; }
  // The CLI's /mcp is another terminal-only picker; this is the extension's window for it.
  // No echo, like /resume: a window opened, nothing was sent.
  if (cmd === '/mcp') { openMcpServers(); return true; }
  if (cmd === '/help') {
    const ht = activeTab();
    addUserMessage(text, null, null, null, nowIso());
    addSystemTo(ht, 'Commands: /advisor — set up an advisor model · /clear — new conversation · /compact — compact the conversation into a summary · /context — show context window usage for this conversation · /mcp — manage MCP servers · /model — switch model · /remote-control — continue this conversation on the web or your phone · /rewind — restore code and fork from an earlier message · /help — this list. Type / to see them. The Agents panel has its own toolbar button in the composer.');
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
   and the tab would silently disagree with it.
   A bare /model then opens the chooser as well, the one "Switch model" opens, so the
   model can be picked there instead of typed. With a name there is nothing left to pick. */
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
    // After the reply, so the command and its answer are already in the conversation.
    if (composerShowing()) openModelChooser();
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
 * The tab's folder goes with it, as with a message: Java reads a send without one as
 * the workspace root, and would restart the process of a tab in another folder there.
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
  if (window._sendToJava) window._sendToJava(text, false, t.sessionId || '', t.permMode || permMode, effort, curModel, thinkingOn ? '1' : '0', t.id, '', rootPathOf(t), ultracodeOn);
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
  if (window._sendToJava) window._sendToJava('/compact', false, t.sessionId || '', t.permMode || permMode, effort, curModel, thinkingOn ? '1' : '0', t.id, '', rootPathOf(t), ultracodeOn);
  persistTabPrefs(t);
}


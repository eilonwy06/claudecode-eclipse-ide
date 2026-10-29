/* slash.js — Slash command menu + handlers (/clear /compact /model /rewind /help),
   sendCompact. */

/* ===================== Slash commands ===================== */
const SLASH_COMMANDS = [
  { cmd: '/advisor', desc: 'Set up an advisor model' },
  { cmd: '/clear',   desc: 'Start a new session (tab)' },
  { cmd: '/compact', desc: 'Clear conversation history but keep a summary in context' },
  { cmd: '/mcp',     desc: 'Manage MCP servers' },
  { cmd: '/plugins', desc: 'Manage Claude Code plugins', aliases: ['plugin', 'marketplace'] },
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
  // The three the CLI has in its terminal only and the extension answers in its panel.
  { cmd: '/copy',    desc: "Copy Claude's last response to clipboard (or /copy N for the Nth-latest)" },
  { cmd: '/plan',    desc: 'Enable plan mode or view the current session plan', hint: '[open|<description>]' },
  { cmd: '/tasks',   desc: 'View and manage everything running in the background', aliases: ['bashes'] },
];
/* The menu these are listed in, and the keys that drive it, are cmdmenu.js: one menu for
   the / button and for a / typed in the composer. */

/* The commands the view answers only when typed bare; what is typed after one goes to the
   CLI with it (`/context all`, `/mcp reconnect all`, `/resume <term>`…). */
const BARE_ONLY_COMMANDS = ['/context', '/rewind', '/remote-control', '/resume', '/mcp', '/plugins', '/help'];

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
  if (cmd === '/feedback' && feedbackMode(activeTab()) === 'disabled') {
    addSystem("Feedback is turned off by your organization's policy or this environment's settings.");
    return true;
  }
  if ((cmd === '/login' || cmd === '/logout' || cmd === '/design-login') && authPolicy.thirdParty) {
    addSystem(cmd + " isn't available in this environment.");
    return true;
  }
  if (cmd === '/feedback') { openFeedbackDialog(text.trim().replace(/^\S+\s*/, '')); return true; }
  // These three are answered here whatever follows them, and leave only a notice in the
  // conversation, no bubble: nothing is sent (but the description after /plan).
  if (cmd === '/copy') { handleCopyCommand(text.trim().replace(/^\S+\s*/, '')); return true; }
  if (cmd === '/plan') { handlePlanCommand(text.trim().replace(/^\S+\s*/, '')); return true; }
  // The Agent map is where what runs in the background is listed and stopped.
  if (cmd === '/tasks') { openAgentsPanel(); return true; }
  const bare = text.trim().split(/\s+/).length === 1;
  // With anything typed after them these are the CLI's, as in the extension.
  if (!bare && BARE_ONLY_COMMANDS.includes(cmd)) return false;
  // A dialog opens for the bare command only; with arguments the command is the CLI's.
  if (bare) {
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
  if (cmd === '/compact') { sendCompact(text.trim().replace(/^\S+\s*/, '')); return true; }   // echoes itself
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
  if (cmd === '/plugins') { openPlugins(); return true; }
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

/* ---- /copy [N] ----
   The CLI's /copy is a picker in its terminal. Here, as in the extension, it copies the
   Nth-latest of Claude's replies (1 = the latest) and says so in one line. A reply is
   what the copy button under it copies: the reply as Claude wrote it (bookmarks.js). */
const COPY_REPLIES_MAX = 20;
/** The tab's replies, latest first, as Claude wrote them. Read between turns only: the
    one being written is among them while a turn runs. */
function repliesToCopy(t) {
  const out = [];
  const items = t.pane ? t.pane.querySelectorAll('.a-item') : [];
  for (let i = items.length - 1; i >= 0 && out.length < COPY_REPLIES_MAX; i--) {
    const text = items[i]._replyText;
    if (typeof text === 'string' && text) out.push(text);
  }
  return out;
}
/** @returns {{text?: string, notice: string}} what /copy with this argument copies and says */
function copyCommandResult(arg, replies) {
  if (!replies.length) return { notice: 'No assistant message to copy' };
  let at = 0;
  if (arg) {
    const n = Number(arg);
    if (!Number.isInteger(n) || n < 1) return { notice: 'Usage: /copy [N] where N is 1 (latest), 2, 3, … Got: ' + arg };
    if (n > replies.length) {
      return { notice: 'Only ' + replies.length + ' assistant ' + (replies.length === 1 ? 'message' : 'messages') + ' available to copy' };
    }
    at = n - 1;
  }
  const text = replies[at];
  const lines = text.split('\n').length;
  return { text, notice: 'Copied to clipboard (' + text.length + ' characters, ' + lines + ' ' + (lines === 1 ? 'line' : 'lines') + ')' };
}
function handleCopyCommand(arg) {
  const t = activeTab(); if (!t) return;
  // The reply being written is not one yet: the copy waits for the turn to end.
  if (t.streaming) {
    addSystemTo(t, 'Copying once the reply finishes');
    (t.afterTurn = t.afterTurn || []).push(() => copyReplyNow(t, arg));
    return;
  }
  copyReplyNow(t, arg);
}
function copyReplyNow(t, arg) {
  const r = copyCommandResult(arg, repliesToCopy(t));
  let notice = r.notice;
  if (r.text !== undefined) {
    // The view's own clipboard first, the one the rest of Eclipse uses (contextmenu.js).
    if (window._clipSet) window._clipSet(r.text);
    else if (navigator.clipboard) navigator.clipboard.writeText(r.text).catch(() => {});
    else notice = 'Could not copy to clipboard';
  }
  addSystemTo(t, notice);
}

/* ---- /plan [open|<description>] ----
   As the extension answers it. Outside plan mode it switches the tab to plan mode, and
   what was typed after it is then sent as a message. In plan mode already it shows the
   plan this conversation has written: in a window here, or with `open` as its file in
   an editor. The plan is the conversation's own, so it is asked of the tab's process. */
function handlePlanCommand(args) {
  const t = activeTab(); if (!t) return;
  const wasPlan = (t.permMode || permMode) === 'plan';
  if (!wasPlan) {
    setMode('plan');
    if (args && args !== 'open' && args !== 'share') { sendSlashToCli(args, true); return; }
  }
  showPlan(t, args.split(/\s+/)[0] === 'open').then((shown) => {
    if (!wasPlan) return;   // the switch was the answer
    if (shown === 'unavailable') addSystemTo(t, "Couldn't show the plan");
    else if (shown === 'none') addSystemTo(t, 'Already in plan mode. No plan written yet.');
  });
}
/** @returns {Promise<string>} 'opened', 'none' (no plan written) or 'unavailable' */
function showPlan(t, inEditor) {
  // A tab with no conversation has no plan, and is not given a process to be asked.
  if (!t.sessionId || typeof cliAsk !== 'function') return Promise.resolve('unavailable');
  return cliAsk(t, 'get_plan').then((res) => {
    if (!res || !res.exists || !res.content) return 'none';
    if (inEditor && res.path && window._openFileInEditor) _openFileInEditor(res.path, rootPathOf(t));
    else openPlanDialog(res.content);
    return 'opened';
  }, () => 'unavailable');
}

/**
 * Sends a slash command the CLI owns down the same persistent process a normal
 * message uses, echoing it in the transcript so the CLI's reply reads as a turn.
 * The tab's folder goes with it, as with a message: Java reads a send without one as
 * the workspace root, and would restart the process of a tab in another folder there.
 * @param {string} text the full command line (may carry arguments)
 * @param {boolean} [asMessage] the text is a message (what follows /plan), so the file
 *        the composer is showing goes with it as it does with any message
 */
function sendSlashToCli(text, asMessage) {
  const t = activeTab(); if (!t) return;
  t.cancelled = false;
  loadRender(t);
  const queueing = !!t.streaming;
  const withCtx = !!asMessage && ctxActive();
  addUserMessage(text, withCtx ? ctxChipLabel() : null, null, null, nowIso(), null, withCtx ? ctxChipTarget() : null);
  if (queueing) noteQueuedSend(t);
  closeSlash();
  if (!queueing) { setStreaming(true); showWorking(); }
  else if (!workingEl) showWorking();
  if (window._sendToJava) window._sendToJava(text, withCtx, t.sessionId || '', t.permMode || permMode, effort, curModel, thinkingOn ? '1' : '0', t.id, '', rootPathOf(t), ultracodeOn);
  persistTabPrefs(t);
}

/* /compact goes to the CLI like a normal send (same persistent process), but with
   the working gerund pinned to "Compacting…" and no tab titling — the CLI answers
   with system compact events (window.onCompact) or, on failure, a plain text turn
   ("Not enough messages to compact."). instructions: what was typed after the command,
   the CLI's custom summarization instructions; none from the context ring. */
function sendCompact(instructions) {
  const t = activeTab(); if (!t) return;
  t.cancelled = false;
  loadRender(t);
  const queueing = !!t.streaming;
  const text = compactCommand(instructions);
  addUserMessage(text, null, null, null, nowIso());
  input.value = ''; input.style.height = 'auto'; t.draft = ''; closeSlash();
  t.compacting = true;
  if (!queueing) { setStreaming(true); showWorking(); }
  else if (!workingEl) showWorking();
  if (window._sendToJava) window._sendToJava(text, false, t.sessionId || '', t.permMode || permMode, effort, curModel, thinkingOn ? '1' : '0', t.id, '', rootPathOf(t), ultracodeOn);
  persistTabPrefs(t);
}

/** The /compact sent to the CLI: the bare command, or the command and its instructions. */
function compactCommand(instructions) {
  const extra = typeof instructions === 'string' ? instructions.trim() : '';
  return extra ? '/compact ' + extra : '/compact';
}


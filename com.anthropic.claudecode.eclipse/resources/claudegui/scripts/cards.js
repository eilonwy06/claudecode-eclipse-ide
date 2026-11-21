/* cards.js — CLI-enforced permission decision card + AskUserQuestion card. */

/* ---- plan approval (ExitPlanMode) ----
   The CLI asks for ExitPlanMode like any other tool — `checkPermissions` returns
   a bare {behavior:"ask", message:"Exit plan mode?"} with NO permission_suggestions,
   so this card's three options cannot be derived from the wire the way an edit's
   middle option is; they are synthesised here to match the CLI's own dialog.

   Plan mode is ONE SHOT: approving ends it. The CLI then exits to the mode it
   remembered as `prePlanMode`, which is NOT necessarily the one picked here — so
   the choice is pushed explicitly with set_permission_mode after the allow. */
const PLAN_TOOL = 'ExitPlanMode';
/** decision value → permission mode the user asked for by choosing it. */
const PLAN_MODE_FOR = { allowAuto: 'acceptEdits', allowManual: 'default' };
/* The CLI's own wording for a rejected plan, copied byte-exact from claude.exe
   (its `dmn`, up to the "\n\nRejected plan:\n" slot, which we can't fill — the
   plan text isn't on this wire). NOT DENY_STOP / DENY_WITH_REASON: those
   assert a file edit was skipped, which is wrong here and would drop edit-rejection
   language into a planning conversation. */
const PLAN_REJECTED =
  "The agent proposed a plan that was rejected by the user. The user chose to stay " +
  "in plan mode rather than proceed with implementation.\n";
/* The tail fragment of the CLI's deny-with-instruction variant, byte-exact and on
   its own — generic enough to carry "here is what to change" feedback without the
   file-edit clause that precedes it there. The blank-line separator is added at
   the join site (below), NOT baked in here, so this stays greppable in claude.exe. */
const PLAN_FEEDBACK = "To tell you how to proceed, the user said:\n";

/* ---- permission decision card (claude --permission-prompt-tool stdio) ---- */

/* The words a rejection tells Claude, as the VS Code extension sends them (its webview bundle):
   with nothing from the reader the run is told to stop and wait for them, with some they are
   the reason. The CLI refuses a rejection with no message at all. */
const DENY_STOP =
  "The user doesn't want to proceed with this tool use. The tool use was rejected " +
  "(eg. if it was a file edit, the new_string was NOT written to the file). " +
  "STOP what you are doing and wait for the user to tell you how to proceed.";
const DENY_WITH_REASON =
  "The user doesn't want to proceed with this tool use. The tool use was rejected " +
  "(eg. if it was a file edit, the new_string was NOT written to the file). " +
  "The user provided the following reason for the rejection: ";

/* Where "don't ask again" saves what it allows, as the CLI names the places, in the order the
   Left and Right arrows go through them. */
const PERM_DESTINATIONS = ['localSettings', 'userSettings', 'projectSettings', 'session'];
const PERM_DEST_NAME = {
  localSettings: 'this project (just you)', userSettings: 'all projects',
  projectSettings: 'this project (shared)', session: 'this session' };
const PERM_DEST_HINT = {
  localSettings: 'Saves to .claude/settings.local.json (gitignored)',
  userSettings: 'Saves to ~/.claude/settings.json',
  projectSettings: 'Saves to .claude/settings.json (shared with team)',
  session: 'Only for this session (not saved)' };
const PERM_DEST_KEY = 'claude-eclipse-permission-destination';
/* Which destination a card starts on when none was chosen before: the widest one the CLI offered. */
const PERM_DEST_WIDEST = ['userSettings', 'projectSettings', 'localSettings', 'session'];

function storedPermDestination() {
  try { const v = localStorage.getItem(PERM_DEST_KEY); return PERM_DESTINATIONS.indexOf(v) >= 0 ? v : null; }
  catch (e) { return null; }
}
function storePermDestination(v) { try { localStorage.setItem(PERM_DEST_KEY, v); } catch (e) {} }

/* A grant's text on one line: a line break in a rule would break the label. */
const PERM_LINE_BREAK = new RegExp('\\r\\n|[\\r\\n\\v\\f\\x85' + String.fromCharCode(0x2028, 0x2029) + ']', 'g');
function permOneLine(text) { return String(text).replace(PERM_LINE_BREAK, String.fromCharCode(0x23ce)); }
function permBase(p) { return String(p || '').split(/[\\/]/).pop() || String(p || ''); }

/** What the CLI's suggested updates would allow: `{rules, dirs, mode}`, each rule or directory as
 *  a short `label` and its `full` text (for a tooltip). */
function describeSuggestions(suggestions) {
  const rules = [], dirs = [];
  let mode = null;
  suggestions.forEach(s => {
    if (!s) return;
    if (s.type === 'addRules') {
      (s.rules || []).forEach(r => {
        if (!r.ruleContent) { rules.push({ label: r.toolName, full: r.toolName }); return; }
        const prefix = /^(.+):\*$/.exec(r.ruleContent);
        if (prefix) { rules.push({ label: prefix[1], full: r.ruleContent }); return; }
        const chars = Array.from(r.ruleContent);
        rules.push({ label: chars.length > 20 ? chars.slice(0, 17).join('') + '…' : r.ruleContent, full: r.ruleContent });
      });
    } else if (s.type === 'addDirectories') {
      (s.directories || []).forEach(d => dirs.push({ label: permBase(d) + '/', full: d }));
    } else if (s.type === 'setMode') {
      mode = { mode: s.mode, destination: s.destination };
    }
  });
  return { rules: rules, dirs: dirs, mode: mode };
}

/** The "don't ask again" option's text for `desc` and the chosen `dest`. `cycle(1)` is what clicking
 *  the destination does. `hasDest` is false when the option saves nowhere the reader can choose
 *  (a change of mode, or nothing that can be named). */
function rememberLabel(desc, dest, cycle) {
  const node = document.createElement('span');
  const put = function () {
    Array.prototype.forEach.call(arguments, p => node.appendChild(typeof p === 'string' ? document.createTextNode(p) : p));
  };
  const grant = g => {
    const e = document.createElement('span');
    e.textContent = permOneLine(g.label);
    if (permOneLine(g.full) !== permOneLine(g.label)) e.title = permOneLine(g.full);
    return e;
  };
  const several = (list, text) => {
    const e = document.createElement('span');
    e.textContent = text;
    e.title = list.map(g => '• ' + permOneLine(g.full)).join('\n');
    return e;
  };
  const link = () => {
    const e = document.createElement('span');
    e.className = 'dec-dest'; e.textContent = PERM_DEST_NAME[dest]; e.title = PERM_DEST_HINT[dest];
    e.onclick = ev => { ev.stopPropagation(); cycle(1); };
    return e;
  };
  const r = desc.rules, d = desc.dirs;
  if (r.length === 1) put('Yes, allow ', grant(r[0]), ' for ', link());
  else if (r.length === 2) put('Yes, allow ', grant(r[0]), ' and ', grant(r[1]), ' for ', link());
  else if (r.length > 2) put('Yes, allow ', grant(r[0]), ' and ', several(r.slice(1), (r.length - 1) + ' more'), ' for ', link());
  else if (d.length === 1) put('Yes, allow access to ', grant(d[0]), ' for ', link());
  else if (d.length > 1) put('Yes, allow access to ', several(d, d.length + ' directories'), ' for ', link());
  else {
    const m = desc.mode && desc.mode.mode;
    put(m === 'acceptEdits' ? 'Yes, allow all edits this session'
        : m === 'default' ? 'Yes, return to normal mode' : "Yes, and don't ask again");
    return { node: node, hasDest: false };
  }
  return { node: node, hasDest: true };
}

/** The card's question, and under it what the reader needs to see or may change, for one tool's
 *  request: `{header: [nodes], body: [nodes], editor}`. `editor` is the command box of a shell
 *  tool, whose text is what gets run. */
function permissionPrompt(toolName, input, t) {
  const key = String(toolName || '').toLowerCase();
  const out = { header: [], body: [], editor: null };
  const ask = function () {
    out.header = Array.prototype.map.call(arguments, p => typeof p === 'string' ? document.createTextNode(p) : p);
  };
  const code = (text, title) => {
    const c = document.createElement('code'); c.className = 'dec-path'; c.textContent = text;
    if (title) c.title = title;
    return c;
  };
  const line = (cls, text) => {
    const d = document.createElement('div'); d.className = cls; d.textContent = text;
    return d;
  };
  const file = p => code(permBase(p), p);
  switch (key) {
    case 'bash': case 'powershell': {
      // Editable only with PREF_EDITABLE_PERMISSION_CARDS (window.__editablePermissionCards, pushed by
      // ClaudeGuiView). Off, the card asks as it did before: the command read-only, in the question.
      // Either way Claude's one-line summary of the command (the tool's `description`) goes under it.
      if (!window.__editablePermissionCards) {
        const cmd = input.command || '';
        if (cmd) { const c = document.createElement('code'); c.textContent = cmd; ask('Allow ' + toolName + ' ', c, '?'); }
        else ask('Allow ' + toolName + '?');
      } else {
        ask('Allow this ' + (key === 'bash' ? 'bash' : 'PowerShell') + ' command?');
        const ed = document.createElement('textarea');
        ed.className = 'dec-cmd'; ed.spellcheck = false; ed.rows = 1; ed.value = input.command || '';
        ed.setAttribute('aria-label', 'Command to run (editable)');
        out.editor = ed; out.body.push(ed);
      }
      if (input.description) out.body.push(line('dec-desc', input.description));
      break;
    }
    case 'edit': case 'multiedit': ask('Make this edit to ', file(input.file_path), '?'); break;
    case 'notebookedit': ask('Make this edit to ', file(input.notebook_path), '?'); break;
    case 'write': ask('Allow write to ', file(input.file_path), '?'); break;
    case 'read': ask('Allow reading from ', file(input.file_path), '?'); break;
    case 'glob': case 'grep': case 'search': {
      const what = key === 'glob' ? 'glob search in' : key === 'grep' ? 'grep in' : 'searching in';
      if (input.path) ask('Allow ' + what + ' ', code(input.path), '?');
      else ask(key === 'search' ? 'Allow this search' : 'Allow this ' + key + ' command');
      break;
    }
    case 'webfetch': ask('Allow fetching this url?'); out.body.push(line('dec-input', input.url || '')); break;
    case 'websearch': {
      ask('Allow searching for this query?');
      out.body.push(line('dec-input', input.query || ''));
      const extra = [];
      if (input.allowed_domains && input.allowed_domains.length) extra.push('Allowed: ' + input.allowed_domains.join(', '));
      if (input.blocked_domains && input.blocked_domains.length) extra.push('Blocked: ' + input.blocked_domains.join(', '));
      if (extra.length) out.body.push(line('dec-input', extra.join(' · ')));
      break;
    }
    case 'sandboxnetworkaccess':
      ask('Allow network connection to this host?'); out.body.push(line('dec-input', input.host || '')); break;
    case 'skill': {
      const name = String(input.skill || '').replace(/^\//, '');
      ask('Use skill ', code('/' + name), '?');
      out.body.push(line('dec-note', 'Claude may use instructions, code, or files from this skill.'));
      const known = typeof allSlashCommands === 'function'
          ? allSlashCommands(t).find(c => c.name === name) : null;
      if (known && known.description) out.body.push(line('dec-desc', known.description));
      if (input.args) out.body.push(line('dec-desc', 'Arguments: ' + input.args));
      break;
    }
    default: {
      const server = typeof mcpServerName === 'function' ? mcpServerName(toolName) : '';
      const label = typeof toolLabel === 'function' ? toolLabel(toolName) : String(toolName || 'this action');
      const strong = document.createElement('strong');
      strong.textContent = server ? server + ' › ' + label : label;
      ask('Do you want to proceed with ', strong, '?');
      if (Object.keys(input).length) {
        const details = document.createElement('details');
        const summary = document.createElement('summary'); summary.textContent = 'Details';
        const pre = document.createElement('pre'); pre.className = 'dec-json';
        pre.textContent = JSON.stringify(input, null, 2);
        details.appendChild(summary); details.appendChild(pre);
        out.body.push(details);
      }
    }
  }
  return out;
}

/* The tool line each open card is for, by request: the diff preview can change the input after
   the card is answered, and the line then has to show what was approved. */
const approvalLines = new Map();
/* Java → page, after an approval whose input the diff preview changed: the line's diff shows the
   edit that was approved instead of the one that was asked for. */
window.onApprovalEdited = function(reqId, inputJson) {
  const entry = approvalLines.get(reqId);
  approvalLines.delete(reqId);
  if (!entry) return;
  const line = entry.line;
  let input; try { input = JSON.parse(inputJson); } catch (e) { return; }
  const oldBlock = line.querySelector(':scope > [data-kind="diff"]');
  if (!oldBlock) return;
  const diff = buildToolDiff(entry.name, input, oldBlock.dataset.mode);
  if (!diff) return;
  const oldSub = line.querySelector(':scope > .tool-sub.diff-sub');
  const sub = document.createElement('div'); sub.className = 'tool-sub diff-sub'; sub.textContent = diff.summary;
  if (oldSub) oldSub.replaceWith(sub); else line.insertBefore(sub, oldBlock);
  oldBlock.replaceWith(diff.block);
  applyCardMode(diff.block, diff.block.dataset.mode);
  settleCards(line);
};
/**
 * Permission decision card (CLI can_use_tool). Blocks the CLI until _decide(reqId, decisionJson).
 * `inputJson` is the tool's input (without the text a diff preview shows instead); `metaJson`
 * is what the CLI says besides it: `{suggestions, reason, description, defaultToNo,
 * suppressAlwaysAllow}`. The decision is `{d, input?, dest?, msg?, interrupt?}`, see
 * `permission_response` in chat.rs.
 * @type {(tabId: string, reqId: string, toolName: string, inputJson: string,
 *         metaJson: string) => void}
 */
window.onApprovalRequest = function(tabId, reqId, toolName, inputJson, metaJson) {
  const t = tabById(tabId);
  if (t && t.cancelled) { if (window._decide) window._decide(reqId, 'deny'); return; }  // stopped: auto-deny, no card
  if (t) loadRender(t);   // card belongs to this conversation
  // The card's OWNER tab, for showBottomCard/clearBottomCard below — t if it
  // resolved, else whatever loadRender last pointed at, same fallback order the
  // pane lookup already used. Captured once so a later tab switch (rtab/activeTab
  // changing) can't retarget which tab's card this decide() call clears.
  const owner = t || rtab || activeTab();
  // One card at a time: a request that comes while one is up waits behind it (carddock.js).
  if (queueCardRequest(owner, reqId, () => window.onApprovalRequest(tabId, reqId, toolName, inputJson, metaJson), 'approval')) return;
  const pane = streamPane() || (activeTab() ? activeTab().pane : null);
  if (!pane) { if (window._decide) window._decide(reqId, 'deny'); return; }
  hideWorking();
  let input = {}, meta = {};
  try { input = JSON.parse(inputJson) || {}; } catch (e) {}
  try { meta = JSON.parse(metaJson) || {}; } catch (e) {}
  const suggestions = Array.isArray(meta.suggestions) ? meta.suggestions : [];
  // The tool line awaiting this decision is the last one in the turn. Keep its dot
  // GRAY while the card is open (mark .pending so markToolsDone skips it); decide()
  // flips it green on allow / red on deny.
  const pendingTool = lastToolLine(curTurn);
  if (pendingTool) pendingTool.classList.add('pending');
  if (pendingTool) approvalLines.set(reqId, { line: pendingTool, name: toolName });
  // Keyed on the tool NAME alone — never on "has no suggestions", which plenty of
  // ordinary asks also satisfy and which would give them plan options.
  const isPlan = (toolName === PLAN_TOOL);
  const card = document.createElement('div'); card.className = 'decision';

  const fold = document.createElement('button');
  fold.type = 'button'; fold.className = 'dec-fold'; fold.title = 'Fold'; fold.innerHTML = ICONS.CHEVRON;
  fold.onclick = () => { fold.title = card.classList.toggle('folded') ? 'Unfold' : 'Fold'; };
  card.appendChild(fold);

  const prompt = isPlan ? { header: ['Accept this plan?'], body: [], editor: null }
                        : permissionPrompt(toolName, input, t);
  const q = document.createElement('div'); q.className = 'dec-q';
  prompt.header.forEach(n => q.appendChild(typeof n === 'string' ? document.createTextNode(n) : n));
  card.appendChild(q);
  prompt.body.forEach(n => card.appendChild(n));
  const editor = prompt.editor;
  if (editor) {
    const grow = () => { editor.style.height = 'auto'; editor.style.height = Math.min(editor.scrollHeight + 2, window.innerHeight * 0.4) + 'px'; };
    editor.addEventListener('input', grow);
    requestAnimationFrame(grow);
  }

  let resolved = false;
  /* `o` is what the decision carries besides its kind. */
  function decide(d, o) {
    if (resolved) return; resolved = true;
    unregisterCardTimeout(reqId);
    if (d === 'deny') approvalLines.delete(reqId);
    unregisterCardCancel();
    document.removeEventListener('keydown', onKey, true);
    // Resolve the pending tool's dot: allow → green (finished), deny → red (rejected).
    if (pendingTool) {
      pendingTool.classList.remove('pending');
      const dot = pendingTool.querySelector('.dot');
      if (dot) dot.className = (d === 'deny') ? 'dot red' : 'dot done';
      // The plan line states the OUTCOME, not a diff summary — a green
      // "Claude's Plan … User approved the plan" reads very differently from a
      // red "… Stayed in plan mode", and the dot alone doesn't say which.
      if (isPlan) {
        const sub = document.createElement('div'); sub.className = 'tool-sub';
        sub.textContent = planOutcomeText(d === 'deny');   // shared with the reload path
        pendingTool.appendChild(sub);
      }
    }
    const payload = Object.assign({ d: d }, o || {});
    // A shell command the reader changed is the command that runs.
    if (d !== 'deny' && editor && editor.value !== (input.command || '')) {
      payload.input = Object.assign({}, input, { command: editor.value });
      // The tool's IN row shows what runs, not what was asked.
      const inRow = pendingTool && pendingTool.querySelector('.io-item[data-kind="input"]');
      if (inRow && inRow._setText) inRow._setText(editor.value);
    }
    if (window._decide) window._decide(reqId, JSON.stringify(payload));
    // Approving the plan ENDS plan mode, so the indicator has to move off "Plan"
    // — and to the mode the user actually chose, which the CLI won't do for us
    // (it restores its own prePlanMode). Push it only AFTER the allow is written,
    // or the control request races the CLI's own transition.
    if (isPlan && PLAN_MODE_FOR[d]) {
      const mode = PLAN_MODE_FOR[d];
      if (t && window._setPermissionMode) {
        try { window._setPermissionMode(t.id, mode); } catch (e) {}
      }
      if (typeof adoptModeForTab === 'function') adoptModeForTab(t, mode);
    }
    // A remembered mode change IS a mode switch: from here on the session behaves like that mode,
    // so the indicator moves to match, or it keeps claiming "Manual" while nothing prompts again.
    // NOT into acceptEdits while the tab is in Plan. There, "allow all edits this session" answers
    // the pending edit — it is not a request to leave planning. Adopting it anyway set
    // t.permMode='acceptEdits', so the next message respawned out of plan mode, and the CLI then
    // remembered prePlanMode==='acceptEdits', which SUPPRESSES the setMode suggestion on every
    // later card. Leaving t.permMode alone keeps Plan sticky.
    if (d === 'allowRemember' && typeof adoptModeForTab === 'function') {
      const change = suggestions.find(s => s && s.type === 'setMode');
      if (change && (change.mode === 'default' || (change.mode === 'acceptEdits' && !(t && t.permMode === 'plan')))) {
        adoptModeForTab(t, change.mode);
      }
    }
    clearBottomCard(owner);                     // card disappears — composer returns
    // Process continues after the decision — start a fresh body below the edits
    // and re-show the working indicator so the user sees activity again.
    finalizeThink(); curBody = null; curText = '';
    showWorking();
    // force only with Smart Scroll Lock on — see its comment in chat.js. By default
    // this obeys Scroll Lock: answering must not move them either.
    scrollBottom(smartScrollLock);
  }
  /* A rejection. With words of the reader's the run goes on, told why; without, it stops and waits
     for them (the CLI ends the turn). A plan keeps the CLI's own words and never stops the run. */
  function reject(text) {
    let msg, interrupt = false;
    if (isPlan) msg = text ? PLAN_REJECTED + '\n' + PLAN_FEEDBACK + text : PLAN_REJECTED;
    else if (text) msg = DENY_WITH_REASON + text;
    else { msg = DENY_STOP; interrupt = true; }
    decide('deny', { msg: msg, interrupt: interrupt });
  }

  // Where "don't ask again" saves: what was chosen last, else the widest the CLI offered.
  let dest = storedPermDestination();
  if (!dest) {
    const offered = suggestions.map(s => s && s.destination).filter(Boolean);
    dest = PERM_DEST_WIDEST.find(d => offered.indexOf(d) >= 0) || 'session';
  }
  const desc = describeSuggestions(suggestions);
  const canRemember = !isPlan && !meta.suppressAlwaysAllow && suggestions.length > 0;

  const opts = isPlan
    ? [{ d: 'allowAuto',   label: 'Yes, and auto-accept' },
       { d: 'allowManual', label: 'Yes, and manually approve edits' },
       { d: 'deny',        label: 'No, keep planning' }]
    : [{ d: 'allow', label: 'Yes' }];
  if (canRemember) opts.push({ d: 'allowRemember', label: '' });
  if (!isPlan) opts.push({ d: 'deny', label: 'No' });

  // When the CLI wants "No" to be the answer to an unthinking keypress, no number answers
  // for the reader, and the card starts on No.
  const numbersOff = !!meta.defaultToNo && !isPlan;
  if (numbersOff) card.classList.add('no-nums');
  let sel = numbersOff ? opts.findIndex(o => o.d === 'deny') : 0;
  const optEls = [];
  const setSel = i => { sel = i; optEls.forEach((e, k) => e.classList.toggle('sel', k === i)); };

  // The remember option is repainted when its destination changes.
  let rememberLbl = null, rememberHasDest = false;
  const paintRemember = () => {
    const made = rememberLabel(desc, dest, cycleDest);
    rememberHasDest = made.hasDest;
    rememberLbl.textContent = '';
    rememberLbl.appendChild(made.node);
  };
  function cycleDest(by) {
    dest = PERM_DESTINATIONS[(PERM_DESTINATIONS.indexOf(dest) + by + PERM_DESTINATIONS.length) % PERM_DESTINATIONS.length];
    storePermDestination(dest);
    paintRemember();
  }

  const act = o => {
    if (o.d === 'deny') reject('');
    else if (o.d === 'allowRemember') decide('allowRemember', { dest: dest });
    else decide(o.d);
  };
  opts.forEach((o, i) => {
    const opt = document.createElement('div'); opt.className = 'dec-opt' + (i === sel ? ' sel' : '');
    opt.setAttribute('data-d', o.d);
    opt.innerHTML = '<span class="num"></span><span class="dec-lbl"></span>';
    opt.querySelector('.num').textContent = (i + 1);
    const lbl = opt.querySelector('.dec-lbl');
    if (o.d === 'allowRemember') rememberLbl = lbl; else lbl.textContent = o.label;
    opt.onclick = () => act(o);
    opt.onmouseenter = () => setSel(i);
    optEls.push(opt);
    card.appendChild(opt);
  });
  if (rememberLbl) paintRemember();

  const insteadNum = opts.length + 1;
  const instead = document.createElement('div'); instead.className = 'dec-instead';
  instead.innerHTML = '<span class="num">' + insteadNum + '</span>';
  const inp = document.createElement('input'); inp.type = 'text';
  inp.placeholder = isPlan ? 'Tell Claude what to change' : 'Tell Claude what to do instead';
  inp.onkeydown = (e) => {
    e.stopPropagation();
    if (e.key === 'Enter') { e.preventDefault(); const v = inp.value.trim(); if (v) reject(v); }
    else if (e.key === 'Escape') { e.preventDefault(); reject(''); }
  };
  instead.appendChild(inp); card.appendChild(instead);

  // Label comes from the live Eclipse binding (Esc, or Ctrl+G under Emacs). Always built,
  // then hidden when nothing is bound — an element that was never created could not repaint
  // itself if the user binds a key while the card is up.
  const esc = document.createElement('div'); esc.className = 'dec-esc';
  card.appendChild(esc);
  registerHintPainter(esc, () => {
    const t = cancelHintText();
    esc.textContent = t;
    esc.style.display = t ? '' : 'none';
  });

  // Keys: a number answers with that option (the last number is the box for the reader's own
  // words), Enter with the option the card is on, Up and Down move it, Left and Right change where
  // "don't ask again" saves, Esc rejects.
  function onKey(e) {
    if (resolved || document.activeElement === inp) return;
    // A background tab's card (now that per-tab cards can coexist instead of
    // evicting each other) isn't in the DOM — bail so its number/Esc shortcuts
    // can't fire on whatever card the ACTIVE tab is actually showing.
    if (!card.isConnected) { document.removeEventListener('keydown', onKey, true); return; }
    if (card.classList.contains('folded')) return;
    if (editor && document.activeElement === editor) return;   // typing a command
    if (e.metaKey || e.ctrlKey || e.altKey) return;
    if (e.key === 'Escape') { e.preventDefault(); reject(''); return; }
    const n = parseInt(e.key, 10);
    if (n >= 1 && n <= insteadNum && !numbersOff) {
      e.preventDefault();
      if (n === insteadNum) inp.focus(); else act(opts[n - 1]);
    } else if (e.key === 'Enter') {
      e.preventDefault(); act(opts[sel]);
    } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const next = sel + (e.key === 'ArrowDown' ? 1 : -1);
      if (next >= opts.length) inp.focus(); else if (next >= 0) setSel(next);
    } else if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight') && rememberHasDest && opts[sel].d === 'allowRemember') {
      e.preventDefault();
      if (!e.repeat) cycleDest(e.key === 'ArrowRight' ? 1 : -1);
    }
  }
  document.addEventListener('keydown', onKey, true);
  registerCardCancel(() => reject(''), owner);

  // Presentation-only cleanup for a card nobody here answered — see the reason
  // codes on registerCardTimeout in carddock.js. It must NOT call window._decide
  // for either reason: on 'timeout' Java already answered the CLI itself, and on
  // 'cancelled' the CLI is no longer listening for this request at all.
  registerCardTimeout(reqId, (reason) => {
    if (resolved) return; resolved = true;
    unregisterCardCancel();
    document.removeEventListener('keydown', onKey, true);
    // Withdrawn rather than expired: the decision was taken somewhere else (the
    // phone, claude.ai) or the turn ended. Close the card and say nothing —
    // asserting an outcome would be guessing, and the one that actually happened
    // is already on its way here as this tool's own result. Clearing .pending is
    // what lets the normal end-of-turn pass resolve the dot instead of leaving
    // it gray forever on a turn whose result never comes.
    if (reason === 'cancelled') {
      if (pendingTool) pendingTool.classList.remove('pending');
      clearBottomCard(owner);
      return;
    }
    if (pendingTool) {
      pendingTool.classList.remove('pending');
      const dot = pendingTool.querySelector('.dot');
      if (dot) dot.className = 'dot red';
      if (isPlan) {
        const sub = document.createElement('div'); sub.className = 'tool-sub';
        sub.textContent = planOutcomeText(true);
        pendingTool.appendChild(sub);
      }
    }
    clearBottomCard(owner);
    scrollBottom();
  });

  showBottomCard(card, owner);
  // The proposed change opens in its diff editor now that its card is up, not when the
  // request came: it may have waited behind another card (ClaudeGuiView.openPreview).
  if (window._approvalShown) window._approvalShown(reqId);
  // force only with Smart Scroll Lock on — see its comment in chat.js.
  scrollBottom(smartScrollLock);
};

/* ---- AskUserQuestion card (single/multi question, options + Other) ---- */
/**
 * AskUserQuestion card. questionsJson is a serialized {@link AskQuestion} array;
 * answers go back via _answerQuestion(reqId, json) ("[]" = dismissed).
 * @typedef {{question: string, header?: string, multiSelect?: boolean,
 *            options: {label: string, description?: string, preview?: string}[]}} AskQuestion
 * @type {(tabId: string, reqId: string, questionsJson: string) => void}
 */
window.onAskQuestion = function(tabId, reqId, questionsJson) {
  const t = tabById(tabId);
  if (t && t.cancelled) { if (window._answerQuestion) window._answerQuestion(reqId, '[]'); return; }  // stopped: no card
  if (t) loadRender(t);   // card belongs to this conversation
  // Captured once, same reasoning as onApprovalRequest's owner: fixes which tab's
  // card this closure's finish()/cancel() will show/clear, independent of rtab or
  // activeTab() changing later (e.g. the user switches tabs before answering).
  const owner = t || rtab || activeTab();
  if (queueCardRequest(owner, reqId, () => window.onAskQuestion(tabId, reqId, questionsJson), 'question')) return;   // as above
  const pane = streamPane() || (activeTab() ? activeTab().pane : null);
  let questions = [];
  try { questions = JSON.parse(questionsJson) || []; } catch (e) {}
  if (!pane || !questions.length) { if (window._answerQuestion) window._answerQuestion(reqId, '[]'); return; }
  hideWorking();
  // Keep the Questions tool's dot GRAY while the card is open; answered → green, dismissed → red.
  const pendingTool = lastToolLine(curTurn);
  if (pendingTool) pendingTool.classList.add('pending');
  const resolveDot = (rejected) => {
    if (!pendingTool) return;
    pendingTool.classList.remove('pending');
    const dot = pendingTool.querySelector('.dot');
    if (dot) dot.className = rejected ? 'dot red' : 'dot done';
  };

  // choice = option index or 'other' (a single-select question). A multi-select one keeps
  // `picks`, the option indexes ticked, and `otherOn`, whether its Other box is ticked.
  const state = questions.map(() => ({ choice: null, other: '', picks: [], otherOn: false }));
  let activeQ = 0, resolved = false, collapsed = false;
  const card = document.createElement('div'); card.className = 'question-card';

  function answeredText(i) {
    const st = state[i], q = questions[i];
    // A multi-select answer is the ticked labels in the order the options are listed, then
    // the Other text, joined by ", " — the form the CLI and the VS Code card use (they
    // refuse a label that contains ", " for exactly this reason).
    if (q.multiSelect) {
      const labels = (q.options || []).filter((_, oi) => st.picks.indexOf(oi) >= 0).map(o => o.label || '');
      if (st.otherOn && st.other.trim()) labels.push(st.other.trim());
      return labels.join(', ');
    }
    // No "[User typed]: " prefix — this is a legitimate answer on the allow path
    // (updatedInput.answers). The prefix leaked into the visible bubble and made
    // the model read the answer as anomalous tool output (issue #98).
    if (st.choice === 'other') return st.other.trim();
    if (st.choice != null && q.options[st.choice]) return q.options[st.choice].label;
    return '';
  }
  function isAnswered(i) {
    const st = state[i];
    if (questions[i].multiSelect) return (st.picks.length > 0 || st.otherOn) && (!st.otherOn || !!st.other.trim());
    return st.choice === 'other' ? !!st.other.trim() : st.choice != null;
  }
  function allAnswered() { return state.every((_, i) => isAnswered(i)); }
  /* The claudeCodeEclipse tool's answerQuestion (claudetool.js): one string per question, an
     option's label (any case), words of the answerer's own, or for a multi-select question the
     labels joined by ", " — the form the CLI is given. Filled in as clicks would fill it, then
     submitted by the same finish() as Submit. A refusal is returned before anything is set. */
  card._questions = questions;
  card._answer = function (answers) {
    if (resolved) return 'That card has already been answered.';
    if (!Array.isArray(answers) || answers.length !== questions.length) {
      return 'The card asks ' + questions.length + ' question' + (questions.length === 1 ? '' : 's')
        + "; 'answers' needs one for each, in order.";
    }
    const opts = (q) => (q.options || []).map(o => String((o && o.label) || ''));
    const find = (q, text) => {
      const labels = opts(q);
      let at = labels.indexOf(text);
      if (at < 0) at = labels.findIndex(l => l.toLowerCase() === text.toLowerCase());
      return at;
    };
    const filled = [];
    for (let i = 0; i < questions.length; i++) {
      const q = questions[i], text = String(answers[i] || '').trim();
      if (!text) return 'Answer ' + (i + 1) + ' is empty.';
      const st = { choice: null, other: '', picks: [], otherOn: false };
      if (q.multiSelect) {
        const own = [];
        text.split(', ').map(p => p.trim()).filter(Boolean).forEach(p => {
          const at = find(q, p);
          if (at >= 0) { if (st.picks.indexOf(at) < 0) st.picks.push(at); } else own.push(p);
        });
        if (own.length) { st.otherOn = true; st.other = own.join(', '); }
        st.picks.sort((a, b) => a - b);
      } else {
        const at = find(q, text);
        if (at >= 0) st.choice = at; else { st.choice = 'other'; st.other = text; }
      }
      filled.push(st);
    }
    filled.forEach((st, i) => { state[i] = st; });
    finish();
    return resolved ? null : 'The card did not take those answers.';
  };
  function finish() {
    if (resolved || !allAnswered()) return; resolved = true;
    unregisterCardTimeout(reqId);
    unregisterCardCancel();
    document.removeEventListener('keydown', onKey, true);
    resolveDot(false);
    const answers = questions.map((q, i) => ({
      header: q.header || q.question || ('Question ' + (i + 1)),
      question: q.question || '', answer: answeredText(i)
    }));
    if (window._answerQuestion) window._answerQuestion(reqId, JSON.stringify(answers));
    clearBottomCard(owner);
    const byQuestion = {};
    answers.forEach(a => { byQuestion[a.question] = a.answer; });
    // force only with Smart Scroll Lock on — see its comment in chat.js.
    renderQuestionsOutput(pendingTool, questions, byQuestion);
    resumeAfterQuestions(); scrollBottom(smartScrollLock);
  }
  function cancel() {
    if (resolved) return; resolved = true;
    unregisterCardTimeout(reqId);
    unregisterCardCancel();
    document.removeEventListener('keydown', onKey, true);
    resolveDot(true);
    if (window._answerQuestion) window._answerQuestion(reqId, '[]');
    // The questions stay in the conversation as the call's output, marked declined, and Claude's
    // reply to the dismissal streams below it: the same pairing as an answer (finish).
    clearBottomCard(owner);
    renderQuestionsOutput(pendingTool, questions, null, 'declined'); resumeAfterQuestions();
    // force only with Smart Scroll Lock on — see its comment in chat.js.
    scrollBottom(smartScrollLock);
  }

  function render() {
    card.innerHTML = '';
    const tabs = document.createElement('div'); tabs.className = 'q-tabs';
    questions.forEach((q, i) => {
      if (questions.length === 1 && i > 0) return;
      const t = document.createElement('div'); t.className = 'q-tab' + (i === activeQ ? ' active' : '');
      t.textContent = q.header || ('Question ' + (i + 1));
      t.onclick = () => { activeQ = i; render(); };
      tabs.appendChild(t);
    });
    // Collapse control, next to the existing X — matches VSCode's card, which lets you
    // shrink a question back to its header row without cancelling it. Built before .q-x
    // so it reads left-to-right as "collapse, then cancel".
    const chev = document.createElement('div'); chev.className = 'q-chevron'; chev.innerHTML = ICONS.CHEVRON;
    chev.title = collapsed ? 'Expand' : 'Collapse';
    chev.onclick = (e) => { e.stopPropagation(); collapsed = !collapsed; render(); };
    tabs.appendChild(chev);
    const x = document.createElement('div'); x.className = 'q-x'; x.innerHTML = ICONS.X; x.onclick = cancel;
    tabs.appendChild(x);
    card.appendChild(tabs);
    card.classList.toggle('collapsed', collapsed);
    // Collapsed: stop right after the tabs row — nothing below it gets built at all,
    // rather than building the body and hiding it, which would keep re-running the
    // "Other" textarea's autofocus/grow side effects for a body nobody can see.
    if (collapsed) return;
    card.appendChild(Object.assign(document.createElement('div'), { className: 'q-sep' }));

    const q = questions[activeQ];
    const multi = !!q.multiSelect;
    // Options can each carry a preview (a layout, a snippet), shown beside the list as the
    // pointer passes over them. Only a single-select question has them, as in VS Code.
    const hasPreview = !multi && (q.options || []).some(o => o && o.preview);
    const main = hasPreview ? Object.assign(document.createElement('div'), { className: 'q-main' }) : card;
    let pvTitle = null, pvBody = null, hovered = -1;
    // The one the pointer is over; none, the one chosen; none of those, the first.
    const paintPreview = () => {
      if (!pvBody) return;
      const sel = state[activeQ].choice;
      const at = hovered >= 0 ? hovered : (typeof sel === 'number' ? sel : 0);
      const opt = (q.options || [])[at] || {};
      pvTitle.textContent = opt.label || '';
      pvBody.textContent = opt.preview || '';
      // A longer preview than the one before it was scrolled into must not hide a shorter one's top.
      pvBody.parentNode.scrollTop = 0; pvBody.parentNode.scrollLeft = 0;
    };
    const qt = document.createElement('div'); qt.className = 'q-text'; qt.textContent = q.question || '';
    main.appendChild(qt);

    const submit = document.createElement('div');
    (q.options || []).forEach((opt, oi) => {
      const on = multi ? state[activeQ].picks.indexOf(oi) >= 0 : state[activeQ].choice === oi;
      const row = document.createElement('div'); row.className = 'q-opt' + (on ? ' sel' : '');
      const radio = document.createElement('div'); radio.className = multi ? 'q-check' : 'q-radio';
      const txt = document.createElement('div'); txt.className = 'q-otext';
      const title = document.createElement('div'); title.className = 'q-otitle'; title.textContent = opt.label || '';
      txt.appendChild(title);
      if (opt.description) { const d = document.createElement('div'); d.className = 'q-odesc'; d.textContent = opt.description; txt.appendChild(d); }
      row.appendChild(radio); row.appendChild(txt);
      row.onclick = () => {
        if (multi) {
          // Ticks stay: more can be picked, so this neither moves on nor submits.
          const at = state[activeQ].picks.indexOf(oi);
          if (at >= 0) state[activeQ].picks.splice(at, 1); else state[activeQ].picks.push(oi);
          render();
          return;
        }
        state[activeQ].choice = oi;
        // auto-advance to the next question until the last one (then stay so they can submit)
        if (activeQ < questions.length - 1) activeQ++;
        render();
      };
      if (hasPreview) {
        row.onmouseenter = () => { hovered = oi; paintPreview(); };
        row.onmouseleave = () => { hovered = -1; paintPreview(); };
      }
      main.appendChild(row);
    });
    // Other
    const otherOn = multi ? state[activeQ].otherOn : state[activeQ].choice === 'other';
    const orow = document.createElement('div'); orow.className = 'q-opt' + (otherOn ? ' sel' : '');
    orow.appendChild(Object.assign(document.createElement('div'), { className: multi ? 'q-check' : 'q-radio' }));
    const otxt = document.createElement('div'); otxt.className = 'q-otext';
    otxt.appendChild(Object.assign(document.createElement('div'), { className: 'q-otitle', textContent: 'Other' }));
    orow.appendChild(otxt);
    orow.onclick = () => {
      // A multi-select Other is one more box to tick, and ticking it again clears it.
      if (multi) state[activeQ].otherOn = !state[activeQ].otherOn; else state[activeQ].choice = 'other';
      render();
      setTimeout(() => { const i = card.querySelector('.q-other-in textarea'); if (i) i.focus(); }, 0);
    };
    if (hasPreview) {
      orow.onmouseenter = () => { hovered = -1; paintPreview(); };
    }
    main.appendChild(orow);
    if (otherOn) {
      const oin = document.createElement('div'); oin.className = 'q-other-in';
      // textarea, not input: a single-line <input> has no notion of a newline at
      // all, so Shift+Enter had nothing to fall through to. Mirrors the main
      // composer (#input in ui.js) — auto-grow on input, Enter submits, Shift+Enter
      // is left alone to insert a newline natively.
      const inp = document.createElement('textarea'); inp.rows = 1; inp.placeholder = 'Type your answer'; inp.value = state[activeQ].other;
      // box-sizing is border-box globally (tokens.css), so style.height has to cover padding
      // AND border — but scrollHeight only covers content + padding. Setting height straight
      // from scrollHeight leaves the box short by exactly the border, so it overflows by 2px
      // at every size and shows a scrollbar that never goes away. offsetHeight - clientHeight
      // is that border. The composer doesn't need this: #input has no padding or border.
      const grow = () => measuringTextarea(inp, () => {
        inp.style.height = 'auto';
        const natural = inp.scrollHeight + (inp.offsetHeight - inp.clientHeight);
        inp.style.height = Math.min(natural, 160) + 'px';
        // Only scroll once the answer is genuinely taller than the cap.
        inp.style.overflowY = natural > 160 ? 'auto' : 'hidden';
      });
      inp.oninput = () => { state[activeQ].other = inp.value; submit.classList.toggle('ready', allAnswered()); grow(); };
      inp.onkeydown = (e) => { e.stopPropagation(); if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); finish(); } };
      oin.appendChild(inp); main.appendChild(oin);
      // render() re-seeds .value from saved state on every option click / question-tab
      // switch, but oninput (where grow() normally runs) doesn't fire for that — size
      // it once here too, or a revisited multi-line answer comes back collapsed to one
      // row. Needs to be in the DOM first for scrollHeight to mean anything.
      grow();
    }
    if (hasPreview) {
      const pv = document.createElement('div'); pv.className = 'q-preview';
      pvTitle = document.createElement('div'); pvTitle.className = 'q-pv-title';
      pvBody = document.createElement('pre'); pvBody.className = 'q-pv-body';
      pv.appendChild(pvTitle); pv.appendChild(pvBody);
      // Tall enough for the longest preview (up to the CSS cap, past which it scrolls), whichever one is
      // showing: a panel that followed the one under the pointer would move the card, and so the options
      // being pointed at, which swaps the preview again. 17.4px a line is the CSS's 12px * 1.45, and 42px
      // the title, the padding and the border.
      const lines = Math.max.apply(null, (q.options || []).map(o => ((o && o.preview) || '').split('\n').length));
      pv.style.minHeight = Math.min(280, Math.ceil(lines * 17.4) + 42) + 'px';
      const cols = document.createElement('div'); cols.className = 'q-cols';
      cols.appendChild(main); cols.appendChild(pv);
      card.appendChild(cols);
      paintPreview();
    }
    submit.className = 'q-submit' + (allAnswered() ? ' ready' : '');
    submit.innerHTML = '<span class="num">1</span><span>Submit answers</span>';
    submit.onclick = finish;
    card.appendChild(submit);
    // Live Eclipse binding label, not a hardcoded "Esc" — under the Emacs scheme Esc is a
    // multi-stroke prefix and never arrives, so naming it would be a lie. Hidden, not
    // omitted, so it can repaint if the binding changes while the card is open.
    const qesc = Object.assign(document.createElement('div'), { className: 'q-esc' });
    card.appendChild(qesc);
    registerHintPainter(qesc, () => {
      const t = cancelHintText();
      qesc.textContent = t;
      qesc.style.display = t ? '' : 'none';
    });
  }
  function onKey(e) {
    if (resolved) return;
    // A background tab's card (now that per-tab cards can coexist instead of
    // evicting each other) isn't in the DOM — bail so it can't intercept Esc
    // meant for whatever card the ACTIVE tab is actually showing.
    if (!card.isConnected) { document.removeEventListener('keydown', onKey, true); return; }
    // Capture-phase on document: runs before the Other field's own onkeydown, so
    // that handler's stopPropagation can't shield it — the tag check here is what
    // has to do it. Must accept TEXTAREA too now that Other is one (was INPUT-only
    // when it was a single-line <input>), or Escape while typing an answer
    // dismisses the whole card and throws away what was typed.
    const ae = document.activeElement;
    const inField = ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA');
    if (e.key === 'Escape' && !inField) { e.preventDefault(); cancel(); }
  }
  document.addEventListener('keydown', onKey, true);
  registerCardCancel(cancel, owner);

  // Presentation-only, and never calls window._answerQuestion — see the matching
  // comment on the approval card's registerCardTimeout, and the reason codes in
  // carddock.js. On 'timeout' Java already answered "[]" (dismissed); on
  // 'cancelled' the question was answered on another device, or its turn ended.
  registerCardTimeout(reqId, (reason) => {
    if (resolved) return; resolved = true;
    unregisterCardCancel();
    document.removeEventListener('keydown', onKey, true);
    if (reason === 'cancelled') {
      clearBottomCard(owner);
      return;
    }
    resolveDot(true);
    clearBottomCard(owner);
    // Same pairing as finish(): the reply goes on under the output, in the same turn.
    renderQuestionsOutput(pendingTool, questions, null, 'timeout');
    resumeAfterQuestions();
    scrollBottom();
  });

  render(); showBottomCard(card, owner);
  // force only with Smart Scroll Lock on — see its comment in chat.js.
  scrollBottom(smartScrollLock);
};


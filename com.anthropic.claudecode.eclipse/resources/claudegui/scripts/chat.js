/* chat.js — Live transcript rendering: user/assistant turns, tool lines, inline diffs,
   thinking blocks, doSend/doCancel. */

/* ===================== Chat (live, local) ===================== */
let curTurn = null, curBody = null, curText = '';
let curThink = null, curThinkText = '', thinkStart = 0, turnStart = 0;

function clearWelcome(pane) { if (!pane) return; const w = pane.querySelector('.welcome'); if (w) w.remove(); }
/* Scroll Lock — the view toolbar's checkbox (the same Action, and the same icon, the
   Claude Terminal carries; see ClaudeGuiView#createToolBar). It ARMS the follow-tail
   behavior below rather than freezing the transcript outright:

     off  → the transcript always scrolls to the bottom on every render (the original
            behavior, untouched).
     on   → it scrolls only while you are already at the bottom. Scroll up to read and
            it holds; scroll back down and it resumes on its own. Exception: with Smart
            Scroll Lock also on (a separate preference, see smartScrollLock below), your
            OWN deliberate actions — sending a message, answering an approval or question
            card — still jump you to the bottom, and so does Claude raising a NEW
            approval or question card (the session is blocked until it's answered); a
            card timing out on its own does not.

   View-wide, not per-tab: #messages is a single scroll container shared by every tab's
   pane (chat.css), so one toggle governs every conversation in the view. Java owns the
   checked state because this variable doesn't survive a webview reload and the toolbar
   checkbox does — hence the re-push from the page-load handler. */
let scrollLocked = false;
window.onScrollLock = function(locked) {
  scrollLocked = !!locked;
  // Both edges: unlocking has to retire the button now, not at the next render.
  updateJumpToLatest();
};
// Smart Scroll Lock (Preferences > Claude Code): while the lock is armed, still jump to
// the bottom for the user's OWN deliberate actions (sending a message, answering a card)
// AND when Claude raises a new approval/question card, instead of holding through those
// too — see scrollBottom's `force` param and its call sites in cards.js/chat.js. A card
// timing out on its own is excluded, matching the plugin's pre-Scroll-Lock behavior. Off
// matches the plugin's current upstream behavior (the lock holds through everything).
let smartScrollLock = false;
window.onSmartScrollLock = function(smart) { smartScrollLock = !!smart; };
// How far from the true bottom still counts as "at the bottom" for the purpose of
// (re-)arming followTail — has to clear the viewport settling on first render
// (scrollHeight starts equal to clientHeight before any content), not a streamed
// chunk's height. A big chunk arriving is NOT what this threshold has to survive:
// followTail decides that by staying whatever it last was, not by re-measuring.
const SCROLL_BOTTOM_SLOP = 4;
function isNearBottom() {
  const scrollable = messagesEl.scrollHeight - messagesEl.clientHeight;
  if (scrollable <= SCROLL_BOTTOM_SLOP) return true;   // nothing to scroll
  return scrollable - messagesEl.scrollTop <= SCROLL_BOTTOM_SLOP;
}
// Whether the ACTIVE tab's view should keep following new content. This is STATE, not a
// per-call measurement: scrollHeight forces a synchronous layout flush the instant it's
// read, so by the time autoScroll could measure anything the just-appended content is
// already counted, making one big chunk (a code fence, a whole tool-result block — none of
// this streams in byte-sized pieces) indistinguishable from the user having scrolled up.
// Measuring only ever happens in the 'scroll' listener (the user's own wheel/drag) and,
// explicitly, right after each scrollTop write below — a write landing on a position the
// container already holds is a NO-OP that fires no event, so relying on the event alone
// leaves followTail stuck at false forever.
// Maintained even while unlocked, so arming the toggle mid-read is instantly correct.
//
// ONE module global describing whichever tab is on screen (like curTurn/curBody — see
// loadRender in tabs.js) rather than always reading activeTab().followTail, since
// #messages is a single scroll container: only the active tab's position is ever
// meaningfully current. switchTab parks this and the raw scrollTop onto the outgoing Tab
// and restores the incoming one's, the same pattern already used for the composer draft.
let followTail = true;
// Only meaningful while armed: the button must never appear with the toggle off, where
// the transcript follows unconditionally and there is nothing to jump back to.
const jumpToLatestEl = document.getElementById('jump-to-latest');
function updateJumpToLatest() {
  if (jumpToLatestEl) jumpToLatestEl.classList.toggle('show', scrollLocked && !followTail);
}
// Set by find.js immediately before a programmatic scrollIntoView (jumping to a search
// match) and cleared right after: the 'scroll' event this fires is asynchronous relative
// to the call that caused it, so a plain "set followTail then restore it" in find.js
// would race this listener — it could run AFTER the restore and clobber followTail back
// to isNearBottom()'s value. Checking a flag the listener itself respects is the only
// race-free way to except one particular scroll from recomputing followTail.
let suppressFollowTailUpdate = false;
messagesEl.addEventListener('scroll', () => {
  if (suppressFollowTailUpdate) return;
  followTail = isNearBottom(); updateJumpToLatest();
  updatePinnedPrompt();
});
/** Exactly ONE user turn is ever pinned at a time — the most recent one that has already
 *  scrolled up to (or past) #messages' own top edge. Plain CSS `position: sticky` on every
 *  user turn independently can't express this: two turns sharing the same `top: 0` each
 *  satisfy their own "stick" condition once THEY individually scroll past it, with no
 *  awareness of each other, so a shorter later turn sticks right on top of a taller
 *  earlier one without fully covering it — the earlier (still technically "stuck") bubble's
 *  extra height stays visible poking out from underneath. Picking the single correct one by
 *  hand (a `.pinned-prompt` class chat.css keys `position: sticky` off, in place of a blanket
 *  selector matching every user turn) sidesteps that rather than fighting sticky's own math. */
function updatePinnedPrompt() {
  const t = activeTab(); if (!t || !t.pane) return;
  const turns = t.pane.querySelectorAll(':scope > .turn');
  const containerTop = messagesEl.getBoundingClientRect().top;
  let active = null;
  for (let i = turns.length - 1; i >= 0; i--) {
    if (!turns[i].querySelector(':scope > .user-msg')) continue;
    // Reads the turn's CURRENT (possibly still sticky-from-last-pass) position — fine
    // either way: a still-correctly-pinned turn reports a top at/near containerTop and
    // keeps winning; a turn that should no longer be pinned reports its true, now-lower
    // in-flow position (once un-pinned it's a plain block again) and loses to whichever
    // later turn has since crossed the same threshold.
    if (turns[i].getBoundingClientRect().top <= containerTop + 1) { active = turns[i]; break; }
  }
  turns.forEach(turn => turn.classList.toggle('pinned-prompt', turn === active));
}
/* The one place the transcript decides whether to move. Shared with showWorking, which
   appends outside of scrollBottom. */
function autoScroll() {
  if (scrollLocked && !followTail) { updateJumpToLatest(); return; }
  messagesEl.scrollTop = messagesEl.scrollHeight;
  // Set directly rather than left to the 'scroll' event this write may fire: when the view
  // already sits at the bottom the write is a no-op and no event arrives, which would
  // strand followTail at false and leave the button unable to retire itself.
  followTail = true;
  updateJumpToLatest();
  updatePinnedPrompt();
}
/**
 * @param {boolean} [force] Jump to the bottom even when the lock is armed and the user
 *   has scrolled up — for a deliberate action of theirs (sending a message, answering a
 *   permission prompt) where landing on what follows is expected, not a surprise.
 */
function scrollBottom(force) {
  if (workingEl && workingEl.parentNode) workingEl.parentNode.appendChild(workingEl); // keep last
  // Don't yank the visible view to the bottom for a BACKGROUND tab's stream — only
  // the active tab's pane is on screen, so a background render must not scroll it.
  // force is a deliberate action ON THE ACTIVE TAB ITSELF and must bypass this: rtab
  // tracks whichever tab last received a stream event, not the one on screen, so a
  // background tab that has streamed anything since the last switch leaves rtab stale
  // and would otherwise silently defeat every one of those actions.
  if (!force && rtab && rtab !== activeTab()) return;
  if (force) {
    messagesEl.scrollTop = messagesEl.scrollHeight;
    followTail = true;   // same no-op-write reasoning as autoScroll
    updateJumpToLatest();
    updatePinnedPrompt();
    return;
  }
  autoScroll();
}
/** The timestamp a LIVE send happens at — pass this explicitly at every send call
 *  site (never inferred inside addUserMessage itself, see its own doc comment). */
function nowIso() { return new Date().toISOString(); }
/** Formats an ISO timestamp as a short local time for TODAY, or date+time otherwise —
 *  same today/older split as history.js's own relTime(), so the two read consistently. */
function absTime(iso) {
  const t = Date.parse(iso); if (isNaN(t)) return '';
  const d = new Date(t);
  const timePart = d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
  const today = new Date();
  const isToday = d.getFullYear() === today.getFullYear() && d.getMonth() === today.getMonth()
      && d.getDate() === today.getDate();
  return isToday ? timePart : d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) + ', ' + timePart;
}
/**
 * @param {string} text @param {string|null} [ctx] context-chip label (file:lines)
 * @param {{url: string, name: string, w: number, h: number}[]} [images] pasted-image chips
 * @param {string} [id] transcript uuid — enables this bubble's hover actions. A
 *   live send has none yet (the CLI writes the line after us); backfillMessageIds
 *   fills it in once the turn ends.
 * @param {string} [ts] ISO timestamp for the opt-in line above the bubble
 *   (PREF_HISTORY_SHOW_TIMESTAMPS / window.__historyShowTimestamps). NOT defaulted to
 *   "now" here on purpose: a caller reconstructing history that has no recorded
 *   timestamp (an older session predating this field, or a line the CLI itself never
 *   stamped) must pass nothing and get no line, rather than this function silently
 *   rendering today's time on a message from last week. Every LIVE send site passes
 *   `new Date().toISOString()` itself instead.
 */
/* @param pane optional target, for a message that belongs to a tab other than
   the one being looked at — a Remote Control conversation receives messages
   typed on another device whether or not its tab is in front. Omitted, it
   behaves exactly as before and renders into the active tab. */
function addUserMessage(text, ctx, images, id, ts, pane) {
  pane = pane || (activeTab() ? activeTab().pane : messagesEl);
  clearWelcome(pane);
  const turn = document.createElement('div'); turn.className = 'turn';
  if (window.__historyShowTimestamps && ts) {
    const label = absTime(ts);
    if (label) {
      const tsEl = document.createElement('div'); tsEl.className = 'msg-ts'; tsEl.textContent = label;
      turn.appendChild(tsEl);
    }
  }
  const box = document.createElement('div'); box.className = 'user-msg';
  if (id) box.dataset.mid = id;
  box.appendChild(makeMsgActions());
  if (ctx) {
    const chip = document.createElement('span'); chip.className = 'ctx-chip';
    chip.innerHTML = ICONS.CODEICON + ' <span></span>';
    chip.querySelector('span').textContent = ctx;
    box.appendChild(chip);
  }
  if (images && images.length) {
    const strip = document.createElement('div'); strip.className = 'msg-images';
    // Same VSCode-style chip as the composer, minus the remove × (already sent).
    images.forEach(im => strip.appendChild(makeImageChip(im, null)));
    box.appendChild(strip);
  }
  if (text) {
    const body = document.createElement('div');
    // Bare slash commands ("/compact") render mono inside the bubble (VSCode look).
    body.className = 'body' + (/^\/\S+$/.test(text.trim()) ? ' mono-cmd' : '');
    if (typeof appendMentionText === 'function') appendMentionText(body, text);
    else body.textContent = text;
    box.appendChild(body);
    // 2-line clamp + Show more/less (chat.css .user-msg .body.clampable) — added only once
    // attached to the DOM shows the body actually overflows two lines, same measure-then-
    // decide reasoning as makeIoBlock's capIfOverflowing, so a short prompt never gets a
    // toggle with nothing behind it to expand. The class has to go on BEFORE measuring:
    // clientHeight only differs from scrollHeight once something is actually capping it —
    // measuring first (the original bug here) always saw them equal, since nothing had
    // constrained the height yet, so nothing ever counted as overflowing.
    requestAnimationFrame(() => {
      body.classList.add('clampable');
      if (body.scrollHeight <= body.clientHeight + 2) { body.classList.remove('clampable'); return; }
      const more = document.createElement('button');
      more.type = 'button'; more.className = 'clamp-toggle more'; more.textContent = 'Show more';
      more.onclick = () => box.classList.add('expanded');
      const less = document.createElement('button');
      less.type = 'button'; less.className = 'clamp-toggle less'; less.textContent = 'Show less';
      less.onclick = () => box.classList.remove('expanded');
      box.appendChild(more); box.appendChild(less);
    });
  }
  turn.appendChild(box); pane.appendChild(turn);
  // force only with Smart Scroll Lock on: by default, with the lock armed, sending must
  // not move the view either — the lock means "leave my scroll position alone" without
  // exception, being thrown to the bottom by your own message is the same interruption
  // as being thrown there by a streamed chunk. Smart Scroll Lock opts into the opposite
  // read: your OWN deliberate action is expected to land you at the bottom. Unlocked,
  // this jumps to the bottom either way, as it always did.
  //
  // Guarded by the pane: a message landing in a BACKGROUND tab must not move the
  // view the user is actually reading. Callers that omit `pane` always target the
  // active one, so for them this is unconditional exactly as it was.
  if (pane === (activeTab() ? activeTab().pane : messagesEl)) scrollBottom(smartScrollLock);
}
// Lazily create the assistant turn — only when real content (text or a tool)
// arrives. While Claude is just "thinking", nothing but the working sunburst shows.
function ensureTurn() {
  const pane = streamPane(); if (!pane) return null;
  if (!curTurn || !curTurn.parentNode) {
    clearWelcome(pane);
    curTurn = document.createElement('div'); curTurn.className = 'turn';
    pane.appendChild(curTurn);
  }
  return curTurn;
}
// Mark every .a-item that has a following .a-item so a connector line is drawn.
function relinkTurn(turn) {
  if (!turn) return;
  const items = turn.querySelectorAll(':scope > .a-item');
  items.forEach((el, i) => el.classList.toggle('linked', i < items.length - 1));
}
/* Simple per-chunk render: accumulate text and re-render the whole body each chunk.
   Streams live because the native side fires onText per delta. */
function appendAssistant(t) {
  finalizeThink();
  if (!ensureTurn()) return;
  if (!curBody) {
    curBody = document.createElement('div'); curBody.className = 'a-item streaming';
    curBody.innerHTML = '<span class="dot"></span><span class="a-body"></span>';
    curTurn.appendChild(curBody);
  }
  curText += t;
  curBody.querySelector('.a-body').innerHTML = renderMarkdown(curText);
  relinkTurn(curTurn);
  scrollBottom();
}
function endAssistant() {
  finalizeThink();
  markToolsDone(curTurn);   // every tool in this turn ran to completion → green dots
  if (curBody) curBody.classList.remove('streaming');
  if (curTurn && !curTurn.querySelector('.a-item') && curTurn.parentNode) curTurn.remove();
  curTurn = null; curBody = null; curText = '';
}
/* Turn every not-yet-resolved tool line's dot GREEN (finished). Conversational text
   lines keep their gray dot; a tool still awaiting a decision card (.pending) or one
   already interrupted (red) is skipped. Called when the next step starts and when the
   turn ends cleanly — inferring completion without a native onToolEnd signal. */
function markToolsDone(turn) {
  if (!turn) return;
  turn.querySelectorAll(':scope > .tool-line').forEach(line => {
    if (line.classList.contains('pending')) return;          // waiting on a decision card
    const dot = line.querySelector('.dot');
    if (dot && !dot.classList.contains('red')) dot.className = 'dot done';
  });
}
/* Last tool line in a turn (the one a decision card belongs to). Uses the full
   node list, not :last-of-type — a text .a-item div after the tool would fool the
   type selector. */
function lastToolLine(turn) {
  if (!turn) return null;
  const tools = turn.querySelectorAll(':scope > .tool-line');
  return tools.length ? tools[tools.length - 1] : null;
}
/* Cancel: drop the in-progress assistant body WITHOUT rendering it, so a cancelled
   turn shows only "Request cancelled." and never a half-baked partial answer. Any
   tool lines already committed to the turn stay; only the streaming text body goes. */
function discardAssistant() {
  finalizeThink();
  if (curBody && curBody.parentNode) curBody.remove();
  if (curTurn && !curTurn.querySelector('.a-item') && curTurn.parentNode) curTurn.remove();
  curTurn = null; curBody = null; curText = '';
}
/* Map a raw tool name to a short generic verb (like the VSCode panel — it never
   shows internal names like "ToolSearch" or "mcp__eclipse__askUserQuestion"). */
const TOOL_LABELS = {
  read:'Read', write:'Write', edit:'Edit', multiedit:'Edit', notebookedit:'Edit',
  opendiff:'Edit', closealldifftabs:'Edit', savedocument:'Save', openfile:'Open',
  bash:'Bash', bashoutput:'BashOutput', killshell:'KillShell',
  // Each of these used to collapse into one generic 'Search' bucket — you couldn't tell
  // a Grep from a WebSearch from a JDT FindReferences without reading the input. Now
  // distinct, matching VSCode's own per-tool labels.
  glob:'Glob', grep:'Grep', toolsearch:'Search', websearch:'WebSearch',
  findreferences:'FindReferences', gettypehierarchy:'GetTypeHierarchy', getsymbolinfo:'GetSymbolInfo',
  webfetch:'Fetch', agent:'Agent', task:'Agent', skill:'Skill', workflow:'Workflow',
  todowrite:'Planning', exitplanmode:"Claude's Plan",
  askuserquestion:'Asking', runtests:'Testing',
  getdiagnostics:'Checking', checkdocumentdirty:'Checking',
  getcurrentselection:'Reading', getlatestselection:'Reading',
  getopeneditors:'Reading', getworkspacefolders:'Reading', approvalprompt:'Permission'
};
/* Tool names whose result text is naturally structured as "file:line[:col]" rows
   (search/reference/diagnostic-shaped) — their OUT gets rendered as a clickable
   result list instead of a plain <pre>. See renderToolOutput(). */
const RESULT_LIST_TOOLS = new Set([
  'grep', 'glob', 'findreferences', 'gettypehierarchy', 'getsymbolinfo', 'getdiagnostics'
]);
// The subagent-launching tool is named `Agent` in the current CLI (confirmed against the
// official tools-reference) but was `Task` in older ones — accept both raw names so this
// doesn't silently stop matching again if a session runs against a different CLI version.
// One set, checked everywhere `key === 'task'` used to be hardcoded in exactly one place
// (that mismatch — real name "Agent", checked name "task" — is why the agent registry
// stayed empty and /agents always said "No agents this session yet").
const AGENT_KEYS = new Set(['agent', 'task']);
function toolLabel(name) {
  if (!name) return 'Working';
  let n = name;
  if (n.indexOf('mcp__') === 0) { const p = n.split('__'); n = p[p.length - 1]; } // strip mcp__server__
  const key = n.toLowerCase();
  if (TOOL_LABELS[key]) return TOOL_LABELS[key];
  // unknown: humanize (split camelCase/underscores), capitalized — never the raw name
  const words = n.replace(/_/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2').trim();
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : 'Working';
}
/* Build a tool line (+ inline diff inside the same .a-item so the connector spans it).
   Shared by live streaming (addToolLine, no status → gray) and history reconstruction
   (status "done"/"interrupted" from the transcript → green/red, so a reloaded convo
   keeps the colors it had live). */
/**
 * @param {string} name  raw tool name (mcp__… prefixes get stripped for display)
 * @param {Object} input tool_use input (file_path/command/pattern/… picked for detail)
 * @param {"done"|"interrupted"|undefined} [status] reload path only — colors the dot
 * @param {string} [errorText] reload path only
 * @param {string} [root] the OWNING conversation's working directory, for resolving a
 *   relative file_path when the line is clicked — see .tpath's onclick below.
 * @param {string} [resultText] reload path only — a successful tool's full output
 *   (session.rs's resultText field), rendered the same way applyToolResult does live.
 * @returns {HTMLElement} the .tool-line item
 */
/* Outcome text for an ExitPlanMode line. Shared by the LIVE decision path
   (cards.js decide()) and the RELOAD path (below) so a tab switch or restart
   renders the same thing — the transcript's done/interrupted status is the only
   surviving record of which way the plan went.
   @param {boolean} rejected @returns {string} */
function planOutcomeText(rejected) {
  return rejected ? 'Stayed in plan mode' : 'User approved the plan';
}
/* The muted one-liner under a failed tool ("⚠ File does not exist…"). Shared by the
   live path (onToolEnd) and the reload path (makeToolLine) so a conversation renders
   the same either way — the two disagreeing is the bug this fixes. Idempotent: a
   second result for the same tool replaces the line instead of stacking another. */
function setToolError(line, text) {
  if (!line || !text) return;
  let sub = line.querySelector(':scope > .tool-sub.err');
  if (!sub) { sub = document.createElement('div'); sub.className = 'tool-sub err'; line.appendChild(sub); }
  sub.textContent = '⚠ ' + text;
}
/** Copies `text` via the native SWT clipboard bridge when available (same priority
 *  order as contextmenu.js's ccCopy), flashing the button that triggered it. */
function copyToClipboard(btn, text) {
  if (window._clipSet) window._clipSet(text);
  else if (navigator.clipboard) navigator.clipboard.writeText(text).catch(() => {});
  if (!btn) return;
  clearTimeout(btn._copyTimer);
  // Captured once: a second click inside the flash would otherwise record the checkmark as
  // the icon to restore, and the button would stay stuck on it.
  if (btn._copyIcon === undefined) btn._copyIcon = btn.innerHTML;
  const original = btn._copyIcon;
  btn.innerHTML = ICONS.CHECK; btn.classList.add('copied');
  btn._copyTimer = setTimeout(() => { btn.innerHTML = original; btn.classList.remove('copied'); }, 1200);
}
/** A small copy-icon button, hover-revealed by the caller's own CSS (.io-row:hover / .tpath-wrap:hover). */
function makeCopyBtn(getText) {
  const btn = document.createElement('button');
  btn.type = 'button'; btn.className = 'copy-btn'; btn.title = 'Copy'; btn.innerHTML = ICONS.COPY;
  btn.onclick = (e) => { e.stopPropagation(); copyToClipboard(btn, getText()); };
  return btn;
}
/** "View full output/diff" footer for a block whose content really overflows its CSS
 *  cap — appended lazily by capIfOverflowing() below, never up front, so short content
 *  never gets a link with nothing behind it to expand. Opens a real read-only-in-spirit
 *  editor tab (a throwaway temp file — see ClaudeGuiView#openTextInEditor), not a dialog,
 *  matching the same target VSCode uses for "view full output". `diffPayload`, when given
 *  (a {old,new,title} object), opens a real Eclipse Compare editor instead — used for
 *  "View full diff" so it lands in an actual old/new diff view, not a flattened text dump. */
function makeMoreHint(label, getFullText, diffPayload) {
  const hint = document.createElement('div');
  hint.className = 'more-hint'; hint.textContent = label;
  hint.onclick = () => {
    if (diffPayload && window._openDiffInEditor) {
      window._openDiffInEditor(diffPayload.old, diffPayload.new, diffPayload.title);
    } else if (window._openTextInEditor) {
      window._openTextInEditor(getFullText());
    }
  };
  return hint;
}
/** Measures `contentEl` against `block`'s CSS-capped height AFTER layout and only then
 *  appends a more-hint — the cap itself is pure CSS (.io-block.capped), this just decides
 *  whether there's anything to expand. Called once per block, right after it's inserted. */
function capIfOverflowing(block, contentEl, label, getFullText, diffPayload) {
  block.classList.add('capped');
  // Reading scrollHeight forces layout — fine here since this runs once per new block,
  // not per streamed chunk (chat.js's autoScroll doc comment explains why THAT path
  // avoids it).
  if (contentEl.scrollHeight <= contentEl.clientHeight + 2) { block.classList.remove('capped'); return; }
  block.appendChild(makeMoreHint(label, getFullText, diffPayload));
}
/** Parses one line of tool-result text for a leading "path:line[:col]" prefix (grep -n /
 *  ripgrep / JDT reference style). Returns null when the line doesn't look like a hit,
 *  so callers can fall back to plain text instead of mis-rendering unrelated output. */
function parseResultLine(line) {
  const m = /^([^\s:][^:]*):(\d+):(?:(\d+):)?\s?(.*)$/.exec(line);
  if (!m) return null;
  return { file: m[1], line: m[2], col: m[3] || null, rest: m[4] || '' };
}
/** Builds the clickable result-list for search/reference/diagnostic-shaped output
 *  (RESULT_LIST_TOOLS). Capped to a handful of rows + "+N more" into the full text,
 *  same principle as capIfOverflowing but for discrete rows rather than a <pre>. */
function buildResultList(text, root) {
  const lines = text.split('\n').filter(l => l.trim());
  if (!lines.length) return null;
  const MAX_ROWS = 5;
  const list = document.createElement('div'); list.className = 'result-list';
  lines.slice(0, MAX_ROWS).forEach(l => {
    const parsed = parseResultLine(l);
    const row = document.createElement('div'); row.className = 'result-item';
    if (parsed && window._openFileInEditor) {
      row.classList.add('clickable');
      row.textContent = parsed.file;
      const loc = document.createElement('span'); loc.className = 'rloc';
      loc.textContent = 'line ' + parsed.line + (parsed.rest ? ' — ' + parsed.rest : '');
      row.appendChild(loc);
      row.onclick = () => window._openFileInEditor(parsed.file, root);
    } else {
      row.textContent = l;
    }
    list.appendChild(row);
  });
  if (lines.length > MAX_ROWS) {
    list.appendChild(makeMoreHint('+' + (lines.length - MAX_ROWS) + ' more — view all', () => text));
  }
  list.appendChild(makeCopyBtn(() => text));   // full result text, not just the capped rows shown
  return list;
}
/** Structured checklist for TodoWrite — its meaningful payload is the INPUT
 *  (input.todos), not the tool_result text (which is just a short CLI ack), so this
 *  renders at makeToolLine() time like the diff block, not at applyToolResult() time. */
function buildTodoChecklist(input) {
  const todos = Array.isArray(input && input.todos) ? input.todos : null;
  if (!todos || !todos.length) return null;
  const block = document.createElement('div'); block.className = 'io-block todo-block';
  todos.forEach(t => {
    const status = t.status || 'pending';
    const row = document.createElement('div'); row.className = 'todo-item ' + status;
    const chk = document.createElement('span'); chk.className = 'chk';
    chk.textContent = status === 'completed' ? '✓' : '';
    const label = document.createElement('span'); label.className = 'tlabel';
    label.textContent = (status === 'in_progress' && t.activeForm) ? t.activeForm : (t.content || '');
    row.appendChild(chk); row.appendChild(label);
    block.appendChild(row);
  });
  return block;
}
/** Renders one IN or OUT row (gutter label + text + hover-copy button) as a new `.io-item`
 *  inside an EXISTING `block`. Multiple items sharing one block — a Bash call's IN and its
 *  later OUT — render as a single bordered card with a divider between them (matching
 *  VSCode), not two separate boxes with a gap; see makeToolLine/renderToolOutput, which
 *  join into the same block via line._ioBlock instead of each creating their own. */
function appendIoRow(block, label, text) {
  const item = document.createElement('div'); item.className = 'io-item';
  const row = document.createElement('div'); row.className = 'io-row';
  const labelEl = document.createElement('span'); labelEl.className = 'io-label'; labelEl.textContent = label;
  const pre = document.createElement('pre'); pre.textContent = text;
  row.appendChild(labelEl); row.appendChild(pre); row.appendChild(makeCopyBtn(() => text));
  item.appendChild(row);
  block.appendChild(item);
  // Deferred to the next frame: appended to a detached-from-layout line at call time in
  // some paths (history reconstruction builds the whole turn before it's in the DOM), so
  // measuring scrollHeight/clientHeight immediately would see 0/0 and never cap anything.
  const fullWord = label === 'IN' ? 'input' : label === 'OUT' ? 'output' : label.toLowerCase();
  requestAnimationFrame(() => capIfOverflowing(item, pre, 'View full ' + fullWord, () => text));
  return item;
}
/** A fresh `.io-block` holding a single row — the common case (a tool with only an IN, or
 *  only an OUT, and nothing to join it with). */
function makeIoBlock(label, text) {
  const block = document.createElement('div'); block.className = 'io-block';
  appendIoRow(block, label, text);
  return block;
}
/** Per-subagent nested transcript — keyed by the Agent tool_use's own id (the same value a
 *  .tool-line stores as data-tuid). Shared by BOTH the inline collapsible section under a
 *  running agent's own line (renderAgentLogItem below) and the Agents popup's
 *  list/detail/"Open transcript" views (agents.js), so the two never disagree about the
 *  same agent. Populated two ways depending on how the entry got here: live, incrementally
 *  by applyAgentActivity below (fed by chat.rs's onAgentActivity relay); on reload, all at
 *  once by history.js from session.rs's reconstructed agentLog field (built from the same
 *  parent_tool_use_id-tagged transcript lines, just read back off disk instead of streamed
 *  live) — so a reopened conversation's agents keep their duration/tokens/prompt/tool-call
 *  list/transcript instead of losing them the moment the webview that ran them live is gone. */
const agentLogs = new Map();
function ensureAgentLog(id) {
  let log = agentLogs.get(id);
  // startedAt defaults to NOW, not 0: addToolLine's own explicit stamp (the tool_use
  // actually starting) is the accurate one and still wins whenever it runs, but
  // duration must never depend on that ONE call site succeeding — whichever event
  // touches this agent's log FIRST (a live activity update, a reload's reconstructed
  // data) still gives a real, ticking number instead of a permanent "0s" if it doesn't.
  if (!log) {
    log = { items: [], tokens: 0, model: '', startedAt: Date.now(), endedAt: 0, input: {}, taskId: '', finishStatus: '' };
    agentLogs.set(id, log);
  }
  return log;
}
/** Java → JS via stream.js's window.onAgentActivity. One `kind` per chat.rs relay event:
 *  'text'/'thinking' append onto the trailing item of that kind (mirrors curText's own
 *  delta-accumulation for the top-level stream); 'tool_start'/'tool_end' are a step in the
 *  subagent's own tool use, matched by ITS OWN tool_use id (info.id) — distinct from
 *  parentId, which is the AGENT's id; 'tokens' carries cumulative usage + model, piggybacked
 *  on whichever message just completed rather than its own event. */
function applyAgentActivity(json) {
  let info; try { info = JSON.parse(json); } catch (e) { return; }
  if (!info || !info.parentId) return;
  const log = ensureAgentLog(info.parentId);
  if (info.kind === 'tokens') {
    if (typeof info.tokens === 'number') log.tokens = info.tokens;
    if (info.model) log.model = info.model;
  } else if (info.kind === 'text' || info.kind === 'thinking') {
    const last = log.items[log.items.length - 1];
    let item = (last && last.kind === info.kind) ? last : null;
    if (!item) { item = { kind: info.kind, text: '' }; log.items.push(item); }
    item.text += info.text || '';
    renderAgentLogItem(info.parentId, item);
  } else if (info.kind === 'tool_start') {
    const item = { kind: 'tool', name: info.name, input: info.input || {}, id: info.id };
    log.items.push(item);
    renderAgentLogItem(info.parentId, item);
  } else if (info.kind === 'tool_end') {
    const item = log.items.find(it => it.kind === 'tool' && it.id === info.id);
    if (item) {
      item.status = info.isError ? 'interrupted' : 'done';
      item.errorText = info.isError ? info.text : '';
      item.resultText = info.isError ? '' : info.text;
      renderAgentLogItem(info.parentId, item);
    }
  } else if (info.kind === 'finished') {
    // The background agent's own system/task_notification actually finishing (chat.rs)
    // — the only real "it's done" signal for one of these; its top-level tool_result
    // (applyToolResult) fires almost immediately as a "kicked off" ack and deliberately
    // does NOT stamp this itself, or duration would freeze near-zero the same way it
    // used to before that was found and fixed. tokens/durationMs are server-computed
    // totals for the agent's WHOLE run — more accurate than the running total this
    // page tallied itself from each individual message's usage — so they win here.
    if (typeof info.tokens === 'number') log.tokens = info.tokens;
    if (info.summary) log.summary = info.summary;
    // "completed" vs "stopped" (the user hit Stop agent) — agents.js's detail view
    // shows this instead of always saying "Finished".
    if (info.status) log.finishStatus = info.status;
    log.endedAt = (typeof info.durationMs === 'number' && log.startedAt)
        ? log.startedAt + info.durationMs
        : Date.now();
  } else if (info.kind === 'taskId') {
    // The agent's own internal task id (chat.rs's task_started) — a DIFFERENT id
    // from parentId (its tool_use id) — captured while it's still running, since
    // it's the only thing "Stop agent" (agents.js) can actually send a stop_task
    // control_request against.
    log.taskId = info.taskId;
  }
  if (window.renderAgentsPanel) window.renderAgentsPanel();
}
/** Builds ONE log item's DOM node — shared by the live inline collapsible below and
 *  agents.js's "Open transcript" full view, so a subagent's work never renders two
 *  different ways depending on where it's being looked at. */
function buildAgentLogItemEl(item) {
  if (item.kind === 'tool') {
    return makeToolLine(item.name, item.input, item.status, item.errorText, rootPathOf(activeTab()), item.resultText);
  }
  const el = document.createElement('div');
  el.className = 'a-item muted' + (item.kind === 'thinking' ? ' agent-log-think' : '');
  el.innerHTML = '<span class="dot gray"></span><span class="a-body"></span>';
  el.querySelector('.a-body').innerHTML = renderMarkdown(item.text);
  // Below the text, not overlapping it in a corner (chat.css's .a-item.muted .copy-btn
  // override) — this is short-form conversational text, not a boxed IN/OUT/diff, so a
  // floating overlay would sit awkwardly over prose rather than a code block's margin.
  if (item.text) el.appendChild(makeCopyBtn(() => item.text));
  return el;
}
/** (Re)builds one log item's DOM node and places/replaces it inside the LIVE inline
 *  collapsible section under that agent's own tool line — a no-op if that line isn't
 *  rendered right now (the data is still recorded in agentLogs either way; the Agents
 *  popup's "Open transcript" always renders fresh from there, so this is only about
 *  keeping the inline copy live while it's actually on screen). */
function renderAgentLogItem(parentId, item) {
  const line = document.querySelector('.tool-line[data-tuid="' + parentId + '"]');
  const body = line && line.querySelector(':scope > .agent-log > .agent-log-body');
  if (!body) return;
  const el = buildAgentLogItemEl(item);
  if (item._el && item._el.parentNode) item._el.replaceWith(el); else body.appendChild(el);
  item._el = el;
}
/** The collapsible "Agent activity" toggle appended under a running agent's own tool
 *  line — collapsed by default so a busy subagent's own steps don't dominate the main
 *  transcript, but never hidden entirely per-request: real work, just tucked behind a
 *  fold instead of interleaved as if it were the top-level Claude's own steps. */
function makeAgentLogSection() {
  const wrap = document.createElement('div'); wrap.className = 'agent-log';
  wrap.innerHTML = '<div class="agent-log-head"><span class="chev">' + ICONS.CHEVRON + '</span>'
      + '<span class="agent-log-label">Agent activity</span></div><div class="agent-log-body"></div>';
  wrap.querySelector('.agent-log-head').onclick = () => wrap.classList.toggle('open');
  return wrap;
}
// Tools whose input is fully represented some other way (a diff block, the description
// span, a checklist, or their own dedicated card elsewhere) — never also get a boxed IN.
const SKIP_IN_BOX = new Set([
  'write', 'edit', 'multiedit', 'notebookedit', 'agent', 'task', 'todowrite',
  'askuserquestion', 'approvalprompt', 'exitplanmode'
]);
function makeToolLine(name, input, status, errorText, root, resultText, hasAgentLog) {
  input = input || {};
  const key = String(name || '').indexOf('mcp__') === 0
      ? String(name).split('__').pop().toLowerCase() : String(name || '').toLowerCase();
  const path = input.file_path || input.path || input.notebook_path || '';
  const isAgent = AGENT_KEYS.has(key);
  // A shell command or a Workflow script is always boxed below regardless of length —
  // unlike a short Grep pattern or file path, VSCode renders these as code in their own
  // IN box even on one line.
  const hasCommand = !isAgent && typeof input.command === 'string' && input.command.length > 0;
  const hasScript = !isAgent && typeof input.script === 'string' && input.script.length > 0;
  // Grep/Glob both accept an optional `path` that merely SCOPES the search — it isn't the
  // interesting value the way file_path is for Read/Write/Edit. Left in the default
  // priority order, a scoped search would show the search directory inline and never the
  // pattern actually being searched for.
  const preferPatternOverPath = key === 'grep' || key === 'glob';
  // Agent's own description takes the place of the generic one-liner entirely (matching
  // VSCode's "Agent: <description>") rather than showing a truncated raw prompt underneath.
  const detailFields = isAgent ? [] : preferPatternOverPath
      ? [input.pattern, path, input.command, input.query, input.url, input.prompt, input.script]
      : [path, input.command, input.pattern, input.query, input.url, input.prompt, input.script];
  const detail = detailFields.find(v => typeof v === 'string' && v) || '';
  const line = document.createElement('div'); line.className = 'a-item tool-line';
  line.dataset.tname = key;   // looked up again in applyToolResult to decide how to render OUT
  const dotClass = status === 'done' ? 'dot done' : status === 'interrupted' ? 'dot red' : 'dot';
  line.innerHTML = '<span class="' + dotClass + '"></span><span class="tname"></span>'
      + (isAgent ? ' <span class="tagent-type"></span> <span class="tdesc"></span>'
                 : ' <span class="tdesc"></span> <span class="tpath-wrap"><span class="tpath"></span></span>');
  line.querySelector('.tname').textContent = toolLabel(name);   // generic label, not the raw name
  // Multi-line detail (or any command/script, see hasCommand/hasScript) gets ONLY the boxed
  // IN below, never also the inline one-liner — the two used to both render for the same
  // content (e.g. a multi-line Bash script showed once wrapped inline via .tpath, then again
  // in its own IN box right after it).
  const isMultiline = !isAgent && detail.indexOf('\n') !== -1;
  const boxDetail = isMultiline || hasCommand || hasScript;
  if (isAgent) {
    // Which kind of agent, right in front of the "Agent" name (e.g. "Agent (Explore)") —
    // otherwise every subagent call reads identically regardless of what actually ran.
    const agentType = input.subagent_type || '';
    if (agentType) line.querySelector('.tagent-type').textContent = '(' + agentType + ')';
    // The short description sits inline (unchanged); the full prompt gets its own boxed
    // IN below — unconditionally, not gated on multi-line like other tools, since even a
    // short one-line prompt is worth separating from the description it's easy to conflate
    // it with otherwise.
    line.querySelector('.tdesc').textContent = input.description || '';
    // Stashed on the line so a later OUT (the agent's own eventual result) joins into
    // this SAME box instead of opening a second, separately-bordered one right under it.
    if (input.prompt) line.appendChild(line._ioBlock = makeIoBlock('IN', input.prompt));
    // Live (status undefined) always gets the toggle — it starts empty and fills in as
    // the agent actually runs, so there's nothing to check upfront. Reload only gets one
    // when session.rs's reconstructed agentLog actually has something in it (hasAgentLog,
    // set by the caller — never hardcode "no toggle with nothing behind it" the other way
    // around, matching capIfOverflowing's own rule elsewhere in this file).
    if (status === undefined || hasAgentLog) line.appendChild(makeAgentLogSection());
  } else {
    // A tool's own description (e.g. Bash's "what this command does") sits inline next to
    // the name, same slot Agent uses above — independent of whether detail is boxed below.
    if (input.description) line.querySelector('.tdesc').textContent = input.description;
    if (boxDetail) {
      // Leave .tpath-wrap in the DOM (simpler than branching the innerHTML above) but
      // empty and invisible — the boxed IN a few lines down is the sole representation.
      line.querySelector('.tpath-wrap').style.display = 'none';
    } else {
      const tpathEl = line.querySelector('.tpath');
      tpathEl.textContent = detail;
      // Only when `detail` IS the path (not a pattern/query/etc. that outranked it — see
      // preferPatternOverPath) is it clickable. Otherwise the visible text and the path a
      // click would open are two different things (e.g. a scoped Grep showing its pattern
      // but a `path` field pointing at the search directory). Silently a no-op if the path
      // turns out stale (deleted, renamed) or unresolvable; see ClaudeGuiView#openFileInEditor.
      if (path && detail === path && window._openFileInEditor) {
        tpathEl.classList.add('clickable');
        tpathEl.title = 'Open in editor';
        // No stopPropagation: unlike msgactions.js's icons (which sit inside a clickable
        // .item), no ancestor of .tool-line has its own onclick, and this transcript sits
        // inside the same page as menus tracked by openMenuEl — stopping propagation here
        // would silently stop clicking a path from also closing an open menu via ui.js's
        // click-outside handler, same class of bug as cycleSearchScope's innerHTML swap.
        tpathEl.onclick = () => window._openFileInEditor(path, root || rootPathOf(activeTab()));
      }
      // Grep/Glob's own `path` merely scopes the search (preferPatternOverPath already
      // outranked it for `detail` above) — shown as a faint suffix after the pattern so a
      // scoped search reads differently from an unscoped one instead of looking identical.
      if (preferPatternOverPath && path && path !== detail) {
        const scope = document.createElement('span');
        scope.className = 'tscope';
        scope.textContent = ' (in: ' + path + ')';
        line.querySelector('.tpath-wrap').appendChild(scope);
      }
      if (detail) line.querySelector('.tpath-wrap').appendChild(makeCopyBtn(() => detail));
    }
  }
  // A boxed IN row for input that .tpath's one-line fallback chain can't represent well:
  // a command (always, see hasCommand), genuinely multi-line text (a long Agent-less
  // prompt), or a tool whose real payload is structured JSON with no single dominant field
  // (SendMessage, CronCreate, TaskStop…). Never for tools in SKIP_IN_BOX — input renders
  // elsewhere for those.
  if (!SKIP_IN_BOX.has(key)) {
    // Stashed on the line (same as the Agent branch above) so a later OUT joins into
    // this SAME bordered box instead of opening a second one right under it.
    if (boxDetail) {
      line.appendChild(line._ioBlock = makeIoBlock('IN', detail));
    } else if (!isAgent && !detail && Object.keys(input).length) {
      line.appendChild(line._ioBlock = makeIoBlock('IN', JSON.stringify(input, null, 2)));
    }
  }
  const todoBlock = key === 'todowrite' ? buildTodoChecklist(input) : null;
  if (todoBlock) line.appendChild(todoBlock);
  const diff = buildToolDiff(name, input);
  if (diff) {
    const sub = document.createElement('div'); sub.className = 'tool-sub'; sub.textContent = diff.summary;
    line.appendChild(sub);
    line.appendChild(diff.block);
  }
  // Reload path only (status set): re-state the plan outcome that decide() wrote
  // live, so a reloaded conversation isn't left with a bare "Claude's Plan" line.
  if (status && String(name).toLowerCase() === 'exitplanmode') {
    const sub = document.createElement('div'); sub.className = 'tool-sub';
    sub.textContent = planOutcomeText(status === 'interrupted');
    line.appendChild(sub);
  }
  // Reload path: why it failed, when the loader kept a reason. A turn that was
  // simply cut off has no result and no text — the red dot alone still reads
  // "stopped", which is what it meant live.
  if (status === 'interrupted') setToolError(line, errorText);
  // Reload path: the successful tool's actual output — same renderer applyToolResult
  // calls live, so a reopened conversation shows the OUT box/result-list/checklist it had
  // live instead of just the input. Absent entirely on the live path (undefined), and
  // absent here too for a tool session.rs didn't record success output for (errors, asks,
  // a cut-off turn) — renderToolOutput's own `if (!text...) return` covers both.
  if (resultText) renderToolOutput(line, key, resultText);
  return line;
}
function addToolLine(payload) {
  finalizeThink();
  if (!ensureTurn()) return;
  markToolsDone(curTurn);   // a new tool starting means the previous one finished → green
  let info; try { info = JSON.parse(payload); } catch (e) { info = { name: payload, input: {} }; }
  // rtab, not activeTab(): a background tab's own stream must resolve a relative
  // file_path against ITS OWN root, not whichever tab happens to be on screen.
  const line = makeToolLine(info.name || 'tool', info.input || {}, undefined, undefined, rootPathOf(rtab));
  // The tool_use id, so this line can be found again when its result lands. An
  // older core sends no id — the line then just keeps the inferred green dot.
  if (info.id) line.dataset.tuid = info.id;
  // agents.js reads the transcript DOM directly (rather than a separately tracked list) —
  // works the same whether a card got here via live streaming or history reconstruction,
  // and survives a reload/resume that a session-only registry wouldn't. This just pokes it
  // to re-render if the popup happens to be open right now.
  if (AGENT_KEYS.has(line.dataset.tname)) {
    if (info.id) {
      // Duration/prompt for the Agents popup's list+detail views — recorded here
      // (start of the tool_use) rather than only in agentLogs' activity-driven
      // entries, so it exists even for an agent that finishes with zero steps of
      // its own logged (a near-instant one, or one that only ever answers in text).
      const log = ensureAgentLog(info.id);
      log.startedAt = Date.now();
      log.input = info.input || {};
    }
    if (window.renderAgentsPanel) window.renderAgentsPanel();
  }
  curTurn.appendChild(line);
  // End the current text body so any text Claude emits AFTER this tool starts a new
  // body BELOW the tool line (otherwise the closing "Done…" merges in above the edits).
  curBody = null; curText = '';
  relinkTurn(curTurn);
  scrollBottom();
}
/* A tool finished (live). Resolves THAT tool's dot from what actually happened
   instead of the optimistic green markToolsDone would infer, and shows the reason
   when it failed. Searches the whole pane, not just curTurn: a result can land
   after the turn ended, by which point curTurn is null.
   @param {string} payload {"id":…,"isError":bool,"text":…} */
function applyToolResult(payload) {
  let info; try { info = JSON.parse(payload); } catch (e) { return; }
  if (!info || !info.id) return;
  const pane = streamPane() || (activeTab() ? activeTab().pane : null);
  if (!pane) return;
  // Matched by walking the nodes rather than an attribute selector — tool ids come
  // from the CLI and are never interpolated into a selector this way.
  let line = null;
  pane.querySelectorAll('.tool-line[data-tuid]').forEach(el => {
    if (el.dataset.tuid === info.id) line = el;
  });
  if (!line) return;
  // A tool still holding a decision card keeps its pending look until the card
  // resolves it — that path sets its own colour.
  if (line.classList.contains('pending')) return;
  const dot = line.querySelector('.dot');
  if (dot) dot.className = info.isError ? 'dot red' : 'dot done';
  // The dot's class (just set above) already reflects the outcome — agents.js reads that
  // straight off the DOM, so this is just a poke to re-render if the popup is open.
  if (AGENT_KEYS.has(line.dataset.tname)) {
    const log = ensureAgentLog(info.id);
    // A BACKGROUND agent's own top-level tool_result is an early "kicked off"
    // acknowledgment, not its real completion — confirmed live: a real run logged 21k
    // tokens and 3 of its own steps (via onAgentActivity) in the ~30ms window between
    // this firing and the agent's own start. Stamping endedAt from it froze duration at
    // that ~30ms forever. Foreground agents don't have this problem — their tool_result
    // genuinely IS the final result — so only skip the stamp for background ones; leaving
    // endedAt unset lets agentDurationMs keep counting up against Date.now() instead.
    if (!(log.input && log.input.run_in_background)) {
      log.endedAt = Date.now();
    }
    if (window.renderAgentsPanel) window.renderAgentsPanel();
  }
  if (info.isError) setToolError(line, info.text);
  else renderToolOutput(line, line.dataset.tname || '', info.text);
  // The result lands on a line addToolLine already scrolled to, so without this the view
  // stopped at the tool line and only caught up when the NEXT tool started. Twice: once
  // for the box itself, and again a frame later, after appendIoRow's deferred
  // capIfOverflowing has capped it and added its "View full output" hint below.
  // Pane-guarded like addSystemToPane — a background tab's result must not yank the view.
  const followResult = () => { if (pane === (activeTab() && activeTab().pane)) scrollBottom(); };
  followResult();
  requestAnimationFrame(followResult);
}
// Tools whose successful result is already fully represented some other way (a diff,
// the plan-outcome tool-sub, the question card) — showing the CLI's boilerplate ack text
// underneath would just be noise, so OUT is skipped for these specifically.
const SKIP_OUT_BOX = new Set(['write', 'edit', 'multiedit', 'notebookedit', 'exitplanmode', 'askuserquestion', 'approvalprompt', 'todowrite', 'read']);
/* The actual output-rendering fix: until now a successful tool_result only ever colored
   the dot (applyToolResult above) — the CLI's answer never appeared anywhere. Shared by
   the live path (applyToolResult) and, when a reload's session.rs starts carrying result
   text too, the history path — both just need a line + a tool name + result text.
   Idempotent: matches setToolError's "replace, don't stack" rule for a duplicate result. */
function renderToolOutput(line, key, text) {
  if (!text || SKIP_OUT_BOX.has(key)) return;
  // Idempotent: a duplicate/updated result for the same tool replaces just the OUT
  // piece — never the whole shared block an IN row might still belong to.
  const stale = line.querySelector('.io-item.out, .result-list.out');
  if (stale) stale.remove();
  if (RESULT_LIST_TOOLS.has(key)) {
    const resultList = buildResultList(text, rootPathOf(activeTab()));
    if (resultList) { resultList.classList.add('out'); line.appendChild(resultList); }
    return;
  }
  // Joins into the SAME box as an existing IN row (matches VSCode's single bordered
  // IN/OUT card with a divider between them, not two separate boxes with a gap) —
  // falls back to a fresh block when this tool never got a boxed IN in the first place
  // (e.g. a simple command whose input is just the inline .tpath one-liner).
  let block = line._ioBlock;
  if (!block || !line.contains(block)) {
    block = document.createElement('div'); block.className = 'io-block';
    line.appendChild(block);
    line._ioBlock = block;
  }
  appendIoRow(block, 'OUT', text).classList.add('out');
}
/* Minimal LCS line diff (guarded against pathological sizes). */
function lineDiff(oldStr, newStr) {
  const a = (oldStr || '').split('\n'), b = (newStr || '').split('\n');
  const n = a.length, m = b.length;
  if (n * m > 250000) { // too big for O(n*m) — degrade to remove-all / add-all
    return a.map(l => ['del', l]).concat(b.map(l => ['add', l]));
  }
  const dp = []; for (let i = 0; i <= n; i++) dp.push(new Int32Array(m + 1));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--)
    dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
  const out = []; let i = 0, j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) { out.push(['ctx', a[i]]); i++; j++; }
    else if (dp[i + 1][j] >= dp[i][j + 1]) { out.push(['del', a[i]]); i++; }
    else { out.push(['add', b[j]]); j++; }
  }
  while (i < n) out.push(['del', a[i++]]);
  while (j < m) out.push(['add', b[j++]]);
  return out;
}
function buildToolDiff(name, input) {
  const n = (name || '').toLowerCase();
  let rows = null, added = 0, removed = 0, oldText = '', newText = '';
  if (n === 'write' && typeof input.content === 'string') {
    rows = input.content.replace(/\n$/, '').split('\n').map(l => ['add', l]); added = rows.length;
    newText = input.content;
  } else if (typeof input.old_string === 'string' && typeof input.new_string === 'string') {
    rows = lineDiff(input.old_string, input.new_string);
    oldText = input.old_string; newText = input.new_string;
  } else if (n === 'multiedit' && Array.isArray(input.edits)) {
    rows = [];
    const oldParts = [], newParts = [];
    input.edits.forEach((e, i) => {
      if (i) rows.push(['gap', '']);
      lineDiff(e.old_string || '', e.new_string || '').forEach(r => rows.push(r));
      oldParts.push(e.old_string || ''); newParts.push(e.new_string || '');
    });
    oldText = oldParts.join('\n\n'); newText = newParts.join('\n\n');
  } else if (n === 'notebookedit') {
    // The prior cell source isn't available client-side (NotebookEdit's own input never
    // carries it, and we don't cache Read's notebook output per cell) — so a real old/new
    // line diff isn't possible here. 'delete' has no new_source to show at all; 'insert'
    // and 'replace' render new_source as all-added, same honest treatment Write gets for
    // a brand new file, rather than pretending to know what the cell used to say.
    if (input.edit_mode === 'delete') {
      rows = [['gap', 'Cell deleted']];
    } else if (typeof input.new_source === 'string') {
      rows = input.new_source.replace(/\n$/, '').split('\n').map(l => ['add', l]); added = rows.length;
      newText = input.new_source;
    }
  }
  if (!rows || !rows.length) return null;
  const diffPayload = { old: oldText, new: newText, title: toolLabel(name) || name };
  rows.forEach(r => { if (r[0] === 'add') added++; else if (r[0] === 'del') removed++; });
  const block = document.createElement('div'); block.className = 'code-block edit';
  const pre = document.createElement('pre');
  // A hard DOM-size safety ceiling for pathological diffs (thousands of rows) — NOT the
  // normal "don't show too much" cap, which is now the real CSS/measured one below
  // (capIfOverflowing). This only bites for diffs far bigger than anything a visual cap
  // alone would need to guard against; ordinary 30-100 line diffs stay well under it and
  // rely entirely on the height cap instead — that's the bug this replaces: a 29-line
  // diff used to render in full because it was under the old MAX=40, uncapped either way.
  const HARD_MAX = 500;
  rows.slice(0, HARD_MAX).forEach(r => {
    const ln = document.createElement('span');
    ln.className = 'ln mono' + (r[0] === 'add' ? ' add' : r[0] === 'del' ? ' del' : r[0] === 'gap' ? ' meta' : '');
    ln.textContent = r[0] === 'gap' ? '⋯' : r[1];
    pre.appendChild(ln);
  });
  // Full plain-text form (every row, +/- prefixed) for both the copy button and, when
  // capped, the "view full diff" link below — same text either way.
  const fullText = () => rows.map(r => (r[0] === 'add' ? '+ ' : r[0] === 'del' ? '- ' : '  ') + r[1]).join('\n');
  block.appendChild(pre);
  block.appendChild(makeCopyBtn(fullText));
  if (rows.length > HARD_MAX) {
    // Rows past HARD_MAX were never put in the DOM at all — always show this one
    // regardless of measured height, since there's genuinely missing content, not just
    // clipped-but-present content the way the height cap below handles.
    block.appendChild(makeMoreHint('⋯ ' + (rows.length - HARD_MAX) + ' more line' + (rows.length - HARD_MAX > 1 ? 's' : '') + ' — view full diff', fullText, diffPayload));
  } else {
    // Every row IS in the DOM; only add the link if they actually overflow the CSS cap
    // (.code-block.capped pre, chat.css) — measured, not guessed, same as makeIoBlock.
    requestAnimationFrame(() => capIfOverflowing(block, pre, 'View full diff', fullText, diffPayload));
  }
  const parts = [];
  if (added) parts.push('Added ' + added + ' line' + (added > 1 ? 's' : ''));
  if (removed) parts.push('Removed ' + removed + ' line' + (removed > 1 ? 's' : ''));
  return { block, summary: parts.join(', ') || 'Updated' };
}

/* Thinking marker. The reasoning text only streams when the CLI is asked for it
   (--thinking-display summarized); with the default "omitted" the block carries
   an encrypted signature and an EMPTY string. So the chevron is conditional: it
   appears once text actually arrives (has-body) and stays hidden otherwise,
   rather than rendering an affordance that expands nothing. Either way we show
   "Thinking…" → "Thought for Ns", timed from turn start. */
function ensureThink() {
  if (!ensureTurn()) return null;
  if (!curThink) {
    thinkStart = turnStart || Date.now();
    const el = document.createElement('div'); el.className = 'a-item think muted live';
    el.innerHTML = '<span class="dot gray"></span>'
      + '<span class="think-head"><span class="think-label">Thinking…</span>'
      + '<span class="chev">' + ICONS.CHEVRON + '</span></span>'
      + '<div class="think-body"></div>';
    // Click the head to reveal/collapse the thinking text (only meaningful once
    // there's text — has-body). Bound to the element so it survives curThink=null.
    el.querySelector('.think-head').onclick = () => {
      if (el.classList.contains('has-body')) el.classList.toggle('open');
    };
    curThink = el;
    curTurn.appendChild(el);
    renderThinkLabel();
  }
  return curThink;
}
function appendThinking(t) {
  const el = ensureThink(); if (!el) return;
  // The reasoning text streams when thinking is on (extended thinking); surface it
  // in the collapsible body so the chevron reveals what the model is thinking.
  if (t) {
    curThinkText += t;
    const body = el.querySelector('.think-body');
    if (body) body.textContent = curThinkText;
    el.classList.add('has-body');
  }
  relinkTurn(curTurn);
  scrollBottom();
}
function finalizeThink() {
  if (curThink) {
    const secs = Math.max(1, Math.round((Date.now() - (thinkStart || Date.now())) / 1000));
    const lbl = curThink.querySelector('.think-label');
    if (lbl) lbl.textContent = 'Thought for ' + secs + 's';
    curThink.classList.remove('live');
  }
  curThink = null; curThinkText = '';
}
function addAssistantStatic(text) {
  const pane = activeTab() ? activeTab().pane : messagesEl;
  clearWelcome(pane);
  const turn = document.createElement('div'); turn.className = 'turn';
  const item = document.createElement('div'); item.className = 'a-item';
  item.innerHTML = '<span class="dot"></span><span class="a-body"></span>';
  item.querySelector('.a-body').innerHTML = renderMarkdown(text);
  turn.appendChild(item); pane.appendChild(turn);
}
/** Muted gray-dot system line in the ACTIVE tab (display-only — never sent to the model). @param {string} text */
function addSystem(text) {
  const pane = streamPane() || (activeTab() ? activeTab().pane : messagesEl);
  addSystemToPane(pane, text);
}
/** System line in a SPECIFIC tab (callbacks can arrive for a background tab). */
function addSystemTo(t, text) {
  if (!t || !t.pane) return;
  addSystemToPane(t.pane, text);
}
function addSystemToPane(pane, text) {
  if (!pane) return;
  const turn = document.createElement('div'); turn.className = 'turn';
  turn.innerHTML = '<div class="a-item muted"><span class="dot gray"></span><span class="sys"></span></div>';
  turn.querySelector('.sys').textContent = text;
  pane.appendChild(turn);
  if (pane === (activeTab() && activeTab().pane)) scrollBottom();   // don't yank a background tab
}

function doSend() {
  // The composer is shut while a Remote Control toggle is in flight, but the send
  // button is a div — greyed, still clickable — and a leftover draft would go out
  // through it. A message sent before the bridge is up reaches nothing else.
  const at0 = activeTab();
  if (at0 && (at0.rcConnecting || at0.rcDisconnecting)) return;
  const text = input.value.trim();
  const imgs = (typeof pendingImages === 'function') ? pendingImages() : [];
  // Allow an image-only turn (text may be empty when a screenshot is attached).
  if (!text && !imgs.length) return;
  // Slash commands are text-only; images stay pending (don't send them with a command).
  if (text.startsWith('/') && handleSlashCommand(text)) { input.value = ''; input.style.height = 'auto'; const at = activeTab(); if (at) at.draft = ''; closeSlash(); return; }
  const t = activeTab(); if (!t) return;
  t.cancelled = false;           // new user turn: lift the post-cancel callback guard
  loadRender(t);                 // render into (and stream for) THIS tab
  // Mid-stream sends QUEUE onto THIS tab's own conversation; other tabs stream
  // independently (each has its own process), so they never block this send.
  const queueing = !!t.streaming;
  const withCtx = !!(ctxEnabled && ctxData && ctxData.fileName && !ctxIsDismissed());
  const imagesJson = (typeof pendingImagesJson === 'function') ? pendingImagesJson(t) : '';
  addUserMessage(text, withCtx ? ctxChipLabel() : null, imgs, null, nowIso());
  if (!t.titled && text) setTabTitle(t, text);   // title from text; an image-only first turn stays untitled
  input.value = ''; input.style.height = 'auto'; t.draft = ''; closeSlash();
  if (typeof closeMention === 'function') closeMention();
  if (typeof clearPendingImages === 'function') clearPendingImages(t);   // consumed → clear the strip
  if (!queueing) { setStreaming(true); showWorking(); }
  else if (!workingEl) showWorking();
  // Last arg is this conversation's working root — claude is spawned with it as its
  // cwd, so two tabs under different supertabs run in different folders.
  if (window._sendToJava) window._sendToJava(text, withCtx, t.sessionId || '', t.permMode || permMode, effort, curModel, thinkingOn ? '1' : '0', t.id, imagesJson, rootPathOf(t));
  persistTabPrefs(t);   // resumed tab already has a sessionId; new ones persist on onSessionId
}
function doCancel() {
  const t = activeTab(); if (!t) return;
  loadRender(t);
  // Fire the native interrupt AND raise a front-end guard: chatCancel lands
  // asynchronously, so a delta already in flight can still arrive after this. The
  // flag makes withTab() drop EVERY further stream callback for this tab, so once
  // Stop is pressed nothing from the stopped turn ever renders (joebiden18). Cleared
  // when the next turn starts (doSend / onStreamStart).
  t.cancelled = true;
  if (window._cancelRequest) window._cancelRequest(t.id);
  // Mark the stop point: if a tool was running, turn only THAT tool line's dot red
  // (earlier dots stay green) → note "Tool interrupted"; otherwise it was a plain
  // text turn → note "Interrupted". Redden BEFORE discardAssistant nulls curTurn.
  const stoppedTool = markInterrupted();
  hideWorking(); discardAssistant(); setStreaming(false);
  // "Request cancelled." first, then the italic note LAST — each in its own turn so
  // the note sits below the system line (not tucked under the reddened tool).
  addSystem('Request cancelled.');
  addInterrupted(stoppedTool ? 'Tool interrupted' : 'Interrupted');
  // t.cancelled makes withTab swallow this tab's onStreamEnd, which is the other
  // backfill trigger — so claim the sent bubble's transcript id here, or a stopped
  // turn's message would have no hover actions until the conversation is reloaded.
  backfillMessageIds(t);
}
/* Redden the last (in-progress) tool line's dot in the current turn. Returns true
   if a tool line was found, so the caller can pick the right interrupted label. */
function markInterrupted() {
  const line = lastToolLine(curTurn);
  if (!line) return false;
  const dot = line.querySelector('.dot');
  if (dot) dot.className = 'dot red';
  return true;
}
/* Append the italic "Tool interrupted" / "Interrupted" note as its own turn, last
   (below the "Request cancelled." system line). */
function addInterrupted(text) {
  const pane = streamPane() || (activeTab() ? activeTab().pane : messagesEl);
  if (!pane) return;
  const note = document.createElement('div'); note.className = 'interrupted'; note.textContent = text;
  const turn = document.createElement('div'); turn.className = 'turn'; turn.appendChild(note); pane.appendChild(turn);
  scrollBottom();
}

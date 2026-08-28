/* chat.js — Live transcript rendering: user/assistant turns, tool lines, inline diffs,
   thinking blocks, doSend/doCancel. */

/* ===================== Chat (live, local) ===================== */
let curTurn = null, curBody = null, curText = '';
let curThink = null, curThinkText = '', thinkStart = 0, turnStart = 0;

function clearWelcome(pane) { if (!pane) return; const w = pane.querySelector('.welcome'); if (w) w.remove(); }
// How far from the true bottom still counts as "at the bottom" for the purpose of
// (re-)arming followTail below — has to clear the viewport settling on first render
// (scrollHeight starts equal to clientHeight before any content), not a streamed
// chunk's height. A big chunk arriving is NOT what this threshold has to survive:
// followTail decides that by staying whatever it last was, not by re-measuring.
const SCROLL_BOTTOM_SLOP = 4;
function isNearBottom() {
  const scrollable = messagesEl.scrollHeight - messagesEl.clientHeight;
  if (scrollable <= SCROLL_BOTTOM_SLOP) return true;   // nothing to scroll
  return scrollable - messagesEl.scrollTop <= SCROLL_BOTTOM_SLOP;
}
// Whether the ACTIVE tab's view should keep following new content as it streams
// in. This is STATE, not a per-call measurement: scrollHeight forces a synchronous
// layout flush the instant it's read, so by the time scrollBottom() below could
// measure anything the just-appended content is already counted, making one big
// chunk (a code fence, a whole tool-result block — none of this streams in
// byte-sized pieces) indistinguishable from the user having scrolled up. Measuring
// only ever happens in the 'scroll' listener (the user's own wheel/drag) and,
// explicitly, right after scrollBottom's own scrollTop write below — that write is
// a NO-OP (and so fires no 'scroll' event) whenever the view is already sitting at
// that same position, which is exactly the state a stale-false followTail plus a
// since-arrived resize back to the bottom can produce; relying on the event alone
// would leave followTail stuck at false forever.
//
// Kept as ONE module global describing whichever tab is currently on screen (like
// curTurn/curBody etc. — see loadRender's comment in tabs.js) rather than always
// reading activeTab().followTail, since #messages is one shared scroll container:
// only the active tab's position is ever meaningfully "current". switchTab saves
// this (and the raw scrollTop, since "was scrolled up" alone doesn't say how far —
// a background pane's scrollTop isn't preserved by the DOM on its own) onto the
// outgoing Tab and restores the incoming one's, the same pattern already used for
// each tab's composer draft.
let followTail = true;
/**
 * @param {boolean} [force] Jump to the bottom even if the user scrolled up to read
 *   older content — for a deliberate action of theirs (sending a message, answering
 *   a permission prompt) where snapping back down is expected, not a surprise.
 *   Streamed content omits this so reading history isn't interrupted every time a
 *   chunk arrives; see updateJumpToLatest for how they get back down themselves.
 */
function scrollBottom(force) {
  if (workingEl && workingEl.parentNode) workingEl.parentNode.appendChild(workingEl); // keep last
  // Don't yank the visible view to the bottom for a BACKGROUND tab's stream — only
  // the active tab's pane is on screen, so a background render must not scroll it.
  // force is a deliberate action ON THE ACTIVE TAB ITSELF (send, answer a card,
  // click the jump-to-latest arrow) — it must bypass this, or a background tab
  // that streamed anything since the last switch leaves rtab stale and silently
  // defeats every one of those actions on the tab actually on screen.
  if (!force && rtab && rtab !== activeTab()) return;
  if (!force && !followTail) { updateJumpToLatest(); return; }
  messagesEl.scrollTop = messagesEl.scrollHeight;
  // Set directly rather than left to the 'scroll' event this write may fire: if the
  // view is already sitting at the bottom (e.g. this is a force call arriving while
  // followTail is stale-false), the write above is a no-op and no event fires —
  // leaving followTail stuck false and the arrow visibly unable to fix itself even
  // though scrollBottom(true) just ran and did put the view at the true bottom.
  followTail = true;
  updateJumpToLatest();
}
// Shows the button once the user has scrolled away from the tail (followTail false),
// hides it once they're following again — whether that's from clicking it (scrollBottom
// sets followTail directly, not relying on the scroll event its own write may not fire)
// or scrolling back down themselves (the listener below).
const jumpToLatestEl = document.getElementById('jump-to-latest');
function updateJumpToLatest() {
  if (jumpToLatestEl) jumpToLatestEl.classList.toggle('show', !followTail);
}
messagesEl.addEventListener('scroll', () => { followTail = isNearBottom(); updateJumpToLatest(); });
/**
 * @param {string} text @param {string|null} [ctx] context-chip label (file:lines)
 * @param {{url: string, name: string, w: number, h: number}[]} [images] pasted-image chips
 * @param {string} [id] transcript uuid — enables this bubble's hover actions. A
 *   live send has none yet (the CLI writes the line after us); backfillMessageIds
 *   fills it in once the turn ends.
 */
function addUserMessage(text, ctx, images, id) {
  const pane = activeTab() ? activeTab().pane : messagesEl;
  clearWelcome(pane);
  const turn = document.createElement('div'); turn.className = 'turn';
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
    body.textContent = text;
    box.appendChild(body);
  }
  turn.appendChild(box); pane.appendChild(turn);
  scrollBottom(true);   // the user just sent this — take them to it even if they'd scrolled up
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
  bash:'Run', bashoutput:'Run', killshell:'Run',
  glob:'Search', grep:'Search', toolsearch:'Search', websearch:'Search',
  findreferences:'Search', gettypehierarchy:'Search', getsymbolinfo:'Search',
  webfetch:'Fetch', task:'Working', todowrite:'Planning', exitplanmode:"Claude's Plan",
  askuserquestion:'Asking', runtests:'Testing',
  getdiagnostics:'Checking', checkdocumentdirty:'Checking',
  getcurrentselection:'Reading', getlatestselection:'Reading',
  getopeneditors:'Reading', getworkspacefolders:'Reading', approvalprompt:'Permission'
};
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
function makeToolLine(name, input, status) {
  input = input || {};
  const path = input.file_path || input.path || input.notebook_path || '';
  const detail = path || input.command || input.pattern || input.query || input.url || input.prompt || '';
  const line = document.createElement('div'); line.className = 'a-item tool-line';
  const dotClass = status === 'done' ? 'dot done' : status === 'interrupted' ? 'dot red' : 'dot';
  line.innerHTML = '<span class="' + dotClass + '"></span><span class="tname"></span> <span class="tpath"></span>';
  line.querySelector('.tname').textContent = toolLabel(name);   // generic label, not the raw name
  line.querySelector('.tpath').textContent = detail;
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
  return line;
}
function addToolLine(payload) {
  finalizeThink();
  if (!ensureTurn()) return;
  markToolsDone(curTurn);   // a new tool starting means the previous one finished → green
  let info; try { info = JSON.parse(payload); } catch (e) { info = { name: payload, input: {} }; }
  curTurn.appendChild(makeToolLine(info.name || 'tool', info.input || {}));
  // End the current text body so any text Claude emits AFTER this tool starts a new
  // body BELOW the tool line (otherwise the closing "Done…" merges in above the edits).
  curBody = null; curText = '';
  relinkTurn(curTurn);
  scrollBottom();
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
  let rows = null, added = 0, removed = 0;
  if (n === 'write' && typeof input.content === 'string') {
    rows = input.content.replace(/\n$/, '').split('\n').map(l => ['add', l]); added = rows.length;
  } else if (typeof input.old_string === 'string' && typeof input.new_string === 'string') {
    rows = lineDiff(input.old_string, input.new_string);
  } else if (n === 'multiedit' && Array.isArray(input.edits)) {
    rows = [];
    input.edits.forEach((e, i) => {
      if (i) rows.push(['gap', '']);
      lineDiff(e.old_string || '', e.new_string || '').forEach(r => rows.push(r));
    });
  }
  if (!rows || !rows.length) return null;
  rows.forEach(r => { if (r[0] === 'add') added++; else if (r[0] === 'del') removed++; });
  const block = document.createElement('div'); block.className = 'code-block edit';
  const pre = document.createElement('pre');
  const MAX = 40;
  rows.slice(0, MAX).forEach(r => {
    const ln = document.createElement('span');
    ln.className = 'ln mono' + (r[0] === 'add' ? ' add' : r[0] === 'del' ? ' del' : r[0] === 'gap' ? ' meta' : '');
    ln.textContent = r[0] === 'gap' ? '⋯' : r[1];
    pre.appendChild(ln);
  });
  if (rows.length > MAX) {
    const ln = document.createElement('span'); ln.className = 'ln meta mono';
    ln.textContent = '⋯ ' + (rows.length - MAX) + ' more line' + (rows.length - MAX > 1 ? 's' : '');
    pre.appendChild(ln);
  }
  block.appendChild(pre);
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
  const withCtx = !!(ctxEnabled && ctxData && ctxData.fileName);
  const imagesJson = (typeof pendingImagesJson === 'function') ? pendingImagesJson(t) : '';
  addUserMessage(text, withCtx ? ctxChipLabel() : null, imgs);
  if (!t.titled && text) setTabTitle(t, text);   // title from text; an image-only first turn stays untitled
  input.value = ''; input.style.height = 'auto'; t.draft = ''; closeSlash();
  if (typeof clearPendingImages === 'function') clearPendingImages(t);   // consumed → clear the strip
  if (!queueing) { setStreaming(true); showWorking(); }
  else if (!workingEl) showWorking();
  if (window._sendToJava) window._sendToJava(text, withCtx, t.sessionId || '', t.permMode || permMode, effort, curModel, thinkingOn ? '1' : '0', t.id, imagesJson);
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

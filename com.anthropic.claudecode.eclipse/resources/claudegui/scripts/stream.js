/* stream.js — Java->JS streaming callbacks (window.on*), withTab guard, onCompact handling
   + the "Compacted chat" collapsible, theme push. */

/* Java → JS streaming callbacks. Each carries the TAB ID its process belongs to;
   loadRender(tab) points the render globals at that tab so concurrent streams from
   different tabs never clobber each other. */
/* onStreamStart also fires when a QUEUED turn begins (after the previous turn's
   onStreamEnd) — restore the working indicator so the succession is visible. */
/* Drop any streaming callback for a tab whose turn was cancelled: chatCancel is
   async, so late deltas from the stopped process keep arriving — swallow them all
   until the user starts a new turn (doSend clears the flag). This is what makes Stop
   truly stop the front-end (joebiden18). */
/** @param {string} tabId @param {(t: Tab) => void} fn */
function withTab(tabId, fn) { const t = tabById(tabId); if (!t || t.cancelled) return; loadRender(t); fn(t); }
window.onStreamStart   = (tabId) => withTab(tabId, () => { setStreaming(true); ensureWorking(); });
window.onThinking      = (tabId, t) => withTab(tabId, () => appendThinking(t));
window.onStreamText    = (tabId, t) => withTab(tabId, () => appendAssistant(t));
/* Turn over → the CLI has written this turn's user line, so the bubble sent a
   moment ago can finally learn which transcript line it owns (its hover actions
   stay hidden until then). */
window.onStreamEnd     = (tabId) => withTab(tabId, (t) => { t.compacting = false; hideWorking(); endAssistant(); setStreaming(false); backfillMessageIds(t); if (typeof refreshReplies === 'function') refreshReplies(t); refreshTabTitle(t); });
window.onToolStart     = (tabId, n) => withTab(tabId, () => addToolLine(n));
window.onToolEnd       = (tabId, j) => withTab(tabId, () => applyToolResult(j));
/* A running subagent's own current step (chat.rs never gives its OWN tool calls a
   top-level onToolStart — that would interleave a bogus line into the main
   transcript) — agents.js reads this to show what a running Agent is doing right now. */
window.onAgentActivity = (tabId, j) => withTab(tabId, () => {
  if (typeof applyAgentActivity === 'function') applyAgentActivity(j);
});
window.onSystemMessage = () => {};   /* backend system/init noise — ignored */
/* A note from the plugin itself, not the CLI (e.g. the FreeBSD setup guide sent to
   Claude after an fdescfs failure). Display only, in the tab it concerns. */
window.onNotice        = (tabId, m) => withTab(tabId, (t) => addSystemTo(t, 'ⓘ ' + m));
window.onError         = (tabId, m) => withTab(tabId, (t) => { hideWorking(); endAssistant(); setStreaming(false); addSystem('⚠ ' + augmentError(m)); if (typeof refreshReplies === 'function') refreshReplies(t); });
window.onStatusUpdate  = () => {};
window.onSessionId     = (tabId, id) => { const t = tabById(tabId); if (t && id) { t.sessionId = id; persistTabPrefs(t); } };

/* Compaction lifecycle from the CLI (manual /compact or auto-compact), phases:
   compacting → (failed | boundary → summary). While compacting the working gerund
   is pinned to "Compacting…"; a boundary drops the collapsible "Compacted chat ·
   <trigger> · Nk tokens freed" line, whose body fills in when the summary echo
   arrives. A failure needs nothing extra — the CLI answers the turn with the
   error text ("Not enough messages to compact."), which renders as a normal
   gray-dot line (joebiden reference). */
/**
 * @typedef {Object} CompactEvent
 * @property {"compacting"|"failed"|"boundary"|"summary"} phase
 * @property {string} [error]      failed only ("Not enough messages to compact.")
 * @property {"manual"|"auto"} [trigger]  boundary only
 * @property {number} [preTokens]  boundary only — context tokens before compaction
 * @property {number} [postTokens] boundary only — tokens after (freed = pre − post)
 * @property {string} [text]       summary only — the markdown summary body
 */
/** @type {(tabId: string, json: string) => void} json is a serialized {@link CompactEvent} */
window.onCompact = (tabId, json) => withTab(tabId, (t) => {
  let info = {}; try { info = JSON.parse(json) || {}; } catch (e) { return; }
  if (info.phase === 'compacting') {
    t.compacting = true;
    if (workingEl) {
      // Morph whatever gerund is up into the pinned "Compacting" and stop cycling.
      if (gerundCycleTimer) { clearTimeout(gerundCycleTimer); gerundCycleTimer = null; }
      if (gerundTypeTimer) { clearTimeout(gerundTypeTimer); gerundTypeTimer = null; }
      const el = workingEl.querySelector('.gerund');
      const prev = workingGerund; workingGerund = 'Compacting';
      if (el) morphGerund(prev, 'Compacting', el, null);
    } else ensureWorking();   // showWorking pins itself via t.compacting
  } else if (info.phase === 'failed') {
    t.compacting = false;
    compactionOver(t);
  } else if (info.phase === 'boundary') {
    const freed = Math.max(0, (info.preTokens || 0) - (info.postTokens || 0));
    // An automatic compaction comes in the middle of a turn. What Claude writes after it
    // belongs under its line, as a reload shows it — not in the turn above, which is
    // about to be folded away with everything else up there.
    endAssistant();
    const answered = info.trigger === 'manual' ? compactBubbleTurn(t.pane) : null;
    // What the user has sent and Claude has not been given yet is not from before the
    // compaction: it is answered after it. It stays in view, under the compaction's line.
    const waiting = sentForAfterCompaction(t, answered);
    foldBeforeCompaction(t.pane, answered, waiting);
    t._compEl = addCompacted(t.pane, info.trigger, freed, '');
    waiting.forEach(turn => t.pane.appendChild(turn));
    scrollBottom();
    // The boundary is the compaction's end: the summary that follows only fills the line in,
    // and need not come for the word to be let go of.
    t.compacting = false;
    compactionOver(t);
  } else if (info.phase === 'summary') {
    t.compacting = false;
    if (t._compEl) t._compEl.querySelector('.comp-body').innerHTML = renderMarkdown(info.text || '');
    compactionOver(t);
  }
});
/* The working indicator lets go of "Compacting" once compaction is over. A moment later,
   not at once: a manual /compact ends its turn right behind this, and the indicator with
   it, so there is nothing to let go of and no other word should flash by on the way out.
   An automatic one is in the middle of a turn that goes on, and so does the indicator. */
const COMPACTION_OVER_MS = 400;
function compactionOver(t) {
  clearTimeout(t._compOverTimer);   // the boundary and its summary ask one after the other
  t._compOverTimer = setTimeout(() => {
    if (t.cancelled || !t.streaming || t.compacting) return;
    loadRender(t);
    unpinCompactingGerund(t);
  }, COMPACTION_OVER_MS);
}

/* The "Compacted chat · manual · 25k tokens freed ⌄" collapsible (joebiden):
   italic muted head, chevron flips when the summary body is expanded. */
const CHEV_DOWN = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 9l6 6 6-6"/></svg>';
/** @param {number} n @returns {string} "22k" / "812" */
function fmtTokens(n) { return n >= 1000 ? Math.round(n / 1000) + 'k' : String(n); }
/**
 * Renders the "Compacted chat · <trigger> · Nk tokens freed" collapsible.
 * @param {HTMLElement} pane        the tab's transcript pane
 * @param {"manual"|"auto"} trigger
 * @param {number} freed            tokens freed (0 hides the "freed" segment)
 * @param {string} summaryText      markdown body; "" = filled later via t._compEl
 * @returns {HTMLElement|null}      the .compacted element (null without a pane)
 */
function addCompacted(pane, trigger, freed, summaryText) {
  if (!pane) return null;
  const turn = document.createElement('div'); turn.className = 'turn';
  const el = document.createElement('div'); el.className = 'compacted';
  const head = document.createElement('div'); head.className = 'comp-head';
  let label = 'Compacted chat · ' + (trigger || 'manual');
  if (freed > 0) label += ' · ' + fmtTokens(freed) + ' tokens freed';
  head.innerHTML = '<span class="comp-lbl"></span><span class="chev">' + CHEV_DOWN + '</span>';
  head.querySelector('.comp-lbl').textContent = label;
  head.onclick = () => el.classList.toggle('open');
  // a-body class: the global `* { margin:0; padding:0 }` reset strips list
  // indentation, and only .a-body's rules restore it — without this, ol/ul
  // bullets in the summary hang at the pane's left edge.
  const body = document.createElement('div'); body.className = 'comp-body a-body';
  if (summaryText) body.innerHTML = renderMarkdown(summaryText);
  el.appendChild(head); el.appendChild(body);
  turn.appendChild(el); pane.appendChild(turn);
  return el;
}

/* ---- The messages from before a compaction ----
   Once a conversation is compacted, Claude no longer has what was said above the
   compaction, only its summary. Those messages are put under one "Messages before
   compaction" line at the top of the conversation, closed until it is clicked — or,
   with the preference "Hide messages from before a compaction" (on unless turned off),
   not shown at all, line included.

   They are marked where they stand, never moved: a .pre-compact class on each of the
   pane's children above the last compaction, and chat.css to say whether they show.
   find.js, the pinned prompt and the turn in progress all go by the pane's own
   children, and so stay right; find.js passes over what has no box, as it does a closed
   thinking block. */
const PRE_COMPACT_LABEL = 'Messages before compaction';
/**
 * Folds away everything in `pane` above `before` — every child there is when `before`
 * is null — and closes the section. Called when a compaction finishes and when a
 * compacted conversation is rebuilt from history, before the compaction's own line (and
 * its /compact bubble) are drawn.
 * @param {HTMLElement} pane
 * @param {Element|null} before  the first child that is NOT part of it
 * @param {Element[]} [except]   children above `before` that are not part of it either
 *   (sentForAfterCompaction)
 */
function foldBeforeCompaction(pane, before, except) {
  if (!pane) return;
  const head = pane.querySelector(':scope > .pre-compact-head');
  for (let el = pane.firstElementChild; el && el !== before; el = el.nextElementSibling) {
    if (el === head || el.classList.contains('working-turn')) continue;
    if (except && except.indexOf(el) >= 0) continue;
    el.classList.add('pre-compact');
  }
  if (!ensurePreCompactHead(pane, false)) return;   // nothing above the compaction: nothing to put a line over
  setPreCompactOpen(pane, false);
}
/**
 * Sees that a pane has its "Messages before compaction" line, first of its children,
 * when it has anything for one: a message marked as from before a compaction — or,
 * with `anyway`, a part of the conversation that was not read and is fetched when the
 * line is opened (history.js, fetchEarlierPart). Opens and closes nothing.
 * @returns {HTMLElement|null} the line, null when the pane has none and needs none
 */
function ensurePreCompactHead(pane, anyway) {
  let head = pane.querySelector(':scope > .pre-compact-head');
  if (!head) {
    if (!anyway && !pane.querySelector(':scope > .pre-compact')) return null;
    head = makePreCompactHead(pane);
  }
  if (pane.firstElementChild !== head) pane.insertBefore(head, pane.firstElementChild);
  return head;
}
/* The "Messages before compaction ⌄" line. A turn like any other, so it takes the first
   turn's place and margin; the look is the "Compacted chat" line's (chat.css names both). */
function makePreCompactHead(pane) {
  const turn = document.createElement('div'); turn.className = 'turn pre-compact-head';
  const el = document.createElement('div'); el.className = 'pre-compact-fold';
  const head = document.createElement('div'); head.className = 'comp-head';
  head.innerHTML = '<span class="comp-lbl"></span><span class="chev">' + CHEV_DOWN + '</span>';
  head.querySelector('.comp-lbl').textContent = PRE_COMPACT_LABEL;
  head.onclick = () => setPreCompactOpen(pane, !pane.classList.contains('pre-open'));
  el.appendChild(head); turn.appendChild(el);
  return turn;
}
/** Opens or closes a pane's "Messages before compaction" section. Opened, the part of
 *  the conversation that was not read when it was reopened is fetched, if there is one. */
function setPreCompactOpen(pane, open) {
  if (!pane) return;
  pane.classList.toggle('pre-open', !!open);
  const fold = pane.querySelector(':scope > .pre-compact-head > .pre-compact-fold');
  if (fold) fold.classList.toggle('open', !!open);
  if (open && typeof fetchEarlierPart === 'function') fetchEarlierPart(tabs.find(t => t.pane === pane));
  if (open) measureRevealed(pane);   // what was drawn under it while it was closed is cut to size now
  updatePinnedPrompt();   // the prompts that just came or went are candidates for it
}
/* The turns of the messages the user has sent that a compaction now finishing does NOT
   take with it: the ones Claude is given after it. A prompt that set an automatic
   compaction off is one (the CLI compacts first and answers it then), and so is anything
   sent while a turn was running that is still waiting its turn. The transcript tells them
   apart, and a reload draws them under the compaction's line, where they are put here.

   A message is one of them when the transcript does not hold its line yet, or holds it
   after this compaction's own — the list of the conversation's messages says how many
   compactions precede each (session.rs, message_ids), and the tab counts its own
   (`t.compactions`). A bubble still without a line that stands above one that has its
   line is not waiting to be written: it is one the transcript never took, and stays
   where it is. `answered` is the /compact this compaction answers, which keeps its place.
   None when the transcript cannot be read, or lists no message at all. */
function sentForAfterCompaction(t, answered) {
  t.compactions = (t.compactions || 0) + 1;
  const list = typeof backfillMessageIds === 'function' ? backfillMessageIds(t) : null;
  if (!list || !list.length) return [];   // nothing to tell them apart by
  const precede = {};
  list.forEach(m => { if (m && m.id) precede[m.id] = Number(m.compactions) || 0; });
  const turns = [];
  let lineBelow = false;   // a bubble further down has its line
  const boxes = [].slice.call(t.pane.querySelectorAll(':scope > .turn > .user-msg'));
  for (let i = boxes.length - 1; i >= 0; i--) {
    const turn = boxes[i].parentNode, mid = boxes[i].dataset.mid;
    if (turn === answered || turn.classList.contains('pre-compact')) continue;
    if (mid) { lineBelow = true; if ((precede[mid] || 0) >= t.compactions) turns.unshift(turn); }
    else if (!lineBelow) turns.unshift(turn);
  }
  return turns;
}
/* The turn of the /compact the user sent and the compaction now finishing answers, so
   it stays out of the fold with the line that follows it. Looked for from the end, and
   no further back than the last "Compacted chat" line: a /compact above that one was
   answered by it. None when the compaction came from elsewhere (another device). */
function compactBubbleTurn(pane) {
  for (let el = pane.lastElementChild; el; el = el.previousElementSibling) {
    if (el.querySelector(':scope > .compacted')) return null;
    const box = el.querySelector(':scope > .user-msg');
    if (box && /^\/compact(\s|$)/.test(String(box._rawText || '').trim())) return el;
  }
  return null;
}
/* The preference "Hide messages from before a compaction", pushed by Java at load and
   on every change. On until Java says otherwise, as the preference is, so a tab rebuilt
   before the first push never shows what is about to be hidden. */
document.documentElement.classList.add('hide-pre-compact');
function hidingBeforeCompaction() { return document.documentElement.classList.contains('hide-pre-compact'); }
window.onHideBeforeCompaction = function(hide) {
  document.documentElement.classList.toggle('hide-pre-compact', !!hide);
  // A section left open has its unread part fetched now that it shows.
  if (!hide && typeof fetchEarlierPart === 'function') {
    tabs.forEach(t => { if (t.pane.classList.contains('pre-open')) fetchEarlierPart(t); });
  }
  if (!hide && activeTab()) measureRevealed(activeTab().pane);   // an open section shows again
  updatePinnedPrompt();
  if (typeof renderBookmarks === 'function') renderBookmarks();   // its list leaves the hidden replies out
};

/* Light/dark theming (issue #78). Java pushes the ambient Eclipse theme via
   onTheme('light'|'dark') on load, on refocus, and on the workbench theme change;
   the <html> class toggles the :root.light token overrides. Dark is the default.

   tabBg/tabBgActive (optional): the REAL editor-area tab-folder colors, sampled
   directly off Eclipse's own CTabFolder widget (ClaudeGuiView.findEditorAreaTabColors)
   rather than guessed at in tokens.css — a hardcoded value can't be right for every
   OS/GTK theme. Set as inline styles on :root, which win over both :root and
   :root.light in the cascade without touching any selector; omitted (both undefined)
   when Java couldn't sample them, leaving tokens.css's own values as the fallback. */
window.onTheme = (mode, tabBg, tabBgActive) => {
  document.documentElement.classList.toggle('light', mode === 'light');
  const root = document.documentElement.style;
  if (tabBg) root.setProperty('--tab-bg', tabBg); else root.removeProperty('--tab-bg');
  if (tabBgActive) root.setProperty('--tab-bg-active', tabBgActive); else root.removeProperty('--tab-bg-active');
};

/* A "User answered:" card left in the flow (at the decision point) when the user
   types an instruction instead of accepting/rejecting. */
function addAnswered(text, pane) {
  pane = pane || streamPane() || (activeTab() ? activeTab().pane : null);
  if (!pane) return;
  const turn = document.createElement('div'); turn.className = 'turn';
  const card = document.createElement('div'); card.className = 'answered';
  const h = document.createElement('div'); h.className = 'ans-head'; h.textContent = 'User answered:';
  const b = document.createElement('div'); b.className = 'ans-body'; b.textContent = text;
  card.appendChild(h); card.appendChild(b); turn.appendChild(card); pane.appendChild(turn);
}
/* Which of a question's options an answer names, and what is left over (an "Other" answer).
   A single-select answer is one label. A multi-select one is the ticked labels joined by ", ",
   then perhaps the typed text, so labels are taken off the front one at a time, the longest that
   fits first — a label may itself hold a comma — and whatever no label matches is the Other text. */
function pickedFromAnswer(q, answer) {
  const labels = (q.options || []).map(o => (o && o.label) || '');
  const text = String(answer == null ? '' : answer);
  if (!q.multiSelect) {
    const at = labels.indexOf(text);
    return at >= 0 ? { picked: [at], other: '' } : { picked: [], other: text };
  }
  const picked = [];
  let rest = text;
  while (rest) {
    let best = -1;
    labels.forEach((l, i) => {
      if (picked.indexOf(i) >= 0 || !l || !rest.startsWith(l)) return;
      if (rest.length !== l.length && !rest.startsWith(', ', l.length)) return;
      if (best < 0 || l.length > labels[best].length) best = i;
    });
    if (best < 0) break;
    picked.push(best);
    rest = rest.slice(labels[best].length + (rest.length > labels[best].length ? 2 : 0));
  }
  return { picked, other: rest };
}
/* The overview left in the conversation once questions are answered: every question with all
   its options, the chosen ones marked, under a "Questions — Answered · N questions" header that
   folds the list away. `answers` is the answer text by question text, as the CLI records it.
   `outcome` is 'declined' (dismissed) or 'timeout' for questions nobody answered: the same card,
   under a red dot and "Declined" / "Timed out", with every option left unselected.
   Drawn live from the card's own state and again when a conversation is reopened. */
function addQuestionsAnswered(questions, answers, pane, outcome) {
  pane = pane || streamPane() || (activeTab() ? activeTab().pane : null);
  if (!pane) return;
  const unanswered = outcome === 'declined' || outcome === 'timeout';
  const turn = document.createElement('div'); turn.className = 'turn';
  const box = document.createElement('div'); box.className = 'a-item q-overview' + (unanswered ? ' unanswered' : '');
  box.appendChild(Object.assign(document.createElement('span'), { className: unanswered ? 'dot red' : 'dot done' }));
  const head = document.createElement('div'); head.className = 'qo-head';
  head.appendChild(Object.assign(document.createElement('div'), { className: 'qo-title', textContent: 'Questions' }));
  const sub = document.createElement('div'); sub.className = 'qo-sub';
  sub.appendChild(document.createTextNode((outcome === 'declined' ? 'Declined' : outcome === 'timeout' ? 'Timed out' : 'Answered') + ' \u00b7 ' + questions.length + (questions.length === 1 ? ' question' : ' questions')));
  const chev = document.createElement('span'); chev.className = 'chev'; chev.innerHTML = ICONS.CHEVRON;
  sub.appendChild(chev); head.appendChild(sub);
  head.onclick = () => box.classList.toggle('collapsed');
  box.appendChild(head);
  const body = document.createElement('div'); body.className = 'qo-body';
  questions.forEach(q => {
    body.appendChild(Object.assign(document.createElement('div'), { className: 'qo-q', textContent: q.question || '' }));
    const got = pickedFromAnswer(q, answers && answers[q.question]);
    const row = (label, desc, on) => {
      const r = document.createElement('div'); r.className = 'qo-opt' + (on ? ' sel' : '');
      r.appendChild(Object.assign(document.createElement('div'), { className: q.multiSelect ? 'qo-check' : 'qo-radio' }));
      const t = document.createElement('div'); t.className = 'qo-text';
      t.appendChild(Object.assign(document.createElement('div'), { className: 'qo-label', textContent: label }));
      if (desc) t.appendChild(Object.assign(document.createElement('div'), { className: 'qo-desc', textContent: desc }));
      r.appendChild(t); body.appendChild(r);
    };
    (q.options || []).forEach((o, oi) => row((o && o.label) || '', o && o.description, got.picked.indexOf(oi) >= 0));
    // An "Other" answer is not one of the options, so it is the extra row, with what was typed.
    if (got.other) row('Other', got.other, true);
  });
  box.appendChild(body);
  turn.appendChild(box); pane.appendChild(turn);
}
/* The "Asking" line of the call that asked a set of questions, which the overview of their answers
   makes redundant: both say the call happened and that it finished, and the overview says more.
   Taken out only when there IS an overview — a dismissed or timed-out question has none, and
   that line, with its red dot, is all it leaves. A turn left with nothing in it goes with it. */
function dropAskingLine(line) {
  if (!line || !line.parentNode) return;
  const turn = line.parentNode;
  line.remove();
  if (!turn.classList || !turn.classList.contains('turn')) return;
  if (!turn.querySelector('.a-item') && turn.parentNode) turn.remove(); else relinkTurn(turn);
}
function lastAskingLine(turn) {
  if (!turn) return null;
  const lines = [].slice.call(turn.querySelectorAll(':scope > .tool-line')).filter(l => l.dataset.tname === 'askuserquestion');
  return lines.length ? lines[lines.length - 1] : null;
}
/* After the user answers a card, end the current assistant turn so Claude's
   follow-up response streams into a NEW turn BELOW the answer card — not into the
   turn that was open above it (which would push the answer card to the bottom). */
function startFreshTurn() {
  finalizeThink();
  curTurn = null; curBody = null; curText = '';
  showWorking();
}


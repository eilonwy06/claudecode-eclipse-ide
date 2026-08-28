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
window.onStreamEnd     = (tabId) => withTab(tabId, (t) => { t.compacting = false; hideWorking(); endAssistant(); setStreaming(false); backfillMessageIds(t); });
window.onToolStart     = (tabId, n) => withTab(tabId, () => addToolLine(n));
window.onToolEnd       = () => {};
window.onSystemMessage = () => {};   /* backend system/init noise — ignored */
window.onError         = (tabId, m) => withTab(tabId, () => { hideWorking(); endAssistant(); setStreaming(false); addSystem('⚠ ' + augmentError(m)); });
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
  } else if (info.phase === 'boundary') {
    const freed = Math.max(0, (info.preTokens || 0) - (info.postTokens || 0));
    t._compEl = addCompacted(t.pane, info.trigger, freed, '');
    scrollBottom();
  } else if (info.phase === 'summary') {
    t.compacting = false;
    if (t._compEl) t._compEl.querySelector('.comp-body').innerHTML = renderMarkdown(info.text || '');
  }
});

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
/* After the user answers a card, end the current assistant turn so Claude's
   follow-up response streams into a NEW turn BELOW the answer card — not into the
   turn that was open above it (which would push the answer card to the bottom). */
function startFreshTurn() {
  finalizeThink();
  curTurn = null; curBody = null; curText = '';
  showWorking();
}


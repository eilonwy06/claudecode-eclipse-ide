/* tabs.js — Tab model (create/switch/close/drag-reorder), per-tab render-state context
   switch (loadRender), title editing. */

/* ===================== Tabs ===================== */
/**
 * One conversation tab — created by createTab(), passed around the whole codebase.
 * @typedef {Object} Tab
 * @property {string} id              "tab1", "tab2", … — also keys the Java-side ChatProcessManager
 * @property {string} title           tab-strip / header title
 * @property {string} sessionId       CLI session id ("" until the first init event; non-empty → sends resume)
 * @property {HTMLElement} pane       this conversation's transcript container inside #messages
 * @property {boolean} titled         true once the title is fixed (first send or manual rename)
 * @property {string} draft           unsent composer text, restored on tab switch
 * @property {string} model           per-conversation model ("" = Default)
 * @property {number} effortIdx       index into EFFORTS
 * @property {boolean} thinking       extended-thinking toggle
 * @property {boolean} [streaming]    a turn is in flight on this tab's process
 * @property {boolean} [cancelled]    Stop pressed — withTab drops stream callbacks until the next send
 * @property {boolean} [compacting]   /compact (or auto-compact) running — gerund pinned to "Compacting…"
 * @property {Object}  [_r]           parked render globals while another tab is loaded (see loadRender)
 * @property {HTMLElement|null} [_compEl] latest "Compacted chat" element awaiting its summary body
 * @property {boolean} [followTail] whether #messages should follow new content for THIS tab;
 *   undefined (a brand-new tab) means caught-up, same as true (see chat.js)
 * @property {number} [scrollTop] this tab's own #messages.scrollTop, parked on switch-away
 *   since #messages is one shared scroll container — undefined means "at the bottom"
 */
/** @type {Tab[]} */
let tabs = [], activeId = null, tabSeq = 0;
// Defaults a NEW conversation starts with (not inherited from the last-viewed tab).
const DEFAULT_EFFORT_IDX = 2;      // "high"
const DEFAULT_THINKING = false;    // thinking off
const DEFAULT_PERM_MODE = 'default';   // "Manual"
function defaultModel() { return (typeof customModel !== 'undefined' && customModel) ? customModel : ''; }
/** @returns {Tab|null} */
function activeTab() { return tabs.find(t => t.id === activeId) || null; }
/** @param {string} id @returns {Tab|null} */
function tabById(id) { return tabs.find(t => t.id === id) || null; }

/* ── Per-tab concurrency ──────────────────────────────────────────────────
   Each conversation runs its OWN claude process (Java: one ChatProcessManager
   per tab) and keeps its OWN render state, so tabs never block each other. The
   streaming render functions still use module globals (curTurn, curBody, curThink,
   workingEl, …); rather than thread a tab through all of them, we CONTEXT-SWITCH:
   loadRender(tab) parks the current globals on the previous tab and loads the
   target tab's, so every render fn transparently operates on the right tab. */
/** @type {Tab|null} */
let rtab = null;   // the tab whose render state is currently loaded into the globals
/** @param {Tab} tab */
function loadRender(tab) {
  if (!tab || rtab === tab) return;
  if (rtab) rtab._r = { curTurn, curBody, curText, curThink, curThinkText, thinkStart, turnStart, workingEl };
  rtab = tab;
  const r = tab._r || {};
  curTurn = r.curTurn || null; curBody = r.curBody || null; curText = r.curText || '';
  curThink = r.curThink || null; curThinkText = r.curThinkText || '';
  thinkStart = r.thinkStart || 0; turnStart = r.turnStart || 0; workingEl = r.workingEl || null;
}
function streamPane() { return rtab ? rtab.pane : (activeTab() ? activeTab().pane : null); }
function activeStreaming() { return !!(activeTab() && activeTab().streaming); }
/* Reflect the ACTIVE tab's streaming state in the composer (send/stop + placeholder). */
function syncComposer() {
  const s = activeStreaming();
  const hasImgs = typeof hasPendingImages === 'function' && hasPendingImages();
  send.classList.toggle('stop', s);
  send.classList.toggle('disabled', !s && input.value.trim() === '' && !hasImgs);
  send.innerHTML = s ? ICONS.STOP : ICONS.SEND;
  input.placeholder = s ? 'Queue another message…' : 'Message Claude…';
}
function WELCOME_HTML() {
  return '<div class="welcome"><div class="wc-logo">' + ICONS.SUNBURST + '</div>' +
    '<div class="wc-h">Claude Code</div>' +
    '<div class="wc-p">Ask anything about your workspace. Type <code class="ic">/</code> for commands.</div></div>';
}
/**
 * @param {{title?: string, sessionId?: string, titled?: boolean, model?: string,
 *          effortIdx?: number, thinking?: boolean, permMode?: string}} [opts]
 * @returns {Tab} the new (now active) tab
 */
function createTab(opts) {
  opts = opts || {};
  const id = 'tab' + (++tabSeq);
  const pane = document.createElement('div'); pane.className = 'pane'; pane.dataset.id = id;
  pane.innerHTML = WELCOME_HTML();
  messagesEl.appendChild(pane);
  // Per-conversation model/effort/thinking (VSCode-style). A NEW tab starts at the
  // DEFAULTS (not whatever the last-viewed convo used); each tab then remembers its
  // own. Defaults: high effort, thinking off, the user's configured default model.
  tabs.push({ id, title: opts.title || 'Claude Code', sessionId: opts.sessionId || '', pane, titled: !!opts.titled, draft: '',
    model: (opts.model !== undefined ? opts.model : defaultModel()),
    effortIdx: (opts.effortIdx !== undefined ? opts.effortIdx : DEFAULT_EFFORT_IDX),
    thinking: (opts.thinking !== undefined ? opts.thinking : DEFAULT_THINKING),
    permMode: (opts.permMode !== undefined ? opts.permMode : DEFAULT_PERM_MODE) });
  switchTab(id);
  return tabs[tabs.length - 1];
}
function switchTab(id) {
  // Each tab keeps its own unsent draft: stash the composer into the outgoing tab,
  // then restore the incoming tab's draft so drafts don't bleed across tabs.
  const prev = tabById(activeId);
  if (prev && prev.id !== id) {
    prev.draft = input.value;
    // Parked unconditionally, even with the lock off: the toggle can be armed while this
    // tab sits in the background, and the position it was left at is the only record of
    // where the user had read up to.
    prev.followTail = followTail;
    prev.scrollTop = messagesEl.scrollTop;
  }
  activeId = id;
  tabs.forEach(t => { t.pane.style.display = (t.id === id) ? '' : 'none'; });
  const t = activeTab();
  if (t) {
    document.getElementById('convo-title').textContent = t.title;
    applyTabSettings(t);   // restore this conversation's model/effort/thinking
    input.value = t.draft || '';                                    // restore this tab's draft
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 160) + 'px';  // resize to the draft
  }
  if (typeof renderBottomCard === 'function') renderBottomCard();   // card only in its own tab
  if (typeof renderPendingImages === 'function') renderPendingImages();  // this tab's pasted-image chips
  if (typeof syncComposer === 'function') syncComposer();           // send/stop reflects THIS tab
  try { if (window._activeTab) window._activeTab(id); } catch (e) {} // status bar follows active tab
  renderTabs();
  // #messages is one scroll container shared by every pane, so a background pane's
  // position is not preserved by the DOM on its own and every switch has to place it.
  //
  // Armed: restore THIS tab's own remembered spot, so a tab left scrolled up reopens
  // where the user stopped reading instead of snapping down and hiding exactly the
  // content they had scrolled up to see. A brand-new tab (both undefined) reads as
  // caught-up, matching the previous always-jump behavior.
  //
  // Off: land on the bottom. That is the released behavior, and the toggle promises to
  // change nothing while off — with the lock off the transcript follows unconditionally
  // anyway, so a restored position would only survive until the next render.
  //
  // followTail is set directly either way rather than left to the 'scroll' event this
  // write may fire: restoring a position the container already holds is a no-op that
  // fires nothing, which would strand followTail at whatever the PREVIOUS tab left.
  if (scrollLocked && t) {
    followTail = t.followTail !== false;
    messagesEl.scrollTop = followTail ? messagesEl.scrollHeight : (t.scrollTop || 0);
  } else {
    followTail = true;
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }
  updateJumpToLatest();
}
/* Loads a tab's stored model/effort/thinking/permission-mode into the composer UI
   + status bar. */
function applyTabSettings(t) {
  if (t.model !== undefined) { curModel = t.model; updateModelLabel(); }
  // Thinking BEFORE effort: the effort cap is a function of the thinking flag, so
  // restoring in the other order would clamp against the previous tab's state.
  if (t.thinking !== undefined) thinkingOn = t.thinking;
  if (t.effortIdx !== undefined) setEffort(t.effortIdx, { force: true });   // sliders + notify
  // Reconcile silently — a stored pair predating this gate may be illegal.
  if (typeof enforceThinkingGate === 'function') enforceThinkingGate({ silent: true });
  else updateThinkingCheck();
  // Each conversation keeps its own permission mode (VSCode-style).
  permMode = (t.permMode !== undefined ? t.permMode : DEFAULT_PERM_MODE);
  if (typeof applyModeUI === 'function') applyModeUI(permMode);
  if (typeof notifyStatusSelection === 'function') notifyStatusSelection();
}
function closeTab(id) {
  const idx = tabs.findIndex(t => t.id === id);
  if (idx < 0) return;
  const t = tabs[idx];
  if (t.streaming && window._cancelRequest) window._cancelRequest(id);   // stop its stream
  if (window._disposeTab) window._disposeTab(id);                         // free its process
  if (rtab === t) rtab = null;
  if (pendingCardOwner === t) { pendingCard = null; pendingCardOwner = null; }
  t.pane.remove();
  tabs.splice(idx, 1);
  if (!tabs.length) { createTab(); return; }
  if (activeId === id) switchTab(tabs[Math.min(idx, tabs.length - 1)].id);
  else renderTabs();
}
let dragTabId = null;
function clearDropMarks() {
  document.querySelectorAll('#tabs .tab.drop-before, #tabs .tab.drop-after')
    .forEach(el => el.classList.remove('drop-before', 'drop-after'));
}
/* Move the dragged tab to before/after the target tab, then re-render. */
function moveTab(fromId, toId, after) {
  if (fromId === toId) return;
  const fromIdx = tabs.findIndex(t => t.id === fromId);
  if (fromIdx < 0) return;
  const [moved] = tabs.splice(fromIdx, 1);
  let toIdx = tabs.findIndex(t => t.id === toId);
  if (toIdx < 0) { tabs.splice(fromIdx, 0, moved); return; }
  if (after) toIdx += 1;
  tabs.splice(toIdx, 0, moved);
  renderTabs();
}
function renderTabs() {
  const c = document.getElementById('tabs'); if (!c) return;
  c.innerHTML = '';
  tabs.forEach(t => {
    const el = document.createElement('div'); el.className = 'tab' + (t.id === activeId ? ' active' : '');
    el.draggable = true; el.dataset.id = t.id;
    el.innerHTML = '<span class="ti">' + ICONS.SUNBURST + '</span><span class="tt"></span><span class="tab-close">' + ICONS.X + '</span>';
    el.querySelector('.tt').textContent = t.title;
    el.title = t.title;
    el.onclick = (e) => { if (e.target.closest('.tab-close')) closeTab(t.id); else switchTab(t.id); };
    // Drag-to-reorder with a drop-line indicator (best-practice: line before/after).
    el.addEventListener('dragstart', (e) => {
      dragTabId = t.id; el.classList.add('dragging');
      e.dataTransfer.effectAllowed = 'move';
      try { e.dataTransfer.setData('text/plain', t.id); } catch (_) {}
    });
    el.addEventListener('dragend', () => { dragTabId = null; el.classList.remove('dragging'); clearDropMarks(); });
    el.addEventListener('dragover', (e) => {
      if (dragTabId === null || dragTabId === t.id) return;
      e.preventDefault(); e.dataTransfer.dropEffect = 'move';
      const r = el.getBoundingClientRect();
      const after = (e.clientX - r.left) > r.width / 2;
      clearDropMarks();
      el.classList.add(after ? 'drop-after' : 'drop-before');
    });
    el.addEventListener('dragleave', () => el.classList.remove('drop-before', 'drop-after'));
    el.addEventListener('drop', (e) => {
      e.preventDefault();
      if (dragTabId === null) return;
      const r = el.getBoundingClientRect();
      const after = (e.clientX - r.left) > r.width / 2;
      clearDropMarks();
      moveTab(dragTabId, t.id, after);
    });
    c.appendChild(el);
  });
  // Scroll the active tab into view so a newly created session (off the right edge
  // on a narrow view) is always reachable.
  const a = c.querySelector('.tab.active');
  if (a) {
    const al = a.offsetLeft, ar = al + a.offsetWidth;
    if (ar > c.scrollLeft + c.clientWidth) c.scrollLeft = ar - c.clientWidth;
    else if (al < c.scrollLeft) c.scrollLeft = al;
  }
}
function setTabTitle(t, raw) {
  const title = ((stripContext(raw) || raw || '').trim().slice(0, 40)) || 'Claude Code';
  t.title = title; t.titled = true;
  if (t.id === activeId) document.getElementById('convo-title').textContent = title;
  renderTabs();
}
function startTitleEdit() {
  const t = activeTab(); if (!t) return;
  const wrap = document.getElementById('title-wrap'); if (!wrap) return;
  // Already editing → clicking the input again does nothing (no re-open, no clone).
  if (wrap.querySelector('.title-input')) return;
  const titleEl = document.getElementById('convo-title');
  const editBtn = document.getElementById('title-edit');
  if (!titleEl || !editBtn) return;
  const curTitle = t.title || 'Claude Code';
  titleEl.style.display = 'none';
  editBtn.style.display = 'none';
  const inp = document.createElement('input');
  inp.type = 'text'; inp.className = 'title-input'; inp.value = curTitle;
  wrap.appendChild(inp);
  // Size the field to its content via a hidden mirror span (inputs don't shrink-wrap).
  // border-box width = text width + the space the pencil used to take (gap+icon ≈ 19px),
  // which (a) matches the hovered pill's width and (b) is the allowance that keeps the
  // text from ever being clipped — the input's own padding/border overhead lives inside it.
  const EDIT_ALLOWANCE = 20;
  const meas = document.createElement('span');
  meas.style.cssText = 'position:absolute;visibility:hidden;white-space:pre;font-size:13px;font-weight:600;';
  document.body.appendChild(meas);
  const sizer = () => { meas.textContent = inp.value || ' '; inp.style.width = Math.min(324, meas.offsetWidth + EDIT_ALLOWANCE) + 'px'; };
  inp.oninput = sizer; sizer();
  inp.focus(); inp.select();
  let done = false;
  function finish(save) {
    if (done) return; done = true;
    const newTitle = inp.value.trim() || 'Claude Code';
    inp.remove(); meas.remove();
    titleEl.style.display = '';
    editBtn.style.display = '';
    if (save && newTitle !== curTitle) {
      t.title = newTitle; t.titled = true;
      titleEl.textContent = newTitle;
      renderTabs();
      if (t.sessionId && window._renameSession) window._renameSession(t.sessionId, newTitle);
    }
  }
  inp.onclick = (e) => e.stopPropagation();   // don't bubble to the wrap's onclick
  inp.onblur = () => finish(true);
  inp.onkeydown = (e) => {
    if (e.key === 'Enter') { e.preventDefault(); finish(true); }
    else if (e.key === 'Escape') { e.preventDefault(); inp.onblur = null; finish(false); }
  };
}
function newSession() { closeMenus(); createTab(); input.focus(); }

/* /clear — start a fresh conversation IN PLACE. VSCode stays on the tab the
   command was invoked from rather than opening another one, so the tab, its
   position and its composer settings (model/effort/thinking/mode) all survive;
   only the conversation is replaced. The composer draft survives too — /clear
   clears the conversation, not what you were typing. Contrast newSession(),
   which deliberately opens a NEW tab. */
function clearSession() {
  const t = activeTab(); if (!t) return;
  closeMenus();
  loadRender(t);                 // operate on THIS tab's render state
  if (t.streaming) doCancel();
  hideWorking();
  curTurn = null; curBody = null; curText = ''; curThink = null; curThinkText = '';
  if (pendingCardOwner === t) { pendingCard = null; pendingCardOwner = null; }
  clearBottomCard();
  // Drop the process so the next send starts a genuinely new conversation
  // (spawns without --resume) instead of continuing the one just cleared.
  if (window._disposeTab) window._disposeTab(t.id);
  t.sessionId = '';
  t.titled = false;
  t.title = 'Claude Code';
  t.images = [];
  t.compacting = false;
  t.downgradeWarned = null;
  t.pane.innerHTML = '';
  // The old transcript's scroll state means nothing against an emptied pane: left alone, a
  // scrolled-up scrollTop would reopen the fresh conversation scrolled into blank space
  // with the button showing, the next time this tab is switched to while the lock is on.
  // t is the active tab, so the module global needs resetting alongside it.
  t.followTail = true; t.scrollTop = 0;
  followTail = true;
  if (typeof updateJumpToLatest === 'function') updateJumpToLatest();
  document.getElementById('convo-title').textContent = t.title;
  renderTabs();
  if (typeof renderPendingImages === 'function') renderPendingImages();
  if (typeof syncComposer === 'function') syncComposer();
  input.focus();
}


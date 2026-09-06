/* tabs.js — Tab model (create/switch/close/drag-reorder), per-tab render-state context
   switch (loadRender), title editing. */

/* ===================== Tabs ===================== */
/**
 * One conversation tab — created by createTab(), passed around the whole codebase.
 * @typedef {Object} Tab
 * @property {string} id              "tab1", "tab2", … — also keys the Java-side ChatProcessManager
 * @property {string} title           tab-strip / header title
 * @property {string} sessionId       CLI session id ("" until the first init event; non-empty → sends resume)
 * @property {string} rootId          the supertab (working root) this conversation runs in — its claude cwd
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
 * @property {HTMLElement|null} [pendingCard] this tab's own blocking approval/question/advisor
 *   card, if one is currently awaiting an answer (see carddock.js) — kept per-tab so one tab's
 *   card can never evict another's
 */
/** @type {Tab[]} */
let tabs = [], activeId = null, tabSeq = 0;
// id of the tab currently mid-rename (double-clicked .tt), or null. renderTabs() runs
// on every tab add/remove/reorder/retitle — including a BACKGROUND tab's stream
// naming itself — so a bare rebuild would blow away whatever's typed into another
// tab's input mid-edit. Tracked here so renderTabs() can re-create that one input
// (with its value/selection preserved) instead of just losing it.
let editingTabId = null;
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
 *          effortIdx?: number, thinking?: boolean, permMode?: string, rootId?: string}} [opts]
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
    // Conversations belong to a working root; #tabs shows only the active root's.
    rootId: opts.rootId || activeRootId,
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
  // Selecting a conversation also selects its root, so clicking a background root's
  // tab (via history, or a close falling through) keeps the two rows agreeing.
  if (t && t.rootId && t.rootId !== activeRootId) activeRootId = t.rootId;
  const ar = activeRoot && activeRoot();
  if (ar) ar.activeTabId = id;
  if (t) {
    applyTabSettings(t);   // restore this conversation's model/effort/thinking
    input.value = t.draft || '';                                    // restore this tab's draft
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 160) + 'px';  // resize to the draft
  }
  if (typeof renderBottomCard === 'function') renderBottomCard();   // card only in its own tab
  if (typeof renderPendingImages === 'function') renderPendingImages();  // this tab's pasted-image chips
  if (typeof syncComposer === 'function') syncComposer();           // send/stop reflects THIS tab
  // The root rides along: Java scopes session history, rewind and the status bar to
  // the conversation's own folder, not to the workspace root.
  try { if (window._activeTab) window._activeTab(id, rootPathOf(t)); } catch (e) {} // status bar follows active tab
  renderTabs();
  if (typeof renderSupertabs === 'function') renderSupertabs();
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
/**
 * @param {string} id
 * @param {{keepRoot?: boolean}} [opts] keepRoot: the caller is tearing the whole root
 *   down (closeRoot), so skip the refill that would otherwise resurrect a tab in it.
 */
function closeTab(id, opts) {
  opts = opts || {};
  const idx = tabs.findIndex(t => t.id === id);
  if (idx < 0) return;
  const t = tabs[idx];
  // A root with no conversations has nothing to show, so its last one closing closes
  // the root too — and when that is also the LAST root, closeRoot asks before taking
  // the view down. Checked BEFORE any teardown: the confirmation is asynchronous, so
  // a cancel has to find this tab still whole.
  if (!opts.keepRoot && tabs.filter(x => x.rootId === t.rootId).length === 1) {
    closeRoot(t.rootId); return;
  }
  if (t.streaming && window._cancelRequest) window._cancelRequest(id);   // stop its stream
  if (window._disposeTab) window._disposeTab(id);                         // free its process
  if (rtab === t) rtab = null;
  // t.pendingCard (if any) goes with it — no separate global reference to clear now
  // that the card lives on the Tab object itself.
  t.pane.remove();
  tabs.splice(idx, 1);
  if (opts.keepRoot) return;   // closeRoot is tearing the whole root down
  // Non-empty by construction: the guard above diverted the last-one-left case, so
  // this root still has at least one conversation to land on.
  const own = tabs.filter(x => x.rootId === t.rootId);
  if (activeId === id) {
    // Land on the neighbour WITHIN this root — the flat index next door may well
    // belong to another root and would silently switch the user's folder.
    const oidx = own.findIndex(x => tabs.indexOf(x) >= idx);
    switchTab((oidx >= 0 ? own[oidx] : own[own.length - 1]).id);
  } else renderTabs();
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
  // Snapshot the in-progress edit (if any) so the rebuild below can restore it —
  // the input element itself is about to be destroyed along with the rest of #tabs.
  let editSnapshot = null;
  if (editingTabId) {
    const liveInp = c.querySelector('.tab.editing .title-input');
    if (liveInp) editSnapshot = { value: liveInp.value, selStart: liveInp.selectionStart, selEnd: liveInp.selectionEnd };
  }
  c.innerHTML = '';
  // Only the active root's conversations. The array stays flat and globally ordered,
  // so a filtered view keeps each root's tabs in the order the user dragged them into.
  tabs.filter(t => t.rootId === activeRootId).forEach(t => {
    const editing = t.id === editingTabId;
    const el = document.createElement('div'); el.className = 'tab' + (t.id === activeId ? ' active' : '') + (editing ? ' editing' : '');
    el.draggable = !editing;   // a draggable ancestor steals mousedown-drag from an input's own text selection
    el.dataset.id = t.id;
    el.innerHTML = '<span class="ti">' + ICONS.SUNBURST + '</span><span class="tt"></span><span class="tab-close">' + ICONS.X + '</span>';
    const tt = el.querySelector('.tt');
    el.title = t.title;
    el.onclick = (e) => { if (e.target.closest('.tab-close')) closeTab(t.id); else if (!editing) switchTab(t.id); };
    tt.ondblclick = (e) => { e.stopPropagation(); startTitleEdit(t.id); };
    if (editing) startTabEditInput(el, tt, t, editSnapshot);
    else tt.textContent = t.title;
    // Drag-to-reorder with a drop-line indicator (best-practice: line before/after).
    el.addEventListener('dragstart', (e) => {
      dragTabId = t.id; el.classList.add('dragging');
      e.dataTransfer.effectAllowed = 'move';
      try { e.dataTransfer.setData('text/plain', t.id); } catch (_) {}
    });
    el.addEventListener('dragend', () => { dragTabId = null; el.classList.remove('dragging'); clearDropMarks(); });
    el.addEventListener('dragover', (e) => {
      // dragTabId is null while a ROOT is being dragged, so returning without
      // preventDefault is also what refuses a root dropped onto the conversation
      // row — the browser shows the no-drop cursor and never fires drop.
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
  renderTabs();
}
/* Renders the <input> for the tab currently being renamed, called from renderTabs()
 * both on first open (resume === null) and on every subsequent rebuild while the edit
 * is still open (resume carries over the value/selection a rebuild would otherwise
 * wipe — see editingTabId's comment). */
function startTabEditInput(el, tt, t, resume) {
  const inp = document.createElement('input');
  inp.type = 'text'; inp.className = 'title-input'; inp.value = resume ? resume.value : (t.title || 'Claude Code');
  tt.replaceWith(inp);
  inp.focus();
  if (resume) inp.setSelectionRange(resume.selStart, resume.selEnd);
  else inp.select();
  let done = false;
  function finish(save) {
    if (done) return; done = true;
    const newTitle = inp.value.trim() || 'Claude Code';
    editingTabId = null;
    if (save && newTitle !== t.title) {
      t.title = newTitle; t.titled = true;
      if (t.sessionId && window._renameSession) window._renameSession(t.sessionId, newTitle);
    }
    renderTabs();
  }
  inp.onclick = (e) => e.stopPropagation();   // don't bubble to the tab's onclick (switchTab)
  inp.onblur = () => finish(true);
  inp.onkeydown = (e) => {
    e.stopPropagation();   // don't let Enter/Escape reach anything else while renaming
    if (e.key === 'Enter') { e.preventDefault(); finish(true); }
    else if (e.key === 'Escape') { e.preventDefault(); inp.onblur = null; finish(false); }
  };
}
function startTitleEdit(tabId) {
  const t = tabById(tabId); if (!t) return;
  if (editingTabId === tabId) return;   // already editing this tab → no-op
  editingTabId = tabId;
  renderTabs();
}
/* New conversation in the ACTIVE root — a new FOLDER is newRootDirectory(). */
function newSession() { closeMenus(); createTab({ rootId: activeRootId }); input.focus(); }

/* True when t has no conversation AND nothing typed/attached that a reuse would lose —
 * checked before silently repurposing a tab instead of opening a new one (see
 * loadHistory's toolbar branch in history.js). Active-tab only: reads the composer live
 * from #input rather than t.draft, which is only synced on switchTab (see its own
 * comment) and so can be stale for the tab currently on screen.
 *
 * t.sessionId === '' alone is NOT enough — /help echoes a user bubble + a system message
 * without ever sending anything to the CLI (slash.js), and /clear echoes its own command
 * bubble back into an emptied pane, so both leave a sessionId-less tab with real content
 * on screen. addUserMessage()/addSystem() both append into t.pane, and createTab() seeds
 * it with WELCOME_HTML's placeholder and nothing else — so the pane itself, not sessionId,
 * is what actually answers "is there something here a reuse would silently discard". */
function isTabEmpty(t) {
  return !!t && t === activeTab() && !t.sessionId && !t.streaming && !t.pendingCard
      && !(t.images && t.images.length) && !input.value.trim()
      && !t.pane.querySelector('.turn');
}

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
  clearBottomCard(t);   // drop this tab's own pending card, if any — /clear replaces its conversation
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
  renderTabs();
  if (typeof renderPendingImages === 'function') renderPendingImages();
  if (typeof syncComposer === 'function') syncComposer();
  input.focus();
}


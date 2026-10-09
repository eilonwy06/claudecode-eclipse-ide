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
 * @property {boolean} [ultracode]    per-conversation Ultracode toggle (default false)
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
const DEFAULT_THINKING = true;     // thinking on, unless the preference says otherwise
const DEFAULT_ULTRACODE = false;
/* The preference behind a NEW conversation's thinking toggle ("Enable Thinking by
   default"). Read per call rather than cached, so changing it takes effect on the next
   conversation without a restart; DEFAULT_THINKING covers a page with no view behind
   it (the jsdom tests). */
function defaultThinking() {
  try { return window._thinkingOnStartup ? !!_thinkingOnStartup() : DEFAULT_THINKING; }
  catch (e) { return DEFAULT_THINKING; }
}
const DEFAULT_PERM_MODE = 'default';   // "Manual"
/* The preference behind the permission mode a conversation starts in ("Initial
   Permission Mode"), read per call like defaultThinking(). Unset, that is Manual, as it
   was before the preference existed. */
function defaultPermMode() {
  try { return (window._initialPermissionMode && _initialPermissionMode()) || DEFAULT_PERM_MODE; }
  catch (e) { return DEFAULT_PERM_MODE; }
}
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
  const at = activeTab();
  // Closed while the Remote Control bridge is coming up. A message typed before
  // the bridge is connected does not reach the other devices, so the composer
  // stays shut until it is — rather than silently dropping the first thing said.
  const connecting = !!(at && at.rcConnecting);
  // And closed on the way back DOWN, until the CLI confirms it. The switch-off is
  // one control request with one answer; a second /remote-control issued before
  // that answer sent a second one, and the CLI's reply to it — a plain "not
  // enabled", the same shape as a refused switch-on — put the tab permanently out
  // of step with the bridge. No indicator for this one: there is nothing being
  // waited on remotely, and the composer already says what is happening.
  const disconnecting = !!(at && at.rcDisconnecting);
  const busy = connecting || disconnecting;
  const hasImgs = typeof hasPendingImages === 'function' && hasPendingImages();
  input.disabled = busy;
  send.classList.toggle('stop', s);
  send.classList.toggle('disabled', busy || (!s && input.value.trim() === '' && !hasImgs));
  send.innerHTML = s ? ICONS.STOP : ICONS.SEND;
  input.placeholder = connecting ? 'Establishing connection…'
                    : disconnecting ? 'Disconnecting…'
                    : (s ? 'Queue another message…' : 'Message Claude…');
}
/* "Prefer the Terminal experience?" — a persistent chrome row (#terminal-tip in
   claudegui.html, right above #input-wrap, same slot as #browser-banner) rather than
   part of WELCOME_HTML's own per-tab content: it needs to sit fixed above the composer,
   not scroll away inside the pane with the rest of the welcome copy. Shown only while
   the active tab is still on its welcome/empty state. Dismissed for the rest of this
   run once closed — in-memory only, same rule controls.js's usage-warning banner
   follows (usageDismissed), not persisted across Eclipse restarts. */
let terminalBannerDismissed = false;
function dismissTerminalBanner() {
  terminalBannerDismissed = true;
  renderTerminalTip();
}
function renderTerminalTip() {
  const el = document.getElementById('terminal-tip');
  if (!el) return;
  const t = activeTab();
  const showing = !!(t && t.pane && t.pane.querySelector('.welcome') && !terminalBannerDismissed);
  el.hidden = !showing;
}
function WELCOME_HTML() {
  // wc-wordmark is a SIBLING before .welcome, not a child of it: .welcome centers its
  // children as one group (justify-content:center), which would carry the wordmark down
  // into the middle of the pane along with the mascot/heading whenever that group is
  // shorter than .welcome's min-height. Kept outside that group so it stays pinned to
  // the actual top of the pane regardless of how tall the rest of the welcome state is.
  return '<div class="wc-wordmark"><span class="wc-word-ic">' + ICONS.SUNBURST + '</span><span class="wc-word-txt">Claude Code</span></div>'
    + '<div class="welcome">'
    + '<div class="wc-mascot">' + ICONS.MASCOT + '</div>'
    + '<div class="wc-h">Ready to code?</div>'
    + '<div class="wc-p">Let\'s write something worth deploying.</div>'
    // We have a real in-GUI MCP servers window (openMcpServers/mcp.js), so this
    // points straight at it instead of sending the user out to a terminal for it.
    + '<div class="wc-mcp-tip">Set up <span class="wc-link" onclick="openMcpServers()">MCP servers</span> to connect Claude to more tools and data.</div>'
    + '</div>';
}
/* The settings a conversation was last saved with (the per-session sidecar), as
   createTab options — empty for a new conversation or one with nothing saved.

   Read when the tab is MADE, not only when its transcript is first shown: a tab
   restored from the last Eclipse session is rendered lazily, and with Remote Control
   on startup its process is started the moment it is made. Left to loadHistory, that
   process started on the defaults, and the conversation's own settings — arriving
   with its next message — replaced it and the bridge with it. A read only: see the
   note on applyTabSettings about what a write from here did (issue #114). */
function storedTabSettings(sessionId) {
  const out = {};
  if (!sessionId || !window._loadSessionPrefs) return out;
  let saved = null;
  try { saved = JSON.parse(window._loadSessionPrefs(sessionId) || '{}'); } catch (e) {}
  if (!saved) return out;
  if (saved.model) out.model = saved.model;
  const ei = parseInt(saved.effort, 10);
  if (!isNaN(ei)) out.effortIdx = ei;
  if (saved.thinking === '1') out.thinking = true; else if (saved.thinking === '0') out.thinking = false;
  if (saved.permMode) out.permMode = saved.permMode;
  if (saved.ultracode === '1') out.ultracode = true; else if (saved.ultracode === '0') out.ultracode = false;
  return out;
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
  observePane(pane);
  // Per-conversation model/effort/thinking (VSCode-style). A NEW tab starts at the
  // DEFAULTS (not whatever the last-viewed convo used); each tab then remembers its
  // own. Defaults: high effort, thinking off, the user's configured default model.
  // A conversation reopened by its session id starts on what it was saved with.
  const stored = storedTabSettings(opts.sessionId);
  const setting = (key, fallback) =>
    opts[key] !== undefined ? opts[key] : (stored[key] !== undefined ? stored[key] : fallback);
  tabs.push({ id, title: opts.title || 'Claude Code', sessionId: opts.sessionId || '', pane, titled: !!opts.titled, draft: '',
    // Conversations belong to a working root; #tabs shows only the active root's.
    rootId: opts.rootId || activeRootId,
    model: setting('model', defaultModel()),
    effortIdx: setting('effortIdx', DEFAULT_EFFORT_IDX),
    thinking: setting('thinking', defaultThinking()),
    permMode: setting('permMode', defaultPermMode()),
    ultracode: setting('ultracode', DEFAULT_ULTRACODE) });
  switchTab(id);
  const created = tabs[tabs.length - 1];
  // With "Enable Remote Control for all sessions" set, a new conversation comes up
  // already reachable from a phone — so this starts on "Establishing
  // connection…" rather than waiting to be asked. A no-op when the preference
  // is off, which is the default.
  if (typeof autoEnableRemoteControl === 'function') autoEnableRemoteControl(created);
  return created;
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
  // Find bar: park the outgoing tab's query/matches/open-state, restore the incoming
  // tab's — same per-tab-state shape as the draft/scroll position above.
  if (typeof onFindTabSwitch === 'function') onFindTabSwitch(prev, tabById(id));
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
    measuringTextarea(input, () => {
      input.style.height = 'auto';
      input.style.height = Math.min(input.scrollHeight, 160) + 'px';  // resize to the draft
    });
  }
  if (typeof renderBottomCard === 'function') renderBottomCard();   // card only in its own tab
  if (typeof renderPendingImages === 'function') renderPendingImages();  // this tab's pasted-image chips
  if (typeof renderBrowserBanner === 'function') renderBrowserBanner();  // this tab's browser connection
  if (typeof renderTerminalTip === 'function') renderTerminalTip();      // shown only on THIS tab's welcome state
  if (typeof syncComposer === 'function') syncComposer();           // send/stop reflects THIS tab
  if (typeof updateAgentsBtn === 'function') updateAgentsBtn();     // toolbar pill reflects THIS tab's agents
  if (typeof switchToContextRing === 'function') switchToContextRing(id);  // ring reflects THIS tab's context
  if (typeof renderBookmarks === 'function') renderBookmarks();     // the panel shows THIS tab's bookmarks
  // The root rides along: Java scopes session history, rewind and the status bar to
  // the conversation's own folder, not to the workspace root.
  try { if (window._activeTab) window._activeTab(id, rootPathOf(t)); } catch (e) {} // status bar follows active tab
  refreshStaleTitles();   // after the line above: Java lists the folder it was just told
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
  // Explicit, not left to the 'scroll' event this write may fire: a write landing on a
  // position the container already holds (e.g. switching back to a tab left at the exact
  // same spot) is a no-op that fires nothing — same reasoning as the followTail line above.
  // A tab left at the tail comes back at it: pinning its prompt moves the bottom, so scroll again.
  if (typeof updatePinnedPrompt === 'function' && updatePinnedPrompt() && followTail) messagesEl.scrollTop = messagesEl.scrollHeight;
  // What was drawn into this tab while it was not showing has had no size to be measured
  // by, and a conversation that was coming in when it was left goes on from there.
  if (t && typeof measureRevealed === 'function') measureRevealed(t.pane);
  if (t && typeof resumeDrawing === 'function') resumeDrawing(t);
  // A conversation restored from the last Eclipse session holds only its session id
  // until it is first shown (see viewstate.js) — rebuilding every transcript at
  // startup would cost one full reconstruction per tab, for panes nobody is looking
  // at. Render here, on the switch that makes it visible, then place the scroll the
  // user left it at: the block above ran against a pane that was still empty, and
  // with Scroll Lock off it lands at the bottom unconditionally.
  if (t && t._restore) {
    const rs = t._restore;
    t._restore = null;                    // cleared FIRST — this must not re-enter
    // The tab's title as it stands, not the stored one: it may have been read from the
    // session since (refreshStaleTitles), and the stored one would put "Claude Code" back.
    // The place is put back once the whole conversation is drawn: it is a distance from
    // the top, and the older messages come in after the newest (history.js).
    loadHistory(rs.sessionId, t.title || rs.title, t, { then: readerMoved => placeRestoredTab(t, rs, readerMoved) });
  }
}
/* How near its end a restored conversation has to have been left to count as left AT
   its end, where it then stays, following. The page is not to the pixel the one the
   distance was taken in: the note a reopened conversation may end with is new in it. */
const RESTORED_AT_END_SLOP = 200;
/* Puts a tab restored from the last Eclipse session back where the user left it, now
   that its conversation is drawn — unless that was at its end, or they have moved the
   transcript themselves in the meantime. */
function placeRestoredTab(t, rs, readerMoved) {
  if (!(rs.scrollTop > 0) || readerMoved) return;
  // A frame on. The blocks drawn last are cut to size in a frame of their own
  // (measureWhenShown), and the distance was taken in a page where they already were.
  requestAnimationFrame(() => {
    if (t !== activeTab() || t.opening) return;
    if (rs.scrollTop >= messagesEl.scrollHeight - messagesEl.clientHeight - RESTORED_AT_END_SLOP) return;
    // The watch that keeps a reopened conversation on its newest message while its
    // blocks size themselves would take it straight back there.
    if (settleWatchStop) settleWatchStop();
    messagesEl.scrollTop = rs.scrollTop;
    t.scrollTop = messagesEl.scrollTop;
    // Set here, lock armed or not, rather than left to the 'scroll' event this write fires:
    // that event comes a frame on, and a block sizing itself in THIS frame would have
    // paneResizeObserver (chat.js) read the view as still following and pin it to the bottom.
    followTail = false; t.followTail = false;
    if (typeof updatePinnedPrompt === 'function') updatePinnedPrompt();
    updateJumpToLatest();
  });
}
/* Loads a tab's stored model/effort/thinking/permission-mode into the composer UI
   + status bar.

   READ-ONLY with respect to the sidecar. This runs on every switchTab(), and the
   createTab() inside loadHistory() switches to the new tab BEFORE the restore has
   read the stored prefs — so any write from here lands on the conversation's own
   key with the fresh tab's defaults, destroying the entry the restore is about to
   read. That is exactly what issue #114 was: a [PREFS-SAVE] of defaults under the
   session id, immediately followed by the [PREFS-LOAD] that read them back. */
function applyTabSettings(t) {
  if (t.model !== undefined) { curModel = t.model; updateModelLabel(); }
  // Thinking BEFORE effort: the effort cap is a function of the thinking flag, so
  // restoring in the other order would clamp against the previous tab's state.
  if (t.thinking !== undefined) thinkingOn = t.thinking;
  // noPersist: painting is not an edit — see the note above.
  if (t.effortIdx !== undefined) setEffort(t.effortIdx, { force: true, noPersist: true });
  // Reconcile silently — a stored pair predating this gate may be illegal.
  if (typeof enforceThinkingGate === 'function') enforceThinkingGate({ silent: true });
  else updateThinkingCheck();
  // Each conversation keeps its own permission mode (VSCode-style).
  permMode = (t.permMode !== undefined ? t.permMode : DEFAULT_PERM_MODE);
  if (typeof applyModeUI === 'function') applyModeUI(permMode);
  ultracodeOn = (t.ultracode !== undefined ? t.ultracode : DEFAULT_ULTRACODE);
  if (typeof updateUltracodeToggle === 'function') updateUltracodeToggle();
  if (typeof updateSwitchModelsOnFlagToggle === 'function') updateSwitchModelsOnFlagToggle();
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
  if (t.setupGuide) return;   // the FreeBSD setup guide stays until claude is installed
  // A root with no conversations has nothing to show, so its last one closing closes
  // the root too — and when that is also the LAST root, closeRoot asks before taking
  // the view down. Checked BEFORE any teardown: the confirmation is asynchronous, so
  // a cancel has to find this tab still whole.
  if (!opts.keepRoot && tabs.filter(x => x.rootId === t.rootId).length === 1) {
    closeRoot(t.rootId, 'session'); return;
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
/* ---- "Open Claude in Terminal": a tab's conversation moves to the Claude Terminal ----
   The same conversation is not to run in two places, so the tab does not stay: the view
   asks first (a real dialog, as for closing the last tab), and on yes has the page let
   the tab go, starts the session in the Terminal, and closes itself when that was its
   last tab. A tab with no conversation yet has nothing to move, and only shows the
   Terminal. */

/* Whether a tab runs in the workspace folder, the only one the Terminal runs in. */
function tabInWorkspaceFolder(t) {
  const r = t && rootById(t.rootId);
  return !!r && r === roots[0];
}
/* Whether a tab is the last one of the view: closing it closes the view. */
function isLastTabOfView(t) {
  return roots.length === 1 && tabs.filter(x => x.rootId === t.rootId).length === 1;
}
function openTabInTerminal(t) {
  if (!t || t.setupGuide) return;
  if (!t.sessionId || !window._openSessionInTerminal) {
    if (window._openTerminalView) window._openTerminalView();
    return;
  }
  window._openSessionInTerminal(t.id, t.sessionId, isLastTabOfView(t));
}
/**
 * The user said yes: the tab lets go of its conversation, which is about to be started in
 * the Terminal. Asked by the view, which acts on the answer.
 * @returns {string} 'closed' when the tab was closed; 'last' when it was the view's last,
 *   which the view now closes (the tab is emptied, so that the next start does not bring
 *   the conversation back beside the Terminal's); '' when the tab is gone or holds
 *   another conversation by now, and nothing was done
 */
window.releaseTabForTerminal = function(tabId, sessionId) {
  const t = tabById(tabId);
  if (!t || !t.sessionId || t.sessionId !== sessionId) return '';
  if (!isLastTabOfView(t)) { closeTab(tabId); return 'closed'; }
  if (t.streaming && window._cancelRequest) window._cancelRequest(tabId);
  if (window._disposeTab) window._disposeTab(tabId);
  t.sessionId = ''; t._restore = null; t.opening = null; t.earlier = null;
  if (typeof saveViewStateIfChanged === 'function') saveViewStateIfChanged();
  return 'last';
};
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
    if (liveInp) {
      editSnapshot = { value: liveInp.value, selStart: liveInp.selectionStart, selEnd: liveInp.selectionEnd };
      // Detach commit-on-blur BEFORE the rebuild destroys this input. Browsers disagree
      // on whether removing a focused element fires blur at all, so leaving it attached
      // makes an unrelated rebuild (a BACKGROUND tab naming itself mid-stream) sometimes
      // commit and close the edit and sometimes not — the exact "sometimes it closes,
      // sometimes it doesn't" inconsistency. The edit is being restored below, not ended.
      liveInp.onblur = null;
    }
  }
  c.innerHTML = '';
  // The restored input, focused only once it is actually in the document — see below.
  let editInput = null;
  // Only the active root's conversations. The array stays flat and globally ordered,
  // so a filtered view keeps each root's tabs in the order the user dragged them into.
  tabs.filter(t => t.rootId === activeRootId).forEach(t => {
    const editing = t.id === editingTabId;
    const el = document.createElement('div'); el.className = 'tab' + (t.id === activeId ? ' active' : '') + (editing ? ' editing' : '');
    el.draggable = !editing;   // a draggable ancestor steals mousedown-drag from an input's own text selection
    el.dataset.id = t.id;
    // Rename + close share one action group, the same shape (and 2px gap) the history
    // list's own row actions use — see .tab-actions in layout.css for why they can't
    // just be siblings of the title.
    // The FreeBSD setup guide's tab (cliversion.js) can be neither renamed nor closed,
    // so it gets no actions at all, not even on hover.
    el.innerHTML = '<span class="ti">' + ICONS.SUNBURST + '</span><span class="tt"></span>'
      + (t.setupGuide ? '' :
          '<span class="tab-actions">'
        +   '<span class="tab-edit" title="Rename">' + ICONS.PENCIL + '</span>'
        +   '<span class="tab-close">' + ICONS.X + '</span>'
        + '</span>');
    const tt = el.querySelector('.tt');
    el.title = t.title;
    // Routed here rather than as the pencil's own handler, for the same reason .tab-close
    // is: one listener on the tab owns every click inside it, so there is a single place
    // that decides what a click on this tab means.
    el.onclick = (e) => {
      if (e.target.closest('.tab-close')) closeTab(t.id);
      else if (e.target.closest('.tab-edit')) startTitleEdit(t.id);
      else if (!editing) switchTab(t.id);
    };
    tt.ondblclick = (e) => { e.stopPropagation(); if (!t.setupGuide) startTitleEdit(t.id); };
    if (editing) editInput = startTabEditInput(el, tt, t, editSnapshot);
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
  // Focus the rename field only NOW, once its tab is actually in the document.
  // focus() on a detached element is a no-op, so doing this inside the loop (where the
  // tab is still being built) left the box unfocused: you had to click it before typing,
  // and because it had never been focused it never fired blur either, which is what made
  // clicking away sometimes end the edit and sometimes not. Same ordering as
  // startHistoryRename in history.js, which appends to a live element and then focuses.
  if (editInput) {
    editInput.focus();
    if (editSnapshot) editInput.setSelectionRange(editSnapshot.selStart, editSnapshot.selEnd);
    else editInput.select();
  }
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
  const title = ((stripContext(raw) || raw || '').trim()) || 'Claude Code';
  t.title = title; t.titled = true;
  renderTabs();
}
/* A tab is named exactly like its row in the history list (the stored title: rename,
 * else AI title, else first message), so once the session exists its tab adopts that.
 * Tabs the user renamed by hand are skipped — that rename is already the stored title. */
function refreshTabTitle(t) {
  if (!t || !t.sessionId || t.userTitled || !window._listSessionsAsync) return;
  // The list is the VIEWED folder's: a tab in another folder is not in it, so it waits
  // (titleStale) and switchTab asks again once its folder is the one being viewed.
  if (t.rootId && t.rootId !== activeRootId) { t.titleStale = true; return; }
  requestSessionList();   // lands in onHistoryLoaded → syncTabTitles
}
/* One list request for every tab of the viewed folder that is waiting for its title —
 * whichever of them was selected, since syncTabTitles names them all from one list.
 * A tab stops waiting when the list names it (syncTabTitles), or after being asked for
 * twice: once is not enough, because a reply that arrives while the page is still loading
 * is dropped, and without a limit a session the list never holds would ask at every switch. */
const TITLE_ASKS = 2;
function refreshStaleTitles() {
  if (!window._listSessionsAsync) return;
  let waiting = false;
  for (const t of tabs) {
    if (!t.titleStale || (t.rootId && t.rootId !== activeRootId)) continue;
    t.titleAsks = (t.titleAsks || 0) + 1;
    if (t.titleAsks >= TITLE_ASKS) { t.titleStale = false; t.titleAsks = 0; }
    waiting = true;
  }
  if (waiting) requestSessionList();
}
function syncTabTitles(sessions) {
  let changed = false;
  for (const t of tabs) {
    if (!t.sessionId || t.userTitled) continue;
    const s = sessions.find(x => x.sessionId === t.sessionId);
    if (s) { t.titleStale = false; t.titleAsks = 0; }
    const title = s ? (stripContext(s.display) || '').trim() : '';
    if (title && title !== t.title) { t.title = title; t.titled = true; changed = true; }
  }
  if (changed) renderTabs();
}
/* Renders the <input> for the tab currently being renamed, called from renderTabs()
 * both on first open (resume === null) and on every subsequent rebuild while the edit
 * is still open (resume carries over the value/selection a rebuild would otherwise
 * wipe — see editingTabId's comment). Returns the input WITHOUT focusing it: the tab
 * it lives in is still detached at this point, so renderTabs() does that after append.
 *
 * Dismissal matches startHistoryRename (history.js) exactly, so the two renames in this
 * app behave identically: Enter commits, Escape reverts, clicking away commits. */
function startTabEditInput(el, tt, t, resume) {
  const inp = document.createElement('input');
  inp.type = 'text'; inp.className = 'title-input'; inp.value = resume ? resume.value : (t.title || 'Claude Code');
  tt.replaceWith(inp);
  let done = false;
  function finish(save) {
    if (done) return; done = true;
    const newTitle = inp.value.trim() || 'Claude Code';
    editingTabId = null;
    if (save && newTitle !== t.title) {
      t.title = newTitle; t.titled = true; t.userTitled = true;
      if (t.sessionId && window._renameSession) window._renameSession(t.sessionId, newTitle);
    }
    renderTabs();
  }
  // Both stopped, same as startHistoryRename: click so it never reaches the tab's own
  // onclick (switchTab), and mousedown so a drag inside the field selects TEXT instead of
  // being claimed by the tab strip's drag-to-reorder — the tab is already draggable=false
  // while editing, but stopping it here is what makes that independent of this one.
  inp.onclick = (e) => e.stopPropagation();
  inp.onmousedown = (e) => e.stopPropagation();
  inp.onblur = () => finish(true);
  inp.onkeydown = (e) => {
    e.stopPropagation();   // don't let Enter/Escape reach anything else while renaming
    if (e.key === 'Enter') { e.preventDefault(); finish(true); }
    else if (e.key === 'Escape') { e.preventDefault(); inp.onblur = null; finish(false); }
  };
  return inp;
}
function startTitleEdit(tabId) {
  const t = tabById(tabId); if (!t) return;
  if (editingTabId === tabId) return;   // already editing this tab → no-op
  editingTabId = tabId;
  renderTabs();
}
/* New conversation in the ACTIVE root — a new FOLDER is newRootDirectory(). */
function newSession() {
  if (setupGuideMode) return;   // nothing to start a session with (see cliversion.js)
  closeMenus(); createTab({ rootId: activeRootId }); input.focus();
}

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
  clearTab(t);
  input.focus();
}
/* The same for any tab, in front or not: the claudeCodeEclipse tool clears one by its id.
   A tab that is not in front is emptied without the render globals, the gerund timers or
   the composer — all three belong to whichever tab is being looked at, which may be in
   the middle of a turn of its own. */
function clearTab(t) {
  const front = t === activeTab();
  if (front) {
    loadRender(t);                 // operate on THIS tab's render state
    if (t.streaming) doCancel();
    hideWorking();
  } else if (t.streaming) {
    // What doCancel does, less its rendering: the pane it would write into is emptied below.
    t.cancelled = true;
    if (window._cancelRequest) window._cancelRequest(t.id);
    t.streaming = false;
  }
  if (rtab === t) { curTurn = null; curBody = null; curText = ''; curThink = null; curThinkText = ''; workingEl = null; }
  else t._r = null;
  // drop this tab's own pending card, if any — /clear replaces its conversation
  if (front) clearBottomCard(t); else t.pendingCard = null;
  // A conversation restored from the last Eclipse session and not shown yet would be
  // rebuilt into the emptied pane the first time the tab is shown (see switchTab).
  t._restore = null;
  // And one still being read or drawn is no longer waited on (history.js).
  t.opening = null; t.earlier = null; t.compactions = 0;
  // Drop the process so the next send starts a genuinely new conversation
  // (spawns without --resume) instead of continuing the one just cleared.
  if (window._disposeTab) window._disposeTab(t.id);
  t.sessionId = '';
  t.titled = false; t.userTitled = false;
  t.title = 'Claude Code';
  t.images = [];
  t.compacting = false;
  t.downgradeWarned = null;
  t.pane.innerHTML = '';
  // The bridge went with the process, so the new conversation starts Remote Control
  // afresh — off, or connecting again (which raises its own indicator) when Remote
  // Control on startup is set.
  if (typeof resetRemoteControlForNewConversation === 'function') resetRemoteControlForNewConversation(t);
  // The old transcript's scroll state means nothing against an emptied pane: left alone, a
  // scrolled-up scrollTop would reopen the fresh conversation scrolled into blank space
  // with the button showing, the next time this tab is switched to while the lock is on.
  // For the active tab the module global needs resetting alongside it.
  t.followTail = true; t.scrollTop = 0;
  if (front) {
    followTail = true;
    if (typeof updateJumpToLatest === 'function') updateJumpToLatest();
  }
  renderTabs();
  if (typeof renderPendingImages === 'function') renderPendingImages();
  if (typeof syncComposer === 'function') syncComposer();
  if (typeof updateAgentsBtn === 'function') updateAgentsBtn();   // pane emptied — no agents left in it
}

/* The CLI's own get_context_usage control request (cli_ask.rs) — the /context popup.
   Typing "/context" itself as a message gets no answer in this plugin's headless
   stream-json mode, but the request does. Every category, token count and percentage
   here is exactly what the CLI itself returns; nothing is computed locally. It is asked
   like any other dialog's data (clidialogs.js cliAsk), so it may have to start the tab's
   process, and an answer that never comes ends in an error instead of waiting forever. */
let contextOpenTab = '';
function openContextDialog() {
  closeMenus();
  const t = activeTab();
  if (!t) return;
  contextOpenTab = t.id;
  const win = document.getElementById('context-win');
  win.innerHTML =
    '<div class="aw-head"><span class="t">Context usage</span>' +
    '<span class="x" onclick="closeContext()">' + ICONS.X + '</span></div>' +
    '<div class="ew-body">Loading…</div>';
  document.getElementById('context-overlay').classList.add('open');
  document.addEventListener('keydown', contextKey, true);
  registerOverlayCancel(closeContext, false);
  const asked = t.id;
  cliAsk(t, 'get_context_usage').then(
    (r) => { if (contextOpenTab === asked) drawContextUsage(r, ''); },
    (e) => { if (contextOpenTab === asked) drawContextUsage(null, e && e.message); });
}
function contextKey(e) { if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); closeContext(); } }
function closeContext() {
  document.getElementById('context-overlay').classList.remove('open');
  document.removeEventListener('keydown', contextKey, true);
  unregisterOverlayCancel();
  contextOpenTab = '';
}
/** 27466 -> "27.5k", 939534 -> "939.5k", 210 -> "210", 1000000 -> "1.0M" — matches the
 *  real popup's abbreviated columns. */
function fmtCtxTokens(n) {
  n = Number(n) || 0;
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k';
  return String(n);
}
function fmtCtxPct(tokens, max) {
  if (!max) return '0%';
  const p = (Number(tokens) || 0) / max * 100;
  if (p > 0 && p < 0.1) return '<0.1%';
  return p.toFixed(1) + '%';
}
/* Keyed by category NAME, not the API's own opaque "color" field (e.g. "promptBorder",
   "purple_FOR_SUBAGENTS_ONLY") — those are the real extension's internal theme tokens,
   not hex values this page can reuse, so this picks its own from the existing palette
   instead. "Free space" is deliberately absent: it renders with no dot at all, below. */
const CTX_CATEGORY_COLOR = {
  'System prompt': 'var(--accent)',
  'System tools': 'var(--sel)',
  'Memory files': 'var(--green)',
  'Skills': 'var(--warn-fg)',
  'Messages': 'var(--hunk)',
  'Autocompact buffer': 'var(--red)',
};
/** Draws the popup. `r` is the CLI's own answer — {categories, totalTokens, maxTokens,
 *  percentage, memoryFiles, agents, …} — or null with `error` saying why there is none. */
function drawContextUsage(r, error) {
  const win = document.getElementById('context-win');
  if (!win) return;
  const head = '<div class="aw-head"><span class="t">Context usage</span>' +
    '<span class="x" onclick="closeContext()">' + ICONS.X + '</span></div>';
  if (!r || !Array.isArray(r.categories)) {
    const err = error || 'Could not read context usage for this conversation.';
    win.innerHTML = head + '<div class="ew-body">' + escapeHtml(err) + '</div>' +
      '<div class="ew-btn" onclick="closeContext()">Close</div>';
    return;
  }
  const max = r.maxTokens || 1;
  // "deferred" tools aren't a row in the real popup either — they're folded into
  // nothing visible, so this drops them rather than inventing a row for them.
  const cats = (r.categories || []).filter(c => c.kind !== 'deferred');
  const bar = cats.map(c => {
    const pct = Math.max((Number(c.tokens) || 0) / max * 100, 0);
    const color = c.kind === 'free' ? 'var(--track)' : (CTX_CATEGORY_COLOR[c.name] || 'var(--fg-dim)');
    return '<span class="ctx-seg" style="width:' + pct + '%;background:' + color + '"></span>';
  }).join('');
  const rows = cats.map(c => {
    const dot = c.kind === 'free' ? '<span></span>' :
      '<span class="ctx-dot" style="background:' + (CTX_CATEGORY_COLOR[c.name] || 'var(--fg-dim)') + '"></span>';
    return '<div class="ctx-row">' + dot +
      '<span class="ctx-name">' + escapeHtml(c.name) + '</span>' +
      '<span class="ctx-tokens">' + fmtCtxTokens(c.tokens) + '</span>' +
      '<span class="ctx-pct">' + fmtCtxPct(c.tokens, max) + '</span></div>';
  }).join('');
  const mem = (r.memoryFiles || []).map(f =>
    '<div class="ctx-mem-row"><span class="ctx-mem-path">' + escapeHtml(f.path || '') + '</span>' +
    '<span class="ctx-mem-tokens">' + fmtCtxTokens(f.tokens) + '</span></div>'
  ).join('');
  const agents = (r.agents || []).map(a =>
    '<div class="ctx-mem-row"><span class="ctx-mem-path">' + escapeHtml(a.agentType || '') + '</span>' +
    '<span class="ctx-mem-tokens">' + fmtCtxTokens(a.tokens) + '</span></div>'
  ).join('');
  win.innerHTML = head +
    '<div class="ctx-model">' + escapeHtml(r.model || '') + '</div>' +
    '<div class="ctx-total">' + fmtCtxTokens(r.totalTokens) + ' / ' + fmtCtxTokens(max) +
      ' tokens (' + (r.percentage != null ? r.percentage : 0) + '%)</div>' +
    '<div class="ctx-bar">' + bar + '</div>' +
    '<div class="ctx-table"><div class="ctx-head-row"><span></span><span>Category</span>' +
      '<span class="ctx-tokens">Tokens</span><span class="ctx-pct">Usage</span></div>' + rows + '</div>' +
    (mem ? '<div class="ctx-mem-head">Memory files</div><div class="ctx-mem-list">' + mem + '</div>' : '') +
    (agents ? '<div class="ctx-mem-head">Custom agents</div><div class="ctx-mem-list">' + agents + '</div>' : '');
}


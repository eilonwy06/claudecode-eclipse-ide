/* viewstate.js — the view's layout, remembered across Eclipse restarts.

   STORED: the working roots, each root's conversations (session id + title), which
   conversation was active, where each was scrolled, and whether the root row was
   collapsed. NOT stored: any conversation content — the transcript already lives in
   the CLI's own JSONL, and a conversation's model / effort / thinking / permission
   mode already round-trip through _loadSessionPrefs when it loads. This blob holds
   ids and flags only, so it stays small enough to sit in the workbench's own state.

   Java never parses it: ClaudeGuiView pushes the string into the view's IMemento and
   hands it back at boot (see ClaudeGuiView#viewStateJson), so the shape stays a JS
   concern. Everything read back is treated as optional — a blob written by an older
   build, or a half-written one, must never be able to stop the view from opening. */

const VIEW_STATE_VERSION = 1;
// Saving is off until boot finishes, so the empty state that exists mid-restore can
// never overwrite the state being restored FROM.
let viewStateReady = false;
let lastViewStateJson = '';

/** The current layout, as the object that gets serialized. */
function collectViewState() {
  const rootsOut = roots.map(r => {
    // Each root remembers its own last conversation (switchRoot returns to it). Stored
    // by session id, not tab id: tab ids are per-page-load and mean nothing next time.
    const at = (typeof tabById === 'function') ? tabById(r.activeTabId) : null;
    return { path: r.path, activeSession: at ? at.sessionId : '' };
  });
  const tabsOut = [];
  tabs.forEach(t => {
    // A conversation with no session id was never sent to — there is nothing on disk
    // to reopen, and restoring it would only add an empty tab the user didn't leave.
    if (!t.sessionId) return;
    const r = rootById(t.rootId);
    // #messages is one shared scroll container: the ACTIVE tab's position is live in
    // it, every other tab's was parked on the way out (see switchTab). A tab restored
    // but never since opened has neither — its pane is still empty and its parked
    // scrollTop is 0, so the position it was restored WITH is the one to carry
    // forward, or a restart without visiting that tab would forget where it was.
    let top = t.scrollTop || 0;
    if (t.id === activeId) top = messagesEl.scrollTop;
    else if (t._restore) top = t._restore.scrollTop || 0;
    tabsOut.push({
      root: r ? r.path : '',
      sessionId: t.sessionId,
      title: t.title || '',
      active: t.id === activeId,
      scrollTop: Math.round(top)
    });
  });
  return { v: VIEW_STATE_VERSION, roots: rootsOut,
           activeRoot: activeRoot() ? activeRoot().path : '',
           rowVisible: !!supertabsVisible,
           tabs: tabsOut };
}

/* Polled rather than hooked into createTab/closeTab/switchTab/setTabTitle/toggle-
   Supertabs/addRoot/closeRoot/the scroll listener/the init event that assigns a
   session id. Those are nine call sites in five files, and the one that gets missed
   is the one that silently stops persisting; a poll that diffs the serialized form
   cannot miss a path, and stringifying an object of a few dozen ids costs nothing at
   this interval. The cost of the poll is a lost few seconds on a hard crash. */
function saveViewStateIfChanged() {
  if (!viewStateReady || !window._saveViewState) return;
  let json = '';
  try { json = JSON.stringify(collectViewState()); } catch (e) { return; }
  if (json === lastViewStateJson) return;
  lastViewStateJson = json;
  try { window._saveViewState(json); } catch (e) {}
}

/**
 * Rebuilds the roots and conversations the view was last closed with.
 * @returns {boolean} true when at least one conversation was rebuilt — the caller
 *   then skips its own createTab(). False on a first run, a cleared state, or
 *   anything unreadable, all of which mean "open normally".
 */
function restoreViewState() {
  let st = null;
  try { st = JSON.parse((window._savedViewState && window._savedViewState()) || 'null'); } catch (e) {}
  if (!st || st.v !== VIEW_STATE_VERSION || !Array.isArray(st.tabs) || !st.tabs.length) return false;

  if (st.rowVisible === false) supertabsVisible = false;

  // initRoots has already made the workspace root. The rest are re-added by path, in
  // their stored order, with select:false so none of them opens a conversation of its
  // own — the stored tabs below are the only ones that should exist.
  (st.roots || []).forEach(r => {
    if (r && r.path && !rootByPath(r.path)) { try { addRoot(r.path, { select: false }); } catch (e) {} }
  });

  let wantActive = null;
  // Saved before its title was ever read from the session (a first turn still running, or
  // one that ended while another folder was in view): named once its folder is shown.
  const untitled = [];
  st.tabs.forEach(s => {
    if (!s || !s.sessionId) return;
    const r = rootByPath(s.root || '') || roots[0];
    if (!r) return;
    // Deferred on purpose: the transcript is rebuilt the first time the tab is shown,
    // not here. Rendering every conversation up front would make opening the workspace
    // cost one full transcript reconstruction per tab, for panes nobody is looking at.
    // Eclipse's closing may have cut its last turn off: once it is read back, the CLI is
    // asked to finish it (Continue After Reload). Only conversations restored here.
    const t = openStoredConversation(r.id, s.sessionId, s.title, s.scrollTop, true);
    if (s.active) wantActive = t.id;
    if (!s.title || s.title === 'Claude Code') untitled.push(t);
  });
  // Flagged only now: each createTab above switched tabs, and a flag set inside the loop
  // would have sent one list request per tab.
  untitled.forEach(t => { t.titleStale = true; });
  if (!tabs.length) return false;

  // Per-root last conversation, mapped back from session id to the tab just built.
  (st.roots || []).forEach(sr => {
    if (!sr || !sr.activeSession) return;
    const r = rootByPath(sr.path || '');
    if (!r) return;
    const t = tabs.find(tb => tb.rootId === r.id && tb.sessionId === sr.activeSession);
    if (t) r.activeTabId = t.id;
  });

  const ar = rootByPath(st.activeRoot || '');
  if (ar) activeRootId = ar.id;
  // Switching in is what renders the active conversation (see switchTab's _restore
  // branch) — this call is the one transcript rebuild startup pays for.
  switchTab(wantActive || tabs[tabs.length - 1].id);
  relabelRoots();
  renderSupertabs();
  return true;
}

/** Called once, at the end of the boot sequence. */
function startViewStatePersistence() {
  viewStateReady = true;
  try { lastViewStateJson = JSON.stringify(collectViewState()); } catch (e) {}
  setInterval(saveViewStateIfChanged, 2000);
}

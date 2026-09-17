/* history.js — Session history panel + past-conversation reconstruction (loadHistory),
   including compact markers and model-switch dividers. */

/* ===================== History (past conversations) ===================== */
function relTime(iso) {
  if (!iso) return '';
  const t = Date.parse(iso); if (isNaN(t)) return '';
  const s = Math.floor((Date.now() - t) / 1000);
  if (s < 60) return 'just now';
  if (s < 3600) return Math.floor(s / 60) + 'm ago';
  if (s < 86400) return Math.floor(s / 3600) + 'h ago';
  if (s < 604800) return Math.floor(s / 86400) + 'd ago';
  return new Date(t).toLocaleDateString();
}
/* Strip the editor-context preamble AND Claude Code's internal command/meta wrappers
   so loaded history shows the user's actual text — never raw <ide_selection>,
   <command-name>, <local-command-caveat>, <local-command-stdout>, … tags. */
function stripMeta(s) {
  if (!s) return '';
  return s
    // Every <ide_*> wrapper, not just the two we knew about: the CLI keeps adding
    // them (ide_opened_file arrived with 2.1.x and leaked whole paragraphs into
    // bubbles, the rewind list and the forked composer). Matching the family
    // means the next one can't leak either.
    .replace(/<(ide_[a-z_]*)\b[^>]*>[\s\S]*?<\/\1>/gi, '')
    .replace(/<ide_[a-z_]*\b[^>]*\/>/gi, '')
    .replace(/<local-command-caveat>[\s\S]*?<\/local-command-caveat>/gi, '')
    .replace(/<command-message>[\s\S]*?<\/command-message>/gi, '')
    .replace(/<command-args>[\s\S]*?<\/command-args>/gi, '')
    .replace(/<local-command-stdout>[\s\S]*?<\/local-command-stdout>/gi, '')
    .replace(/<command-stdout>[\s\S]*?<\/command-stdout>/gi, '')
    .replace(/<command-contents>[\s\S]*?<\/command-contents>/gi, '')
    .replace(/<system-reminder>[\s\S]*?<\/system-reminder>/gi, '')
    // keep the command itself (e.g. /usage) but drop the tag
    .replace(/<command-name>([\s\S]*?)<\/command-name>/gi, '$1')
    .trim();
}
function stripContext(s) { return stripMeta(s || ''); }
function parseUserContent(s) {
  if (!s) return { chip: null, text: '' };
  let chip = null;
  const m = s.match(/<ide_selection\b([^>]*)>[\s\S]*?<\/ide_selection>/i);
  if (m) {
    const f  = (m[1].match(/file="([^"]*)"/i) || [])[1];
    const sl = (m[1].match(/startLine="(\d+)"/i) || [])[1];
    const el = (m[1].match(/endLine="(\d+)"/i) || [])[1];
    if (f) { const base = f.split(/[\\/]/).pop(); chip = (sl && el) ? base + ':' + sl + '-' + el : base; }
  }
  const c = s.match(/<ide_context\b[^>]*openFile="([^"]*)"[^>]*\/>/i);
  if (c && !chip) chip = c[1].split(/[\\/]/).pop();
  return { chip, text: stripMeta(s) };
}

let histSessions = [], histLoading = false, histLoaded = false;
function setHistoryLoading(v) { histLoading = v; }   // list shows "Loading…"; button stays the clock

/* Search scope: 'title' (default) → 'own' (titles + the user's own messages) →
   'all' (titles + the full conversation, including Claude's replies) → back to
   'title'. Persisted across panel opens/restarts — the user's chosen search
   scope, not a session detail. */
const SEARCH_SCOPES = ['title', 'own', 'all'];
const SEARCH_SCOPE_LABEL = { title: 'Search: titles only', own: 'Search: titles + my messages', all: 'Search: titles + full conversation' };
let searchScope = 'title';
try {
  const saved = localStorage.getItem('claude.histSearchScope');
  if (SEARCH_SCOPES.includes(saved)) searchScope = saved;
} catch (e) {}
// Bumped on every content search kicked off; a result whose requestId doesn't match
// the current value is stale (the user kept typing) and is discarded on arrival.
let searchRequestId = 0;
let searchInFlight = false;
// sessionId -> snippet, for the content matches found by the CURRENT search only.
// Cleared at the start of each new search — never appended to across searches.
let contentMatches = {};

function cycleSearchScope(e) {
  // Without this, the click bubbles from the __SEARCH__-substituted <svg> the user
  // actually clicked — updateSearchScopeButton()'s innerHTML swap below detaches
  // that svg from the document before the event finishes bubbling, so ui.js's
  // document-level click-outside check sees a detached e.target, reads it as
  // "outside the panel", and closes History along with the scope change.
  if (e) e.stopPropagation();
  searchScope = SEARCH_SCOPES[(SEARCH_SCOPES.indexOf(searchScope) + 1) % SEARCH_SCOPES.length];
  try { localStorage.setItem('claude.histSearchScope', searchScope); } catch (err) {}
  updateSearchScopeButton();
  onHistorySearchInput();
}
const SEARCH_SCOPE_ICON = { title: 'SEARCH', own: 'SEARCHOWN', all: 'SEARCHALL' };
function updateSearchScopeButton() {
  const btn = document.getElementById('hist-search-scope');
  if (!btn) return;
  btn.classList.toggle('active', searchScope !== 'title');
  btn.title = SEARCH_SCOPE_LABEL[searchScope];
  btn.innerHTML = ICONS[SEARCH_SCOPE_ICON[searchScope]];
}

// Below this, a content scan is skipped — title-only filtering still applies (it's
// instant, from the already-cached list) but grepping every session's transcript for
// 1-2 characters is expensive for a query too short to be selective, matching the same
// gate on Find in Conversation (find.js's FIND_MIN_QUERY_LEN).
const CONTENT_SEARCH_MIN_QUERY_LEN = 3;

function runContentSearch(query) {
  const myId = ++searchRequestId;
  contentMatches = {};
  // Every early return here must re-render: the caller (onHistorySearchInput) already
  // rendered once with the OLD contentMatches before calling this, so skipping the
  // render below would leave phantom rows on screen (sessions that matched the
  // previous, longer query's content but neither the new query's title nor anything
  // else) until the user's next keystroke happened to trigger a real scan.
  if (!query || query.length < CONTENT_SEARCH_MIN_QUERY_LEN || searchScope === 'title' || !window._searchSessionContentAsync) {
    searchInFlight = false; updateSearchBusy(); renderHistoryList(); return;
  }
  // Only the sessions the title filter didn't already catch — a title match is
  // shown regardless, so there's no reason to also grep that session's body.
  const q = query.toLowerCase();
  const idsToScan = histSessions
    .filter(s => !(s.display || '').toLowerCase().includes(q))
    .map(s => s.sessionId);
  if (!idsToScan.length) { searchInFlight = false; updateSearchBusy(); renderHistoryList(); return; }
  searchInFlight = true;
  updateSearchBusy();
  window._searchSessionContentAsync(JSON.stringify(idsToScan), query, String(myId), searchScope === 'own');
}
window.onSessionSearchResult = function(json, requestId) {
  if (Number(requestId) !== searchRequestId) return;   // superseded by a later keystroke
  searchInFlight = false;
  updateSearchBusy();
  let matches = [];
  try { matches = JSON.parse(json || '[]'); } catch (e) {}
  matches.forEach(m => { contentMatches[m.sessionId] = m.snippet; });
  renderHistoryList();
};
function updateSearchBusy() {
  const btn = document.getElementById('hist-search-scope');
  if (btn) btn.classList.toggle('busy', searchInFlight);
}
/* Load the session list off the UI thread (the first call extracts the bundled
   PHP runtime + spawns php, which would otherwise freeze the click). */
function loadHistoryAsync() {
  if (histLoading) return;
  setHistoryLoading(true);
  renderHistoryList();   // show "Loading…" (or cached items if we have them) right away
  if (window._listSessionsAsync) { window._listSessionsAsync(); return; }
  // Fallback: old synchronous bridge.
  try { histSessions = JSON.parse(window._listSessions() || '[]'); } catch (e) { histSessions = []; }
  histLoaded = true; setHistoryLoading(false); renderHistoryList();
}
window.onHistoryLoaded = function(json) {
  try { histSessions = JSON.parse(json || '[]'); } catch (e) { histSessions = []; }
  histLoaded = true; setHistoryLoading(false); renderHistoryList();
  clampOpenMenu();   // the list may be a different width than "Loading…" — re-pin so it isn't cut off
};
// True while the history panel is open FOR /resume specifically — picking an item
// then loads it into the CURRENT tab (in place) instead of opening a new one. Set ONLY
// on openHistoryPanel's success path (never on the "already open → just close" path,
// where no panel ends up open at all) and consumed exactly once by loadHistory(),
// which resets it immediately — so it can never outlive a single open→pick cycle or
// leak into some later, unrelated opening of the same panel.
let historyResumeInPlace = false;

/* Shared panel-opening logic for both entry points below. Toggles: calling this while
 * already open just closes the panel (matches both callers' own "click it again to
 * close" expectations) rather than reopening/repositioning it.
 * @param {boolean} resumeInPlace this opening's historyResumeInPlace value — only takes
 *   effect if a panel actually ends up open (see historyResumeInPlace's own comment). */
function openHistoryPanel(resumeInPlace) {
  const panel = document.getElementById('history-panel');
  const wasOpen = panel.classList.contains('open');
  closeMenus();
  if (wasOpen) return false;
  historyResumeInPlace = resumeInPlace;
  histTab('local');
  const s = document.getElementById('hist-search'); if (s) s.value = '';
  // A fresh search each time the panel opens — no stale matches or in-flight
  // request from the last time it was open.
  searchRequestId++; searchInFlight = false; contentMatches = {};
  updateSearchBusy();
  updateSearchScopeButton();
  // Open the panel immediately; show cached results if we have them, otherwise a
  // "Loading…" state — and (re)load in the background either way.
  renderHistoryList();
  loadHistoryAsync();
  panel.classList.add('open');
  if (s) setTimeout(() => s.focus(), 0);
  // Same mechanism the advisor card / rewind dialog / model picker / lightbox use:
  // without this, the Java-side cancel-key context (Esc, or Ctrl+G under Emacs — see
  // plugin.xml's dismissCard binding) never activates for this panel, because nothing
  // on the Java side raised it. closeHistoryPanel is registered (not the generic
  // closeMenus) so ui.js's closeMenus() can tell, via identity, whether ITS registration
  // is still the live one before unregistering — a later overlay (e.g. an in-transcript
  // image's lightbox) can register after this panel closed-then-reopened in the same
  // event's bubble phase, and closeMenus() must not clobber that newer registration.
  registerOverlayCancel(closeHistoryPanel, false);
  return true;
}

/* The ONE place that closes history-panel specifically — every other close path (click
 * outside, opening a different menu, picking a session, the Java-bound cancel key) routes
 * through here or through closeMenus() (ui.js), which defers to this when it detects the
 * panel was open. Kept as its own function (not inlined into closeMenus) so that deferral
 * can check identity: activeCardCancel === closeHistoryPanel is how closeMenus knows the
 * registration it might unregister is still this panel's, not some other overlay's. */
function closeHistoryPanel() {
  const panel = document.getElementById('history-panel');
  if (panel) panel.classList.remove('open');
  if (openMenuEl === panel) { openMenuEl = null; openAnchor = null; }
  // By identity, not bare: cancelActiveCard (carddock.js) already pops this entry off
  // the stack itself before calling closeHistoryPanel as entry.fn() — a bare unregister
  // here would then pop whatever's now on TOP instead (e.g. the find bar, if it was
  // opened before History and is still up), silently stranding ITS own registration.
  // Confirmed by repro: Ctrl+F, then History, then two Ctrl+G presses closed History
  // then did nothing — the find bar's entry had already been eaten by this line.
  unregisterOverlayCancel(closeHistoryPanel);
}

/* Called from the native Eclipse view toolbar's "Session history" Action
 * (ClaudeGuiView#createToolBar → pushToolbarAction) — that button lives outside the
 * webview entirely, so there's no in-page anchor element to glue the panel to the
 * way an ordinary in-page button would (see positionMenuFixed's comment in ui.js).
 *
 * Toggles: clicking the toolbar button again closes the panel. This works cleanly
 * here (unlike an in-page trigger) because the click never reaches the page's own
 * document-level "close on click outside" listener at all — this function is the
 * ENTIRE reaction to that click, so wasOpen faithfully reflects the panel's state
 * from just before this call, with no risk of that other listener having already
 * closed it first.
 *
 * Picking an item here opens a NEW tab — matches the Claude Terminal view's own
 * Session History button, which always opens a new tab too (--resume is a launch
 * flag, its only option). See openHistoryForResume for the other entry point.
 */
window.openHistoryFromToolbar = function() {
  const panel = document.getElementById('history-panel');
  if (!openHistoryPanel(false)) return;
  positionMenuFixed(panel);
  openMenuEl = panel;   // openAnchor stays null — nothing in-page to re-anchor to
};

/* Called from the /resume composer slash command (slash.js) — this one deliberately
 * behaves like the CLI's OWN /resume typed at an existing Claude Terminal prompt:
 * picking a session swaps the CURRENT tab's conversation in place, not a new tab.
 * /resume is something you type INTO a specific conversation ("change what THIS is"),
 * unlike the toolbar button's generic "browse history" with no current-tab context —
 * the two are allowed to differ on purpose; see loadHistory's historyResumeInPlace
 * branch for where this actually takes effect.
 *
 * Positioned the SAME way as the toolbar's own opening (positionMenuFixed, top-right
 * of the viewport) rather than anchored to #slash-btn: that button sits in the
 * composer at the BOTTOM of the view, and positionMenu's below/right rules (hardcoded
 * per menu id, see its own comment) drop history-panel BELOW its anchor — for a
 * bottom-of-page trigger that means off the bottom edge, clamped back up into
 * overlapping the composer instead of rising above it like #modes-menu does. Where
 * the panel appears from doesn't need to encode which entry point opened it.
 */
window.openHistoryForResume = function() {
  const panel = document.getElementById('history-panel');
  if (!openHistoryPanel(true)) return;
  positionMenuFixed(panel);
  openMenuEl = panel;   // openAnchor stays null — nothing in-page to re-anchor to
};
/* Which tab the panel is showing. The search box is shared between them, so the
   input handler has to know which list a keystroke is meant to filter. */
let histActive = 'local';
function histTab(which) {
  const local = which === 'local';
  histActive = local ? 'local' : 'web';
  document.getElementById('hist-tab-local').classList.toggle('active', local);
  document.getElementById('hist-tab-web').classList.toggle('active', !local);
  document.getElementById('history-list').style.display = local ? '' : 'none';
  document.getElementById('history-web').style.display = local ? 'none' : '';
  // The search box stays on both tabs; only its scope cycler is local-only. Web
  // rows are titles and repo names with no transcript on this machine to grep,
  // so "titles + my messages" has nothing to widen to.
  const scope = document.getElementById('hist-search-scope');
  if (scope) scope.style.display = local ? '' : 'none';
  if (local) renderHistoryList();
  else loadWebHistoryAsync(false);
}
/* oninput handler for #hist-search: title matches render instantly from the
   already-cached list; a content search (if enabled) runs in the background and
   its matches get merged in via onSessionSearchResult as they arrive. */
function onHistorySearchInput() {
  if (histActive === 'web') { renderWebHistoryList(); return; }
  renderHistoryList();
  if (searchScope !== 'title') {
    const q = document.getElementById('hist-search').value;
    runContentSearch(q);
  }
}
function renderHistoryList() {
  const q = (document.getElementById('hist-search') ? document.getElementById('hist-search').value : '').toLowerCase();
  const list = document.getElementById('history-list');
  list.innerHTML = '';
  if (histLoading && !histLoaded) { list.innerHTML = '<div class="h-empty">Loading…</div>'; return; }
  const items = histSessions.filter(s =>
    (s.display || '').toLowerCase().includes(q) ||
    (searchScope !== 'title' && Object.prototype.hasOwnProperty.call(contentMatches, s.sessionId)));
  if (!items.length) {
    const empty = !histSessions.length ? 'No past conversations yet.' : (searchInFlight ? 'Searching…' : 'No matches.');
    list.innerHTML = '<div class="h-empty">' + empty + '</div>';
    return;
  }
  items.forEach(s => {
    const it = document.createElement('div'); it.className = 'item'; it.dataset.sid = s.sessionId;
    const main = document.createElement('div'); main.className = 'h-main';
    const title = document.createElement('div'); title.className = 'h-title'; title.textContent = stripContext(s.display) || '(untitled)';
    const time = document.createElement('div'); time.className = 'h-time'; time.textContent = relTime(s.timestamp);
    main.appendChild(title); main.appendChild(time);
    const titleMatched = (s.display || '').toLowerCase().includes(q);
    if (q && !titleMatched && contentMatches[s.sessionId]) {
      const snippet = document.createElement('div'); snippet.className = 'h-snippet';
      snippet.textContent = contentMatches[s.sessionId];
      main.appendChild(snippet);
    }
    const actions = document.createElement('div'); actions.className = 'h-actions';
    const rename = document.createElement('span'); rename.className = 'h-action h-rename'; rename.title = 'Rename';
    rename.innerHTML = ICONS.PENCIL;
    rename.onclick = (e) => { e.stopPropagation(); startHistoryRename(it, s); };
    const del = document.createElement('span'); del.className = 'h-action h-del'; del.title = 'Delete';
    del.innerHTML = ICONS.TRASH;
    del.onclick = (e) => { e.stopPropagation(); deleteHistory(s.sessionId); };
    actions.appendChild(rename); actions.appendChild(del);
    it.appendChild(main); it.appendChild(actions);
    it.onclick = () => loadHistory(s.sessionId, s.display);
    list.appendChild(it);
  });
}
function startHistoryRename(itemEl, session) {
  const main = itemEl.querySelector('.h-main');
  const titleEl = itemEl.querySelector('.h-title');
  const timeEl = itemEl.querySelector('.h-time');
  const actions = itemEl.querySelector('.h-actions');
  if (!main || !titleEl) return;
  const curTitle = stripContext(session.display) || '(untitled)';
  titleEl.style.display = 'none';
  if (timeEl) timeEl.style.display = 'none';
  if (actions) actions.style.display = 'none';
  const inp = document.createElement('input');
  inp.type = 'text'; inp.className = 'h-rename-input'; inp.value = curTitle;
  // While editing, clicking the field must behave like a normal input — never
  // bubble up to the item's onclick (which would open the session) nor blur it.
  inp.onclick = (e) => e.stopPropagation();
  inp.onmousedown = (e) => e.stopPropagation();
  main.appendChild(inp);
  inp.focus(); inp.select();
  function finish(save) {
    const newTitle = inp.value.trim() || '(untitled)';
    inp.remove();
    titleEl.style.display = '';
    if (timeEl) timeEl.style.display = '';
    if (actions) actions.style.display = '';
    if (save && newTitle !== curTitle) {
      session.display = newTitle;
      titleEl.textContent = newTitle;
      if (window._renameSession) window._renameSession(session.sessionId, newTitle);
      const t = tabs.find(tab => tab.sessionId === session.sessionId);
      if (t) { t.title = newTitle; renderTabs(); }
    }
  }
  inp.onblur = () => finish(true);
  inp.onkeydown = (e) => {
    e.stopPropagation();
    if (e.key === 'Enter') { e.preventDefault(); finish(true); }
    else if (e.key === 'Escape') { e.preventDefault(); inp.onblur = null; finish(false); }
  };
}
function deleteHistory(id) {
  try { if (window._deleteSession) window._deleteSession(id); } catch (e) {}
  histSessions = histSessions.filter(s => s.sessionId !== id);
  renderHistoryList();
  // If this session is open in a tab, close that tab. closeTab() replaces the
  // last remaining tab with a fresh blank session, so deleting the only open
  // conversation just clears the view (VSCode behaviour).
  const open = tabs.find(t => t.sessionId === id);
  if (open) closeTab(open.id);
}
/* static (non-streaming) reconstruction helpers for loaded history */
function appendThinkStatic(turn, text) {
  const el = document.createElement('div'); el.className = 'a-item think muted';
  // no saved duration → just "Thinking"; if the transcript kept the reasoning
  // text, make it revealable via the chevron just like a live turn.
  el.innerHTML = '<span class="dot gray"></span><span class="think-head"><span class="think-label">Thinking</span>'
    + '<span class="chev">' + ICONS.CHEVRON + '</span></span><div class="think-body"></div>';
  if (text && text.trim()) {
    el.querySelector('.think-body').textContent = text;
    el.classList.add('has-body');
    el.querySelector('.think-head').onclick = () => el.classList.toggle('open');
  }
  turn.appendChild(el);
}
function appendTextStatic(turn, text) {
  if (!text || !text.trim()) return;
  const el = document.createElement('div'); el.className = 'a-item';
  el.innerHTML = '<span class="dot"></span><span class="a-body"></span>';
  el.querySelector('.a-body').innerHTML = renderMarkdown(text);
  turn.appendChild(el);
}
/**
 * @param {Tab} [targetTab] render INTO this existing tab instead of picking one. Used
 *   by the restore path (viewstate.js), which has already built the tab and only needs
 *   its transcript rebuilt — so neither the "already open" dedupe nor the two entry
 *   point behaviours below apply to it.
 */
function loadHistory(id, title, targetTab) {
  closeMenus();
  // Read-and-reset IMMEDIATELY: historyResumeInPlace must never outlive this one
  // open→pick cycle. Past this line the module flag is back to its default, so any
  // later, unrelated opening/pick of this same panel can't be affected by whichever
  // entry point was used here.
  const resumeInPlace = historyResumeInPlace;
  historyResumeInPlace = false;
  // Already open → never a second instance of the same conversation. In another tab,
  // switch to it; in the tab you are already on, do nothing at all (the panel has
  // closed above, which is the whole of the interaction). Excluding the active tab
  // here used to fall through to the createTab branch below — reuseCurrent is false
  // for a tab already holding a real session — so picking the session you were
  // looking at duplicated it into a new tab. Applies to BOTH entry points below.
  if (!targetTab) {
    const already = tabs.find(tb => tb.sessionId === id);
    if (already) { if (already.id !== activeId) switchTab(already.id); return; }
  }
  let items = [];
  try { items = JSON.parse(window._loadSession(id) || '[]'); } catch (e) {}

  // Two entry points, two behaviors (resumeInPlace, read above from historyResumeInPlace
  // — set by whichever openHistory* function opened the panel, see window.openHistory*):
  //
  //  - Toolbar's Session History button → opens a NEW tab, matching the Claude
  //    Terminal view's own History button (--resume is a launch flag, its only
  //    option there). Avoids the old "replace the current tab" behavior's real cost:
  //    it silently discarded an in-progress conversation on that tab, no undo.
  //    EXCEPTION: if the current tab is already empty (isTabEmpty — no session, no
  //    stream, nothing typed or attached), there is nothing an "in-progress
  //    conversation" cost could apply to, so reuse it instead of leaving a blank tab
  //    behind — same reuse the resumeInPlace branch below does, just reached by a
  //    different condition.
  //
  //  - /resume typed in the composer → loads IN PLACE on the CURRENT tab, matching
  //    the CLI's own /resume typed at an existing Claude Terminal prompt: it swaps
  //    THAT session in place too, no new tab. /resume is something you type INTO a
  //    specific conversation ("change what THIS is"), unlike the toolbar's generic
  //    "browse history" with no such context — the two are allowed to differ.
  const reuseCurrent = resumeInPlace || isTabEmpty(activeTab());
  let t;
  if (targetTab) {
    t = targetTab;
    loadRender(t);                        // operate on THIS tab's render state
    // Restore only ever targets a tab built moments ago from the stored session id:
    // no stream to cancel, no live content to clear.
  } else if (reuseCurrent) {
    t = activeTab(); if (!t) return;
    loadRender(t);                        // operate on THIS tab's render state
    // An in-flight stream on the tab being overwritten must stop NOW, or its output
    // keeps landing in a pane that no longer represents that conversation — unlike
    // the new-tab path, this tab is NOT untouched, its live content is about to be
    // replaced out from under it. (No-op for the isTabEmpty case: that condition
    // already excludes a streaming tab.)
    if (t.streaming) doCancel();
    hideWorking();
    curTurn = null; curBody = null; curText = ''; curThink = null; curThinkText = '';
  } else {
    t = createTab({ sessionId: id, titled: true });
    loadRender(t);                        // operate on THIS tab's render state
    // t is brand new (no stream, no render state) — nothing here to cancel or clear.
  }
  const pane = t.pane;
  pane.innerHTML = '';                  // clear old content (or createTab()'s WELCOME_HTML)
  t.sessionId = id;                     // continuing this tab resumes the session
  setTabTitle(t, title);
  if (!items.length) { addSystem('This conversation is empty or could not be loaded.'); }

  // Group consecutive assistant blocks (thinking / tool / text) into one turn with
  // its dotted rail; user messages and answer cards are their own turns.
  let aTurn = null;
  let lastModel = '';    // the model this conversation last used (resume with it)
  let sawThinking = false; // any thinking block ⇒ this conversation had thinking ON
  let renderedModel = null; // model in effect while reconstructing → switch dividers
  function assistantTurn() {
    if (!aTurn || !aTurn.parentNode) { aTurn = document.createElement('div'); aTurn.className = 'turn'; pane.appendChild(aTurn); }
    return aTurn;
  }
  // The model a given turn ran on (the next assistant model before the next user msg).
  function turnModelAt(idx) {
    for (let j = idx + 1; j < items.length; j++) {
      const jt = items[j].t || (items[j].role === 'user' ? 'user' : 'text');
      if (jt === 'user') break;
      if (typeof items[j].model === 'string' && items[j].model.indexOf('claude-') === 0) return items[j].model;
    }
    return '';
  }
  // Compaction markers: the transcript stores boundary + summary BEFORE the
  // "/compact" command echo, but live rendering showed the bubble first — hold the
  // "Compacted chat" line and flush it after that bubble (or before whatever
  // renders next, e.g. after an auto-compact) so a reload reads like the live run.
  let pendingCompact = null;   // { trigger, freed, text }
  function flushCompact() {
    if (!pendingCompact) return;
    addCompacted(pane, pendingCompact.trigger, pendingCompact.freed, pendingCompact.text);
    pendingCompact = null;
  }
  items.forEach((it, i) => {
    const ty = it.t || (it.role === 'user' ? 'user' : 'text');   // back-compat with old text-only format
    // Only real model ids — skip "<synthetic>" (CLI-injected messages) and blanks.
    if (typeof it.model === 'string' && it.model.indexOf('claude-') === 0) lastModel = it.model;
    if (ty === 'thinking') sawThinking = true;
    if (ty === 'compact') {
      aTurn = null;
      pendingCompact = { trigger: it.trigger || 'manual',
        freed: Math.max(0, (it.preTokens || 0) - (it.postTokens || 0)), text: '' };
    } else if (ty === 'teleported') {
      // The boundary between the conversation as it arrived from claude.ai and
      // whatever was said here afterwards.
      aTurn = null;
      pane.appendChild(makeTeleportDivider());
    } else if (ty === 'compact_summary') {
      if (pendingCompact) pendingCompact.text = it.text || '';
      else { aTurn = null; addCompacted(pane, 'manual', 0, it.text || ''); }
    } else if (ty === 'user') {
      // Reconstruct "Switched to <model>" dividers from the transcript (the model
      // is recorded per turn) so past model switches persist across reloads.
      const tm = turnModelAt(i);
      if (tm) { if (renderedModel !== null && tm !== renderedModel) pane.appendChild(makeSwitchDivider(tm)); renderedModel = tm; }
      aTurn = null;
      const p = parseUserContent(it.content || '');
      const isCompactCmd = p.text.trim() === '/compact';
      // A line that paints nothing (e.g. the <local-command-caveat> the CLI
      // inserts between the summary and the "/compact" echo) can't be the
      // bubble the pending compact marker is waiting to render after.
      const imgs = (it.images || []).map(imageFromBlock)
        .concat((it.documents || []).map(d => documentFromBlock(d, it.id, id))).filter(Boolean);
      const invisible = !p.text && !p.chip && !imgs.length;
      if (!isCompactCmd && !invisible) flushCompact();
      // Bracketed markers the CLI writes as user lines are not messages anyone
      // sent. Each pattern must match the WHOLE text: a real message that merely
      // QUOTES a marker ("[Request interrupted by user for tool use] still
      // appears as a bubble") has to stay a normal bubble, or the user's words
      // get thrown away. The trailing [^\]]* still absorbs suffix variants.
      const marker = p.text.trim();
      // An interruption renders live as the italic muted note (two variants,
      // matching the two labels doCancel picks between) — a reload shows the same.
      if (/^\[Request interrupted by user[^\]]*\]$/.test(marker)) {
        addInterrupted(/for tool use/i.test(marker) ? 'Tool interrupted' : 'Interrupted');
        return;
      }
      // Image-scaling note the CLI injects beside an upload ("[Image: original
      // 2352x4160, displayed at …]"). Internal metadata with no image block of
      // its own — nothing to show, so it renders nothing at all.
      if (/^\[Image:[^\]]*\]$/.test(marker)) return;
      // Messages sent with pasted images carry them as {media_type,data} blocks —
      // rebuild the same chips the live bubble showed.
      if (!invisible) addUserMessage(p.text, p.chip, imgs, it.id, it.ts);
      if (isCompactCmd) flushCompact();
    } else if (ty === 'answered') {
      flushCompact();
      aTurn = null;
      addAnswered(it.text || '', pane);
    } else if (ty === 'error') {
      // A backend error (rate limit, 529 overload, …). Live it is the muted
      // "⚠ …" line onError paints — a reload rebuilds exactly that, never an
      // assistant paragraph, so a past session reads the way it ran.
      flushCompact();
      aTurn = null;
      const em = it.text || '';
      addSystemToPane(pane, '⚠ ' + (typeof augmentError === 'function' ? augmentError(em) : em));
    } else if (ty === 'thinking') {
      flushCompact();
      appendThinkStatic(assistantTurn(), it.text || '');
    } else if (ty === 'tool') {
      flushCompact();
      // A reconstructed Agent/Task call's own nested log (session.rs's agentLog field) —
      // the disk-backed counterpart of chat.js's live agentLogs, so a reopened
      // conversation's /agents popup (duration, tokens, model, Prompt, Tool calls, "Open
      // transcript") works the same as it did live instead of losing it the moment the
      // webview that ran it live is gone. Populated BEFORE makeToolLine so its isAgent
      // branch can tell there's actually something to show and add the collapsible.
      const isAgentTool = AGENT_KEYS.has(String(it.name || '').toLowerCase());
      const hasLog = isAgentTool && it.agentLog && Array.isArray(it.agentLog.items) && it.agentLog.items.length > 0;
      if (hasLog && it.id) {
        const log = ensureAgentLog(it.id);
        log.items = it.agentLog.items.map(x => Object.assign({}, x));   // fresh copies — no stale _el refs
        log.tokens = it.agentLog.tokens || 0;
        log.model = it.agentLog.model || '';
        log.startedAt = it.agentLog.startedAt ? Date.parse(it.agentLog.startedAt) || 0 : 0;
        log.endedAt = it.agentLog.endedAt ? Date.parse(it.agentLog.endedAt) || 0 : 0;
        log.input = it.input || {};
      }
      const line = makeToolLine(it.name || 'tool', it.input || {}, it.status, it.errorText, rootPathOf(t), it.resultText, hasLog);
      if (it.id) line.dataset.tuid = it.id;
      if (hasLog) {
        // Built directly from the line just created rather than through
        // renderAgentLogItem's document.querySelector lookup: this turn isn't attached to
        // the DOM yet (the whole pane is built off-screen, see flushCompact/assistantTurn
        // above), so that lookup would find nothing.
        const body = line.querySelector(':scope > .agent-log > .agent-log-body');
        if (body) agentLogs.get(it.id).items.forEach(item => body.appendChild(buildAgentLogItemEl(item)));
      }
      assistantTurn().appendChild(line);
    } else { // text
      flushCompact();
      appendTextStatic(assistantTurn(), it.text || it.content || '');
    }
  });
  flushCompact();
  // draw the connector rails
  pane.querySelectorAll(':scope > .turn').forEach(relinkTurn);
  // This reconstruction may be for a BACKGROUND tab (not the one on screen) — only
  // refresh the toolbar pill when it's the one actually showing right now.
  if (t === activeTab() && typeof updateAgentsBtn === 'function') updateAgentsBtn();
  // Restore this conversation's settings. Our own sidecar (saved per session id)
  // is authoritative — it's the ONLY source of effort and it captures the user's
  // last selection; the transcript is the fallback for model + thinking.
  let saved = {};
  try { saved = JSON.parse(window._loadSessionPrefs ? window._loadSessionPrefs(id) : '{}') || {}; } catch (e) {}
  // What the sidecar actually returned for the id History handed us. Debug mode only.
  // Read next to the [PREFS-SAVE] lines: a save of defaults appearing just ABOVE this
  // one, under the same id, is the issue #114 signature.
  try {
    if (window.__ccDebug && window._debugLog)
      _debugLog('[PREFS-LOAD] sid=' + String(id).slice(0, 8) + ' -> ' + JSON.stringify(saved)
        + ' (tab=' + t.id + ' active=' + (t === activeTab()) + ')');
  } catch (e) {}

  // Write the restored values into the TAB first, then paint the composer from the
  // tab via applyTabSettings. Doing it the other way round (assigning the module
  // globals directly) only worked while loadHistory was guaranteed to be rendering
  // the active tab — the targetTab branch above (viewstate's deferred restore) can
  // rebuild a BACKGROUND tab, and createTab() -> switchTab() -> applyTabSettings()
  // has already painted this tab's DEFAULTS by the time we get here. Storing first
  // makes the tab the single source of truth for both cases.
  let think = sawThinking;
  if (saved.thinking === '1') think = true; else if (saved.thinking === '0') think = false;
  t.thinking = think;

  const model = saved.model || lastModel;
  if (model) t.model = model;

  if (saved.effort !== undefined && saved.effort !== '') {
    const ei = parseInt(saved.effort, 10);
    if (!isNaN(ei)) t.effortIdx = ei;
  }
  // Permission mode is a launch flag the transcript never records, so the sidecar
  // is the only source. Entries saved before permMode existed fall back to default.
  t.permMode = saved.permMode || DEFAULT_PERM_MODE;

  // One chokepoint for the composer + status bar, and it already sequences thinking
  // before effort (the effort cap depends on the thinking flag) and reconciles an
  // illegal stored pair through enforceThinkingGate. Only the visible tab paints;
  // a background tab keeps its values and paints when the user switches to it —
  // which calls this very function.
  if (t === activeTab()) applyTabSettings(t);
  // The pane was emptied above, and a Remote Control bridge coming up in this tab
  // had its indicator in it. Both of the ways a conversation gets reconstructed run
  // through here AFTER the tab was switched on: reopening from history (createTab
  // enables it, this then clears the pane) and a tab restored from the last Eclipse
  // session (enabled at startup, rendered lazily on the switch that first shows it).
  // No-op unless the tab is genuinely still connecting.
  if (typeof showWorkingFor === 'function') showWorkingFor(t);
  pane.scrollTop = 0;
  // #messages is shared by every pane, so only move it when the tab just rebuilt is
  // the visible one — a restore rendering a background tab must not yank the view.
  if (t === activeTab()) messagesEl.scrollTop = 0;
}


/* ===================== History → Web tab (claude.ai sessions) =====================

   The conversations this account has on claude.ai — including ones started on
   another machine or from the phone — as the CLI's own History shows them under
   "Web".

   The fetch itself lives in the Rust core (web_history.rs), not here and not in
   Java: the OAuth token is read there, spent on one request and wiped, so all
   that ever reaches this page is {id, title, status, repo, timestamp}. Nothing
   on this side can leak a credential, because nothing on this side has one.

   Independent of Remote Control — this is a plain REST list, no bridge involved. */

let webSessions = [], webState = '', webMessage = '', webLoading = false, webLoaded = false;

/* Asks Java for the list on every tab switch and lets the Rust side decide whether
   that means a real fetch or its own cached copy — one freshness policy, in one
   place, instead of a second timer here that could disagree with it.
   @param force skip that freshness window and re-fetch now. */
function loadWebHistoryAsync(force) {
  if (webLoading) { renderWebHistoryList(); return; }
  if (!window._listWebSessionsAsync) {
    // No bridge (an old host, or the page opened outside Eclipse): say so rather
    // than spinning on a request that will never be answered.
    webLoading = false; webLoaded = true; webState = 'error'; webMessage = '';
    renderWebHistoryList();
    return;
  }
  webLoading = true;
  // Returns whatever was cached — possibly from a previous Eclipse run, since the
  // cache survives restarts — so the tab paints now instead of after a round trip.
  // onWebHistoryLoaded replaces it when the fetch lands.
  applyWebPayload(window._listWebSessionsAsync(!!force), false);
  renderWebHistoryList();
}

window.onWebHistoryLoaded = function(json) {
  webLoading = false;
  applyWebPayload(json, true);
  renderWebHistoryList();
  clampOpenMenu();   // rows may be wider than "Loading…" — re-pin so they aren't cut off
};

/* @param settle true for the fetched result, which settles the tab's state; false
   for the optimistic cached paint, which must NOT mark it loaded or let an empty
   cache overwrite what's on screen. */
function applyWebPayload(json, settle) {
  if (settle) webLoaded = true;
  if (!json) return;
  let p = null;
  try { p = JSON.parse(json); } catch (e) { p = null; }
  if (!p) {
    if (settle) { webSessions = []; webState = 'error'; webMessage = ''; }
    return;
  }
  webSessions = Array.isArray(p.sessions) ? p.sessions : [];
  webState = p.state || '';
  webMessage = p.message || '';
}

function webEmpty(text) {
  const d = document.createElement('div');
  d.className = 'h-empty';
  d.textContent = text;
  return d;
}

function renderWebHistoryList() {
  const q = (document.getElementById('hist-search') ? document.getElementById('hist-search').value : '').toLowerCase();
  const el = document.getElementById('history-web');
  el.innerHTML = '';
  // Anything already on hand outranks the spinner — a cached list from the last
  // run is more useful than "Loading…" while the fetch confirms it.
  if (webLoading && !webLoaded && !webSessions.length) { el.appendChild(webEmpty('Loading…')); return; }
  if (webState === 'signed-out') { el.appendChild(webEmpty('Sign in to Claude Code to see your web sessions.')); return; }
  if (webState === 'expired')    { el.appendChild(webEmpty('Your login expired. Sign in again to see your web sessions.')); return; }
  if (webState === 'error' && !webSessions.length) {
    el.appendChild(webEmpty(webMessage ? 'Couldn\u2019t load web sessions \u2014 ' + webMessage + '.'
                                       : 'Couldn\u2019t load web sessions.'));
    return;
  }
  const items = webSessions.filter(s => (s.title || '').toLowerCase().includes(q)
                                     || (s.repo || '').toLowerCase().includes(q));
  if (!items.length) {
    el.appendChild(webEmpty(!webSessions.length ? 'No web sessions yet.' : 'No matches.'));
    return;
  }
  items.forEach(s => {
    const it = document.createElement('div'); it.className = 'item'; it.dataset.sid = s.id;
    // Only the two statuses that mean something is still happening get a dot;
    // idle, completed and archived sessions show none.
    if (s.status === 'working' || s.status === 'waiting') {
      const dot = document.createElement('span');
      dot.className = 'h-dot ' + s.status;
      dot.title = s.status === 'working' ? 'Working' : 'Waiting for a reply';
      it.appendChild(dot);
    }
    const main = document.createElement('div'); main.className = 'h-main';
    const title = document.createElement('div'); title.className = 'h-title';
    title.textContent = s.title || '(untitled)';
    const time = document.createElement('div'); time.className = 'h-time';
    const age = relTime(s.timestamp);
    time.textContent = s.repo ? (age ? s.repo + ' \u00b7 ' + age : s.repo) : age;
    main.appendChild(title); main.appendChild(time);
    it.appendChild(main);
    // Clicking continues the conversation HERE (teleport.js); the arrow beside
    // it is the way out to the browser. Separated because they are different
    // intentions, and the one you reach for by default should be the one that
    // keeps you in the editor.
    it.title = 'Continue this conversation here';
    it.onclick = () => { closeHistoryPanel(); startTeleport(s); };
    const open = document.createElement('span');
    open.className = 'h-action h-open';
    open.title = 'Open on claude.ai';
    open.innerHTML = ICONS.EXTERNAL || ICONS.GLOBE;
    open.onclick = (e) => { e.stopPropagation(); openWebSession(s.id); };
    const actions = document.createElement('div');
    actions.className = 'h-actions';
    actions.appendChild(open);
    it.appendChild(actions);
    el.appendChild(it);
  });
}

/* Opens the conversation on claude.ai in the system browser — what the CLI's own
   Remote Control link does, and the one thing we can do with a web session that
   needs nothing but its id. Continuing one inside Eclipse means pulling its
   transcript and reconciling the repo it was created in; that's its own piece of
   work, not a shortcut off this click. */
function openWebSession(id) {
  if (!id) return;
  // The API hands back `cse_<ulid>`, but claude.ai addresses the very same
  // session as `session_<ulid>`: two namespaces over one id. The CLI converts by
  // SWAPPING the prefix, never by adding one -- claude.exe 2.1.251 carries the
  // pair verbatim, `"session_"+e.slice(4)` one way and `"cse_"+e.slice(8)` back.
  // Prefixing a cse_ id instead of replacing it yields session_cse_<ulid>, and
  // claude.ai answers that with "The session could not be found".
  const slug = 'session_' + String(id).replace(/^(?:session|cse)_/, '');
  if (window._openExternal) _openExternal('https://claude.ai/code/' + slug);
  closeHistoryPanel();
}

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
  // The form sent since the context became its own block (the two above are what
  // older transcripts hold).
  const sel = s.match(/<ide_selection>The user selected the lines (\d+) to (\d+) from ([^\n]+):\n/);
  // Only this form gives the pill something to open (`target`); the older two stay a label.
  let target = null;
  if (sel && !chip) {
    chip = sel[3].split(/[\\/]/).pop() + ':' + sel[1] + '-' + sel[2];
    target = { file: sel[3], startLine: +sel[1], endLine: +sel[2] };
  }
  const opened = s.match(/<ide_opened_file>The user opened the file (.+?) in the IDE\./);
  if (opened && !chip) { chip = opened[1].split(/[\\/]/).pop(); target = { file: opened[1] }; }
  return { chip, target, text: stripMeta(s) };
}

let histSessions = [], histLoading = false, histLoaded = false;
function setHistoryLoading(v) { histLoading = v; }   // list shows "Loading…"; button stays the clock
/* Every request for the list is numbered, and the view hands the number back with its
   answer. Two things follow. An answer that a later one has overtaken is dropped: scans
   run side by side (a tab asks for the list too, to learn its title) and finish in any
   order. And the open panel says "Loading…" until the answer to its OWN request, or to a
   later one, is in — a list asked for before it was opened is not the list as it is now,
   and showing the one kept from last time let a row be picked that was hours out of date. */
let histAsked = 0;       // the number of the last request
let histShown = 0;       // the request the list in hand answers
let histAwaited = 0;     // the request the panel is waiting on
let histAskedAt = 0;     // when the panel asked, so a request that is never answered is not waited on forever
const HIST_ANSWER_WAIT_MS = 60000;
/** Asks the view for the session list. `openIdsJson` — the conversations open in tabs —
 *  is the panel's alone: naming them is what asks for the inactive ones to be archived.
 *  @returns {number} the request's number, 0 when there is no view to ask */
function requestSessionList(openIdsJson) {
  if (!window._listSessionsAsync) return 0;
  histAsked++;
  window._listSessionsAsync(openIdsJson === undefined ? null : openIdsJson, histAsked);
  return histAsked;
}
/* Whether the view behind the page keeps an archive at all (a native library from
   before it does not). Without one nothing is grouped and no row offers to archive. */
let histArchiveOn = false;
/* The "Archived sessions" group starts closed and is left as the user last had it. */
let histArchivedCollapsed = true;
try { if (localStorage.getItem('claude.histArchivedCollapsed') === '0') histArchivedCollapsed = false; } catch (e) {}
/* The conversations open in a tab, whichever folder they are under: what the archive
   never files away on its own. A restored tab not shown yet counts as open too. */
function openSessionIds() { return tabs.map(t => t.sessionId).filter(Boolean); }

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
  // One request of the panel's at a time — unless the last has gone a minute unanswered.
  if (histLoading && Date.now() - histAskedAt < HIST_ANSWER_WAIT_MS) return;
  setHistoryLoading(true);
  renderHistoryList();   // "Loading…", never the list kept from the last opening
  // Naming the open conversations is what asks for the inactive ones to be archived:
  // only this, the panel's own request, does (a tab asking for its title does not).
  if (window._listSessionsAsync) {
    histAskedAt = Date.now();
    histAwaited = requestSessionList(JSON.stringify(openSessionIds()));
    return;
  }
  // Fallback: old synchronous bridge.
  try { histSessions = JSON.parse(window._listSessions() || '[]'); } catch (e) { histSessions = []; }
  histLoaded = true; setHistoryLoading(false); renderHistoryList();
}
/** @param {number} [asked] the number of the request this answers (none from a view
 *    that does not hand it back: such an answer is taken as it comes) */
window.onHistoryLoaded = function(json, archiveOn, asked) {
  asked = Number(asked) || 0;
  if (asked && asked < histShown) return;   // overtaken by the answer to a later request
  if (asked) histShown = asked;
  try { histSessions = JSON.parse(json || '[]'); } catch (e) { histSessions = []; }
  histArchiveOn = archiveOn === true;
  histLoaded = true;
  // What the panel waits for is the list as of its opening: its own answer, or a later one.
  if (!asked || asked >= histAwaited) { histAwaited = 0; setHistoryLoading(false); }
  renderHistoryList();
  syncTabTitles(histSessions);
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
  // Open the panel immediately on "Loading…" and ask for the list as it is now. The core
  // reads only the two ends of each transcript (session.rs, summarize), so the answer
  // is a moment away — which is what lets the panel wait for it every time.
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
  if (setupGuideMode) return;   // no sessions to resume without claude (see cliversion.js)
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
  if (histLoading) { list.innerHTML = '<div class="h-empty">Loading…</div>'; return; }
  const items = histSessions.filter(s =>
    (s.display || '').toLowerCase().includes(q) ||
    (searchScope !== 'title' && Object.prototype.hasOwnProperty.call(contentMatches, s.sessionId)));
  if (!items.length) {
    const empty = !histSessions.length ? 'No past conversations yet.' : (searchInFlight ? 'Searching…' : 'No matches.');
    list.innerHTML = '<div class="h-empty">' + empty + '</div>';
    return;
  }
  const archived = items.filter(s => s.archived);
  items.filter(s => !s.archived).forEach(s => list.appendChild(historyRow(s, q)));
  if (!archived.length) return;
  // While a search is typed the group is open and cannot be closed, so a match inside
  // it is never hidden behind it.
  const collapsed = !q && histArchivedCollapsed;
  list.appendChild(archivedGroupRow(archived.length, collapsed, !q));
  if (!collapsed) archived.forEach(s => list.appendChild(historyRow(s, q)));
}
/* One conversation's row. Hovered, it offers Rename and — where there is an archive —
   Archive session, or Unarchive session on a row already in the archive. */
function historyRow(s, q) {
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
  actions.appendChild(rename);
  if (histArchiveOn) {
    const move = document.createElement('span');
    move.className = 'h-action ' + (s.archived ? 'h-unarchive' : 'h-archive');
    move.title = s.archived ? 'Unarchive session' : 'Archive session';
    move.innerHTML = s.archived ? ICONS.UNARCHIVE : ICONS.ARCHIVE;
    move.onclick = (e) => { e.stopPropagation(); setHistoryArchived(s, !s.archived); };
    actions.appendChild(move);
  }
  it.appendChild(main); it.appendChild(actions);
  it.onclick = () => loadHistory(s.sessionId, s.display);
  return it;
}
/* The row the archived conversations sit under: a chevron, the name and their count.
   A click opens or closes the group; `canToggle` is off while a search is typed. */
function archivedGroupRow(count, collapsed, canToggle) {
  const row = document.createElement('div'); row.className = 'item h-group';
  row.title = !canToggle ? 'Archived sessions' : (collapsed ? 'Expand Archived sessions' : 'Collapse Archived sessions');
  const chev = document.createElement('span'); chev.className = 'h-group-chev';
  chev.innerHTML = collapsed ? ICONS.CHEVRON : ICONS.CHEVRONDOWN;
  const name = document.createElement('span'); name.className = 'h-group-name'; name.textContent = 'Archived sessions';
  const n = document.createElement('span'); n.className = 'h-group-count'; n.textContent = String(count);
  row.appendChild(chev); row.appendChild(name); row.appendChild(n);
  // stopPropagation: re-rendering detaches this row mid-click, and ui.js takes a click
  // on a detached node for one outside the panel (see cycleSearchScope).
  row.onclick = (e) => {
    e.stopPropagation();
    if (!canToggle) return;
    histArchivedCollapsed = !histArchivedCollapsed;
    try { localStorage.setItem('claude.histArchivedCollapsed', histArchivedCollapsed ? '1' : '0'); } catch (err) {}
    renderHistoryList();
  };
  return row;
}
/* Files a conversation under "Archived sessions", or takes it back out. Nothing of the
   conversation is changed; it is only listed elsewhere. */
function setHistoryArchived(session, archived) {
  // Open in the view's last tab, archiving it closes the view, and the view asks before it
  // does. The conversation is archived only on "Close the view" (the view does both then):
  // a cancel has to leave it open AND listed where it was.
  if (archived) {
    const last = tabs.find(t => t.sessionId === session.sessionId);
    if (last && isLastTabOfView(last) && window._confirmCloseView) {
      try { window._confirmCloseView('session', session.sessionId); } catch (e) {}
      return;
    }
  }
  let recorded = false;
  try {
    recorded = !!(window._setSessionsArchived
      && window._setSessionsArchived(JSON.stringify([session.sessionId]), archived));
  } catch (e) {}
  if (!recorded) return;
  session.archived = archived;
  renderHistoryList();
  // Archived while open in a tab, it is put away with the rest: the tab closes, as the
  // VS Code extension leaves a session it has just archived.
  if (archived) {
    const open = tabs.find(t => t.sessionId === session.sessionId);
    if (open) closeTab(open.id);
  }
}
/* The view took conversations back out of the archive (the notice's Unarchive): show
   the list as it now is, if it is being looked at. */
window.onSessionsUnarchived = function() {
  const panel = document.getElementById('history-panel');
  if (panel && panel.classList.contains('open') && histActive === 'local') loadHistoryAsync();
};
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
/* Not on a row any more: the rows archive instead (setHistoryArchived). This and its
   bridge (_deleteSession) remain the way to remove a conversation for good. */
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
/* `id` and `at` are the transcript line this reply is and when it was written, when the
   view sent them along with the conversation (see "Opening a saved conversation"
   below). Without them the reply learns its line afterwards, by its text
   (bookmarks.js, backfillReplyIds). */
function appendTextStatic(turn, text, id, at) {
  if (!text || !text.trim()) return;
  const el = document.createElement('div'); el.className = 'a-item';
  el.innerHTML = '<span class="dot"></span><span class="a-body"></span>';
  el.querySelector('.a-body').innerHTML = renderMarkdown(text);
  if (typeof sealReply === 'function') sealReply(el, text);   // one of Claude's replies: copy and bookmark under it
  if (id && el.classList.contains('reply')) {
    el.dataset.rid = id;
    const when = Date.parse(at || '');
    if (!isNaN(when)) el._replyAt = when;
  }
  turn.appendChild(el);
}
/* The line a reopened conversation ends with when its next message will cost more than
   usual: the prompt cache no longer holds it. The core writes the sentence from the
   transcript's own usage numbers (promptcache.rs); here it is only placed, as a line of
   the same kind as "Interrupted" in a turn of its own, so whatever is said next goes
   under it. No line when there is nothing to say. */
function resumeNoteTurn(text) {
  if (!text) return null;
  const note = document.createElement('div'); note.className = 'resume-note'; note.textContent = text;
  const turn = document.createElement('div'); turn.className = 'turn'; turn.appendChild(note);
  return turn;
}
/* The same sentence asked for on its own, for a conversation that did not come with it
   (one read the old way, see loadHistory). Empty when the native library is from before
   the note. With the tab's folder, for the reason backfillMessageIds gives. */
function resumeNoteFor(t, id) {
  try { return (window._resumeNote && window._resumeNote(id, rootPathOf(t))) || ''; } catch (e) { return ''; }
}

/* ===================== Opening a saved conversation =====================

   A long conversation is megabytes of transcript and thousands of lines to draw. Read
   and drawn in one go on the UI thread, opening one held all of Eclipse for seconds. So:

   - The view reads it on a thread of its own (_openSessionAsync) and says when it has
     it (onSessionOpened). The tab is there at once, saying "Loading…", with the
     settings the conversation was saved with already in force.
   - Its end is drawn first, which is where a reopened conversation lands, and the older
     messages are filled in above it a few at a time, between turns of the page. What
     takes the time is not making the elements but the browser laying them out, so the
     parts are small and each is laid out before the clock is read again.
   - Only while its tab is in front. What is drawn out of sight is laid out all at once
     the moment it shows, which is the wait this is here to avoid; a tab left while its
     conversation is coming in goes on from where it was when it is shown again
     (resumeDrawing).
   - While "Hide messages from before a compaction" is on, the part before the last
     compaction is not read into items and not drawn. It is fetched when the preference
     is off and its line is opened (fetchEarlierPart).

   Whatever is said in the tab meanwhile stays below: after the first, immediate part of
   loadHistory nothing here empties the pane, and nothing here touches the render state a
   turn in progress is using (loadRender).

   A view that cannot read a conversation this way (a native library from before
   sessionOpen) and a caller that cannot wait (opts.sync) have it as before: read and
   drawn on the spot, all of it. */

/* How many items, at least, the first draw of a conversation holds: its end. */
let HIST_TAIL_ITEMS = 30;
/* How many items, at most, are drawn and laid out at a time after that — where the
   conversation can be drawn apart that finely (historyParts). */
let HIST_PART_ITEMS = 16;
/* How long one turn of the page may go on drawing older messages before it lets the
   page answer the user again. */
let HIST_SLICE_MS = 40;
/* Puts off the next part of a drawing. A timer, not an animation frame: a view that is
   not showing is given no frames, and its conversation would never be finished. */
let laterDraw = fn => setTimeout(fn, 0);
let openAsks = 0;              // the number of the last request made of the view
const openAsked = {};          // request number → what it was for, until it is answered
let openInBackground = true;   // until the view answers that it cannot
/* What tells that the reader has taken the transcript over (also holdBottomWhileSettling). */
const READER_TAKES_OVER = ['wheel', 'touchstart', 'pointerdown'];

const histKind = it => it.t || (it.role === 'user' ? 'user' : 'text');   // back-compat with old text-only format
// Only real model ids — skip "<synthetic>" (CLI-injected messages) and blanks.
const histModel = it => (typeof it.model === 'string' && it.model.indexOf('claude-') === 0) ? it.model : '';
/* The kinds of item after which no "Compacted chat" line is being held back for its
   /compact bubble: each of them draws that line first. */
const HIST_SETTLED = { text: true, tool: true, thinking: true, error: true, answered: true };
/* The kinds an assistant turn is made of: consecutive ones share one turn and its rail. */
const HIST_RUN = { text: true, tool: true, thinking: true };

/* Where a conversation's items can be drawn apart and come out the same as drawn in one
   go: after any settled item. `cuts` are those indexes. `joins[k]` says that cut k falls
   inside an assistant turn, so the two halves are one turn once both are in the pane
   (placeHistoryPart). `modelBefore[k]` is the model in effect on reaching cut k, for the
   "Switched to" lines; `turnModel[i]` the model user message i's turn ran on — the first
   one named before the next user message. */
function historyParts(items) {
  const n = items.length, turnModel = new Array(n);
  let next = '';
  for (let i = n - 1; i >= 0; i--) {
    if (histKind(items[i]) === 'user') { turnModel[i] = next; next = ''; }
    else if (histModel(items[i])) next = histModel(items[i]);
  }
  const cuts = [], joins = {}, modelBefore = {};
  let rendered = null;
  for (let i = 0; i < n; i++) {
    const kind = histKind(items[i]);
    if (i > 0 && HIST_SETTLED[histKind(items[i - 1])]) {
      cuts.push(i); modelBefore[i] = rendered;
      if (HIST_RUN[kind] && HIST_RUN[histKind(items[i - 1])]) joins[i] = true;
    }
    if (kind === 'user' && turnModel[i]) rendered = turnModel[i];
  }
  return { cuts: cuts, joins: joins, modelBefore: modelBefore, turnModel: turnModel };
}
/* Where the part that ends at item `end` starts: at the cut furthest back that keeps it
   to HIST_PART_ITEMS items, or the nearest one when none is that close (a run of items
   that cannot be drawn apart). */
function partStart(cuts, end) {
  if (end <= HIST_PART_ITEMS) return 0;
  let nearest = 0;
  for (let i = 0; i < cuts.length && cuts[i] < end; i++) {
    if (end - cuts[i] <= HIST_PART_ITEMS) return cuts[i];
    nearest = cuts[i];
  }
  return nearest;
}
/* The same going forward: where the part that starts at item `at` ends, of `n` items. */
function partEnd(cuts, at, n) {
  if (n - at <= HIST_PART_ITEMS) return n;
  let furthest = 0;
  for (let i = 0; i < cuts.length; i++) {
    if (cuts[i] <= at) continue;
    if (cuts[i] - at > HIST_PART_ITEMS) return furthest || cuts[i];
    furthest = cuts[i];
  }
  return furthest || n;
}
/* What a conversation's items say it was last run with: the model it last used (resume
   with it), and whether it had thinking on — any thinking block says so. */
function historySaid(items) {
  const said = { model: '', thinking: false };
  items.forEach(it => {
    if (histModel(it)) said.model = histModel(it);
    if (histKind(it) === 'thinking') said.thinking = true;
  });
  return said;
}

/**
 * Draws items[from..to) of a conversation into a box of their own, which is returned
 * for its children to be moved into the pane. `from` is 0 or one of the cuts, so
 * nothing drawn before it reaches into this part, save the assistant turn it may be cut
 * in: that turn's two halves are made one when both are in the pane (placeHistoryPart).
 * @param {Tab} t  the tab the conversation is in (for its folder)
 * @param {string} id  the conversation
 * @param {{modelBefore: Object, turnModel: string[]}} parts  see historyParts
 * @param {number} foldAt  index of the conversation's last compaction: what is drawn
 *   of the items before it is marked as from before a compaction (stream.js,
 *   foldBeforeCompaction). -1 for none, Infinity when all of these items are.
 * @param {string|null} modelBefore  the model in effect on reaching `from`
 */
function drawHistoryPart(t, id, items, parts, from, to, foldAt, modelBefore) {
  const box = document.createElement('div');
  // Group consecutive assistant blocks (thinking / tool / text) into one turn with
  // its dotted rail; user messages and answer cards are their own turns.
  let aTurn = null;
  let renderedModel = modelBefore; // model in effect while reconstructing → switch dividers
  function assistantTurn() {
    if (!aTurn || !aTurn.parentNode) { aTurn = document.createElement('div'); aTurn.className = 'turn'; box.appendChild(aTurn); }
    return aTurn;
  }
  // Compaction markers: the transcript stores boundary + summary BEFORE the
  // "/compact" command echo, but live rendering showed the bubble first — hold the
  // "Compacted chat" line and flush it after that bubble (or before whatever
  // renders next, e.g. after an auto-compact) so a reload reads like the live run.
  let pendingCompact = null;   // { trigger, freed, text }
  function flushCompact() {
    if (!pendingCompact) return;
    addCompacted(box, pendingCompact.trigger, pendingCompact.freed, pendingCompact.text);
    pendingCompact = null;
  }
  function markBefore() {
    for (let el = box.firstElementChild; el; el = el.nextElementSibling) el.classList.add('pre-compact');
  }
  for (let i = from; i < to; i++) {
    const it = items[i], ty = histKind(it);
    // What was said above the last compaction is folded away the moment the loop
    // reaches it: before its own line, and the /compact bubble that line follows, are drawn.
    if (i === foldAt) {
      flushCompact();   // an earlier compaction's line still held back belongs inside, in its place
      aTurn = null;
      markBefore();
    }
    if (ty === 'compact') {
      aTurn = null;
      pendingCompact = { trigger: it.trigger || 'manual',
        freed: Math.max(0, (it.preTokens || 0) - (it.postTokens || 0)), text: '' };
    } else if (ty === 'teleported') {
      // The boundary between the conversation as it arrived from claude.ai and
      // whatever was said here afterwards.
      aTurn = null;
      box.appendChild(makeTeleportDivider());
    } else if (ty === 'compact_summary') {
      if (pendingCompact) pendingCompact.text = it.text || '';
      else { aTurn = null; addCompacted(box, 'manual', 0, it.text || ''); }
    } else if (ty === 'user') {
      // Reconstruct "Switched to <model>" dividers from the transcript (the model
      // is recorded per turn) so past model switches persist across reloads.
      const tm = parts.turnModel[i];
      if (tm) { if (renderedModel !== null && tm !== renderedModel) box.appendChild(makeSwitchDivider(tm)); renderedModel = tm; }
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
        addInterrupted(/for tool use/i.test(marker) ? 'Tool interrupted' : 'Interrupted', box);
        continue;
      }
      // Image-scaling note the CLI injects beside an upload ("[Image: original
      // 2352x4160, displayed at …]"). Internal metadata with no image block of
      // its own — nothing to show, so it renders nothing at all.
      if (/^\[Image:[^\]]*\]$/.test(marker)) continue;
      // Messages sent with pasted images carry them as {media_type,data} blocks —
      // rebuild the same chips the live bubble showed.
      if (!invisible) addUserMessage(p.text, p.chip, imgs, it.id, it.ts, box, p.target);
      if (isCompactCmd) flushCompact();
    } else if (ty === 'answered') {
      flushCompact();
      const asking = lastAskingLine(aTurn);   // the call that asked, in the turn this answer closes
      aTurn = null;
      // With the questions (the result line's, or a declined call's own), the overview, which stands in
      // for the "Asking" line; without (an older line), just the words under that line.
      if (Array.isArray(it.questions) && it.questions.length && (it.answers || it.declined)) {
        dropAskingLine(asking);
        addQuestionsAnswered(it.questions, it.answers, box, it.declined ? 'declined' : undefined);
      } else addAnswered(it.text || '', box);
    } else if (ty === 'error') {
      // A backend error (rate limit, 529 overload, …). Live it is the muted
      // "⚠ …" line onError paints — a reload rebuilds exactly that, never an
      // assistant paragraph, so a past session reads the way it ran.
      flushCompact();
      aTurn = null;
      const em = it.text || '';
      addSystemToPane(box, '⚠ ' + (typeof augmentError === 'function' ? augmentError(em) : em));
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
        // the DOM yet (every part is built off-screen, in its box), so that lookup would
        // find nothing.
        const body = line.querySelector(':scope > .agent-log > .agent-log-body');
        if (body) agentLogs.get(it.id).items.forEach(item => body.appendChild(buildAgentLogItemEl(item)));
      }
      assistantTurn().appendChild(line);
    } else { // text
      flushCompact();
      appendTextStatic(assistantTurn(), it.text || it.content || '', it.id, it.at);
    }
  }
  flushCompact();
  if (to <= foldAt) markBefore();
  // draw the connector rails
  box.querySelectorAll(':scope > .turn').forEach(relinkTurn);
  return box;
}
/* The line a tab shows where messages are still to come: the system line's look. */
function loadingLine() {
  const turn = document.createElement('div'); turn.className = 'turn opening';
  turn.innerHTML = '<div class="a-item muted"><span class="dot gray"></span><span class="sys"></span></div>';
  turn.querySelector('.sys').textContent = 'Loading…';
  return turn;
}
/**
 * Moves a drawn part into a tab's pane, before `ref`. Its replies show their bookmarks
 * as they go in; what stands above a message from before a compaction is from before it
 * too (one that finished while the older messages were still to come marks what was
 * there, and they are not yet); and the pane has its "Messages before compaction" line
 * as soon as it holds anything for one.
 *
 * A part cut inside an assistant turn (historyParts, `joins`) is made one turn again
 * with its other half, which is already in the pane:
 * @param {Element|null} [after]   that half when it follows this part: the part's last
 *   turn goes in at its front
 * @param {Element|null} [before]  that half when it precedes this part: the part's
 *   first turn goes on at its end
 */
function placeHistoryPart(t, box, ref, after, before) {
  if (ref && ref.classList && ref.classList.contains('pre-compact')) {
    for (let el = box.firstElementChild; el; el = el.nextElementSibling) el.classList.add('pre-compact');
  }
  if (typeof paintReplyMark === 'function') box.querySelectorAll('.a-item.reply').forEach(el => paintReplyMark(el, t));
  if (before && box.firstElementChild) {
    const run = box.firstElementChild;
    while (run.firstChild) before.appendChild(run.firstChild);
    run.remove();
    relinkTurn(before);
  }
  if (after && box.lastElementChild) {
    const run = box.lastElementChild;
    while (run.lastChild) after.insertBefore(run.lastChild, after.firstChild);
    run.remove();
    relinkTurn(after);
  }
  const part = document.createDocumentFragment();
  while (box.firstChild) part.appendChild(box.firstChild);
  t.pane.insertBefore(part, ref);
  ensurePreCompactHead(t.pane, false);
}
/**
 * Changes what stands above `anchor` in a tab's pane without moving what the reader is
 * looking at. #messages is one scroll container: what goes in above the view pushes it
 * down unless the position is moved along. Measured on the anchor itself, so it comes
 * out right whether or not the browser has already moved the position on its own.
 * @param {boolean} [flowing] the change adds below something the reader may be looking
 *   at (a section they opened, filling downwards): then the position is left alone
 *   unless the change was above the view altogether, and never sent to the bottom.
 */
function keepingPlace(t, anchor, change, flowing) {
  if (t !== activeTab() || !anchor || !anchor.getBoundingClientRect) { change(); return; }
  const viewTop = messagesEl.getBoundingClientRect().top;
  const was = anchor.getBoundingClientRect().top, scrolled = messagesEl.scrollTop;
  change();
  if (flowing) {
    if (was >= viewTop) { if (messagesEl.scrollTop !== scrolled) messagesEl.scrollTop = scrolled; }
    else { const moved = anchor.getBoundingClientRect().top - was; if (moved) messagesEl.scrollTop += moved; }
    followTail = isNearBottom();   // no scroll event says so when the position did not move
    updateJumpToLatest();
    return;
  }
  if (followTail) { messagesEl.scrollTop = messagesEl.scrollHeight; return; }
  const moved = anchor.getBoundingClientRect().top - was;
  if (moved) messagesEl.scrollTop += moved;
}
/* Whether `opening` is still what tab `t` is waiting on: the tab has not been closed,
   cleared, or given another conversation since. */
function stillOpening(t, opening) { return tabs.indexOf(t) >= 0 && t.opening === opening; }

/* Restore a conversation's settings. Our own sidecar (saved per session id) is
   authoritative — it's the ONLY source of effort and it captures the user's last
   selection; the transcript is the fallback for model + thinking. `said` is what the
   transcript says (historySaid), null while it has not been read yet: then only what
   the sidecar holds is set, and this runs again once the transcript is in. */
function restoreSettings(t, id, said) {
  let saved = {};
  try { saved = JSON.parse(window._loadSessionPrefs ? window._loadSessionPrefs(id) : '{}') || {}; } catch (e) {}
  // What the sidecar actually returned for the id History handed us. Debug mode only.
  // Read next to the [PREFS-SAVE] lines: a save of defaults appearing just ABOVE this
  // one, under the same id, is the issue #114 signature.
  try {
    if (window.__ccDebug && window._debugLog)
      _debugLog('[PREFS-LOAD] sid=' + String(id).slice(0, 8) + ' -> ' + JSON.stringify(saved)
        + ' (tab=' + t.id + ' active=' + (t === activeTab()) + ' transcript=' + (said ? 'read' : 'pending') + ')');
  } catch (e) {}

  // Write the restored values into the TAB first, then paint the composer from the
  // tab via applyTabSettings. Doing it the other way round (assigning the module
  // globals directly) only worked while loadHistory was guaranteed to be rendering
  // the active tab — a restore can rebuild a BACKGROUND tab, and createTab() ->
  // switchTab() -> applyTabSettings() has already painted this tab's DEFAULTS by the
  // time we get here. Storing first makes the tab the single source of truth for both.
  if (saved.thinking === '1') t.thinking = true;
  else if (saved.thinking === '0') t.thinking = false;
  else if (said) t.thinking = said.thinking;

  const model = saved.model || (said ? said.model : '');
  if (model) t.model = model;

  if (saved.effort !== undefined && saved.effort !== '') {
    const ei = parseInt(saved.effort, 10);
    if (!isNaN(ei)) t.effortIdx = ei;
  }
  // Permission mode is a launch flag the transcript never records, so the sidecar
  // is the only source. A conversation with none saved (one from before permMode
  // existed, or from the Claude Terminal) starts in the mode a new one would.
  t.permMode = saved.permMode || defaultPermMode();

  // One chokepoint for the composer + status bar, and it already sequences thinking
  // before effort (the effort cap depends on the thinking flag) and reconciles an
  // illegal stored pair through enforceThinkingGate. Only the visible tab paints;
  // a background tab keeps its values and paints when the user switches to it —
  // which calls this very function.
  if (t === activeTab()) applyTabSettings(t);
}
/**
 * @param {Tab} [targetTab] render INTO this existing tab instead of picking one. Used
 *   by the restore path (viewstate.js), which has already built the tab and only needs
 *   its transcript rebuilt — so neither the "already open" dedupe nor the two entry
 *   point behaviours below apply to it.
 * @param {{sync?: boolean, then?: (readerMoved: boolean) => void}} [opts] sync: the
 *   conversation is read and drawn before this returns, for a caller that goes on to
 *   use it. then: called once all of it is drawn (see "Opening a saved conversation").
 */
function loadHistory(id, title, targetTab, opts) {
  opts = opts || {};
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
  pane.classList.remove('pre-open');    // and with it an opened "Messages before compaction"
  t.sessionId = id;                     // continuing this tab resumes the session
  setTabTitle(t, title);
  if (typeof renderTerminalTip === 'function') renderTerminalTip();   // the welcome it goes with is gone
  // What this tab is now waiting on. Whatever it was waiting on before is no longer
  // that, and is dropped when it arrives (stillOpening).
  const opening = t.opening = { id: id, then: opts.then || null, line: null, joinTo: null, resume: null, touched: false, unwatch: null };
  t.earlier = null;
  // The pane was emptied above, and a Remote Control bridge coming up in this tab
  // had its indicator in it. Both of the ways a conversation gets reconstructed run
  // through here AFTER the tab was switched on: reopening from history (createTab
  // enables it, this then clears the pane) and a tab restored from the last Eclipse
  // session (enabled at startup, rendered lazily on the switch that first shows it).
  // No-op unless the tab is genuinely still connecting.
  if (typeof showWorkingFor === 'function') showWorkingFor(t);
  if (!opts.sync && askForConversation(t, opening)) {
    // Its own settings at once, not when the transcript is in: a message sent
    // meanwhile goes out with them.
    restoreSettings(t, id, null);
    return;
  }
  let items = [];
  // Java reads a conversation from the folder in front unless told which. A tab handed in
  // need not be in that folder: one restored from the last Eclipse session and rebuilt while
  // another folder is in front (the claudeCodeEclipse tool sends to tabs that are not shown).
  try {
    items = JSON.parse((targetTab ? window._loadSession(id, rootPathOf(targetTab)) : window._loadSession(id)) || '[]');
  } catch (e) {}
  drawOpened(t, opening, { items: items }, true);
}
/* Asks the view to read a tab's conversation in the background, and puts "Loading…"
   where it will go. False when the view cannot: the caller then reads it itself. While
   messages from before a compaction are hidden, only what follows the last one is asked for. */
function askForConversation(t, opening) {
  if (!openInBackground || !window._openSessionAsync || !window._takeOpenedSession) return false;
  const request = ++openAsks;
  openAsked[request] = { t: t, opening: opening };   // before the call: its answer must find it
  let asked = false;
  try { asked = !!window._openSessionAsync(request, opening.id, rootPathOf(t), hidingBeforeCompaction(), ''); } catch (e) {}
  if (!asked) { delete openAsked[request]; return false; }
  opening.line = loadingLine();
  t.pane.insertBefore(opening.line, t.pane.firstChild);
  return true;
}
/* The view has read what request `request` asked for (`read` false: it cannot, its
   native library is from before sessionOpen). The page fetches it by that number, also
   when nothing is waiting for it any more, so that the view lets go of it. */
window.onSessionOpened = function(request, read) {
  const asked = openAsked[request];
  delete openAsked[request];
  let json = null;
  if (read) { try { json = window._takeOpenedSession(request); } catch (e) {} }
  if (!asked) return;
  const t = asked.t;
  const got = () => { try { return JSON.parse(json || 'null'); } catch (e) { return null; } };
  if (asked.earlier) { if (tabs.indexOf(t) >= 0 && t.earlier === asked.earlier) drawEarlierPart(t, asked.earlier, got()); return; }
  if (!stillOpening(t, asked.opening)) return;
  // Read, and not there to be fetched (or not to be made out): that is an answer gone
  // missing, not a conversation with nothing in it. This one is read directly.
  const opened = read ? got() : null;
  if (!opened) {
    // A view that cannot read in the background opens conversations as it always did,
    // from here on without asking first.
    if (!read) openInBackground = false;
    let items = [];
    try { items = JSON.parse(window._loadSession(asked.opening.id, rootPathOf(t)) || '[]'); } catch (e) {}
    drawOpened(t, asked.opening, { items: items }, true);
    return;
  }
  drawOpened(t, asked.opening, opened, false);
};
/**
 * Draws a conversation that has been read into its tab, where "Loading…" stands (or
 * at the top of a pane that has none), above whatever has been said there since.
 * @param {{items: Object[], note?: string, cut?: {uuid: string}, earlier?: Object}} got
 *   what the view read (session.rs, open_session), or just the items of the old loader
 * @param {boolean} inOneGo  all of it now, in front or not, for a caller that needs it
 *   whole on return
 */
function drawOpened(t, opening, got, inOneGo) {
  const pane = t.pane, id = opening.id;
  const items = Array.isArray(got.items) ? got.items : [];
  // The part before the last compaction, when the view left it out: which replies it
  // holds, and what names it for fetching (fetchEarlierPart).
  const left = (got.earlier && got.cut && got.cut.uuid) ? got.earlier : null;
  if (left) {
    t.earlier = { id: id, uuid: got.cut.uuid, nth: got.cut.nth, ids: new Set(left.replies || []), state: 'out',
      waiting: [], line: null, joinTo: null, resume: null };
  }
  // How many times the conversation has been compacted, for the next time it is (stream.js).
  t.compactions = (got.cut && got.cut.nth) || items.filter(it => it.t === 'compact').length;
  const said = historySaid(items);
  if (left) { said.thinking = said.thinking || !!left.thinking; said.model = said.model || left.model || ''; }
  restoreSettings(t, id, said);

  const n = items.length, parts = historyParts(items);
  let foldAt = -1;
  items.forEach((it, i) => { if (it.t === 'compact') foldAt = i; });
  // The model in effect where these items begin: the left-out part's last, if there is one.
  const firstModel = (left && left.model) || null;
  const modelAt = k => k ? parts.modelBefore[k] : firstModel;
  // Its end first: from the last cut that leaves enough of it.
  let from = 0;
  if (!inOneGo && n > HIST_TAIL_ITEMS) parts.cuts.forEach(k => { if (n - k >= HIST_TAIL_ITEMS) from = k; });

  const first = () => {
    const box = drawHistoryPart(t, id, items, parts, from, n, foldAt, modelAt(from));
    if (!n) addSystemToPane(box, 'This conversation is empty or could not be loaded.');
    else {
      const note = resumeNoteTurn(typeof got.note === 'string' ? got.note : resumeNoteFor(t, id));
      if (note) box.appendChild(note);
    }
    const waiting = opening.line && opening.line.parentNode === pane;
    placeHistoryPart(t, box, waiting ? opening.line.nextSibling : pane.firstChild);
    if (waiting) opening.joinTo = parts.joins[from] ? opening.line.nextElementSibling : null;
    if (t.earlier) ensurePreCompactHead(pane, true);   // its line, for what is still to be fetched
    if (!from) openingDone(t, opening);
    // A reopened conversation lands on its newest message, like a live one. #messages is
    // shared by every pane, so only move it when the tab just rebuilt is the visible one —
    // a restore rendering a background tab must not yank the view.
    if (t === activeTab()) {
      const pinBottom = () => {
        pinToBottom();
        followTail = true;   // set directly: a write to the position already held fires no scroll event
        updateJumpToLatest();
      };
      pinBottom();
      holdBottomWhileSettling(t, pinBottom);
    }
    if (!from) { if (opening.then) opening.then(false); return; }
    if (opening.then) {
      // Whoever asked to be told when it is all in is told whether the reader has moved since.
      const took = () => { opening.touched = true; opening.unwatch(); };
      opening.unwatch = () => READER_TAKES_OVER.forEach(ev => messagesEl.removeEventListener(ev, took));
      READER_TAKES_OVER.forEach(ev => messagesEl.addEventListener(ev, took, { passive: true }));
    }
    laterDraw(older);
  };
  // The older messages, the part next above each time, until the first is in. Each part
  // is laid out as it is placed (keepingPlace measures), so the clock below counts what
  // the browser spends on it and not only the making of its elements.
  let end = from;
  const older = () => {
    if (!stillOpening(t, opening) || opening.line.parentNode !== pane) return;
    if (t !== activeTab()) { opening.resume = older; return; }
    const began = Date.now();
    do {
      const start = partStart(parts.cuts, end);
      const box = drawHistoryPart(t, id, items, parts, start, end, foldAt, modelAt(start));
      const next = opening.line.nextElementSibling;
      // The half of an assistant turn this part ends in, if it is still what follows.
      const into = (parts.joins[end] && opening.joinTo && opening.joinTo === next) ? next : null;
      keepingPlace(t, into ? (into.firstElementChild || into) : next,
        () => placeHistoryPart(t, box, opening.line.nextSibling, into, null));
      opening.joinTo = parts.joins[start] ? opening.line.nextElementSibling : null;
      end = start;
    } while (end > 0 && Date.now() - began < HIST_SLICE_MS);
    if (end > 0) { laterDraw(older); return; }
    openingDone(t, opening);
    if (opening.then) opening.then(opening.touched);
  };
  if (inOneGo || t === activeTab()) first();
  else opening.resume = first;
}
/* A tab's conversation is all in: "Loading…" goes, and its replies, its bookmarks and
   the toolbar are brought up to date with it. */
function openingDone(t, opening) {
  if (opening.unwatch) opening.unwatch();
  if (opening.line) keepingPlace(t, opening.line.nextElementSibling, () => opening.line.remove());
  t.opening = null;
  // A message sent while the conversation was coming in learns its transcript line now
  // that all of it is drawn (a turn that ended meanwhile left it for this).
  if (!t.streaming && typeof backfillMessageIds === 'function') backfillMessageIds(t);
  // Each reply that did not come with its transcript line learns it, and shows its
  // bookmark if it has one.
  if (typeof refreshReplies === 'function') refreshReplies(t);
  // This reconstruction may be for a BACKGROUND tab (not the one on screen) — only
  // refresh the toolbar pill when it's the one actually showing right now.
  if (t === activeTab() && typeof updateAgentsBtn === 'function') updateAgentsBtn();
  // The prompt that belongs at the top of the view may be one that has only just come in.
  if (t === activeTab()) updatePinnedPrompt();
}
/* A tab has come to the front: a conversation that was being drawn into it when it was
   left, or that arrived while it was not showing, goes on from where it was. */
function resumeDrawing(t) {
  const opening = t && t.opening, e = t && t.earlier;
  if (opening && opening.resume) { const go = opening.resume; opening.resume = null; go(); }
  if (e && e.resume) { const go = e.resume; e.resume = null; go(); }
}

/* ---- the part before the last compaction, when it was not read ----
   t.earlier is what a tab knows of it: the boundary line that names it (`uuid`), the
   replies it holds (`ids`, for the bookmarks of them), and where it stands: 'out' (not
   fetched), 'asked', or 'in' (drawn, under "Messages before compaction"). */

/* Whether a transcript line — an entry of the view's list of the conversation's messages
   or replies (_messageIds, _replyIds) — is in a part of the tab's conversation that has
   not been drawn, and so cannot be the line of anything in its pane. A reply of that
   part is known by name; a message by how many compactions precede it, which the list
   says of each line and the conversation said of its last when it was opened (`nth`). A
   view whose native library says neither leaves the messages as they were. */
function inUnreadPart(t, entry) {
  const e = t && t.earlier;
  if (!e || e.state === 'in' || !entry) return false;
  if (e.ids.has(entry.id)) return true;
  return typeof e.nth === 'number' && (Number(entry.compactions) || 0) < e.nth;
}
/* Whether a reply is in a part of the tab's conversation that has not been fetched. */
function earlierHolds(t, uuid) {
  const e = t && t.earlier;
  return !!(e && e.state !== 'in' && e.ids.has(uuid));
}
/**
 * Fetches the part of a tab's conversation that was left out and draws it under its
 * line, once. Nothing while messages from before a compaction are hidden: it would be
 * drawn to be kept out of sight.
 * @param {Function} [then] called when the part is in (at once when it already is, or
 *   when nothing was left out); not called if it cannot be fetched
 */
function fetchEarlierPart(t, then) {
  const e = t && t.earlier;
  if (!e || e.state === 'in') { if (then) then(); return; }
  if (hidingBeforeCompaction()) return;
  if (then) e.waiting.push(then);
  if (e.state === 'asked') return;
  const request = ++openAsks;
  openAsked[request] = { t: t, earlier: e };
  let asked = false;
  try { asked = !!(window._openSessionAsync && window._openSessionAsync(request, e.id, rootPathOf(t), false, e.uuid)); } catch (err) {}
  if (!asked) { delete openAsked[request]; e.waiting = []; return; }
  e.state = 'asked';
  e.line = loadingLine(); e.line.classList.add('pre-compact');
  const head = ensurePreCompactHead(t.pane, true);
  t.pane.insertBefore(e.line, head.nextSibling);
}
/* The left-out part has been read: drawn from its first message down, a part at a time,
   above "Loading…", which stays under what is in so far until all of it is. */
function drawEarlierPart(t, e, got) {
  const items = (got && Array.isArray(got.items)) ? got.items : null;
  const here = () => tabs.indexOf(t) >= 0 && t.earlier === e && e.line && e.line.parentNode === t.pane;
  if (!items || !here()) {
    // Not to be had: as it was before it was asked for, so that it can be asked for again.
    if (e.line) e.line.remove();
    e.line = null; e.state = 'out'; e.waiting = [];
    return;
  }
  const n = items.length, parts = historyParts(items);
  let at = 0;
  const further = () => {
    if (!here()) return;
    if (t !== activeTab()) { e.resume = further; return; }
    const began = Date.now();
    while (at < n) {
      const to = partEnd(parts.cuts, at, n);
      const box = drawHistoryPart(t, e.id, items, parts, at, to, Infinity, at ? parts.modelBefore[at] : null);
      const prev = e.line.previousElementSibling;
      // The half of an assistant turn this part begins in, if it is still what precedes.
      const onto = (parts.joins[at] && e.joinTo && e.joinTo === prev) ? prev : null;
      keepingPlace(t, e.line, () => placeHistoryPart(t, box, e.line, null, onto), true);
      e.joinTo = parts.joins[to] ? e.line.previousElementSibling : null;
      at = to;
      if (Date.now() - began >= HIST_SLICE_MS) break;
    }
    if (at < n) { laterDraw(further); return; }
    keepingPlace(t, e.line.nextElementSibling, () => e.line.remove(), true);
    e.line = null; e.state = 'in';
    if (typeof refreshReplies === 'function') refreshReplies(t);
    const waiting = e.waiting;
    e.waiting = [];
    waiting.forEach(fn => fn());
  };
  further();
}

/* Blocks size themselves AFTER they are inserted — the 2-line "Show more" clamp and capped
   tool output a frame later, images and anything else later still — and each one moves the
   bottom, so a single pin lands near the end but not at it. Instead of guessing how long
   that takes, follow the pane's own height: every time it changes, pin again. Stops at the
   first sign the reader has taken over (wheel, touch, click or drag on the transcript), when
   the tab stops being the visible one, or after a couple of seconds either way. Only ever
   one watch: a newer resume replaces the older. */
let settleWatchStop = null;
function holdBottomWhileSettling(t, pinBottom) {
  if (settleWatchStop) settleWatchStop();
  if (typeof ResizeObserver === 'undefined' || !t.pane) return;
  const takeOver = READER_TAKES_OVER;
  let timer = 0;
  const ro = new ResizeObserver(() => { if (t !== activeTab()) stop(); else pinBottom(); });
  function stop() {
    ro.disconnect(); clearTimeout(timer);
    takeOver.forEach(ev => messagesEl.removeEventListener(ev, stop));
    if (settleWatchStop === stop) settleWatchStop = null;
  }
  takeOver.forEach(ev => messagesEl.addEventListener(ev, stop, { passive: true }));
  ro.observe(t.pane);
  timer = setTimeout(stop, 2000);
  settleWatchStop = stop;
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

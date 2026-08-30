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
  // Open the panel immediately; show cached results if we have them, otherwise a
  // "Loading…" state — and (re)load in the background either way.
  renderHistoryList();
  loadHistoryAsync();
  panel.classList.add('open');
  if (s) setTimeout(() => s.focus(), 0);
  return true;
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
function histTab(which) {
  const local = which === 'local';
  document.getElementById('hist-tab-local').classList.toggle('active', local);
  document.getElementById('hist-tab-web').classList.toggle('active', !local);
  document.getElementById('history-list').style.display = local ? '' : 'none';
  document.querySelector('.hist-search').style.display = local ? '' : 'none';
  document.getElementById('history-web').style.display = local ? 'none' : '';
}
function renderHistoryList() {
  const q = (document.getElementById('hist-search') ? document.getElementById('hist-search').value : '').toLowerCase();
  const list = document.getElementById('history-list');
  list.innerHTML = '';
  if (histLoading && !histLoaded) { list.innerHTML = '<div class="h-empty">Loading…</div>'; return; }
  const items = histSessions.filter(s => (s.display || '').toLowerCase().includes(q));
  if (!items.length) {
    list.innerHTML = '<div class="h-empty">' + (histSessions.length ? 'No matches.' : 'No past conversations yet.') + '</div>';
    return;
  }
  items.forEach(s => {
    const it = document.createElement('div'); it.className = 'item'; it.dataset.sid = s.sessionId;
    const main = document.createElement('div'); main.className = 'h-main';
    const title = document.createElement('div'); title.className = 'h-title'; title.textContent = stripContext(s.display) || '(untitled)';
    const time = document.createElement('div'); time.className = 'h-time'; time.textContent = relTime(s.timestamp);
    main.appendChild(title); main.appendChild(time);
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
function loadHistory(id, title) {
  closeMenus();
  // Read-and-reset IMMEDIATELY: historyResumeInPlace must never outlive this one
  // open→pick cycle. Past this line the module flag is back to its default, so any
  // later, unrelated opening/pick of this same panel can't be affected by whichever
  // entry point was used here.
  const resumeInPlace = historyResumeInPlace;
  historyResumeInPlace = false;
  // Already open in ANOTHER tab → don't open a second instance of the same
  // conversation; just switch to that tab. (Re-opening it in its OWN tab still
  // reloads as before.) Applies to BOTH entry points below — never worth a duplicate
  // tab, in place or not.
  const already = tabs.find(tb => tb.sessionId === id && tb.id !== activeId);
  if (already) { switchTab(already.id); return; }
  let items = [];
  try { items = JSON.parse(window._loadSession(id) || '[]'); } catch (e) {}

  // Two entry points, two behaviors (resumeInPlace, read above from historyResumeInPlace
  // — set by whichever openHistory* function opened the panel, see window.openHistory*):
  //
  //  - Toolbar's Session History button → opens a NEW tab, matching the Claude
  //    Terminal view's own History button (--resume is a launch flag, its only
  //    option there). Avoids the old "replace the current tab" behavior's real cost:
  //    it silently discarded an in-progress conversation on that tab, no undo.
  //
  //  - /resume typed in the composer → loads IN PLACE on the CURRENT tab, matching
  //    the CLI's own /resume typed at an existing Claude Terminal prompt: it swaps
  //    THAT session in place too, no new tab. /resume is something you type INTO a
  //    specific conversation ("change what THIS is"), unlike the toolbar's generic
  //    "browse history" with no such context — the two are allowed to differ.
  let t;
  if (resumeInPlace) {
    t = activeTab(); if (!t) return;
    loadRender(t);                        // operate on THIS tab's render state
    // An in-flight stream on the tab being overwritten must stop NOW, or its output
    // keeps landing in a pane that no longer represents that conversation — unlike
    // the new-tab path, this tab is NOT untouched, its live content is about to be
    // replaced out from under it.
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
      const imgs = (it.images || []).map(imageFromBlock).filter(Boolean);
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
      if (!invisible) addUserMessage(p.text, p.chip, imgs, it.id);
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
      assistantTurn().appendChild(makeToolLine(it.name || 'tool', it.input || {}, it.status));
    } else { // text
      flushCompact();
      appendTextStatic(assistantTurn(), it.text || it.content || '');
    }
  });
  flushCompact();
  // draw the connector rails
  pane.querySelectorAll(':scope > .turn').forEach(relinkTurn);
  // Restore this conversation's settings. Our own sidecar (saved per session id)
  // is authoritative — it's the ONLY source of effort and it captures the user's
  // last selection; the transcript is the fallback for model + thinking.
  let saved = {};
  try { saved = JSON.parse(window._loadSessionPrefs ? window._loadSessionPrefs(id) : '{}') || {}; } catch (e) {}

  let think = sawThinking;
  if (saved.thinking === '1') think = true; else if (saved.thinking === '0') think = false;
  t.thinking = think; thinkingOn = think;

  const model = saved.model || lastModel;
  if (model) { t.model = model; curModel = model; updateModelLabel(); }

  // force: the stored pair is restored as-is (thinking and model are already set
  // above), then reconciled once below — see applyTabSettings for the ordering.
  if (saved.effort !== undefined && saved.effort !== '') {
    const ei = parseInt(saved.effort, 10);
    if (!isNaN(ei)) setEffort(ei, { force: true });   // updates the sliders + t.effortIdx
  }
  // A session saved before this gate existed (or on a since-updated CLI) can hold
  // an illegal thinking/effort pair — correct it quietly on restore.
  if (typeof enforceThinkingGate === 'function') enforceThinkingGate({ silent: true });
  else updateThinkingCheck();
  t.thinking = thinkingOn;
  // Permission mode is a launch flag the transcript never records, so the sidecar
  // is the only source. Entries saved before permMode existed fall back to default.
  const pm = saved.permMode || DEFAULT_PERM_MODE;
  t.permMode = pm;
  // Only paint the composer when this tab is the visible one (loadHistory always
  // targets the active tab today, but keep the same guard onResolvedModel uses).
  if (t === activeTab()) { permMode = pm; if (typeof applyModeUI === 'function') applyModeUI(pm); }
  if (typeof notifyStatusSelection === 'function') notifyStatusSelection();
  pane.scrollTop = 0;
  messagesEl.scrollTop = 0;
}


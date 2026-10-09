/* find.js — Find-in-conversation: a per-tab find bar over the active tab's own .pane.
   Ctrl+F (rebindable) is wired through ClaudeGuiView#toggleFindInConversation, which
   calls window.toggleFindBar(); Escape closes it via the usual registerOverlayCancel
   path, same as every other page-local overlay (History, the model picker, …).

   Matches are painted with the CSS Custom Highlight API (CSS.highlights) rather than by
   wrapping matched text in <mark> elements: a wrapped-node approach would mean rebuilding
   the matched text nodes on every keystroke, which risks corrupting markdown-rendered
   markup (inline code, links, bold) and can clobber a live streaming turn's own DOM
   mutations. Highlights paint Ranges without touching the DOM at all, so re-searching is
   just "throw away the old Ranges and build new ones" with nothing to undo.

   Design: only what's on screen is ever highlighted, and it follows the scroll position
   live. A long conversation made typing in the find box scan every text node in the whole
   transcript on every keystroke, and held a live Range for every match found anywhere —
   both costs scale with conversation length and match count instead of with what's
   actually useful to look at. Since the user only cares about matches near what they're
   currently reading, every re-highlight (typing, scrolling, stepping) only ever touches
   the `.turn`s intersecting the viewport — bounded by screen size, not conversation
   length or match density. There is deliberately no "N of M" count: with no fixed search
   window, there both isn't a stable "M" to report and it invites reading a viewport-
   relative position as if it were the match's place in the whole conversation.

   Enter/Shift+Enter still search the WHOLE document for the next/previous match, same as
   a browser's own Ctrl+F — they just don't pre-collect the whole document's matches to do
   it: nextMatch walks outward turn-by-turn (see turnSibling) from wherever the active
   match is (or the near/far edge of the viewport, if none is active yet) until it finds
   one, bounded by how far away that match actually is rather than by conversation length.
   Typing can also jump the view, via the same probe: if what you just typed has no match
   on screen but does exist elsewhere, reHighlight shows it immediately (goToMatch) rather
   than making you press Enter to find out — but only the first time per query, so editing
   a query whose match is already off-screen doesn't re-jump the view on every keystroke.

   State (query, active match, open/closed) is per-tab, parked/restored by
   onFindTabSwitch — called from tabs.js#switchTab exactly like the composer draft. */

const FIND_MATCH_HL = 'cc-find-match';
const FIND_ACTIVE_HL = 'cc-find-active';
const HAS_HIGHLIGHT_API = typeof CSS !== 'undefined' && !!CSS.highlights && typeof Highlight !== 'undefined';
// Last Highlight object painted under each key. CSS.highlights.set() does not reliably
// REPLACE an existing registration under WebKitGTK — clearing the OBJECT itself (a
// `Highlight` is Set-like) before delete()/set() below is a second, independent way to
// make old ranges disappear that doesn't depend on the registry-level calls alone.
let lastMatchHl = null, lastActiveHl = null;

// onFindInput's debounce timer — module-level (not per-tab) since only one find bar is
// ever open/focused at a time. Cleared on tab switch and bar close so a keystroke typed
// just before either can't fire afterward against the wrong tab or a closed bar.
let findInputDebounce = null;
// Scroll re-highlight's own debounce timer, same reasoning — scroll fires far more often
// than is useful to react to.
let findScrollDebounce = null;
// The element currently wired with the scroll listener below, so it can be removed
// again on close/tab-switch rather than accumulating one listener per open.
let findScrollTarget = null;

/** @typedef {Object} FindState
 * @property {boolean} open
 * @property {string} query
 * @property {?Range} active the current Enter/Shift+Enter target, or null if none — the
 *   only match tracked by index/position; everything else visible is just "highlighted",
 *   not addressable, since the viewport set is rebuilt from scratch on every recompute.
 */
/** @param {Tab} tab @returns {FindState} */
function findStateOf(tab) {
  if (!tab.findState) tab.findState = { open: false, query: '', active: null };
  return tab.findState;
}

/** Called from tabs.js#switchTab before the pane swap. Highlights are global (painted
 *  by name, not scoped to a pane), so the outgoing tab's matches must be cleared even
 *  though its pane is just hidden, not destroyed — otherwise they'd still show through
 *  on top of the newly-active pane at the same viewport coordinates. */
function onFindTabSwitch(prevTab, nextTab) {
  // A pending debounced re-highlight (see onFindInput/onFindScroll) closes
  // over activeTab() at the time it FIRES, not when it was scheduled — left alone,
  // switching tabs within the debounce window would fire it against the newly-active
  // tab, overwriting that tab's own query/highlights with the outgoing tab's.
  clearTimeout(findInputDebounce);
  clearTimeout(findScrollDebounce);
  detachFindScroll();
  // Unconditional, not gated on prevTab existing: CSS.highlights is document-global
  // state, not tied to any one pane, so "clear, then repaint whatever the incoming
  // tab has" is the only invariant that's correct in every case — including closing
  // the active tab, where switchTab's prev lookup comes back null because the tab
  // was already spliced out before this runs.
  clearHighlights();
  // The bar's cancel-stack entry belongs to whichever tab had it open, not to the bar
  // element itself — switching away from a tab whose bar was open must pop that entry
  // (otherwise it's stranded: Eclipse still reports the dismiss key as bound to a card
  // that isn't visible, and the actual top of the stack eats the keypress instead), and
  // switching TO a tab whose bar is open must push a fresh one. Gated on the open state
  // actually changing (not "prevTab != nextTab") because both tabs can have the bar open
  // at once — a blind unregister+register there would fire cardClosed()/cardOpened() back
  // to back for no visible change, which is exactly the context-activation churn the
  // toolbar-jump bug is suspected to come from. See carddock.js's pushOverlay/popOverlay.
  // Unconditional unregister (not gated on prevOpen) rather than a symmetric
  // prevOpen/nextOpen diff: closing the active tab splices it out before this runs, so
  // prevTab comes back null and prevOpen reads false even when ITS bar was open (see the
  // clearHighlights comment above) — a diff would miss exactly that case. popOverlay is
  // safe to call speculatively: with nothing registered it just logs a MISS and returns,
  // touching neither the stack nor notifyingCount.
  const nextOpen = nextTab ? findStateOf(nextTab).open : false;
  if (!nextOpen) unregisterOverlayCancel(closeFindBar);
  else {
    const prevOpen = prevTab ? findStateOf(prevTab).open : false;
    if (!prevOpen) registerOverlayCancel(closeFindBar, false);
  }
  const bar = document.getElementById('find-bar');
  if (!nextTab) { if (bar) bar.classList.remove('open'); return; }
  const st = findStateOf(nextTab);
  if (bar) {
    bar.classList.toggle('open', st.open);
    // See openFindBar's matching comment: chrome-row visibility (the update banner,
    // collapsed root row, …) can have changed since this bar last computed its offset,
    // so a tab switch that re-shows it needs a fresh one too, not just openFindBar.
    if (st.open) bar.style.top = (messagesEl.offsetTop + 8) + 'px';
  }
  if (st.open) {
    document.getElementById('find-input').value = st.query;
    attachFindScroll();
    // This runs BEFORE tabs.js#switchTab swaps pane.style.display — the incoming pane
    // is still display:none here, so every turn's getBoundingClientRect() would come
    // back 0,0 and reHighlight would (wrongly) paint nothing. Defer one frame, by which
    // point the swap (a synchronous style write, not itself async) has already applied.
    requestAnimationFrame(() => { if (activeTab() === nextTab) reHighlight(nextTab, false); });
  }
}

window.toggleFindBar = function() {
  const tab = activeTab();
  if (!tab) return;
  const st = findStateOf(tab);
  if (st.open) closeFindBar(); else openFindBar();
};

function openFindBar() {
  const tab = activeTab();
  if (!tab) return;
  const st = findStateOf(tab);
  st.open = true;
  const bar = document.getElementById('find-bar');
  // #find-bar is positioned against #zoom-root (chat.css), whose top edge is the very
  // top of the page — ABOVE #supertab-row/#cwd-row/#toolbar/#update-banner, not the top
  // of the transcript. A fixed CSS `top` therefore floats the bar over whichever of
  // those chrome rows happens to be visible instead of over #messages, covering the tab
  // strip (confirmed by screenshot: the bar sat over the first tab). messagesEl.offsetTop
  // is #messages' actual distance from that same origin, so this clears every combination
  // of shown/hidden chrome rows without hardcoding any of their heights.
  bar.style.top = (messagesEl.offsetTop + 8) + 'px';
  bar.classList.add('open');
  const inp = document.getElementById('find-input');
  inp.value = st.query;
  registerOverlayCancel(closeFindBar, false);
  setTimeout(() => { inp.focus(); inp.select(); }, 0);
  attachFindScroll();
  if (st.query) reHighlight(tab, true);
}
function closeFindBar() {
  const tab = activeTab();
  const bar = document.getElementById('find-bar');
  // A keystroke or scroll just before Escape could otherwise fire after this, repainting
  // highlights for a bar the user just closed.
  clearTimeout(findInputDebounce);
  clearTimeout(findScrollDebounce);
  detachFindScroll();
  if (bar) bar.classList.remove('open');
  if (tab) {
    const st = findStateOf(tab);
    st.open = false;
    st.active = null;
  }
  clearHighlights();
  // A stale "Not found" from this session would otherwise still be on screen the next
  // time the bar opens, before anything has re-run to clear it.
  updateFindCount(null);
  // Removes closeFindBar's entry BY IDENTITY (see ui.js's own history-panel close for
  // the same pattern) rather than popping the top of carddock.js's cancel stack: another
  // overlay/card can have registered on top of this bar's entry since it opened, and a
  // bare unregister here would pop THAT one instead, leaving this bar's own entry
  // stranded in the stack to consume a future dismiss-key press for nothing.
  unregisterOverlayCancel(closeFindBar);
  // Give focus back to the composer — closing a find bar shouldn't strand keyboard
  // focus on a control that just disappeared.
  if (typeof input !== 'undefined' && input) input.focus();
}

/** Wires the viewport scroll listener for the active tab's pane, so manually scrolling
 *  while the bar is open re-highlights to match without needing Enter/typing. Attached
 *  fresh on open/tab-switch and explicitly removed on close/tab-switch (detachFindScroll)
 *  rather than left to accumulate — messagesEl outlives any one tab or find session. */
function attachFindScroll() {
  detachFindScroll();
  findScrollTarget = messagesEl;
  findScrollTarget.addEventListener('scroll', onFindScroll, { passive: true });
}
function detachFindScroll() {
  if (findScrollTarget) findScrollTarget.removeEventListener('scroll', onFindScroll);
  findScrollTarget = null;
}
function onFindScroll() {
  // scrollToMatch's own scrollIntoView sets this flag for exactly this reason: Enter/
  // Shift+Enter already recompute the viewport highlight themselves right after moving
  // it, so reacting to the scroll THEY caused would just redo the same work a frame
  // later — or worse, race it, since suppressFollowTailUpdate's own reset also happens
  // on the next frame.
  if (typeof suppressFollowTailUpdate !== 'undefined' && suppressFollowTailUpdate) return;
  const tab = activeTab();
  if (!tab || !findStateOf(tab).open) return;
  clearTimeout(findScrollDebounce);
  // A MANUAL scroll means the user just told you where they want to look. reHighlight
  // itself drops st.active whenever it's no longer on screen (see its own comment) — if
  // the previously active match scrolled out of view, keeping it "active" would make the
  // next Enter/Shift+Enter resume from that old, no-longer-visible position instead of
  // from where the user is now, which read as "the arrows seem to do nothing" when the
  // jump landed somewhere the user wasn't looking.
  findScrollDebounce = setTimeout(() => {
    reHighlight(tab, false);
  }, 100);
}

/** True if any of `range`'s client rects intersects messagesEl's visible viewport, both
 *  vertically and horizontally (see matchesInViewport's comment on why horizontal matters
 *  too: a wide code block can scroll a match out of view sideways while it's still
 *  vertically within messagesEl's bounds). */
function rangeIntersectsViewport(range) {
  if (!range.startContainer || !range.startContainer.isConnected) return false;
  const box = messagesEl.getBoundingClientRect();
  for (const r of range.getClientRects()) {
    if (r.bottom >= box.top && r.top <= box.bottom && r.right >= box.left && r.left <= box.right) return true;
  }
  return false;
}

function onFindInput() {
  const tab = activeTab();
  if (!tab) return;
  clearTimeout(findInputDebounce);
  const value = document.getElementById('find-input').value;
  findInputDebounce = setTimeout(() => {
    const st = findStateOf(tab);
    st.query = value;
    // st.active is deliberately left alone here — reHighlight decides whether it still
    // matches the new query (activeStillMatches) and invalidates it itself if not. Typing
    // progressively over the same word (e.g. "qui" → "quilt") should hold the view still,
    // not treat every keystroke as a brand new search that forgets where you just were.
    reHighlight(tab, true);   // recomputes #find-count too — see its own docstring
  }, 120);
}
function onFindKeydown(e) {
  if (e.key === 'Enter') { e.preventDefault(); findStep(e.shiftKey ? -1 : 1); }
}

// A 1-2 character query against a long conversation routinely matches by the
// thousands — noisy to look at and expensive to search for a query too short to mean
// anything specific yet. Below this, the bar just doesn't search: highlights clear.
const FIND_MIN_QUERY_LEN = 3;

/** Recomputes and paints highlights for every match within the current viewport, and
 *  updates the "Not found" indicator. Normally passive — doesn't move the view — EXCEPT
 *  when `allowJump` is true, the viewport is empty, and nothing is active yet (see
 *  below), the one case where this jumps the view on its own.
 *
 *  `allowJump` must be false for any call triggered BY a scroll (onFindScroll) or by a
 *  tab becoming visible again (onFindTabSwitch) — the user just told you where they want
 *  to be by scrolling/switching there, so auto-centering on an off-screen match would
 *  yank the view right back. It's true only for calls triggered by typing (onFindInput)
 *  or opening the bar fresh (openFindBar), where there's no existing scroll position to
 *  respect yet and jumping to the match is the whole point.
 *
 *  An empty viewport does NOT by itself mean an empty conversation — the query may
 *  simply match somewhere off-screen — so when the viewport comes up empty this also
 *  probes forward and backward from it (nextMatch with no starting point) before
 *  declaring "Not found". That probe is cheap on the common case (a match is usually
 *  nearby: it stops at the first turn that has one) and only walks the full document on
 *  a genuine miss — which is exactly when the user needs to be told there's nothing.
 *  This probe (and the "Not found" it can produce) only runs when allowJump is true —
 *  a scroll- or tab-switch-triggered call that lands on an empty viewport just clears
 *  the highlight and leaves the message alone, since it isn't asking "does this exist".
 *
 *  If the probe finds something, it's shown immediately via goToMatch rather than left
 *  off-screen for the user to go hunt for with Enter — but ONLY once st.active no longer
 *  satisfies the CURRENT query (see activeStillMatches): typing progressively over the
 *  same word ("qui" → "quilt") keeps the view exactly where it was, since the match
 *  you're already looking at is still a hit; editing to something that match no longer
 *  contains invalidates it and re-probes, same as if nothing had been found yet. */
function reHighlight(tab, allowJump) {
  const st = findStateOf(tab);
  if (!st.query || st.query.length < FIND_MIN_QUERY_LEN) {
    clearHighlights();
    updateFindCount(null);
    return;
  }
  const needle = st.query.toLowerCase();
  // Validate st.active against the CURRENT query before doing anything else with it — a
  // stale active match (text edited so it no longer satisfies the query) must not be
  // passed to paintHighlights as "active" just because the viewport happens to have
  // OTHER matches of the new query; it also must not be left to feed a later Enter step.
  if (st.active) {
    if (activeStillMatches(st.active, needle)) {
      // Re-anchor the end: the active Range's length is still the OLD needle's, so a
      // longer new needle (matched at the same start) would otherwise paint a highlight
      // shorter than the actual match. Safe without a bounds check — activeStillMatches
      // only returns true when the whole needle already fits from this start position.
      st.active.setEnd(st.active.startContainer, st.active.startOffset + needle.length);
    } else {
      st.active = null;
    }
  }
  // Invariant this whole block maintains: whenever matches are visible, exactly one of
  // them is active. activeStillMatches only checks the TEXT at st.active's position, not
  // whether that position is still on screen — an active match can survive editing the
  // query yet have been scrolled off-screen in the meantime (or, before this check
  // existed, just never have been on screen to begin with while OTHER matches of the
  // same query were visible), leaving nothing visibly marked despite a screen full of
  // highlights. Drop it here too, not just in onFindScroll (which only covers the
  // scroll-triggered path) — this covers every path through reHighlight uniformly.
  if (st.active && !rangeIntersectsViewport(st.active)) {
    st.active = null;
  }
  // A match whose box is in the viewport but clipped away (a long prompt's third line, a
  // collapsed card's output) is not one anybody can see: it neither counts as a visible match
  // here nor is adopted as the active one. With only such matches on screen, the jump below
  // goes to one through goToMatch, which opens what hides it.
  const matches = matchesInViewport(tab.pane, needle);
  const shown = matches.filter(shownInClip);
  if (shown.length) {
    // No active match at all (a fresh query, one just invalidated above, or one dropped
    // by a manual scroll — see onFindScroll): adopt the first VISIBLE one rather than
    // leaving nothing marked. Safe without an allowJump check unlike goToMatch — this
    // never scrolls, it only marks a match that's already on screen, so it can't yank
    // the view like an unconditional jump would. Without this, nothing reads as
    // "selected" until the user presses an arrow once, which is confusing with a screen
    // full of identical-looking matches and made Enter/Shift+Enter's very first press
    // look like it did nothing.
    if (!st.active) st.active = shown[0];
    paintHighlights(matches, st.active);
    updateFindCount(null);
    return;
  }
  // st.active can't be non-null here: the check above already dropped it unless it's
  // in-viewport, and an in-viewport active match would have made shown.length > 0.
  if (!allowJump) { paintHighlights([], null); return; }
  // nextMatch's "no starting point" case begins at the first/last VISIBLE turn — if the
  // viewport has no turns at all (pane not laid out yet, or scrolled into a gap between
  // turns), both directions come back null even though matches could exist elsewhere;
  // fall back to a real document-wide search rather than reporting a false "Not found".
  const found = turnsInViewport(tab.pane).length
      ? (nextMatch(tab.pane, null, 1, needle) || nextMatch(tab.pane, null, -1, needle))
      : firstMatchInDocument(tab.pane, 1, needle);
  if (found) goToMatch(tab, found); else { paintHighlights([], null); updateFindCount('Not found'); }
}

/** True if `active` (a Range from an EARLIER, possibly different query) still starts a
 *  match of the CURRENT `needle` at the same position — i.e. the text hasn't been
 *  edited away from under it, just possibly extended (typing "qui" then "quilt" moves
 *  the query forward over the same word without invalidating where it started). Used by
 *  reHighlight to decide whether to hold the view still or treat the active match as
 *  stale and re-probe for a new one. */
function activeStillMatches(active, needle) {
  if (!active || !active.startContainer || !active.startContainer.isConnected) return false;
  const text = active.startContainer.textContent.toLowerCase();
  return text.startsWith(needle, active.startOffset);
}

/** Moves to `range`: marks it active, scrolls it into view IF IT ISN'T ALREADY (stepping
 *  to a match that's already comfortably on screen shouldn't re-center the view — that
 *  would be a visible jolt on every arrow press for no reason, and made the very first
 *  step from the first visible match look like it did nothing when it re-centered on
 *  the same spot), then repaints highlights for the new viewport and clears any
 *  "Not found". Shared by findStep (Enter/Shift+Enter) and reHighlight's off-screen-match
 *  case (typing revealed a match that wasn't visible yet) — both end up doing exactly
 *  this once a target match is decided on. */
function goToMatch(tab, range) {
  const st = findStateOf(tab);
  st.active = range;
  revealMatch(range);
  // The user pressing Enter/an arrow to jump to a match is what means they've left the
  // tail — not merely "we happened to scroll to get there" (see scrollToMatch's own
  // comment for the rest of this story). Set this here, before the wasVisible branch, so
  // it applies even when the target was already on screen and scrollToMatch never runs:
  // without this, landing on an already-visible match left followTail exactly as it was
  // (often still true from before the search began), so an unrelated autoScroll — a
  // working-indicator tick, streamed content — could yank the view back to the bottom
  // even with scroll-lock armed, since that lock only holds when followTail is ALSO false.
  followTail = false;
  updateJumpToLatest();
  const wasVisible = rangeIntersectsViewport(range);
  if (!wasVisible) {
    scrollToMatch(range);
    // scrollToMatch sets suppressFollowTailUpdate, so the scroll it triggers won't also
    // fire onFindScroll — recompute the viewport highlight here instead, once, now that
    // the view has (or will have, synchronously for an instant scroll) actually moved.
  }
  paintHighlights(matchesInViewport(tab.pane, st.query.toLowerCase()), range);
  updateFindCount(null);
}

function findStep(dir) {
  const tab = activeTab();
  if (!tab) return;
  const st = findStateOf(tab);
  if (!st.query || st.query.length < FIND_MIN_QUERY_LEN) return;
  const needle = st.query.toLowerCase();
  // Wrap around at the document's actual start/end, same as a browser's own Ctrl+F —
  // without this, Enter on the last match would silently do nothing, indistinguishable
  // from a broken feature since there's no "N of M" count to signal "you're at the end".
  const next = nextMatch(tab.pane, st.active, dir, needle) || firstMatchInDocument(tab.pane, dir, needle);
  if (!next) return;   // truly no match anywhere
  goToMatch(tab, next);
}

function updateFindCount(text) {
  const el = document.getElementById('find-count');
  if (el) el.textContent = text || '';
}

/** The direct `.turn` children of `pane` that intersect messagesEl's visible viewport,
 *  in document order. Shared by matchesInViewport (what to highlight) and nextMatch's
 *  "start from the top of the viewport" case (Enter with no active match yet). */
function turnsInViewport(pane) {
  const box = messagesEl.getBoundingClientRect();
  const out = [];
  for (const t of pane.querySelectorAll(':scope > .turn')) {
    const r = t.getBoundingClientRect();
    if (r.bottom >= box.top && r.top <= box.bottom) out.push(t);
  }
  return out;
}

/** The next/previous `.turn` sibling of `turn`, skipping over any non-.turn direct pane
 *  children (a "Claude is working" indicator in working.js, model-switch dividers in
 *  models.js/history.js) — callers that walk turn-by-turn need to agree on what a "turn"
 *  is with turnsInViewport/matchesInTurn's callers, or a sibling walk can silently skip
 *  or misclassify a non-.turn element. */
function turnSibling(turn, dir) {
  let t = turn;
  do {
    t = dir < 0 ? t.previousElementSibling : t.nextElementSibling;
  } while (t && !t.classList.contains('turn'));
  return t;
}

/** Case-insensitive match Ranges for `needle` (already lowercased) within the text nodes
 *  of a single element (one `.turn`), in document order. Deliberately node-local: a match
 *  split across two adjacent text nodes (e.g. a markdown-rendered <strong> boundary
 *  sitting mid-word) is missed rather than risking a Range that spans unrelated markup —
 *  an acceptable, rare miss for a find-as-you-type bar.
 *
 *  Does NOT filter out matches inside collapsed/display:none content (a collapsed
 *  thinking block, a compacted chat's body) — that used to happen here via a trailing
 *  getClientRects() check on every match, but every caller that cares about visibility
 *  already does its own getClientRects()-based check afterward (rangeIntersectsViewport
 *  for the viewport-bound callers, hasAnyRect below for the ones that don't care about
 *  the viewport but still need to skip invisible matches). Doing it here too meant EVERY
 *  match paid for layout twice on the hot path (typing/scrolling in a turn with many
 *  matches) for no benefit — the second check subsumes the first, since an element with
 *  no rects at all also fails the viewport intersection test. Callers that don't need
 *  either check (there are none currently, but a future one might) get the small matches
 *  themselves for free by skipping the extra layout read. */
function matchesInTurn(turn, needle) {
  const ranges = [];
  const walker = document.createTreeWalker(turn, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      const tag = node.parentElement && node.parentElement.tagName;
      if (tag === 'SCRIPT' || tag === 'STYLE') return NodeFilter.FILTER_REJECT;
      // The labels of a card's and a long prompt's own buttons ("Show all", "Show more", …)
      // are controls, not conversation.
      return node.parentElement && node.parentElement.closest('.card-bar, .clamp-toggle, .copy-btn')
          ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
    }
  });
  let node;
  while ((node = walker.nextNode())) {
    const text = node.textContent.toLowerCase();
    let from = 0, idx;
    while ((idx = text.indexOf(needle, from)) !== -1) {
      const r = new Range();
      r.setStart(node, idx);
      r.setEnd(node, idx + needle.length);
      ranges.push(r);
      from = idx + needle.length;
    }
  }
  return ranges;
}

/** True if `r` has any box at all — false for a Range inside collapsed/display:none
 *  content (a collapsed thinking block, a compacted chat's body). Used by callers that
 *  need to skip invisible matches but, unlike rangeIntersectsViewport, don't care whether
 *  the match is actually on screen (nextMatch/firstMatchInDocument can legitimately land
 *  on a match far outside the viewport — see the file header on why Enter jumps freely). */
function hasAnyRect(r) {
  return r.getClientRects().length > 0;
}

/* ── Matches in text that is cut off or folded away ──────────────────────────────────────
   A long prompt is clamped to two lines, a tool's cards can be collapsed or previewed, a
   thinking block, a compacted summary and an agent's log are folded: the text is in the
   page, but not on screen. Such a match is still a match. Landing on one opens exactly
   what hides it (revealMatch), and it stays open, as a browser's own find leaves a
   revealed <details>. Only CONTENT counts, not the buttons beside it — hidden "Show all" and
   "Collapse" labels must not turn up as results. */
const REVEALABLE = '.io-item pre, .code-block.edit pre, .result-item, .comp-body, .think-body, .agent-log-body';
const OPENS_BY_CLASS = '.compacted, .a-item.think.has-body, .agent-log';
function nodeEl(n) { return n && (n.nodeType === 1 ? n : n.parentElement); }
/** A match worth stopping at: drawn, or hidden by something revealMatch can open. */
function findable(r) { return hasAnyRect(r) || !!nodeEl(r.startContainer).closest(REVEALABLE); }
/** Whether the match is drawn inside the box that clips it, not merely laid out behind it. */
function shownInClip(range) {
  const rr = range.getClientRects()[0];
  if (!rr) return false;
  const clip = nodeEl(range.startContainer).closest('.user-msg .body.clampable, .io-item pre, .code-block pre, .result-list');
  if (!clip) return true;
  const cr = clip.getBoundingClientRect();
  return rr.top >= cr.top - 1 && rr.bottom <= cr.bottom + 1;
}
/** Opens whatever hides the match, a step at a time and no further than it takes. */
function revealMatch(range) {
  const el = nodeEl(range.startContainer);
  if (!el) return;
  for (let a = el.closest(OPENS_BY_CLASS); a; a = a.parentElement && a.parentElement.closest(OPENS_BY_CLASS)) a.classList.add('open');
  const msg = el.closest('.user-msg');
  const body = msg && msg.querySelector('.body.clampable');
  if (body && !shownInClip(range)) {
    msg.classList.add('expanded');
    // Opened, a prompt is capped to a fraction of the view and scrolls inside itself.
    const rr = range.getClientRects()[0], br = body.getBoundingClientRect();
    if (rr && !shownInClip(range)) body.scrollTop += (rr.top - br.top) - (br.height - rr.height) / 2;
  }
  const card = el.closest('.tool-line .io-item, .tool-line .code-block.edit, .tool-line .result-list');
  if (card) {
    // collapsed → preview → expanded: the first of them in which the match is on screen.
    // Only the card that holds the match opens, not the rest of its tool line.
    for (let i = CARD_MODES.indexOf(card.dataset.mode); !shownInClip(range) && i < CARD_MODES.length - 1; ) {
      applyCardMode(card, CARD_MODES[++i]);
    }
  }
}

// A screenful can't usefully show more distinct highlighted matches than this regardless
// of how dense a turn's content is — e.g. a single very long line packed with matches,
// only a few of which are within the horizontal scroll position actually on screen (see
// matchesInViewport). Capping the COLLECTED (not just painted) set at a small number
// bounds the worst case for the live-Range/paint cost this whole design exists to avoid;
// it does NOT bound the scan cost of finding them (see matchesInViewport's own comment)
// — that's a different, harder problem (geometry-first pruning) not attempted here. Split
// per turn, not applied as one global total — see matchesInViewport's own comment on why.
const FIND_VIEWPORT_MATCH_CAP = 60;

/** All matches within the viewport itself — NOT just within the turns that intersect it.
 *  A `.turn` is a whole message, and one turn can be far taller than the screen (a large
 *  tool-output block, e.g. a JSON schema dump, rendered as a single turn): matchesInTurn
 *  finds every match anywhere in that turn regardless of how much of it is actually
 *  scrolled into view, so filtering by TURN intersection alone could still collect (and
 *  paint, as live Ranges) hundreds of matches sitting above/below the visible slice of
 *  that one turn — the exact live-Range cost this whole design exists to bound. Filtering
 *  each match's own rect against the viewport, not just its turn's, is what actually
 *  keeps the live set screen-sized regardless of how a turn's content is laid out.
 *
 *  Note what this does NOT bound: matchesInTurn itself still walks and builds a Range for
 *  EVERY match in a turn that intersects the viewport at all, even ones nowhere near the
 *  visible slice (a turn far taller, or with a much wider single line, than the screen).
 *  Each Range then costs one getClientRects() call here to test visibility. If a turn has
 *  many matches but few are actually visible (e.g. one very long line, most of it
 *  scrolled out of view horizontally, with dozens of matches packed into it), that scan
 *  cost is real and unbounded by FIND_VIEWPORT_MATCH_CAP below — the cap only stops
 *  collecting once enough VISIBLE ones are found, it can't skip the invisible ones without
 *  measuring them first. Bounding that scan itself would need pruning by position before
 *  ever calling getClientRects, which is a real redesign; not attempted here. */
function matchesInViewport(pane, needle) {
  const turns = turnsInViewport(pane);
  // Capped PER TURN, not as one global running total that stops the whole scan once hit:
  // the viewport commonly spans several turns, and a global cap filled entirely by a
  // dense first turn would paint zero highlights in every turn below it, even where the
  // user is actually looking. Splitting the budget evenly means a screen with one dense
  // turn and several sparse ones still lights up everywhere there's a match, just with a
  // smaller share going to the dense one — a strict global total isn't worth that cost.
  const perTurnCap = Math.max(8, Math.floor(FIND_VIEWPORT_MATCH_CAP / Math.max(1, turns.length)));
  const matches = [];
  for (const t of turns) {
    let inTurn = 0;
    for (const m of matchesInTurn(t, needle)) {
      // rangeIntersectsViewport checks every client rect a match has (it can wrap across
      // a line break) against BOTH axes — see its own comment on why horizontal bounds
      // matter here too: a wide code block (e.g. an indented JSON dump) renders inside
      // its own horizontally-scrolling <pre>, and a match scrolled out of THAT box still
      // reports its laid-out position in viewport coordinates, which can land far to the
      // right of messagesEl's own visible width. Without this check such a match passes
      // a vertical-only test, gets highlighted, and is completely invisible (painted off
      // to the side) — indistinguishable from "not highlighted at all" to the user, while
      // Enter/Shift+Enter still find and jump to it.
      if (rangeIntersectsViewport(m)) {
        matches.push(m);
        if (++inTurn >= perTurnCap) break;   // next TURN, not the whole scan — see above
      }
    }
  }
  return matches;
}

/** Finds the next (dir>0) or previous (dir<0) match anywhere in the document relative to
 *  `from` (a Range, normally st.active) — or, if `from` is null, starting at the top of
 *  the current viewport searching forward (dir>0) or its bottom searching backward
 *  (dir<0), matching "Enter with nothing active yet starts from what's on screen". Walks
 *  turn-by-turn (via turnSibling) outward from the starting point, examining the starting
 *  turn's OWN matches first (filtered to strictly after/before `from` when one is given),
 *  then whole turns one at a time until a match is found or the document ends — bounded
 *  by how far away the next match actually is, not by conversation length. */
function nextMatch(pane, from, dir, needle) {
  let turn, withinTurn;
  if (from && from.startContainer && from.startContainer.isConnected) {
    turn = from.startContainer.parentElement && from.startContainer.parentElement.closest('.turn');
  }
  if (turn) {
    withinTurn = matchesInTurn(turn, needle).filter(r => findable(r) &&
      (dir < 0 ? r.compareBoundaryPoints(Range.START_TO_START, from) < 0
               : r.compareBoundaryPoints(Range.START_TO_START, from) > 0)
    );
  } else {
    // No active match (or it's gone stale/detached): start from whichever end of the
    // viewport `dir` searches away from. Filtered to matches actually ON SCREEN in that
    // turn — not the turn's full match list — since with no prior position to step from,
    // "the nearest match to what the user is looking at" means visible, not merely inside
    // whichever turn happens to intersect the viewport (that turn can be far taller than
    // the screen, e.g. a large code block, with matches above/below the visible slice).
    const visible = turnsInViewport(pane);
    turn = dir < 0 ? visible[visible.length - 1] : visible[0];
    withinTurn = turn ? matchesInTurn(turn, needle).filter(rangeIntersectsViewport) : [];
  }
  if (withinTurn.length) return dir < 0 ? withinTurn[withinTurn.length - 1] : withinTurn[0];

  let t = turn;
  while (t) {
    t = turnSibling(t, dir);
    if (!t) return null;
    const m = matchesInTurn(t, needle).filter(findable);
    if (m.length) return dir < 0 ? m[m.length - 1] : m[0];
  }
  return null;
}

/** The very first (dir>0) or very last (dir<0) match in the whole pane, ignoring the
 *  viewport entirely — used by findStep to wrap around once nextMatch runs off the
 *  document's actual start/end, same as a browser's own Ctrl+F. */
function firstMatchInDocument(pane, dir, needle) {
  const turns = pane.querySelectorAll(':scope > .turn');
  if (dir > 0) {
    for (const t of turns) {
      const m = matchesInTurn(t, needle).filter(findable);
      if (m.length) return m[0];
    }
    return null;
  }
  for (let i = turns.length - 1; i >= 0; i--) {
    const m = matchesInTurn(turns[i], needle).filter(findable);
    if (m.length) return m[m.length - 1];
  }
  return null;
}

/** Paints (or repaints) the match/active highlights. Safe to call with an empty array to
 *  clear — the only way to remove a stale highlight when switching tabs or closing the
 *  bar, since CSS.highlights is keyed by name globally, not by element. `active` (if
 *  given and not already inside `matches`, e.g. it just scrolled out of the viewport) is
 *  still painted as the active highlight even though it isn't part of the plain set.
 *
 *  The active match is explicitly EXCLUDED from the plain set below, not just added to
 *  its own on top of it: registering the same Range in two overlapping ::highlight()
 *  sets leaves which one paints on top up to the browser/engine's own priority rules,
 *  and WebKitGTK was observed painting the plain "match" color over the "active" one —
 *  every match looked identical, making Enter/Shift+Enter's effect invisible. Excluding
 *  it removes the overlap entirely rather than trying to out-rank it. */
function paintHighlights(matches, active) {
  if (!HAS_HIGHLIGHT_API) return;   // no highlight support: stepping still works, just unpainted
  const all = new Highlight();
  for (const m of matches) {
    if (active && m.compareBoundaryPoints(Range.START_TO_START, active) === 0) continue;
    all.add(m);
  }
  // CSS.highlights.set() does NOT reliably REPLACE an existing registration under this
  // WebKitGTK — observed on both sets independently: (1) set() with a zero-range Highlight
  // over a previously non-empty one left the old ranges visibly painted (a query going
  // from matches to no-matches, e.g. typing an extra space, left the narrower query's
  // stale highlight on screen even though #find-count correctly said "Not found"); (2) far
  // more strikingly, typing "verify" in one continuous burst (no backspace) left an
  // earlier "ver"-era single-range FIND_ACTIVE_HL highlight painted in the ACTIVE color
  // alongside the new one — two active-colored matches on screen from one search, not a
  // plain/active color mismatch. Both are the same underlying failure: set() over an
  // existing key doesn't evict what was there. CSS.highlights.delete() does not have this
  // problem (clearHighlights, which has always used delete, has never shown this symptom)
  // — so unconditionally delete before every set on both keys, not just the empty-match
  // branch this fix originally only covered. lastMatchHl/lastActiveHl's own .clear() is a
  // second, independent belt-and-braces measure — see their declaration above.
  if (lastMatchHl) lastMatchHl.clear();
  CSS.highlights.delete(FIND_MATCH_HL);
  if (all.size > 0) { CSS.highlights.set(FIND_MATCH_HL, all); lastMatchHl = all; } else { lastMatchHl = null; }
  if (lastActiveHl) lastActiveHl.clear();
  CSS.highlights.delete(FIND_ACTIVE_HL);
  if (active) {
    const activeHl = new Highlight(active);
    CSS.highlights.set(FIND_ACTIVE_HL, activeHl);
    lastActiveHl = activeHl;
  } else {
    lastActiveHl = null;
  }
}
function clearHighlights() {
  if (!HAS_HIGHLIGHT_API) return;
  if (lastMatchHl) lastMatchHl.clear();
  if (lastActiveHl) lastActiveHl.clear();
  lastMatchHl = null;
  lastActiveHl = null;
  CSS.highlights.delete(FIND_MATCH_HL);
  CSS.highlights.delete(FIND_ACTIVE_HL);
}

/** Scrolls a match into view. Only handles the scroll itself and the bookkeeping that
 *  goes with a scroll specifically — goToMatch (this function's only caller) disarms
 *  followTail unconditionally before deciding whether a scroll is even needed at all,
 *  since "the user jumped to a match" is what means they left the tail, not "a scroll
 *  happened to occur"; see goToMatch's own comment for why that distinction mattered.
 *
 *  suppressFollowTailUpdate: chat.js's 'scroll' listener normally recomputes followTail
 *  on every scroll (isNearBottom()), which would immediately overwrite goToMatch's
 *  explicit false the instant this scroll fires its own 'scroll' event — a plain save/
 *  restore around the write would race that listener (it could run after the restore and
 *  clobber followTail right back). Setting this flag first makes the listener skip that
 *  recompute for exactly this one scroll. Cleared on the next frame, the reliable point
 *  by which the browser has dispatched the event for this synchronous write.
 *
 *  Sets messagesEl.scrollTop directly from the Range's own rect rather than delegating
 *  to Element.scrollIntoView on some element derived from the Range: a match inside a
 *  horizontally-scrollable code block (.code-block pre / .a-body pre code, both
 *  overflow-x: auto) has THAT element as its nearest scrollable ancestor, and
 *  scrollIntoView is free to satisfy the request by scrolling it instead of messagesEl
 *  — leaving messagesEl exactly where it was while reporting success. This was the root
 *  cause of "stuck" navigation into a dense syntax-highlighted JSON block: every step
 *  correctly advanced to a new, distinct match, but the container that actually needed
 *  to move never did. Computing scrollTop from getClientRects() bypasses ancestor
 *  selection entirely — there's only one container being asked to scroll. */
function scrollToMatch(range) {
  const rects = range.getClientRects();
  const r = rects.length ? rects[0] : null;
  if (!r) return;
  const box = messagesEl.getBoundingClientRect();
  suppressFollowTailUpdate = true;
  messagesEl.scrollTop += (r.top - box.top) - (box.height - r.height) / 2;
  requestAnimationFrame(() => { suppressFollowTailUpdate = false; });
}

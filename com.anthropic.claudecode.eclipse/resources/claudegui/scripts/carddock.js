/* carddock.js — Bottom card dock: floats decision/question cards over the transcript at the
   composer position. */

/* ---- bottom card dock: cards float over the transcript at the composer's
   position while active (joebiden3). #messages keeps full height; we reserve
   bottom padding equal to the card height so the last message scrolls clear. ---- */
const bottomCardEl = document.getElementById('bottom-card');
function padForBottomCard() {
  messagesEl.style.paddingBottom = (bottomCardEl.offsetHeight + 8) + 'px';
}
/* Tracks BOTH growth and shrink (question-tab switches, the "Other" input
   toggling): recompute the reserved padding AND re-pin the transcript to the
   bottom, so the last message always sits exactly one gap above the card —
   never covered by a taller tab, never floating high above a shorter one. */
new ResizeObserver(() => {
  if (bottomCardEl.style.display === 'block') { padForBottomCard(); scrollBottom(); }
}).observe(bottomCardEl);
/* A pending card belongs to the conversation that raised it (its stream tab), and
   is stored ON that Tab (Tab.pendingCard) rather than in one shared global — each
   tab runs its own CLI process and can have its own card pending independently
   (e.g. tab A is mid-AskUserQuestion when tab B's background process raises its
   own approval prompt). A single shared slot would have the later card silently
   evict the earlier one: the evicted tab's card vanishes from the DOM with no
   cleanup (its keydown listener and timeout registration both leak, still armed
   against a card no longer shown), and its Java-side future is orphaned — nothing
   can ever answer it, so that tab hangs on its tool's "pending" state until the
   timeout preference (or the user interrupting the session) ends it. Only shows
   while its owning tab is active; switching away hides it (composer returns),
   switching back re-shows THAT tab's own card, not whichever was raised last. */
function showBottomCard(card, owner) {
  owner.pendingCard = card;
  renderBottomCard();
}
function renderBottomCard() {
  const composer = document.getElementById('composer');
  const t = activeTab();
  const card = t && t.pendingCard;
  if (card) {
    if (bottomCardEl.firstChild !== card) { bottomCardEl.innerHTML = ''; bottomCardEl.appendChild(card); }
    bottomCardEl.style.display = 'block';
    composer.style.display = 'none';
    // Card only shows for the active tab → autoScroll, not scrollBottom, whose background
    // guard reads an rtab that may be a stale background stream. autoScroll has no such
    // guard and obeys Scroll Lock, which a card must too: the card floats at the composer
    // position and is fully visible wherever the transcript sits, so holding still costs
    // the user nothing and moving them costs them their place.
    // padForBottomCard stays OUTSIDE the scroll decision — the reserved space is layout,
    // not movement, and it has to be right for when they do scroll back down.
    requestAnimationFrame(() => { padForBottomCard(); autoScroll(); });
  } else {
    bottomCardEl.innerHTML = '';
    bottomCardEl.style.display = 'none';
    messagesEl.style.paddingBottom = '';
    composer.style.display = '';
  }
}
/** @param {Tab} owner the tab whose OWN card is being cleared — never inferred
 *  from rtab/activeTab, so dismissing tab A's card can never touch tab B's. */
function clearBottomCard(owner) {
  if (owner) owner.pendingCard = null;
  renderBottomCard();      // hides the card, restores the composer (if this was the active tab's)
  input.focus();
  // A blocking card (AskUserQuestion / approval) suspends the turn WITHOUT ending
  // it — dismissing the card resumes the same turn, so no onStreamStart fires to
  // restore the gerund. Guarantee it here (the single dismissal choke point) so
  // the working indicator is present whenever a turn is still processing.
  if (owner) loadRender(owner);
  ensureWorking();
}

/* ---- cancel key (Eclipse binding) ----
   Java binds a real Eclipse command to Esc (default scheme) or Ctrl+G (Emacs, where
   Esc is a multi-stroke prefix Eclipse swallows before the page ever sees it) and calls
   cancelActiveCard when it fires. Each card registers its own cancel() here while it is
   up. This is ADDITIVE to the cards' in-page Escape listeners: if the Eclipse dispatcher
   route turns out not to reach us from inside WebView2, default-scheme Esc keeps working
   exactly as it did, so the worst case is no change rather than a regression.

   setCancelHint carries the label of whichever key is actually bound, so the card can
   advertise the truth instead of hardcoding "Esc". Empty means nothing is bound — the
   card then shows no hint at all rather than naming a key that does nothing. */
let cancelHint = 'Esc';
let activeCardCancel = null, activeCancelIsBottom = false, activeCancelOwner = null;

/* Every hint on screen repaints itself when the binding changes, rather than waiting to be
   rebuilt. Java pushes a new label the moment Eclipse's BindingManager fires — switching
   scheme in Preferences and hitting Apply must update visible text there and then, not on
   the next card. Keyed by element and self-pruning: a hint whose card is gone is simply
   dropped on the next pass, so no surface needs teardown wiring. */
const hintPainters = new Map();   // element -> () => void
function registerHintPainter(el, paint) {
  if (!el) return;
  hintPainters.set(el, paint);
  // Guarded like the refresh pass: a hint that fails to draw must not take the card it
  // belongs to down with it. The hint is the least important thing on screen.
  try { paint(); } catch (e) {}
}
function refreshCancelHints() {
  for (const [el, paint] of hintPainters) {
    if (!el.isConnected) { hintPainters.delete(el); continue; }
    try { paint(); } catch (e) {}
  }
}
window.setCancelHint = function(label) {
  const next = label || '';
  if (next === cancelHint) return;
  cancelHint = next;
  refreshCancelHints();
};
/* Raw key name for hints that embed it in their own sentence ("… to close", "(Esc)"). */
function cancelKeyName() { return cancelHint; }
function cancelHintText() { return cancelHint ? cancelHint + ' to cancel' : ''; }

/* Java-raised blocking cards: Java already activated the key context around its own
   future.get(), so these must NOT notify it again. owner is the tab this card belongs
   to (the same owner showBottomCard was given) — activeCardCancel is still ONE global
   slot, so without it a card raised on a background tab would leave its cancel() as
   the one that fires when the dismiss key is pressed over a DIFFERENT tab's own card. */
function registerCardCancel(fn, owner) { activeCardCancel = fn; activeCancelIsBottom = true; activeCancelOwner = owner; }
function unregisterCardCancel() { activeCardCancel = null; activeCancelIsBottom = false; activeCancelOwner = null; }

/* Page-local overlays (advisor card, rewind picker, lightbox). Java cannot know these are
   open — nothing on its side raised them — so the page has to say so, or the key context
   never activates and the key stays dead however honest the hint is. _overlayOpen is
   edge-triggered on the Java side, so a missed unregister can only ever be one deep and the
   next register/unregister corrects it. owner: see registerCardCancel — only meaningful
   when isBottomCard, since a non-bottom overlay isn't tab-owned. */
function registerOverlayCancel(fn, isBottomCard, owner) {
  activeCardCancel = fn; activeCancelIsBottom = !!isBottomCard; activeCancelOwner = owner || null;
  if (window._overlayOpen) window._overlayOpen(true);
}
function unregisterOverlayCancel() {
  activeCardCancel = null; activeCancelIsBottom = false; activeCancelOwner = null;
  if (window._overlayOpen) window._overlayOpen(false);
}

window.cancelActiveCard = function() {
  if (!activeCardCancel) return;
  // Bottom cards only: one parked on a background tab must not vanish because a key was
  // pressed over another conversation. Mirrors renderBottomCard's own visibility test,
  // now split into two checks since each tab tracks its own pendingCard instead of one
  // shared pendingCard/pendingCardOwner pair: the ACTIVE tab must actually have a card
  // showing, AND it must be the same tab that registered this cancel — two different
  // background tabs can each have their own card pending, so "some card is showing" is
  // not enough on its own; it has to be THIS card's owner specifically, or switching to
  // tab A while activeCardCancel is still tab B's would fire B's cancel from A's screen.
  // Overlays (rewind, lightbox) are not tab-owned, so the test does not apply to them.
  const t = activeTab();
  if (activeCancelIsBottom && (!t || !t.pendingCard || activeCancelOwner !== t)) return;
  const fn = activeCardCancel;
  activeCardCancel = null; activeCancelIsBottom = false; activeCancelOwner = null;
  fn();
};

/* ---- server-side timeout dismissal ----
   The Java side blocks on a per-card timeout preference and, on expiry, already
   answers the CLI itself (deny / dismissed) before this ever runs — it just has
   no way to tell the page the card it raised is now moot. Each card registers a
   same-shape cleanup here (index by reqId) right before showBottomCard, and
   unregisters it the moment it resolves itself (click / Enter / Esc) so a click
   racing the timeout can't double-fire. Java invokes dismissTimedOutCard via
   browser.execute once its future.get(...) times out. */
const pendingCardTimeouts = new Map();  // reqId -> () => void
function registerCardTimeout(reqId, onTimedOut) { pendingCardTimeouts.set(reqId, onTimedOut); }
function unregisterCardTimeout(reqId) { pendingCardTimeouts.delete(reqId); }
window.dismissTimedOutCard = function(reqId) {
  const fn = pendingCardTimeouts.get(reqId);
  if (!fn) return;   // already resolved by the user, or not this page's card
  pendingCardTimeouts.delete(reqId);
  fn();
};


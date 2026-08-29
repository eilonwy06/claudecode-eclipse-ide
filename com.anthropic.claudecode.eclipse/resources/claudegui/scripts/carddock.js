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
/* A pending card belongs to the conversation that raised it (its stream tab). It
   only shows while THAT tab is active; switching away hides it (composer returns),
   switching back re-shows it. */
let pendingCard = null, pendingCardOwner = null;
function showBottomCard(card) {
  pendingCard = card;
  pendingCardOwner = rtab || activeTab();   // set by loadRender in onApprovalRequest/onAskQuestion
  renderBottomCard();
}
function renderBottomCard() {
  const composer = document.getElementById('composer');
  const show = pendingCard && pendingCardOwner && pendingCardOwner === activeTab();
  if (show) {
    if (bottomCardEl.firstChild !== pendingCard) { bottomCardEl.innerHTML = ''; bottomCardEl.appendChild(pendingCard); }
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
function clearBottomCard() {
  const owner = pendingCardOwner;
  pendingCard = null; pendingCardOwner = null;
  renderBottomCard();      // hides the card, restores the composer
  input.focus();
  // A blocking card (AskUserQuestion / approval) suspends the turn WITHOUT ending
  // it — dismissing the card resumes the same turn, so no onStreamStart fires to
  // restore the gerund. Guarantee it here (the single dismissal choke point) so
  // the working indicator is present whenever a turn is still processing.
  if (owner) loadRender(owner);
  ensureWorking();
}


/* working.js — Working indicator: gerund list, morph/cycle animation, show/hide/ensure,
   live token counter. */

/* ---- working indicator (sunburst + gerund) ---- */
const GERUNDS = [
  'Absquatulating','Abstracting','Accomplishing','Actioning','Actualizing','Architecting','Baking','Beaming','Beboppin\'','Befuddling',
  'Billowing','Blanching','Bloviating','Boogieing','Boondoggling','Booping','Bootstrapping','Brainstorming',
  'Brainrotting','Brewing','Bunning','Burrowing','Calculating','Canoodling','Caramelizing','Cascading',
  'Catapulting','Cerebrating','Channeling','Channelling','Chock-a-blocking','Choreographing','Churning','Clauding',
  'Coalescing','Cogitating','Combobulating','Compartmentalizing','Composing','Computing','Concocting','Confabulating','Considering',
  'Contemplating','Cooking','Coruscating','Crafting','Creating','Crocheting','Crunching','Crystallizing','Cultivating','Deciphering',
  'Deliberating','Determining','Dilly-dallying','Discombobulating','Doing','Doodling','Drizzling','Ebbing','Eclipsing',
  'Effecting','Effervescing','Elucidating','Embellishing','Encapsulating','Enchanting','Envisioning','Evaporating','Fanum taxing','Felting','Fermenting',
  'Fiddle-faddling','Finagling','Fishmongering','Flabbergasting','Flambéing','Flibbertigibbeting','Flowing','Flummoxing','Fluttering',
  'Fluxing','Forging','Forming','Frolicking','Frosting','Gallivanting','Galloping','Garnishing',
  'Generating','Gesticulating','Germinating','Gerrymandering','Gibbergaberring','Gitifying','Gooning','Gravitating','Grooving',
  'Gusting','Harmonizing','Hashing','Hatching','Herding','Honking','Hullaballooing','Hyperspacing','Hypnotizing',
  'Hyperenthusiasticating','Ideating','Imagining','Improvising','Incubating','Inferring','Infusing','Inheriting','Intellectualizing',
  'Ionizing','Jitterbugging','Journaling','Julienning','Kneading','Leavening','Levitating','Lollygagging',
  'Malding','Manifesting','Marinating','Meandering','Meowing','Metamorphosing','Mewing','Misting','Moonwalking',
  'Moseying','Mulling','Mustering','Musing','Nebulizing','Nesting','Newspapering','Noodling',
  'Nucleating','Orbiting','Orchestrating','Osmosing','Perambulating','Percolating','Perplexing','Perusing',
  'Philosophising','Photosynthesizing','Pollinating','Polymorphing','Pondering','Pontificating','Pouncing','Precipitating','Prestidigitating',
  'Processing','Proofing','Propagating','Puttering','Puzzling','Quantumizing','Razzle-dazzling','Razzmatazzing',
  'Ratiocinating','Recombobulating','Reticulating','Rizzing','Rizzmastering','Roosting','Ruminating','Sautéing','Scampering',
  'Schlepping','Scurrying','Seasoning','Shenaniganing','Shimmying','Simmering','Skedaddling','Sketching',
  'Skibidirizzing','Skitterscattering','Skylarking','Slithering','Smooshing','Sock-hopping','Sous-viding','Spelunking','Spinning',
  'Sprouting','Stewing','Sublimating','Susurrating','Swirling','Swooping','Symbioting','Synthesizing','Tempering',
  'Thinking','Thundering','Tinkering','Tomfoolering','Topsy-turvying','Transfiguring','Transmogrifying','Transmuting','Twisting',
  'Undulating','Unfurling','Unravelling','Vibing','Vulcanizing','Waddling','Wandering','Warping','Whatchamacalliting',
  'Whirlpooling','Whirring','Whisking','Wibbling','Wewertsing','Woolgathering','Working','Wrangling','Xanthating','Xeriscaping','Xylographing','Zambasdting',
  'Zesting','Zigzagging','Zooming'
];
let workingEl = null, workingGerund = '', lastTokens = 0;
let gerundCycleTimer = null, gerundTypeTimer = null, gerundIdx = 0, gerundHold = 0;
const GERUND_HOLD_START = 5000;   // first gerund holds 5s, then 6s, 7s, 8s, 9s…
const GERUND_HOLD_STEP = 1000;
const GERUND_TYPE_SPEED = 45;
// Schedule the next cycle, growing the hold by 1s each time.
function scheduleGerund() {
  gerundCycleTimer = setTimeout(() => { cycleGerund(); scheduleGerund(); }, gerundHold);
  gerundHold += GERUND_HOLD_STEP;
}
function renderThinkLabel() {
  if (!curThink) return;
  const lbl = curThink.querySelector('.think-label');
  if (lbl) lbl.textContent = 'Thinking…' + (lastTokens > 0 ? ' · ' + lastTokens.toLocaleString() + ' tokens' : '');
}
function shuffleGerunds() {
  const arr = GERUNDS.slice();
  for (let i = arr.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [arr[i], arr[j]] = [arr[j], arr[i]]; }
  return arr;
}
let shuffledGerunds = shuffleGerunds();
const CURSOR = '<span class="cursor">▍</span>';
function escHtml(s) { return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
/* Morph the gerund like VSCode: a cursor (▍) sweeps left→right, a ghost caret (_)
   sits one slot behind it, new letters land behind the ghost while the tail of the
   old word is still visible ahead of the cursor. Types from empty on first show. */
function morphGerund(fromWord, toWord, el, onDone) {
  const from = (fromWord || '') + '...';
  const to = toWord + '...';
  const n = to.length;
  // Caret column c sweeps 0,2,3,…,n. At column c the ▍ eats the old letter from[c]
  // (hidden under it); the ghost _ sits at c-2 with one surviving old letter from[c-1]
  // between it and the caret; everything left of the ghost is the settled new word;
  // from[c+1..] is the remaining old-word tail.
  let c = 0;
  function frame() {
    if (!workingEl || !el.parentNode) return;
    if (c >= n) { el.innerHTML = escHtml(to) + CURSOR; if (onDone) onDone(); return; }
    let html;
    if (c === 0) {
      html = CURSOR + escHtml(from.slice(1));                 // bare caret + full old tail
    } else {
      const settled = escHtml(to.slice(0, c - 2));            // new letters before the ghost
      const survive = escHtml(from.slice(c - 1, c));          // one old letter kept behind caret
      const tail    = escHtml(from.slice(c + 1));             // old word ahead of the caret
      html = settled + '_' + survive + CURSOR + tail;
    }
    el.innerHTML = html;
    c = c === 0 ? 2 : c + 1;
    gerundTypeTimer = setTimeout(frame, GERUND_TYPE_SPEED);
  }
  frame();
}
function cycleGerund() {
  if (!workingEl) return;
  const el = workingEl.querySelector('.gerund');
  if (!el) return;
  const prev = workingGerund;
  gerundIdx = (gerundIdx + 1) % shuffledGerunds.length;
  if (gerundIdx === 0) shuffledGerunds = shuffleGerunds();
  workingGerund = shuffledGerunds[gerundIdx];
  morphGerund(prev, workingGerund, el, null);
}
function showWorking() {
  hideWorking();
  // Nothing running → no gerund, ever. A card can be answered long after the turn
  // behind it ended (a slow decision lets the CLI time the request out, or the
  // process dies while the card sits open): those paths used to re-create the
  // indicator after onStreamEnd, leaving it spinning with nothing left to stop it.
  const owner = rtab || activeTab();
  if (!owner || !owner.streaming) return;
  turnStart = Date.now();
  lastTokens = 0;
  // A compacting turn pins the gerund to "Compacting" — no random pick, no cycling.
  const compacting = !!(rtab && rtab.compacting);
  gerundIdx = Math.floor(Math.random() * shuffledGerunds.length);
  workingGerund = compacting ? 'Compacting' : shuffledGerunds[gerundIdx];
  const pane = streamPane(); if (!pane) return;
  workingEl = document.createElement('div'); workingEl.className = 'turn';
  workingEl.innerHTML = '<div class="working"><span class="sb">' + ICONS.SUNBURST + '</span><span class="gerund"></span></div>';
  pane.appendChild(workingEl);
  // Through autoScroll (chat.js), not a raw write: showWorking runs again after every
  // tool result, not just at turn start, so an ungated write here would re-pin the view
  // mid-turn and the lock would only appear to work on answers that never call a tool.
  // Not scrollBottom() — that reparents workingEl, which we have just appended.
  autoScroll();
  const el = workingEl.querySelector('.gerund');
  gerundHold = GERUND_HOLD_START;
  morphGerund('', workingGerund, el, compacting ? null : () => { scheduleGerund(); });
}
function hideWorking() {
  if (gerundCycleTimer) { clearTimeout(gerundCycleTimer); gerundCycleTimer = null; }
  if (gerundTypeTimer) { clearTimeout(gerundTypeTimer); gerundTypeTimer = null; }
  if (workingEl) { workingEl.remove(); workingEl = null; }
  sweepWorkingNodes(streamPane());
}
/* Remove every indicator in a pane, handle or no handle. `workingEl` is swapped
   per tab by loadRender, so a badly-timed tab switch can leave one in the DOM that
   no global points at any more — a lost handle must not become a permanent gerund. */
function sweepWorkingNodes(pane) {
  if (!pane) return;
  pane.querySelectorAll('.working').forEach(w => {
    const turn = w.parentNode;   // showWorking gives each indicator its own .turn
    if (turn && turn.classList && turn.classList.contains('turn')) turn.remove();
    else w.remove();
  });
}
/* The turn in tab `t` is over → t carries no indicator, whichever tab's render
   state happens to be loaded right now. Every turn-ending path funnels here. */
function stopWorkingFor(t) {
  if (!t) return;
  // The gerund timers are single globals, not per-tab render state — clearing them
  // when there is nothing to remove would freeze a BACKGROUND tab's live morph
  // mid-word. Only take the full hideWorking path when this tab really has one.
  if (t === rtab && t.pane && t.pane.querySelector('.working')) { hideWorking(); return; }
  if (t._r) t._r.workingEl = null;
  if (t === rtab) workingEl = null;
  sweepWorkingNodes(t.pane);
}
/* Watchdog. The two rules above cover every path we know of; this one holds even
   for a path we don't: a tab that is not streaming has no process behind it, so an
   indicator in it is stale by definition. Cheap — it only touches a pane that has
   one, which in normal operation is never. */
function sweepStaleWorking() {
  if (typeof tabs === 'undefined' || !tabs) return;
  tabs.forEach(t => {
    if (!t || t.streaming || !t.pane) return;
    if (!t.pane.querySelector('.working')) return;   // nothing stranded here
    stopWorkingFor(t);
  });
}
setInterval(sweepStaleWorking, 2000);
/* Invariant: the gerund is visible whenever a turn is still processing AND no
   card has replaced it. Idempotent — safe to call from any path. */
function ensureWorking() {
  // Operates on the render-target tab; don't show a gerund if that tab's own card
  // is pending (it replaces the working indicator).
  if (rtab && rtab.streaming && !workingEl && !(pendingCard && pendingCardOwner === rtab)) showWorking();
}
/* Live token count shown beside the "Thinking…" marker (real output tokens via the
   CLI partial-message stream), removed once thinking finalizes to "Thought for Ns". */
window.onTokens = function(tabId, n) {
  const t = tabById(tabId); if (!t) return;
  loadRender(t);
  n = parseInt(n, 10) || 0;
  if (n > lastTokens) lastTokens = n;
  renderThinkLabel();
};


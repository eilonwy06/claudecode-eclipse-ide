/* working.js — Working indicator: gerund list, morph/cycle animation, show/hide/ensure,
   live token counter. */

/* ---- working indicator (sunburst + gerund) ---- */
const GERUNDS = [
  'Absquatulating','Abstracting','Accomplishing','Actioning','Actualizing','Architecting','Aura farming','Baking','Basking','Beaming','Beboppin\'','Befuddling',
  'Billowing','Blanching','Bloviating','Boogieing','Boondoggling','Booping','Bootstrapping','Brainstorming',
  'Brainrotting','Brewing','Bunning','Burrowing','Bussing','Calculating','Canoodling','Cappin\'','Caramelizing','Caramelizing onions','Cascading',
  'Catapulting','Cerebrating','Channeling','Channelling','Chock-a-blocking','Choreographing','Churning','Clauding',
  'Coalescing','Cogitating','Combobulating','Compartmentalizing','Composing','Computing','Concocting','Confabulating','Considering',
  'Contemplating','Cooking','Coruscating','Crafting','Creating','Crocheting','Crunching','Crystallizing','Cultivating','Deciphering',
  'Deliberating','Determining','Dilly-dallying','Discombobulating','Doing','Doodling','Drizzling','Ebbing','Eclipsing','Edging',
  'Effecting','Effervescing','Elucidating','Embellishing','Encapsulating','Enchanting','Envisioning','Evaporating','Fanum taxing','Fathoming','Felting','Fermenting',
  'Fiddle-faddling','Figuring','Finagling','Fishmongering','Flabbergasting','Flambéing','Flibbertigibbeting','Flowing','Flummoxing','Fluttering',
  'Fluxing','Forging','Forming','Frolicking','Frosting','Gallivanting','Galloping','Garnishing',
  'Generating','Gesticulating','Germinating','Gerrymandering','Gibbergaberring','Gitifying','Glazing','Gooning','Gravitating','Grooving',
  'Gusting','Gyatting','Harmonizing','Hashing','Hatching','Herding','Honing','Honking','Hullaballooing','Hyperspacing','Hypnotizing',
  'Hyperenthusiasticating','Ideating','Imagining','Improvising','Incubating','Inferring','Infusing','Inheriting','Intellectualizing',
  'Ionizing','Jitterbugging','Journaling','Julienning','Kneading','Leavening','Levitating','Lollygagging','Looksmaxxing',
  'Malding','Manifesting','Marinating','Meandering','Meowing','Metamorphosing','Mewing','Misting','Moonwalking',
  'Moseying','Mulling','Mustering','Musing','Nebulizing','Nesting','Newspapering','Noodling',
  'Nucleating','Orbiting','Orchestrating','Osmosing','Perambulating','Percolating','Perplexing','Perusing','Picturing',
  'Philosophising','Photosynthesizing','Poggering','Pollinating','Polymorphing','Pondering','Pontificating','Pouncing','Precipitating','Prestidigitating',
  'Processing','Proofing','Propagating','Puttering','Puzzling','Quantumizing','Razzle-dazzling','Razzmatazzing',
  'Ratiocinating','Reckoning','Recombobulating','Reticulating','Rizzing','Rizzmastering','Roosting','Ruminating','Sautéing','Scampering',
  'Schlepping','Scurrying','Seasoning','Shenaniganing','Shimmying','Sifting','Sigmaing','Simmering','Skedaddling','Sketching',
  'Skibidirizzing','Skitterscattering','Skylarking','Slithering','Smooshing','Sock-hopping','Sous-viding','Spelunking','Spinning',
  'Sprouting','Stewing','Sublimating','Susurrating','Swirling','Swooping','Symbioting','Synthesizing','Tempering',
  'Thinking','Thundering','Tinkering','Tomfoolering','Topsy-turvying','Transfiguring','Transmogrifying','Transmuting','Triangulating','Twisting',
  'Undulating','Unfurling','Unravelling','Untangling','Vibecoding','Vibing','Vulcanizing','Waddling','Wandering','Warping','Weighing','Whatchamacalliting',
  'Whirlpooling','Whirring','Whisking','Wibbling','Wewertsing','Woolgathering','Working','Wrangling','Xanthating','Xeriscaping','Xylographing','Zambasdting',
  'Zesting','Zigzagging','Zooming'
];

/* Optional slices of GERUNDS, each behind a preference checkbox (Preferences >
   Claude Code > Miscellaneous Configuration). These are MEMBERSHIP LABELS over the
   master list above, not separate word lists: a word rotates when it belongs to no
   set at all, or to at least one ENABLED set. Written this way so a category can
   never silently disagree with the master list: a name here that isn't in GERUNDS
   simply matches nothing, which the test asserts against (SpinnerVerbsParityTest,
   which also holds SpinnerVerbs.java — the Terminal's mirror of these sets — to
   whatever this file says).

   The sets are pairwise DISJOINT — every word belongs to at most one category, so a
   checkbox is the sole owner of the words it names. Wewertsing and Zambasdting read
   as pack-one words but are dank; they live in `dank` alone, which is why the two of
   them leave the rotation by default. The union rule above still holds and needs no
   special case either way; disjointness is a rule about the data, and the test
   enforces it so a word can't quietly acquire a second owner. */
const VERB_SETS = {
  deprecated: [
    'Accomplishing','Actioning','Actualizing','Doing','Effecting','Processing'
  ],
  pack1: [
    'Absquatulating','Abstracting','Brainstorming','Chock-a-blocking','Compartmentalizing',
    'Confabulating','Coruscating','Crocheting','Eclipsing','Effervescing','Encapsulating',
    'Evaporating','Felting','Fishmongering','Flabbergasting','Fluxing','Gerrymandering',
    'Gibbergaberring','Gravitating','Hypnotizing','Hyperenthusiasticating','Inheriting',
    'Intellectualizing','Journaling','Meowing','Perplexing','Polymorphing','Ratiocinating',
    'Skitterscattering','Skylarking','Sous-viding','Susurrating','Transmogrifying',
    'Vulcanizing','Woolgathering','Xanthating','Xeriscaping','Xylographing','Zooming'
  ],
  pack2: [
    'Fathoming','Triangulating','Picturing','Figuring','Weighing','Honing','Sifting',
    'Untangling','Reckoning','Caramelizing onions','Basking'
  ],
  dank: [
    'Brainrotting','Wewertsing','Fanum taxing','Gooning','Malding','Mewing','Rizzing',
    'Rizzmastering','Skibidirizzing','Zambasdting','Aura farming','Looksmaxxing','Sigmaing',
    'Glazing','Bussing','Cappin\'','Gyatting','Edging','Poggering'
  ],
  vibecoder: ['Vibecoding']
};
/* Mirrors the preference defaults in ClaudePreferenceInitializer, so the rotation is
   already correct in the window before the first onSpinnerVerbs push arrives — and
   stays correct if one never does (an older host that doesn't send it). */
let verbSetsEnabled = { deprecated: true, pack1: true, pack2: true, dank: false, vibecoder: false };
/* The user's own spinnerVerbs from ~/.claude/settings.json, sent alongside the categories
   when "Use custom spinner verbs" is on. No default worth mirroring here — the host reads
   that file, so before the first push we simply have none. */
let customVerbs = [];
/* The words currently in rotation: everything not claimed by any set, plus every
   word claimed by at least one enabled set, plus the user's own. A custom verb survives
   a category that would have hidden it — the user named that word themselves — and the
   dedup only stops it being listed twice, which would double how often it comes up. */
function activeGerunds() {
  const claimed = new Set(), allowed = new Set();
  for (const key of Object.keys(VERB_SETS)) {
    for (const w of VERB_SETS[key]) {
      claimed.add(w);
      if (verbSetsEnabled[key]) allowed.add(w);
    }
  }
  const pool = GERUNDS.filter(w => !claimed.has(w) || allowed.has(w));
  const seen = new Set(pool);
  for (const w of customVerbs) if (!seen.has(w)) { seen.add(w); pool.push(w); }
  return pool;
}
/* Preference push from ClaudeGuiView. Rebuilding the pool is not enough: the shuffle
   is only regenerated when gerundIdx wraps, and the hold grows a second per cycle, so
   a stale shuffle would keep serving switched-off words for the rest of the session.
   Reshuffle and rewind the index. A gerund mid-morph is left alone — the next cycle
   picks from the new pool. */
window.onSpinnerVerbs = function (json) {
  let prefs;
  try { prefs = JSON.parse(json); } catch (e) { return; }
  for (const key of Object.keys(verbSetsEnabled))
    if (typeof prefs[key] === 'boolean') verbSetsEnabled[key] = prefs[key];
  /* Its own branch: the loop above only accepts booleans, and an absent array must leave
     the last-known list alone rather than clear it. */
  if (Array.isArray(prefs.custom))
    customVerbs = prefs.custom.filter(w => typeof w === 'string' && w.trim() !== '');
  shuffledGerunds = shuffleGerunds();
  gerundIdx = 0;
};

/* Some waits are for one specific thing, and say so instead of cycling through the
   playful verbs: compaction, and establishing the Remote Control bridge. */
const GERUND_COMPACTING = 'Compacting';
const GERUND_CONNECTING = 'Establishing connection';

/* Breathing-glyph loader (replaces the old rotating SUNBURST SVG) — cycles .sb's
   textContent through a fixed density ramp instead of spinning an icon; see
   /tmp/spinner-demo.html for the reference this was built from. One global interval
   drives every .sb currently in the DOM at once (there can be more than one — a
   background tab's "Establishing connection" indicator alongside the active tab's own),
   so there is nothing to start/stop per element: a removed node just stops being found
   on the next tick, with nothing left to leak. */
const WORKING_GLYPHS = ['·', '✢', '*', '✶', '✻', '✽'];
const WORKING_GLYPH_SEQ = [...WORKING_GLYPHS, ...WORKING_GLYPHS.slice(0, -1).reverse()];
let workingGlyphIdx = 0;
function workingGlyphNow() { return WORKING_GLYPH_SEQ[workingGlyphIdx]; }
setInterval(() => {
  workingGlyphIdx = (workingGlyphIdx + 1) % WORKING_GLYPH_SEQ.length;
  const glyph = workingGlyphNow();
  document.querySelectorAll('.working .sb').forEach(el => { el.textContent = glyph; });
}, 180);

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
  const arr = activeGerunds();
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
  // Also runs while a bridge is being established: that is a wait with nothing
  // streaming, and it deserves the same "something is happening" indicator.
  if (!owner || (!owner.streaming && !owner.rcConnecting)) return;
  stepStart = Date.now();
  lastTokens = 0;
  const compacting = !!(rtab && rtab.compacting);
  const connecting = !!(owner && owner.rcConnecting);
  const pinned = compacting ? GERUND_COMPACTING : (connecting ? GERUND_CONNECTING : null);
  gerundIdx = Math.floor(Math.random() * shuffledGerunds.length);
  workingGerund = pinned || shuffledGerunds[gerundIdx];
  const pane = streamPane(); if (!pane) return;
  workingEl = document.createElement('div'); workingEl.className = 'turn working-turn';
  workingEl.innerHTML = '<div class="working"><span class="sb">' + workingGlyphNow() + '</span><span class="gerund"></span></div>';
  pane.appendChild(workingEl);
  // Through autoScroll (chat.js), not a raw write: showWorking runs again after every
  // tool result, not just at turn start, so an ungated write here would re-pin the view
  // mid-turn and the lock would only appear to work on answers that never call a tool.
  // Not scrollBottom() — that reparents workingEl, which we have just appended.
  autoScroll();
  const el = workingEl.querySelector('.gerund');
  gerundHold = GERUND_HOLD_START;
  morphGerund('', workingGerund, el, pinned ? null : () => { scheduleGerund(); });
}
/* Compaction is over, and the turn it came in the middle of may not be: an automatic one
   comes mid-turn. The indicator pinned to "Compacting" goes back to the playful verbs.
   Nothing else would bring them back before the turn ends: pinning stopped the cycle, and
   ensureWorking leaves an indicator that is already up alone. `t` is the render target. */
function unpinCompactingGerund(t) {
  if (!t || !t.pane || !t.streaming || t.compacting || t.rcConnecting) return;
  const next = shuffledGerunds[Math.floor(Math.random() * shuffledGerunds.length)];
  if (t !== activeTab()) {
    // A still one, as resumeWorkingFor gives a tab that is not in front.
    if (t.pane.querySelector('.working')) appendStaticWorking(t, next);
    return;
  }
  const el = t === rtab && workingEl ? workingEl.querySelector('.gerund') : null;
  if (!el || workingGerund !== GERUND_COMPACTING) return;
  if (gerundCycleTimer) { clearTimeout(gerundCycleTimer); gerundCycleTimer = null; }
  if (gerundTypeTimer) { clearTimeout(gerundTypeTimer); gerundTypeTimer = null; }
  const prev = workingGerund;
  gerundIdx = Math.max(0, shuffledGerunds.indexOf(next));
  workingGerund = next;
  gerundHold = GERUND_HOLD_START;
  morphGerund(prev, workingGerund, el, () => { scheduleGerund(); });
}
/* The "Establishing connection" indicator, in the tab that is CONNECTING —
   which is very often not the tab whose render state is loaded.

   showWorking() reads `rtab` for whether anything is happening and streamPane()
   for where to put it. That is exactly right for a turn: a turn belongs to the
   tab that started it, and that tab is the render target by then. It is wrong
   for the Remote Control bridge, which gets switched on for tabs that are NOT
   the render target — a tab being created (rtab still points at the one being
   left) and, on view load, every restored conversation at once. Those calls
   either bailed on `owner` or drew into another tab's pane, and the leading
   hideWorking() then swept the one indicator that had landed correctly. With
   "Remote Control on startup" set, the result was a disabled composer saying
   "Establishing connection…" with nothing spinning anywhere.

   Same node and same markup as showWorking; only the morph is dropped, since
   animating a background tab would drive the single set of gerund timers that
   the tab in front is using. The label is pinned, so there is nothing to cycle. */
function showWorkingFor(t) {
  if (!t || !t.pane || !t.rcConnecting) return;
  if (t === rtab) { showWorking(); return; }
  appendStaticWorking(t, GERUND_CONNECTING);
}
/* An indicator that says `word` and does not morph or cycle: the node showWorking makes,
   without the timers. For a tab whose indicator must not drive the single set of gerund
   timers, because another tab's live morph may be running on them. */
function appendStaticWorking(t, word) {
  sweepWorkingNodes(t.pane);
  const el = document.createElement('div');
  el.className = 'turn working-turn';
  el.innerHTML = '<div class="working"><span class="sb">' + workingGlyphNow() +
    '</span><span class="gerund">' + escHtml(word + '...') + CURSOR + '</span></div>';
  t.pane.appendChild(el);
  // Parked where loadRender looks, so switching to this tab ADOPTS the node
  // instead of loading a null handle over it and stranding it in the DOM. The
  // render target's own state is the globals, not _r.
  if (t === rtab) workingEl = el;
  else { t._r = t._r || {}; t._r.workingEl = el; }
  if (t.pane === (activeTab() && activeTab().pane)) autoScroll();
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
    // A tab establishing the Remote Control bridge is NOT streaming and has no
    // turn behind it, but its indicator is live and belongs to a wait that is
    // still going. Without this it was swept within two seconds, leaving the
    // composer shut with nothing on screen explaining why.
    if (!t || t.streaming || t.rcConnecting || !t.pane) return;
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
  if (rtab && rtab.streaming && !workingEl && !rtab.pendingCard) showWorking();
}
/* A Remote Control wait takes the indicator down: switching it on pins "Establishing
   connection" over the gerund, and the wait ends by removing that. A turn that was
   already running when the wait began is still running when it ends, and nothing else
   brings its gerund back before the next turn boundary — text streaming in does not.

   The tab in front gets the live gerund (loadRender first, as carddock.js does: the render
   target is whichever tab spoke last). Any other tab gets a still one: showWorking would
   clear the gerund timers, which are single globals and may be running the morph of the
   tab the user is looking at. */
function resumeWorkingFor(t) {
  if (!t || !t.pane || !t.streaming || t.pendingCard) return;
  if (t === activeTab()) { loadRender(t); ensureWorking(); return; }
  if (t.pane.querySelector('.working')) return;
  const word = t.compacting ? GERUND_COMPACTING
      : shuffledGerunds[Math.floor(Math.random() * shuffledGerunds.length)];
  appendStaticWorking(t, word);
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


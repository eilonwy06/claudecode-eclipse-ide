/* advisor.js — /advisor card: dynamic labels from the model catalog, advisorModel
   settings bridge. */

/* ===================== /advisor card =====================
   The terminal /advisor content (joebiden2) rendered in the decision-card chrome
   (joebiden3): title, description, numbered options with a green ✓ on the current
   advisor, recommended-setup note + learn-more link below the choices. Enter
   confirms, Esc cancels (wired for real), digits pick, arrows move. */
/* Label ↔ CLI settings value. The CLI persists the choice GLOBALLY as
   "advisorModel" in ~/.claude/settings.json ("fable"|"opus"|"sonnet"; key absent
   = disabled) — the card reads/writes THAT via _advisorGet/_advisorSet so it's
   the same setting the terminal /advisor uses. localStorage is only a fallback
   when the Java bridge isn't there (stale jar). */
/* [label, settingsValue] rows. Labels come from the DYNAMIC model catalog
   (/v1/models display names, same source as the model chooser) so "Opus 4.8"
   becomes "Opus 5" automatically when it ships — nothing version-y is hardcoded.
   Values stay the stable CLI aliases, which the CLI resolves to its latest. */
const ADVISOR_ALIASES = ['fable', 'opus', 'sonnet'];
/** @returns {[string, string][]} [label, settingsValue] rows; last row is ["No advisor", ""] */
function advisorOptions() {
  const opts = ADVISOR_ALIASES.map(alias => {
    const m = MODELS.find(x => x.id === alias);
    return [m ? m.label : alias.charAt(0).toUpperCase() + alias.slice(1), alias];
  });
  opts.push(['No advisor', '']);
  return opts;
}
/** @returns {string} current advisorModel from ~/.claude/settings.json ("" = none) */
function advisorCurrent() {
  try {
    if (window._advisorGet) {
      const o = JSON.parse(window._advisorGet() || '{}') || {};
      return o.advisorModel || '';
    }
  } catch (e) {}
  try { return localStorage.getItem('claude-advisor') || ''; } catch (e) {}
  return '';
}
/** @param {string} [echoText] the command to echo into the transcript, but only
 *  once the user actually picks an advisor (nothing is echoed on cancel). */
function openAdvisorCard(echoText) {
  closeMenus(); closeSlash();
  // Opened synchronously by the user's own /advisor command — always the active
  // tab, never a background stream tab (unlike onApprovalRequest/onAskQuestion,
  // which are raised by a possibly-backgrounded tab's own CLI process).
  const owner = activeTab();
  const OPTS = advisorOptions();
  const advisorChoice = advisorCurrent();
  const card = document.createElement('div'); card.className = 'decision advisor';

  const title = document.createElement('div'); title.className = 'adv-title';
  title.textContent = 'Advisor (experimental)';
  card.appendChild(title);
  const desc = document.createElement('div'); desc.className = 'adv-desc';
  desc.textContent = 'When Claude needs stronger judgment — a complex decision, an ambiguous failure, '
    + 'a problem it\'s circling without progress — it escalates to the advisor model for guidance, '
    + 'then resumes. The advisor runs server-side and uses additional tokens.';
  card.appendChild(desc);
  // Scope warning, shown BEFORE the choices: advisorModel lives in
  // ~/.claude/settings.json (the user's home), so it is machine-wide and shared
  // with the Claude Terminal — not scoped to this conversation or workspace.
  const scope = document.createElement('div'); scope.className = 'adv-note adv-scope';
  scope.textContent = 'Advisor is a global setting — it applies to every conversation and workspace '
    + 'on this machine and is shared with the Claude Terminal, not just this session.';
  card.appendChild(scope);

  let curIdx = OPTS.findIndex(([, v]) => v === advisorChoice);
  if (curIdx < 0) curIdx = OPTS.length - 1;   // unknown/absent value → No advisor
  let sel = curIdx;
  let resolved = false;
  function done() {
    resolved = true;
    document.removeEventListener('keydown', onKey, true);
    unregisterOverlayCancel();
    clearBottomCard(owner);
  }
  function cancel() { if (!resolved) done(); }
  function confirm(i) {
    if (resolved) return;
    const label = OPTS[i][0], value = OPTS[i][1];
    if (window._advisorSet) window._advisorSet(value);         // ~/.claude/settings.json
    try { localStorage.setItem('claude-advisor', value); } catch (e) {}
    // The setting is read at claude spawn time — retire every idle tab's process
    // so its next send respawns (and resumes via sessionId) with the new advisor.
    // A tab mid-stream keeps its process; it picks the change up on its own respawn.
    if (window._disposeTab) tabs.forEach(tb => { if (!tb.streaming) window._disposeTab(tb.id); });
    done();
    // The "/advisor" echo lands HERE, not when the card opened: the command only
    // becomes part of the conversation once a choice is made. Cancel/Esc runs
    // cancel() instead, which adds nothing at all.
    if (echoText) addUserMessage(echoText);
    // Target the active tab: addSystem() would follow the render tab instead.
    addSystemTo(activeTab(), value ? 'Advisor set to ' + label + '.' : 'Advisor disabled.');
    scrollBottom();
  }
  function paint() {
    card.querySelectorAll('.dec-opt').forEach((el, j) => el.classList.toggle('sel', j === sel));
  }
  OPTS.forEach(([label], i) => {
    const opt = document.createElement('div'); opt.className = 'dec-opt' + (i === sel ? ' sel' : '');
    opt.innerHTML = '<span class="num"></span><span class="dec-lbl"></span>';
    opt.querySelector('.num').textContent = (i + 1);
    opt.querySelector('.dec-lbl').textContent = label;
    if (i === curIdx) {
      const chk = document.createElement('span'); chk.className = 'adv-check'; chk.textContent = '✓';
      opt.appendChild(chk);
    }
    opt.onclick = () => confirm(i);
    opt.onmouseenter = () => { sel = i; paint(); };
    card.appendChild(opt);
  });

  const note = document.createElement('div'); note.className = 'adv-note';
  note.textContent = 'Recommended setup: Sonnet as the main model with Opus as the advisor. '
    + 'For certain workloads this gives near-Opus performance with reduced token usage.';
  card.appendChild(note);
  const learn = document.createElement('div'); learn.className = 'adv-learn';
  // No target="_blank": in a webview that opens a bare popup window. ui.js routes
  // every link to the system browser instead.
  learn.innerHTML = 'Learn more: <a href="https://claude.com/blog/the-advisor-strategy">https://claude.com/blog/the-advisor-strategy</a>';
  card.appendChild(learn);
  // Cancel half is the live Eclipse binding (Esc, or Ctrl+G under Emacs, where Esc is a
  // multi-stroke prefix that never reaches the page); dropped entirely when unbound. The
  // "Enter to confirm" half is ours and always stands, so only the suffix moves.
  const esc = document.createElement('div'); esc.className = 'dec-esc';
  card.appendChild(esc);
  registerHintPainter(esc, () => {
    const cancelPart = cancelHintText();
    esc.textContent = 'Enter to confirm' + (cancelPart ? ' · ' + cancelPart : '');
  });

  function onKey(e) {
    if (resolved) return;
    // A blocking card (permission/question) may have replaced us mid-turn — bail out.
    if (!card.isConnected) { document.removeEventListener('keydown', onKey, true); return; }
    if (e.key === 'Escape') { e.preventDefault(); cancel(); return; }
    if (e.key === 'Enter') { e.preventDefault(); confirm(sel); return; }
    if (e.key === 'ArrowDown') { e.preventDefault(); sel = (sel + 1) % OPTS.length; paint(); return; }
    if (e.key === 'ArrowUp') { e.preventDefault(); sel = (sel - 1 + OPTS.length) % OPTS.length; paint(); return; }
    const n = parseInt(e.key, 10);
    if (n >= 1 && n <= OPTS.length) { e.preventDefault(); confirm(n - 1); }
  }
  document.addEventListener('keydown', onKey, true);
  registerOverlayCancel(cancel, true, owner);   // bottom card → tab-visibility guard applies

  showBottomCard(card, owner);
}


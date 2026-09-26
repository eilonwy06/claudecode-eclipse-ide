/* controls.js — Composer controls: usage banner, file-context chip, permission mode, effort
   slider, zoom slider. */

/* ---- usage warning banner (from the CLI's rate_limit_event) ---- */
const USAGE_WARN_THRESHOLD = 75;   // percent, when the CLI exposes a usage %
let usageDismissed = false;
function resetIn(ts) {
  const secs = (ts * 1000 - Date.now()) / 1000;
  if (secs <= 0) return 'soon';
  if (secs < 3600) return 'in ' + Math.max(1, Math.round(secs / 60)) + 'm';
  if (secs < 86400) return 'in ' + Math.round(secs / 3600) + 'h';
  return 'in ' + Math.round(secs / 86400) + 'd';
}
window.onRateLimit = function(tabId, json) {   // tabId ignored — usage is account-global
  const el = document.getElementById('usage-warn'); if (!el) return;
  let info = {}; try { info = JSON.parse(json) || {}; } catch (e) { return; }
  // Use a usage % if the CLI provides one (field name varies / may be absent).
  let pct = null;
  for (const k of ['percentUsed', 'usagePercent', 'percent', 'fractionUsed', 'usedFraction']) {
    if (typeof info[k] === 'number') { pct = info[k] <= 1 ? Math.round(info[k] * 100) : Math.round(info[k]); break; }
  }
  const status = info.status || 'allowed';
  const reached = /reject|exceed|block|limit/i.test(status);
  const warn = reached || (pct != null && pct >= USAGE_WARN_THRESHOLD) || /warn|approach/i.test(status) || info.isUsingOverage === true;
  if (!warn || usageDismissed) { el.classList.remove('show'); return; }
  const scope = /five|hour|^5/i.test(info.rateLimitType || '') ? '5-hour limit' : 'weekly limit';
  const resetTxt = info.resetsAt ? ' · resets ' + resetIn(info.resetsAt) : '';
  const msg = (pct != null) ? ("You've used " + pct + '% of your ' + scope)
            : (reached ? "You've reached your " + scope : "You're approaching your " + scope);
  el.innerHTML = '<span class="uw-txt"></span><span class="uw-sep"> · </span>'
    + '<a href="https://claude.ai/settings/usage">View usage</a><span class="uw-x">' + ICONS.X + '</span>';
  el.querySelector('.uw-txt').textContent = msg + resetTxt;
  el.querySelector('.uw-x').onclick = () => { usageDismissed = true; el.classList.remove('show'); };
  el.classList.add('show');
};

/* ---- context-usage ring (composer bar) ----
   Pushed from Java's onStatusForTab (ClaudeGuiView.java) — the same per-turn contextPct
   already computed for the native status bar and the /context command, just also handed
   to the page now. Only ever updates once a turn actually completes (chat.rs's
   build_status_json fires on the "result" event), same limitation /context already has:
   nothing live mid-stream, and nothing meaningful right after a resume until a new turn
   finishes — the ring just stays hidden until then instead of showing a stale/wrong %. */
let ctxRingData = null;
const ctxRingByTab = {};   // tabId -> parsed status, mirrors ClaudeGuiView.java's statusByTab
const CTX_RING_CIRC = 2 * Math.PI * 8;   // matches the SVG circle's r=8 (layout.css)
window.onContextStatus = function(tabId, json) {
  let data = null;
  try { data = JSON.parse(json); } catch (e) {}
  ctxRingByTab[tabId] = data;   // recorded even for a background tab, for switchToContextRing below
  const t = activeTab();
  if (!t || t.id !== tabId) return;   // a background tab's turn finishing shouldn't repaint this
  ctxRingData = data;
  updateContextRing();
};
/** Called on tab switch (tabs.js) so the ring shows THAT tab's own last-known status
 *  immediately, instead of stale data left over from whichever tab was active before. */
function switchToContextRing(tabId) {
  ctxRingData = ctxRingByTab[tabId] || null;
  updateContextRing();
}
function updateContextRing() {
  const btn = document.getElementById('ctx-ring-btn');
  if (!btn) return;
  const CTX_RING_THRESHOLD = 65;   // hidden below this — only worth surfacing once it matters
  const has = !!(ctxRingData && typeof ctxRingData.contextPct === 'number' && ctxRingData.contextWindow
      && ctxRingData.contextPct >= CTX_RING_THRESHOLD);
  btn.style.display = has ? '' : 'none';
  if (!has) { hideCtxRingTip(); return; }
  const pct = Math.min(100, Math.max(0, ctxRingData.contextPct));
  const fill = btn.querySelector('.ctx-ring-fill');
  if (fill) fill.style.strokeDashoffset = CTX_RING_CIRC * (1 - pct / 100);
  const remaining = Math.max(0, 100 - Math.floor(pct));
  const lbl = document.getElementById('ctx-ring-tip-remaining');
  if (lbl) lbl.textContent = remaining + '% of context remaining until auto-compact.';
}
/** Styled popover (not the browser's own native title tooltip) — positioned like a .menu:
 *  fixed, glued above the ring button, clamped so it never runs off the left/right edge. */
function showCtxRingTip() {
  const btn = document.getElementById('ctx-ring-btn');
  const tip = document.getElementById('ctx-ring-tip');
  if (!btn || !tip || btn.style.display === 'none') return;
  tip.classList.add('open');
  const r = btn.getBoundingClientRect();
  const tw = tip.offsetWidth, th = tip.offsetHeight;
  const left = Math.max(8, Math.min(r.left, window.innerWidth - tw - 8));
  tip.style.left = left + 'px';
  tip.style.top = (r.top - th - 6) + 'px';
}
function hideCtxRingTip() {
  const tip = document.getElementById('ctx-ring-tip');
  if (tip) tip.classList.remove('open');
}

/* ---- file context chip ---- */
let ctxData = { fileName: null };
let ctxEnabled = true;
// Dismissing (the X) is per-snapshot, unlike ctxEnabled's persistent on/off: it hides
// THIS file/selection only, and clears itself the moment the tracked file or selection
// range actually changes — ctxEnabled instead stays however the user last left it
// regardless of what file is open.
let ctxDismissed = false;
let ctxDismissKey = '';
function ctxKey() {
  return (ctxData && ctxData.fileName)
    ? ctxData.fileName + ':' + (ctxData.startLine || 0) + '-' + (ctxData.endLine || 0)
    : '';
}
function ctxIsDismissed() { return ctxDismissed && ctxKey() === ctxDismissKey; }
window.onContextChanged = function(c) {
  ctxData = c || { fileName: null };
  if (ctxDismissed && ctxKey() !== ctxDismissKey) ctxDismissed = false;
  updateCtxChip();
};
try { ctxData = JSON.parse(window._currentContext()); } catch (e) {}
function ctxBaseName() {
  return ctxData && ctxData.fileName ? ctxData.fileName.split(/[\\/]/).pop() : '';
}
function ctxLabelText() {
  if (!ctxData || !ctxData.fileName) return 'No file open';
  const base = ctxBaseName();
  if (ctxData.hasSelection) {
    const n = Math.max(1, (ctxData.endLine || 0) - (ctxData.startLine || 0) + 1);
    return base + ' - ' + n + ' line' + (n > 1 ? 's' : '') + ' selected';
  }
  return base;
}
function ctxChipLabel() {
  if (!ctxData || !ctxData.fileName) return null;
  const base = ctxBaseName();
  return ctxData.hasSelection ? (base + ':' + ctxData.startLine + '-' + ctxData.endLine) : base;
}
function updateCtxChip() {
  const chip = document.getElementById('ctx-chip');
  const label = document.getElementById('ctx-label');
  if (!chip || !label) return;
  const has = !!(ctxData && ctxData.fileName) && !ctxIsDismissed();
  label.textContent = has ? ctxLabelText() : 'No file open';
  chip.classList.toggle('empty', !has);
  chip.classList.toggle('off', has && !ctxEnabled);
  const ic = document.getElementById('ctx-ic');
  if (ic) ic.innerHTML = (has && !ctxEnabled) ? ICONS.EYEOFF : ICONS.FILEICON;
  fitComposerBar();   // label text changed — re-check the narrow-width collapse
}
function toggleContext() {
  if (!ctxData || !ctxData.fileName || ctxIsDismissed()) return;
  ctxEnabled = !ctxEnabled;
  updateCtxChip();
}
/** The chip's X — removes the CURRENT file/selection from context (unlike toggleContext's
 *  persistent eye icon, this un-dismisses itself the moment onContextChanged reports a
 *  genuinely different file or selection, so it never permanently hides the chip). */
function dismissContext(e) {
  if (e) e.stopPropagation();
  if (!ctxData || !ctxData.fileName) return;
  ctxDismissed = true;
  ctxDismissKey = ctxKey();
  updateCtxChip();
}

/* Collapse the composer bar progressively at narrow widths so nothing is ever
   pushed out of the input div (joebiden16). Strict priority order, each stage
   applied only if the bar still overflows after the previous one:
     1. mode label ("Manual", …) — hidden the moment it can't fit on
        ONE line, so it never renders wrapped; it goes before the context label
     2. context filename — icon only
     3. send/stop button — minimized
     4. label padding tightens
     5. every control a size smaller and the gaps close
     6. smaller again, into the input box's padding — the last resort, for a view
        squeezed by a minimized Eclipse
   Widening re-runs from the full state, so everything comes back. */
function fitComposerBar() {
  const bar = document.getElementById('composer-bar');
  const chip = document.getElementById('ctx-chip');
  const ctxLbl = document.getElementById('ctx-label');
  const modes = document.getElementById('modes-btn');
  const modesLbl = document.getElementById('modes-lbl');
  const sendBtn = document.getElementById('send');
  if (!bar || !chip || !ctxLbl || !modes || !modesLbl || !sendBtn) return;
  chip.classList.remove('icon-only');
  modes.classList.remove('icon-only');
  sendBtn.classList.remove('mini');
  bar.classList.remove('compact', 'tight', 'tiny');
  const overflowing = () => bar.scrollWidth > bar.clientWidth + 1;
  // The mode label goes as soon as the bar gets tight: either it already
  // overflows, or the context chip is being squeezed below its natural width
  // (its text truncating while under the CSS max-width cap — i.e. the squeeze
  // is from the panel, not from a long filename on a wide panel).
  const chipMax = parseFloat(getComputedStyle(chip).maxWidth) || 220;
  const chipSqueezed = ctxLbl.scrollWidth > ctxLbl.clientWidth + 1
                    && chip.offsetWidth < chipMax - 1;
  if (chipSqueezed || overflowing()) modes.classList.add('icon-only');
  if (overflowing()) chip.classList.add('icon-only');
  if (overflowing()) sendBtn.classList.add('mini');
  if (overflowing()) bar.classList.add('compact');
  if (overflowing()) bar.classList.add('tight');
  if (overflowing()) bar.classList.add('tiny');
  // icon-only mode button still tells you the mode on hover
  modes.title = modes.classList.contains('icon-only') ? (modesLbl.textContent || '') : '';
}
window.addEventListener('resize', fitComposerBar);   // synchronous with the resize
new ResizeObserver(() => requestAnimationFrame(fitComposerBar))
  .observe(document.getElementById('input-wrap'));   // safety net (zoom changes, etc.)

/* ---- permission mode (the "Manual" dropdown) ----
   PER-TAB, like model/effort/thinking: `permMode` mirrors the ACTIVE tab and is
   restored by applyTabSettings() on every tab switch. Switching mid-conversation
   is pushed live to that tab's process via a set_permission_mode control request,
   so it takes effect without a respawn. */
/* DEFAULT_PERM_MODE lives in tabs.js beside the other per-tab defaults. */
let permMode = DEFAULT_PERM_MODE;
/* Composer border + send button color per permission mode (border only when focused —
   #input-wrap.blur overrides back to plain --border, layout.css). Plain classes, not a
   JS-set custom property with a nested var() fallback (var(--mode-color, var(--accent)))
   — that pattern failed to resolve in testing and fell back to border-color's CSS-spec
   initial value, currentColor, which reads as --fg (a light near-white grey) here. Manual
   needs no class at all: #input-wrap/#send's own base rules already use its color. */
const MODE_BORDER_CLASSES = ['mode-acceptEdits', 'mode-plan', 'mode-auto', 'mode-bypassPermissions'];
/** Paints the composer button + menu checkmark for `mode` (no state/pushing). */
function applyModeUI(mode) {
  const el = document.querySelector('#modes-menu .item[data-mode="' + (mode || DEFAULT_PERM_MODE) + '"]')
          || document.querySelector('#modes-menu .item[data-mode="default"]');
  if (!el) return;
  const lbl = el.querySelector('.lbl').textContent;
  const iconKey = el.getAttribute('data-icon') || 'HAND';
  const lblEl = document.getElementById('modes-lbl'); if (lblEl) lblEl.textContent = lbl;
  const icEl = document.getElementById('modes-ic'); if (icEl) icEl.innerHTML = ICONS[iconKey] || ICONS.HAND;
  document.querySelectorAll('#modes-menu .item .check').forEach(c => c.style.visibility = 'hidden');
  const chk = el.querySelector('.check'); if (chk) chk.style.visibility = '';
  const dm = el.getAttribute('data-mode');
  [document.getElementById('input-wrap'), document.getElementById('send')].forEach(node => {
    if (!node) return;
    MODE_BORDER_CLASSES.forEach(c => node.classList.remove(c));
    if (dm && dm !== 'default') node.classList.add('mode-' + dm);
  });
  fitComposerBar();   // mode label changed — re-check the narrow-width collapse
}
/* Bypass permissions is listed only while the preference allows it — the VS Code
   extension hides it behind its own setting the same way. A conversation already in
   that mode is left alone: hiding the entry does not change what is running. */
function applyBypassModeVisibility() {
  const item = document.getElementById('mode-bypass');
  if (!item) return;
  let allowed = true;
  try { allowed = window._bypassModeAllowed ? !!_bypassModeAllowed() : true; } catch (e) {}
  item.style.display = allowed ? '' : 'none';
}

function selectMode(el) {
  const mode = el.getAttribute('data-mode') || DEFAULT_PERM_MODE;
  const t = activeTab();
  permMode = mode;
  if (t) t.permMode = mode;
  applyModeUI(mode);
  closeMenus();
  // Live-apply to a conversation that's already running (spawn-time --permission-mode
  // only covers the first launch); a not-yet-started tab picks it up at spawn.
  if (t && t.sessionId && window._setPermissionMode) {
    try { window._setPermissionMode(t.id, mode); } catch (e) {}
  }
  persistTabPrefs(t);
}
/**
 * Mirrors a mode change the CLI made on its OWN (the user picked "Yes, allow all
 * edits …" on a decision card, which hands the CLI a setMode suggestion). Same
 * state update as selectMode but WITHOUT the set_permission_mode push — the CLI
 * is already in that mode, so pushing would be a redundant round-trip.
 *
 * Without this the indicator lies: the session accepts edits silently while the
 * button still reads "Manual", and the only way back is to switch modes and
 * switch again (which respawns the process and genuinely resets it).
 * @param {Tab} t tab that owned the card (NOT necessarily the active one)
 * @param {string} mode
 */
function adoptModeForTab(t, mode) {
  if (!t || t.permMode === mode) return;
  t.permMode = mode;
  // Only repaint if this tab is on screen; a background tab picks it up from
  // t.permMode via applyTabSettings() when the user switches to it.
  if (t === activeTab()) { permMode = mode; applyModeUI(mode); }
  persistTabPrefs(t);
}

/* ---- effort meter (claude --effort) ---- */
const EFFORTS = ['low', 'medium', 'high', 'xhigh', 'max'];
const EFFORT_LABELS = ['Low', 'Medium', 'High', 'X-High', 'Max'];
let effortIdx = 2;                       // default: high
let effort = EFFORTS[effortIdx];
/** @param {number} idx index into EFFORTS
 *  @param {{force?: boolean, noPersist?: boolean}} [opts]
 *  `force` skips the thinking gate — used when RESTORING a persisted session,
 *  whose stored pair is already consistent and shouldn't be silently rewritten
 *  before the tab's thinking flag has been applied.
 *  `noPersist` suppresses the sidecar write. PAINTING a tab's stored settings is
 *  not a user edit and must never write back: applyTabSettings() runs on every
 *  switchTab(), including the createTab() inside loadHistory(), which fires
 *  BEFORE the restore has read the sidecar — so persisting there overwrote the
 *  saved entry with the new tab's defaults and the restore then read back the
 *  wreck it had just caused. (Verified from a runtime [PREFS-SAVE] trace: two
 *  setEffort saves under two session ids, both defaults, immediately ahead of the
 *  [PREFS-LOAD] that returned them.) */
function setEffort(idx, opts) {
  effortIdx = Math.max(0, Math.min(EFFORTS.length - 1, idx));
  // Claude 5 models 400 on xhigh/max with thinking off — never let the slider
  // land there (see EFFORT_REQUIRES_THINKING in models.js).
  if (!(opts && opts.force) && typeof maxEffortIdx === 'function')
    effortIdx = Math.min(effortIdx, maxEffortIdx());
  effort = EFFORTS[effortIdx];
  const pct = (effortIdx / (EFFORTS.length - 1)) * 100;
  // both effort sliders (modes menu + actions menu) are an intended redundancy — keep them synced
  document.querySelectorAll('.eff-fill').forEach(el => el.style.width = pct + '%');
  document.querySelectorAll('.eff-knob').forEach(el => el.style.left = pct + '%');
  document.querySelectorAll('.eff-lbl').forEach(el => el.textContent = '(' + EFFORT_LABELS[effortIdx] + ')');
  const pillEffort = document.getElementById('model-pill-effort');
  if (pillEffort) pillEffort.textContent = EFFORT_LABELS[effortIdx];
  const t = (typeof activeTab === 'function') ? activeTab() : null; if (t) t.effortIdx = effortIdx;
  // Moving to/from xhigh/max flips whether thinking is mandatory — refresh both
  // affordances so the lock appears the moment the stop is reached.
  if (typeof updateThinkingCheck === 'function') updateThinkingCheck();
  if (typeof updateEffortGate === 'function') updateEffortGate();
  if (!(opts && opts.noPersist) && typeof persistTabPrefs === 'function') persistTabPrefs(t);
  if (typeof notifyStatusSelection === 'function') notifyStatusSelection();
  // A user pick reaches the process at once; painting a tab's stored settings does not
  // (nothing changed, and the process already has them).
  if (!(opts && opts.noPersist) && typeof pushLaunchSettings === 'function') pushLaunchSettings(t);
}
let effortDragging = false, effortDragSlider = null;
function effortFromX(slider, clientX) {
  const r = slider.getBoundingClientRect();
  const ratio = Math.max(0, Math.min(1, (clientX - r.left) / r.width));
  setEffort(Math.round(ratio * (EFFORTS.length - 1)));   // snap to nearest stop
}
function effortDown(e) {
  e.stopPropagation(); e.preventDefault();   // click-drag without closing the menu / selecting text
  effortDragging = true; effortDragSlider = e.currentTarget;
  effortFromX(effortDragSlider, e.clientX);
}
document.addEventListener('mousemove', e => { if (effortDragging && effortDragSlider) effortFromX(effortDragSlider, e.clientX); });
document.addEventListener('mouseup', () => { effortDragging = false; effortDragSlider = null; });

/* ---- zoom level (Appearance) — scales only #zoom-root (the content), leaving menus,
   cards and overlays natural-size and reachable. Global (not per-conversation) and
   persisted in localStorage so it survives reloads. ---- */
const ZOOM_LEVELS = [50, 75, 90, 100, 110, 125, 150];   // denser near 100% (fine control), coarser at extremes
const ZOOM_DEFAULT_IDX = 3;              // 100%
let zoomIdx = ZOOM_DEFAULT_IDX;
function setZoom(idx, persist) {
  zoomIdx = Math.max(0, Math.min(ZOOM_LEVELS.length - 1, idx));
  const pctVal = ZOOM_LEVELS[zoomIdx];
  document.documentElement.style.setProperty('--z', String(pctVal / 100));
  const pos = (zoomIdx / (ZOOM_LEVELS.length - 1)) * 100;
  document.querySelectorAll('.zoom-fill').forEach(el => el.style.width = pos + '%');
  document.querySelectorAll('.zoom-knob').forEach(el => el.style.left = pos + '%');
  document.querySelectorAll('.zoom-lbl').forEach(el => el.textContent = '(' + pctVal + '%)');
  if (persist !== false) { try { localStorage.setItem('zoomIdx', String(zoomIdx)); } catch (e) {} }
}
let zoomDragging = false, zoomDragSlider = null;
function zoomFromX(slider, clientX) {
  const r = slider.getBoundingClientRect();
  const ratio = Math.max(0, Math.min(1, (clientX - r.left) / r.width));
  setZoom(Math.round(ratio * (ZOOM_LEVELS.length - 1)));   // snap to nearest stop
}
function zoomDown(e) {
  e.stopPropagation(); e.preventDefault();   // drag without closing the menu / selecting text
  zoomDragging = true; zoomDragSlider = e.currentTarget;
  zoomFromX(zoomDragSlider, e.clientX);
}
document.addEventListener('mousemove', e => { if (zoomDragging && zoomDragSlider) zoomFromX(zoomDragSlider, e.clientX); });
document.addEventListener('mouseup', () => { zoomDragging = false; zoomDragSlider = null; });
// Restore the saved zoom on load (default 100%). Never zooms the drag itself.
(function initZoom() {
  let saved = ZOOM_DEFAULT_IDX;
  try { const s = localStorage.getItem('zoomIdx'); if (s !== null) saved = parseInt(s, 10) || ZOOM_DEFAULT_IDX; } catch (e) {}
  setZoom(saved, false);
})();


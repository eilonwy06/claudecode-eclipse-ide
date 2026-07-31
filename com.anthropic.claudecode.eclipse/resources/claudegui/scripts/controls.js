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
  el.innerHTML = '<span class="uw-txt"></span><a href="https://claude.ai/settings/usage">View usage</a><span class="uw-x">' + ICONS.X + '</span>';
  el.querySelector('.uw-txt').textContent = msg + resetTxt + ' · ';
  el.querySelector('.uw-x').onclick = () => { usageDismissed = true; el.classList.remove('show'); };
  el.classList.add('show');
};

/* ---- file context chip ---- */
let ctxData = { fileName: null };
let ctxEnabled = true;
window.onContextChanged = function(c) { ctxData = c || { fileName: null }; updateCtxChip(); };
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
  const has = !!(ctxData && ctxData.fileName);
  label.textContent = ctxLabelText();
  chip.classList.toggle('empty', !has);
  chip.classList.toggle('off', has && !ctxEnabled);
  const ic = document.getElementById('ctx-ic');
  if (ic) ic.innerHTML = (has && !ctxEnabled) ? ICONS.EYEOFF : ICONS.FILEICON;
  fitComposerBar();   // label text changed — re-check the narrow-width collapse
}
function toggleContext() {
  if (!ctxData || !ctxData.fileName) return;
  ctxEnabled = !ctxEnabled;
  updateCtxChip();
}

/* Collapse the composer bar progressively at narrow widths so nothing is ever
   pushed out of the input div (joebiden16). Strict priority order, each stage
   applied only if the bar still overflows after the previous one:
     1. mode label ("Ask before edits", …) — hidden the moment it can't fit on
        ONE line, so it never renders wrapped; it goes before the context label
     2. context filename — icon only
     3. send/stop button — minimized
     4. gaps/padding tighten as the last resort
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
  bar.classList.remove('compact');
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
  // icon-only mode button still tells you the mode on hover
  modes.title = modes.classList.contains('icon-only') ? (modesLbl.textContent || '') : '';
}
window.addEventListener('resize', fitComposerBar);   // synchronous with the resize
new ResizeObserver(() => requestAnimationFrame(fitComposerBar))
  .observe(document.getElementById('input-wrap'));   // safety net (zoom changes, etc.)

/* ---- permission mode (the "Ask before edits" dropdown) ----
   PER-TAB, like model/effort/thinking: `permMode` mirrors the ACTIVE tab and is
   restored by applyTabSettings() on every tab switch. Switching mid-conversation
   is pushed live to that tab's process via a set_permission_mode control request,
   so it takes effect without a respawn. */
/* DEFAULT_PERM_MODE lives in tabs.js beside the other per-tab defaults. */
let permMode = DEFAULT_PERM_MODE;
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
  fitComposerBar();   // mode label changed — re-check the narrow-width collapse
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

/* ---- effort meter (claude --effort) ---- */
const EFFORTS = ['low', 'medium', 'high', 'xhigh', 'max'];
const EFFORT_LABELS = ['Low', 'Medium', 'High', 'X-High', 'Max'];
let effortIdx = 2;                       // default: high
let effort = EFFORTS[effortIdx];
/** @param {number} idx index into EFFORTS @param {{force?: boolean}} [opts]
 *  `force` skips the thinking gate — used when RESTORING a persisted session,
 *  whose stored pair is already consistent and shouldn't be silently rewritten
 *  before the tab's thinking flag has been applied. */
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
  const t = (typeof activeTab === 'function') ? activeTab() : null; if (t) t.effortIdx = effortIdx;
  // Moving to/from xhigh/max flips whether thinking is mandatory — refresh both
  // affordances so the lock appears the moment the stop is reached.
  if (typeof updateThinkingCheck === 'function') updateThinkingCheck();
  if (typeof updateEffortGate === 'function') updateEffortGate();
  if (typeof persistTabPrefs === 'function') persistTabPrefs(t);
  if (typeof notifyStatusSelection === 'function') notifyStatusSelection();
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


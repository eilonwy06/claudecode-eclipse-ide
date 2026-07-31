/* ui.js — Menu open/close/positioning, zoom/ctx-menu blockers, tab-strip scrollbar,
   composer input/send elements + basic listeners. */

/* ---- front-end-only interactions ---- */
let openMenuEl = null, openAnchor = null;   // openAnchor = the trigger element the menu is glued to
function closeMenus() { document.querySelectorAll('.menu.open').forEach(m => m.classList.remove('open'));
  document.querySelectorAll('.tbtn.active-tmp').forEach(b=>b.classList.remove('active-tmp'));
  // per-message badges are pinned visible while their menu is up — unpin them
  document.querySelectorAll('.msg-actions.open').forEach(w => w.classList.remove('open'));
  openMenuEl = null; openAnchor = null; }

/* Position a menu relative to its trigger button, so it stays glued to that element
   (in X and Y) when the view is resized — its reference is the element behind it.
   Below-group opens under the button; everything else opens above. Right-group aligns
   its right edge to the button; everything else aligns its left edge. Then clamped. */
function positionMenu(menu, anchor) {
  if (!menu || !anchor) return;
  const r = anchor.getBoundingClientRect();
  const mw = menu.offsetWidth, mh = menu.offsetHeight;
  const below = (menu.id === 'history-panel' || menu.id === 'msg-menu');
  const right = (menu.id === 'modes-menu' || menu.id === 'history-panel' || menu.id === 'msg-menu');
  let left = right ? (r.right - mw) : r.left;
  let top  = below ? (r.bottom + 6) : (r.top - mh - 6);
  left = Math.max(8, Math.min(left, window.innerWidth - mw - 8));
  top  = Math.max(8, Math.min(top, Math.max(8, window.innerHeight - mh - 8)));
  menu.style.left = left + 'px'; menu.style.top = top + 'px';
}

function toggleMenu(id, anchor) {
  const menu = document.getElementById(id);
  const wasOpen = menu.classList.contains('open');
  closeMenus();
  if (wasOpen) return;
  menu.classList.add('open');
  positionMenu(menu, anchor);
  openMenuEl = menu; openAnchor = anchor;
}

// Disable the browser right-click context menu (no "Inspect element" in the plugin).
document.addEventListener('contextmenu', (e) => e.preventDefault());

// Block page zoom — the WebView2 zooms on Ctrl+wheel and Ctrl+[+/-/0], which must not
// happen in the panel. wheel is passive by default, so register non-passive to preventDefault.
window.addEventListener('wheel', (e) => { if (e.ctrlKey) e.preventDefault(); }, { passive: false });
window.addEventListener('keydown', (e) => {
  if ((e.ctrlKey || e.metaKey) && ['+', '-', '=', '0'].includes(e.key)) e.preventDefault();
}, true);

document.addEventListener('click', (e) => {
  if (openMenuEl && !openMenuEl.contains(e.target) &&
      !e.target.closest('#plus-btn,#slash-btn,#modes-btn,#history-btn')) closeMenus();
  // The slash menu isn't tracked by openMenuEl — close it on any click outside it,
  // the input, or the slash button.
  if (slashState.open && !e.target.closest('#slash-menu,#input,#slash-btn')) closeSlash();
});

// Re-pin the open menu/panel so it stays on-screen — snaps its right edge near the
// viewport edge, only sliding left (and letting the far side clip) once the viewport
// is narrower than the menu itself. Used on resize AND after async content changes
// the menu's width (e.g. history "Loading…" → the real list).
function clampOpenMenu() {
  if (!openMenuEl || !openMenuEl.classList.contains('open')) return;
  // Re-anchor to the trigger element so the menu tracks it (X and Y) as the view resizes.
  if (openAnchor && document.body.contains(openAnchor)) { positionMenu(openMenuEl, openAnchor); return; }
  // Anchorless popups (e.g. the inline / autocomplete): just keep them on-screen.
  const m = openMenuEl, mw = m.offsetWidth, mh = m.offsetHeight;
  const left = Math.max(8, Math.min(parseFloat(m.style.left) || 0, window.innerWidth - mw - 8));
  const top = Math.max(8, Math.min(parseFloat(m.style.top) || 0, Math.max(8, window.innerHeight - mh - 8)));
  m.style.left = left + 'px';
  m.style.top = top + 'px';
}
window.addEventListener('resize', clampOpenMenu);

/* Tab strip: vertical mouse-wheel scrolls horizontally, and the scrollbar thumb is
   shown only while hovering or actively scrolling (then auto-hides). */
(function () {
  const tabsEl = document.getElementById('tabs');
  if (!tabsEl) return;
  let sbTimer = null;
  function flashScrollbar() {
    tabsEl.classList.add('sb-show');
    clearTimeout(sbTimer);
    sbTimer = setTimeout(() => { if (!tabsEl.matches(':hover')) tabsEl.classList.remove('sb-show'); }, 900);
  }
  tabsEl.addEventListener('mouseenter', () => { clearTimeout(sbTimer); tabsEl.classList.add('sb-show'); });
  tabsEl.addEventListener('mouseleave', () => { clearTimeout(sbTimer); tabsEl.classList.remove('sb-show'); });
  tabsEl.addEventListener('wheel', (e) => {
    if (e.deltaY === 0) return;
    tabsEl.scrollLeft += e.deltaY;   // translate vertical wheel to horizontal scroll
    e.preventDefault();
    flashScrollbar();
  }, { passive: false });
  tabsEl.addEventListener('scroll', flashScrollbar);
})();

/* textarea auto-grow + focus border + send/stop + Enter-to-send */
const input = document.getElementById('input');
const wrap = document.getElementById('input-wrap');
const send = document.getElementById('send');

input.addEventListener('focus', () => wrap.classList.remove('blur'));
input.addEventListener('blur',  () => wrap.classList.add('blur'));
input.addEventListener('input', () => {
  input.style.height = 'auto';
  input.style.height = Math.min(input.scrollHeight, 160) + 'px';
  const hasImgs = typeof hasPendingImages === 'function' && hasPendingImages();
  send.classList.toggle('disabled', input.value.trim() === '' && !hasImgs && !activeStreaming());
  updateSlashMenu();
});
input.addEventListener('keydown', (e) => {
  if (slashState.open && handleSlashKey(e)) return;
  // Enter always sends: mid-stream it QUEUES the message (VSCode behavior —
  // claude answers queued messages in succession over the persistent process).
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); doSend(); }
  else if (e.key === 'Escape' && activeStreaming()) { e.preventDefault(); doCancel(); }
});

send.addEventListener('click', () => { if (activeStreaming()) doCancel(); else doSend(); });

/* Sets streaming state for the render-target tab (rtab); the composer only
   changes if that tab is the active one. */
function setStreaming(v) {
  const t = rtab || activeTab();
  if (t) t.streaming = v;
  syncComposer();
}

function copyBlock(el) {
  const block = el.closest('.code-block');
  const text = block.querySelector('pre').innerText;
  if (navigator.clipboard) navigator.clipboard.writeText(text).catch(()=>{});
}

const messagesEl = document.getElementById('messages');


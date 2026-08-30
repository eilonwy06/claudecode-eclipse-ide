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

/* Drops a menu from the page's top-right corner rather than gluing it to an in-page
   button — for a trigger that lives OUTSIDE the webview entirely (a native Eclipse
   toolbar Action has no DOM element of its own to hand positionMenu/getBoundingClientRect).
   The Eclipse view toolbar sits directly above the browser viewport with its actions
   right-aligned, so this corner is the closest approximation to "under those buttons"
   available without Java pushing the toolbar's actual screen coordinates across the
   bridge — not true anchoring, just a fixed spot that reads as coming from up there.
   No openAnchor is set (there's nothing to re-anchor to): clampOpenMenu's anchorless
   branch already keeps a fixed-position menu on-screen through a resize on its own. */
function positionMenuFixed(menu, rightGap) {
  if (!menu) return;
  const mw = menu.offsetWidth;
  const left = Math.max(8, window.innerWidth - mw - (rightGap != null ? rightGap : 8));
  // Below whichever header row is actually the last one visible — #update-banner
  // sits right after #toolbar in the DOM and shows only when the installed CLI is
  // outdated, so anchoring to #toolbar alone would put the panel under the banner
  // instead of below it on those days. Read live, not hardcoded, so the banner
  // appearing/disappearing still clears correctly. +6 gap matches positionMenu's
  // own below-anchor case. (#convo-header is gone — titles are edited in place on
  // each tab now, see tabs.js's startTitleEdit — so #toolbar is the last header-ish
  // row left when the banner is hidden.)
  const banner = document.getElementById('update-banner');
  const bannerShown = banner && banner.classList.contains('show');
  const header = document.getElementById(bannerShown ? 'update-banner' : 'toolbar');
  const top = header ? header.getBoundingClientRect().bottom + 6 : 8;
  menu.style.left = left + 'px'; menu.style.top = top + 'px';
}

// Disable the browser right-click context menu (no "Inspect element" in the plugin).
document.addEventListener('contextmenu', (e) => e.preventDefault());

// Block page zoom — the WebView2 zooms on Ctrl+wheel and Ctrl+[+/-/0], which must not
// happen in the panel. wheel is passive by default, so register non-passive to preventDefault.
window.addEventListener('wheel', (e) => { if (e.ctrlKey) e.preventDefault(); }, { passive: false });
window.addEventListener('keydown', (e) => {
  if ((e.ctrlKey || e.metaKey) && ['+', '-', '=', '0'].includes(e.key)) e.preventDefault();
}, true);

/* Links (markdown output, cards, the account panel) must NEVER navigate this page:
   the webview IS the conversation, and there's no back button to return to it.
   Hand every http(s) link to Java, which opens it in the system browser. Capture
   phase + closest() so it fires before anything else and works when the click lands
   on a child node; auxclick covers middle-click. `target` is ignored on purpose so
   target="_blank" anchors route the same way. */
function openLinkExternally(e) {
  const a = e.target.closest && e.target.closest('a[href]');
  if (!a) return;
  const href = a.getAttribute('href') || '';
  e.preventDefault();   // not stopPropagation: menus still need to see the click and close
  if (/^https?:\/\//i.test(href) && window._openExternal) _openExternal(href);
}
document.addEventListener('click', openLinkExternally, true);
document.addEventListener('auxclick', (e) => { if (e.button === 1) openLinkExternally(e); }, true);

document.addEventListener('click', (e) => {
  // #history-btn is gone (moved to the native toolbar, see openHistoryFromToolbar in
  // history.js) — its trigger is now outside the page entirely, so there's no in-page
  // button click for this listener to exempt; nothing else changes here.
  if (openMenuEl && !openMenuEl.contains(e.target) &&
      !e.target.closest('#plus-btn,#slash-btn,#modes-btn')) closeMenus();
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

/* Tab strips: vertical mouse-wheel scrolls horizontally, and the scrollbar thumb is
   shown only while hovering or actively scrolling (then auto-hides). Applied to both
   rows — the root row scrolls the same way and shares the same CSS. */
['tabs', 'supertabs'].forEach(function (stripId) {
  const tabsEl = document.getElementById(stripId);
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
});

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
/* macOS: an arrow key the caret can't act on inserts U+1D (ASCII GROUP SEPARATOR)
   into the composer, which renders as a box. On the Mac the SWT Browser is WebKit
   under Cocoa: a key WebKit reports as unhandled falls back to AppKit's insertText:
   with the raw NSEvent character, and the arrows carry a control character rather
   than nothing. It only shows at the boundaries (Right at the end of the text, Left
   at the start) because anywhere else the caret really moves and the key is consumed.
   Windows goes through WebView2 and Linux through WebKitGTK, where the arrows carry
   no character at all, so this is inert on both -- the guard is keyed to the arrow
   keydown that precedes the insert, not to the platform.

   Gated on that preceding arrow keydown rather than filtering the range outright, so
   a PASTE containing a control character is left alone everywhere. beforeinput is
   cancelable, so the character never lands -- no insert-then-delete, no flicker, no
   caret to restore. Verified against a real macOS repro: keydown(ArrowRight) ->
   textInput -> beforeinput{inputType:"insertText",data:"\u001d"} -> input. */
let arrowGuard = false;

input.addEventListener('beforeinput', (e) => {
  if (!arrowGuard) return;
  arrowGuard = false;   // one insert per arrow press; never spans keys
  if (e.inputType !== 'insertText' || !e.data) return;
  // C0/C1 controls only. Printable text an arrow key could legitimately produce
  // (it shouldn't produce any) is deliberately left untouched.
  if (/^[\u0000-\u001F\u007F-\u009F]+$/.test(e.data)) e.preventDefault();
});

/* A key bound to one of Eclipse's org.eclipse.ui.edit.* commands (issue #97): on GTK,
   the dispatcher consuming the keystroke (doit=false) doesn't stop WebKit from ALSO
   inserting it as text once the key carries a plain, unmodified character -- an Emacs
   Ctrl+X H both selects all AND types "h" into the composer.

   The natural fix would be to arm a guard before running the command and have it
   preventDefault() the stray beforeinput. That does NOT work: browser.execute() on
   WebKitGTK queues the injected script behind the page's own pending key processing,
   so the arm call lands in the page strictly AFTER the stray keydown/beforeinput for
   that same keystroke has already run and inserted the character (confirmed against a
   live GTK build -- the arm log line comes after the insert's beforeinput line, not
   before). There is no preventing an insert that already happened by the time we're
   able to say anything about it.

   So this undoes it instead. Every single-character insertText beforeinput on the
   composer is recorded (character + position); when the command's own arm call
   arrives afterward carrying the SAME character, the just-inserted character at that
   recorded position is deleted. Both the record and the arm are one-shot and scoped
   to the exact character, so ordinary typing (no arm ever follows it) and an edit
   command that inserts nothing (e.g. Delete, which never arms -- see
   activateEditHandler) are both untouched. execCommand('delete') is used rather than
   setRangeText so the removal stays on the textarea's native undo stack, same as
   ccDeleteSelection.

   Arming (and therefore undoing) is refused unless the composer already has focus.
   selectAll works on the transcript too when the composer isn't focused (see
   ccSelectAll/ccField), and a stray GTK insert can only ever land in whatever field
   the keystroke's own focus was in -- so an edit op that ran against the transcript
   never gets a beforeinput on #input to record, and there is nothing here to undo. */
let ccLastInsert = null;   // { code, at, pos, replaced } -- pos is the caret position right
                           // after the insert; replaced is whatever selection the insert
                           // overwrote (usually "", but not when the stray keystroke lands
                           // while a chord's own selectAll has something selected already --
                           // see below).
input.addEventListener('beforeinput', (e) => {
  if (e.inputType === 'insertText' && e.data && e.data.length === 1) {
    ccLastInsert = {
      code: e.data.codePointAt(0), at: Date.now(),
      pos: input.selectionStart + 1,
      replaced: input.value.slice(input.selectionStart, input.selectionEnd),
    };
  }
});
window.__ccArmKeyGuard = (code) => {
  if (document.activeElement !== input) return;
  const ins = ccLastInsert;
  ccLastInsert = null;   // one-shot regardless of match
  if (!ins || ins.code !== code || Date.now() - ins.at > 500) return;
  const pos = Math.min(ins.pos, input.value.length);
  input.focus();
  input.setSelectionRange(pos - 1, pos);
  // Undoing this insert means putting back whatever it overwrote -- usually nothing, but
  // a chord repeated right after its own selectAll (Ctrl+X H, Ctrl+X H) has that command's
  // selection still live when the second H's stray insert lands, and REPLACES it. Deleting
  // the "h" alone would not bring the replaced text back; insertText with the saved
  // replacement does, and re-selecting it after matches what the user actually had before
  // op.run() (queued right after this call) reads the selection for copy/cut.
  let ok;
  if (ins.replaced) {
    ok = document.execCommand('insertText', false, ins.replaced);
    if (ok) input.setSelectionRange(pos - 1, pos - 1 + ins.replaced.length);
  } else {
    ok = document.execCommand('delete');
  }
  if (!ok) {
    input.setRangeText(ins.replaced, pos - 1, pos, 'start');
    input.setSelectionRange(pos - 1, pos - 1 + ins.replaced.length);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }
};

input.addEventListener('keydown', (e) => {
  // Set before the slash menu gets a look in: it claims Up/Down but never the
  // horizontal arrows, so a guard set here is always the one this keypress needs.
  arrowGuard = (e.key === 'ArrowLeft' || e.key === 'ArrowRight'
             || e.key === 'ArrowUp'   || e.key === 'ArrowDown');
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
  // Turn over → the gerund goes with it. The callers all hide it themselves; doing
  // it here too means no future turn-ending path can forget to.
  if (!v && typeof stopWorkingFor === 'function') stopWorkingFor(t);
  syncComposer();
}

function copyBlock(el) {
  const block = el.closest('.code-block');
  const text = block.querySelector('pre').innerText;
  if (navigator.clipboard) navigator.clipboard.writeText(text).catch(()=>{});
}

const messagesEl = document.getElementById('messages');


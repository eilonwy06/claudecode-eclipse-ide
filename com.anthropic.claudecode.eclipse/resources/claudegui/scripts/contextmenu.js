/* contextmenu.js — The editing operations (cut/copy/paste/select-all, plus Delete)
   and the custom right-click menu that exposes them.

   The same four operations are reachable two ways: this menu, and the user's own
   Eclipse key bindings. The host activates org.eclipse.ui.edit.* handlers while the
   view is active and calls the __cc* entry points below, so whatever is configured
   in Window > Preferences > General > Keys works in here too — Emacs (Alt+W / Ctrl+W
   / Ctrl+Y), the default scheme, or a hand-customized one (issue #97). Both paths run
   the same DOM code, and both move text through the host's SWT Clipboard, which is
   the clipboard the rest of Eclipse uses. */

/* ---- editing operations ---- */

/* The field an operation applies to. The menu passes its right-click target (so a
   right-click on a Claude response never edits the composer); the key-binding path
   passes nothing and gets whatever currently has focus. */
function ccField(el) {
  const t = el || document.activeElement;
  return t && (t.tagName === 'TEXTAREA' || t.tagName === 'INPUT') ? t : null;
}

/* Text an operation would copy: the field's selection, else the page selection —
   text dragged over a Claude response is copyable too. '' when there's nothing. */
function ccSelectionText(el) {
  const f = ccField(el);
  if (f) return f.value.slice(f.selectionStart, f.selectionEnd);
  return String(window.getSelection() || '');
}

/* These edits go through execCommand, which keeps the textarea's NATIVE undo stack
   intact — setRangeText is a scripted change that Ctrl+Z can't reverse, and taking
   these keys over must not cost the user their undo. execCommand fires input itself,
   so the fallback is the only path that has to dispatch one. */
function ccDeleteSelection(el) {
  const f = ccField(el);
  if (!f || f.selectionStart === f.selectionEnd) return;
  f.focus();
  if (document.execCommand('delete')) return;
  f.setRangeText('', f.selectionStart, f.selectionEnd, 'start');
  f.dispatchEvent(new Event('input', { bubbles: true }));
}

/* What the Delete key does: drop the selection if there is one, otherwise eat the
   character to the right of the caret. Reached only through the host — see the
   org.eclipse.ui.edit.delete handler — because the key never gets as far as the page. */
function ccDeleteForward(el) {
  const f = ccField(el);
  if (!f) return;
  if (f.selectionStart !== f.selectionEnd) { ccDeleteSelection(el); return; }
  f.focus();
  if (document.execCommand('forwardDelete')) return;
  if (f.selectionStart >= f.value.length) return;   // caret at the end: nothing to eat
  // One code point, not one code unit — an emoji goes whole rather than leaving half.
  const step = f.value.codePointAt(f.selectionStart) > 0xFFFF ? 2 : 1;
  f.setRangeText('', f.selectionStart, f.selectionStart + step, 'start');
  f.dispatchEvent(new Event('input', { bubbles: true }));
}

function ccInsertText(txt, el) {
  const f = ccField(el);
  if (!f || !txt) return;
  f.focus();
  // A key the host consumed never reached the composer's keydown listener, so the
  // arrow-key guard it sets may still be up from an earlier press; this insert is
  // not that arrow's stray character (see the beforeinput guard in ui.js).
  arrowGuard = false;
  if (document.execCommand('insertText', false, txt)) return;
  f.setRangeText(txt, f.selectionStart, f.selectionEnd, 'end');
  f.dispatchEvent(new Event('input', { bubbles: true }));
}

function ccSelectAll(el) {
  const f = ccField(el);
  if (f) { f.focus(); f.select(); return; }
  const r = document.createRange();
  r.selectNodeContents(activeTab() ? activeTab().pane : messagesEl);
  const s = window.getSelection(); s.removeAllRanges(); s.addRange(r);
}

/* Clipboard writes/reads go through the host (_clipSet/_clipGet → SWT Clipboard) so
   the menu and the key bindings share one clipboard with the rest of Eclipse. The
   navigator.clipboard path stays as the fallback for a page opened without the
   bridge; execCommand('paste') is blocked in Chromium, which is why reading has to
   go through the host. */
function ccCopy(el) {
  const txt = ccSelectionText(el);
  if (!txt) return;
  if (window._clipSet) _clipSet(txt);
  else if (navigator.clipboard) navigator.clipboard.writeText(txt).catch(() => document.execCommand('copy'));
  else document.execCommand('copy');
}

function ccCut(el) {
  ccCopy(el);
  ccDeleteSelection(el);
}

function ccPaste(el) {
  const f = ccField(el);
  if (!f) return;
  f.focus();
  /* Images on the clipboard become chips, the same as dropping them in. The host reads
     them for us: the keystroke that got us here was consumed by Eclipse, and anyway only
     Ctrl+V produces a DOM paste event with clipboardData to read — every other paste
     binding would find nothing there.

     `text` says whether to ALSO paste the clipboard's text. Copied rich content pastes
     both — its words into the composer, its images as chips — while a copied image on its
     own attaches only the image, so the platform's text alternative for it (a URL, a file
     name) doesn't end up in the composer as well. Images the fragment names by URL are
     downloaded by the host and arrive later through __ccFetchedImages. */
  if (window._clipImages) {
    let r = null;
    try { r = JSON.parse(_clipImages(ccTabId()) || 'null'); } catch (e) { r = null; }
    if (r) {
      (r.images || []).forEach((u) => addPendingImage(u));
      if (r.text === false) return;
    }
  }
  if (window._clipGet) { ccInsertText(String(_clipGet() || ''), el); return; }
  if (navigator.clipboard && navigator.clipboard.readText) {
    navigator.clipboard.readText()
      .then((txt) => ccInsertText(txt, el))
      .catch(() => document.execCommand('paste'));
  } else {
    document.execCommand('paste');
  }
}

/* Entry points the host's org.eclipse.ui.edit.* handlers call — the menu's operations
   plus Delete, against whatever has focus. The text itself never rides on the injected
   script: it crosses through _clipGet/_clipSet like the menu's does, so there is no
   string to escape in either direction. */
window.__ccCopy = () => ccCopy();
window.__ccCut = () => ccCut();
window.__ccPaste = () => ccPaste();
window.__ccSelectAll = () => ccSelectAll();
window.__ccDelete = () => ccDeleteForward();

/* The conversation a paste belongs to. It rides along with the request so downloaded
   images land in the tab that was pasted into, even if the user has moved on since. */
function ccTabId() { const t = activeTab(); return t ? t.id : ''; }

/* The host calls this when images it downloaded for a pasted fragment are ready. A tab
   closed while they were in flight just drops them. */
window.__ccFetchedImages = () => {
  if (!window._drainImages) return;
  let items = [];
  try { items = JSON.parse(_drainImages() || '[]') || []; } catch (e) { return; }
  for (const it of items) {
    if (!it || !it.url) continue;
    const tab = it.tab ? tabById(it.tab) : activeTab();
    if (tab) addPendingImage(it.url, tab);
  }
};
/* Tells the host the four entry points above exist. It won't take these keys over
   until we do, so a script that fails to load leaves the browser's own defaults
   in place instead of making copy/paste do nothing at all. */
if (window._editOpsReady) _editOpsReady();

/* Menu accelerator labels. These defaults are the standard scheme's; the host
   overwrites them with the user's ACTUAL bindings once the page is up. It has to:
   under Emacs, cut is Ctrl+W and Ctrl+X is a chord prefix, so the hardcoded hints
   would name a keystroke that does something else entirely. */
let ccKeyHints = { cut: 'Ctrl+X', copy: 'Ctrl+C', paste: 'Ctrl+V', selectAll: 'Ctrl+A' };
window.onEditKeyHints = (json) => {
  try {
    const h = JSON.parse(json) || {};
    // '' is a real answer — the command is unbound, so the row shows no accelerator.
    for (const k of Object.keys(ccKeyHints)) if (k in h) ccKeyHints[k] = h[k] || '';
  } catch (e) {}
};

/* ---- custom right-click menu ----
   The native WebView2 context menu never appears inside the SWT host, so we
   draw our own: Cut/Copy/Paste/Select All, styled like the other popups.
   No Inspect — dev tools are also disabled host-side. */
const ctxMenu = document.createElement('div');
ctxMenu.id = 'ctx-menu';
document.body.appendChild(ctxMenu);
// Don't let a click on the menu steal focus/selection from the input.
ctxMenu.addEventListener('mousedown', (e) => e.preventDefault());

function hideCtxMenu() { ctxMenu.style.display = 'none'; }
document.addEventListener('mousedown', (e) => { if (!e.target.closest('#ctx-menu')) hideCtxMenu(); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') hideCtxMenu(); }, true);
window.addEventListener('blur', hideCtxMenu);
messagesEl.addEventListener('scroll', hideCtxMenu);

function ctxItem(label, hint, enabled, fn) {
  const it = document.createElement('div');
  it.className = 'ci' + (enabled ? '' : ' off');
  it.innerHTML = '<span></span><span class="ck"></span>';
  it.firstChild.textContent = label;
  it.lastChild.textContent = hint;
  if (enabled) it.onclick = () => { hideCtxMenu(); fn(); };
  ctxMenu.appendChild(it);
}

document.addEventListener('contextmenu', (e) => {
  e.preventDefault();
  const t = e.target;
  const field = ccField(t);
  const fieldSel = field && field.selectionStart !== field.selectionEnd;
  const pageSel = !!ccSelectionText(t).trim();

  ctxMenu.innerHTML = '';
  if (field) ctxItem('Cut', ccKeyHints.cut, fieldSel, () => ccCut(t));
  ctxItem('Copy', ccKeyHints.copy, field ? fieldSel : pageSel, () => ccCopy(t));
  if (field) ctxItem('Paste', ccKeyHints.paste, true, () => ccPaste(t));
  ctxItem('Select All', ccKeyHints.selectAll, true, () => ccSelectAll(t));

  ctxMenu.style.display = 'block';
  // Clamp inside the viewport (menu is position:fixed).
  const mw = ctxMenu.offsetWidth, mh = ctxMenu.offsetHeight;
  ctxMenu.style.left = Math.min(e.clientX, window.innerWidth - mw - 4) + 'px';
  ctxMenu.style.top  = Math.min(e.clientY, window.innerHeight - mh - 4) + 'px';
});

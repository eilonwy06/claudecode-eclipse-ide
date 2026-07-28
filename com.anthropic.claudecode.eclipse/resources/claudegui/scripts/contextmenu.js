/* contextmenu.js — Custom right-click menu (cut/copy/paste/select-all). */

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
  const field = t && (t.tagName === 'TEXTAREA' || t.tagName === 'INPUT') ? t : null;
  const fieldSel = field && field.selectionStart !== field.selectionEnd;
  const pageSel = !!String(window.getSelection() || '').trim();

  function copyText() {
    const txt = field ? field.value.slice(field.selectionStart, field.selectionEnd)
                      : String(window.getSelection() || '');
    if (!txt) return;
    if (navigator.clipboard) navigator.clipboard.writeText(txt).catch(() => document.execCommand('copy'));
    else document.execCommand('copy');
  }

  ctxMenu.innerHTML = '';
  if (field) {
    ctxItem('Cut', 'Ctrl+X', fieldSel, () => {
      copyText();
      field.setRangeText('', field.selectionStart, field.selectionEnd, 'start');
      field.dispatchEvent(new Event('input', { bubbles: true }));
      field.focus();
    });
  }
  ctxItem('Copy', 'Ctrl+C', field ? fieldSel : pageSel, copyText);
  if (field) {
    ctxItem('Paste', 'Ctrl+V', true, () => {
      field.focus();
      if (navigator.clipboard && navigator.clipboard.readText) {
        navigator.clipboard.readText().then((txt) => {
          if (!txt) return;
          field.setRangeText(txt, field.selectionStart, field.selectionEnd, 'end');
          field.dispatchEvent(new Event('input', { bubbles: true }));
        }).catch(() => document.execCommand('paste'));
      } else {
        document.execCommand('paste');
      }
    });
  }
  ctxItem('Select All', 'Ctrl+A', true, () => {
    if (field) { field.focus(); field.select(); return; }
    const r = document.createRange();
    r.selectNodeContents(activeTab() ? activeTab().pane : messagesEl);
    const s = window.getSelection(); s.removeAllRanges(); s.addRange(r);
  });

  ctxMenu.style.display = 'block';
  // Clamp inside the viewport (menu is position:fixed).
  const mw = ctxMenu.offsetWidth, mh = ctxMenu.offsetHeight;
  ctxMenu.style.left = Math.min(e.clientX, window.innerWidth - mw - 4) + 'px';
  ctxMenu.style.top  = Math.min(e.clientY, window.innerHeight - mh - 4) + 'px';
});


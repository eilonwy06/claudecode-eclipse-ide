/* rewind.js — Rewind dialog (restore code + fork conversation). */

/* ===================== Rewind (restore code + fork conversation) =====================
   The VSCode modal flow (joebiden14): actions menu → "Rewind to…" message list →
   "Fork and rewind" confirmation → files restored from the CLI's checkpoints, the
   conversation forked into a NEW tab with the selected message back in the composer. */
let rwState = null;   // { tab, msgs, sel, phase:'list'|'confirm', msg, preview, confirmSel }
function openRewindDialog() {
  closeMenus(); closeSlash();
  const t = activeTab(); if (!t) return;
  let msgs = [];
  if (t.sessionId && window._rewindList) {
    try { msgs = JSON.parse(window._rewindList(t.sessionId) || '[]') || []; } catch (e) { msgs = []; }
  }
  msgs = msgs.map(m => ({ id: m.id, text: stripMeta(m.text || ''), ts: m.ts }))
             .filter(m => m.id && m.text);
  msgs.reverse();   // newest first, like VSCode
  rwState = { tab: t, msgs, sel: 0, phase: 'list', confirmSel: 0 };
  renderRewind();
  document.getElementById('rewind-overlay').classList.add('open');
  document.addEventListener('keydown', rwKey, true);
}
function closeRewindDialog() {
  rwState = null;
  document.getElementById('rewind-overlay').classList.remove('open');
  document.removeEventListener('keydown', rwKey, true);
}
document.getElementById('rewind-overlay').addEventListener('click', (e) => {
  if (e.target.id === 'rewind-overlay') closeRewindDialog();
});
function rwKey(e) {
  const st = rwState; if (!st) return;
  e.stopPropagation();
  if (e.key === 'Escape') { e.preventDefault(); closeRewindDialog(); return; }
  if (st.phase === 'list') {
    if (!st.msgs.length) return;
    if (e.key === 'ArrowDown') { e.preventDefault(); st.sel = (st.sel + 1) % st.msgs.length; renderRewind(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); st.sel = (st.sel - 1 + st.msgs.length) % st.msgs.length; renderRewind(); }
    else if (e.key === 'Enter') { e.preventDefault(); rwSelect(st.sel); }
  } else {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); st.confirmSel = st.confirmSel ? 0 : 1; renderRewind(); }
    else if (e.key === '1') { e.preventDefault(); rwContinue(); }
    else if (e.key === '2') { e.preventDefault(); closeRewindDialog(); }
    else if (e.key === 'Enter') { e.preventDefault(); if (st.confirmSel === 0) rwContinue(); else closeRewindDialog(); }
  }
}
/* List → confirm: fetch the file restore preview for the chosen message. */
function rwSelect(i) {
  const st = rwState; if (!st) return;
  const m = st.msgs[i]; if (!m) return;
  let prev = {};
  try { prev = JSON.parse(window._rewindPreview(st.tab.sessionId, m.id) || '{}') || {}; } catch (e) {}
  st.phase = 'confirm'; st.msg = m; st.preview = prev; st.confirmSel = 0;
  renderRewind();
}
/* Confirm → restore files, fork the transcript, open the fork in a NEW tab with
   the selected message's text back in the composer (the source tab is untouched). */
function rwContinue() {
  const st = rwState; if (!st || !st.msg) return;
  let res = {};
  try { res = JSON.parse(window._rewindApply(st.tab.sessionId, st.msg.id) || '{}') || {}; } catch (e) {}
  const title = st.tab.title, titled = st.tab.titled;
  closeRewindDialog();
  if (res.error) { addSystem('⚠ Rewind failed: ' + res.error); return; }
  createTab({ title, titled });
  if (res.sessionId) {
    loadHistory(res.sessionId, title);   // fills the new (now active) tab
    if (titled && window._renameSession) window._renameSession(res.sessionId, title);
  }
  const p = stripMeta(res.prompt || '');
  if (p) { input.value = p; input.dispatchEvent(new Event('input', { bubbles: true })); }
  input.focus();
}
function renderRewind() {
  const st = rwState; if (!st) return;
  const win = document.getElementById('rewind-win');
  win.innerHTML = '';
  const head = document.createElement('div'); head.className = 'rw-head';
  const title = document.createElement('div'); title.className = 'rw-title';
  head.appendChild(title);
  win.appendChild(head);
  if (st.phase === 'list') {
    title.textContent = 'Rewind to…';
    const x = document.createElement('div'); x.className = 'rw-x'; x.innerHTML = ICONS.X;
    x.onclick = closeRewindDialog;
    head.appendChild(x);
    const sub = document.createElement('div'); sub.className = 'rw-sub';
    sub.textContent = 'Select a message to restore code and fork the conversation from that point.';
    win.appendChild(sub);
    const list = document.createElement('div'); list.id = 'rewind-list';
    if (!st.msgs.length) {
      const e = document.createElement('div'); e.className = 'h-empty';
      e.textContent = 'No messages to rewind to yet.';
      list.appendChild(e);
    }
    st.msgs.forEach((m, i) => {
      const it = document.createElement('div'); it.className = 'rw-item' + (i === st.sel ? ' sel' : '');
      const tx = document.createElement('div'); tx.className = 'rw-text'; tx.textContent = m.text;
      const tm = document.createElement('div'); tm.className = 'rw-time'; tm.textContent = relTime(m.ts);
      it.appendChild(tx); it.appendChild(tm);
      it.onclick = () => { st.sel = i; rwSelect(i); };
      list.appendChild(it);
    });
    win.appendChild(list);
    const foot = document.createElement('div'); foot.className = 'rw-foot';
    foot.innerHTML = '<span class="key">↑</span><span class="key">↓</span> to navigate · '
      + '<span class="key">Enter</span> to select · <span class="key">Esc</span> to close';
    win.appendChild(foot);
    const s = list.querySelector('.rw-item.sel');
    if (s) s.scrollIntoView({ block: 'nearest' });
  } else {
    title.textContent = 'Fork and rewind';
    const b1 = document.createElement('div'); b1.className = 'rw-body';
    b1.textContent = 'A new forked conversation will be created after rewinding.';
    win.appendChild(b1);
    const files = (st.preview && st.preview.files) || [];
    const sum = document.createElement('div'); sum.className = 'rw-body';
    if (files.length) {
      let dels = 0, adds = 0;
      files.forEach(f => { dels += f.dels || 0; adds += f.adds || 0; });
      sum.innerHTML = '<span class="rw-del"></span> will be removed and <span class="rw-add"></span>'
        + ' will be added across ' + files.length + ' file' + (files.length > 1 ? 's' : '') + ':';
      sum.querySelector('.rw-del').textContent = dels + ' line' + (dels === 1 ? '' : 's');
      sum.querySelector('.rw-add').textContent = adds + ' line' + (adds === 1 ? '' : 's');
      win.appendChild(sum);
      files.forEach(f => {
        const fe = document.createElement('div'); fe.className = 'rw-file';
        fe.textContent = '•  ' + f.path;
        win.appendChild(fe);
      });
    } else if (st.preview && st.preview.error) {
      sum.textContent = '⚠ Could not read the checkpoint (' + st.preview.error + ') — only the conversation will be forked.';
      win.appendChild(sum);
    } else if (st.preview && st.preview.noCheckpoint) {
      sum.textContent = 'This message has no file checkpoint (it was sent before checkpointing was enabled), so code cannot be restored — only the conversation will be forked.';
      win.appendChild(sum);
    } else {
      sum.textContent = 'The files already match this point — only the conversation will be forked.';
      win.appendChild(sum);
    }
    const note = document.createElement('div'); note.className = 'rw-note';
    note.textContent = 'ⓘ Rewinding does not affect files edited manually or via bash.';
    win.appendChild(note);
    [['Continue', rwContinue], ['Never mind', closeRewindDialog]].forEach(([lbl, fn], i) => {
      const o = document.createElement('div'); o.className = 'rw-opt' + (i === st.confirmSel ? ' sel' : '');
      o.innerHTML = '<span class="num">' + (i + 1) + '</span><span></span>';
      o.lastChild.textContent = lbl;
      o.onclick = fn;
      o.onmouseenter = () => {
        st.confirmSel = i;
        win.querySelectorAll('.rw-opt').forEach((el, j) => el.classList.toggle('sel', j === i));
      };
      win.appendChild(o);
    });
  }
}


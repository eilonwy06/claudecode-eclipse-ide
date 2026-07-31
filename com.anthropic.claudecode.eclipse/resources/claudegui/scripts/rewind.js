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
    // Every confirm-style phase is the same two-option list; ONLY the accept
    // action differs. Resolving it per phase is what keeps Enter/1 in the delete
    // dialog from running the fork-and-rewind that shares this window.
    const accept = rwAccept(st.phase);
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); st.confirmSel = st.confirmSel ? 0 : 1; renderRewind(); }
    else if (e.key === '1') { e.preventDefault(); accept(); }
    else if (e.key === '2') { e.preventDefault(); closeRewindDialog(); }
    else if (e.key === 'Enter') { e.preventDefault(); if (st.confirmSel === 0) accept(); else closeRewindDialog(); }
  }
}
/** The numbered "1 <accept> / 2 Never mind" rows every confirm phase ends with. */
function rwOptions(win, st, opts) {
  opts.forEach(([lbl, fn], i) => {
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
/** The action behind option 1 for each confirm-style phase. */
function rwAccept(phase) {
  return phase === 'delete' ? rwDeleteConfirm
       : phase === 'code'   ? rwCodeConfirm
       : rwContinue;
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
  const tab = st.tab, mid = st.msg.id;
  closeRewindDialog();
  forkFrom(tab, mid, window._rewindApply, 'Rewind failed: ');
}
/* Shared fork tail: run the native fork, then open the forked conversation in a
   NEW tab with the selected message back in the composer (the source tab is
   untouched). "Fork conversation and rewind code" and "Fork conversation from
   here" differ only in which native call gets used — whether the files are
   restored along the way. */
function forkFrom(tab, mid, fn, errPrefix) {
  if (!tab || !fn) return;
  let res = {};
  try { res = JSON.parse(fn(tab.sessionId || '', mid) || '{}') || {}; } catch (e) {}
  if (res.error) { addSystem('⚠ ' + errPrefix + res.error); return; }
  const title = tab.title, titled = tab.titled;
  createTab({ title, titled });
  if (res.sessionId) {
    loadHistory(res.sessionId, title);   // fills the new (now active) tab
    if (titled && window._renameSession) window._renameSession(res.sessionId, title);
  }
  const p = stripMeta(res.prompt || '');
  if (p) { input.value = p; input.dispatchEvent(new Event('input', { bubbles: true })); }
  // loadHistory parks a reopened conversation at the TOP, which reads as the wrong
  // conversation when you've just forked one — land on the newest message instead,
  // right above the prompt now sitting in the composer.
  if (messagesEl) messagesEl.scrollTop = messagesEl.scrollHeight;
  input.focus();
}

/* ── Entry points for the per-message badge menu (joebiden8) ──────────────────
   The same window, opened straight at a confirm phase for ONE message instead of
   going through the "Rewind to…" list. */
function openConfirmPhase(tab, mid, phase, box) {
  if (!tab || !mid) return;
  closeMenus(); closeSlash();
  let prev = {};
  if (phase !== 'delete') {
    try { prev = JSON.parse(window._rewindPreview(tab.sessionId || '', mid) || '{}') || {}; } catch (e) {}
  }
  rwState = { tab, msgs: [], sel: 0, phase, msg: { id: mid }, mid, box, preview: prev, confirmSel: 0 };
  renderRewind();
  document.getElementById('rewind-overlay').classList.add('open');
  document.addEventListener('keydown', rwKey, true);
}
/** "Rewind code to here" — restore the files, leave the conversation alone. */
function rwCodeConfirm() {
  const st = rwState; if (!st || !st.mid) return;
  const tab = st.tab, mid = st.mid;
  closeRewindDialog();
  let res = {};
  try { res = JSON.parse(window._rewindCodeOnly(tab.sessionId || '', mid) || '{}') || {}; } catch (e) {}
  if (res.error) { addSystem('⚠ Rewind failed: ' + res.error); return; }
  addInterrupted('Code rewind successful');   // italic muted note (joebiden9)
}
/** Permanent per-message delete. The bubble is replaced in place by an italic
    note; nothing persists it, because after a reload the line is simply gone. */
function rwDeleteConfirm() {
  const st = rwState; if (!st || !st.mid) return;
  const tab = st.tab, mid = st.mid, box = st.box;
  closeRewindDialog();
  if (!window._deleteMessage) { addSystem('⚠ Could not delete the message: not supported by this build.'); return; }
  let res = {};
  try { res = JSON.parse(window._deleteMessage(tab.id, tab.sessionId || '', mid) || '{}') || {}; } catch (e) {}
  if (!res.ok) { addSystem('⚠ Could not delete the message: ' + (res.error || 'unknown error')); return; }
  markMessageDeleted(box);
}
/** Swap a deleted message's bubble for the italic "Message deleted" note, in the
    same muted style a stopped turn leaves behind. */
function markMessageDeleted(box) {
  if (!box) return;
  const note = document.createElement('div');
  note.className = 'interrupted'; note.textContent = 'Message deleted';
  const turn = box.parentNode;
  if (turn && turn.classList && turn.classList.contains('turn')) {
    turn.innerHTML = '';
    turn.appendChild(note);
  } else {
    box.replaceWith(note);
  }
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
  } else if (st.phase === 'delete') {
    title.textContent = 'Delete message';
    const b1 = document.createElement('div'); b1.className = 'rw-body';
    b1.textContent = 'This message will be permanently removed from this conversation’s history.';
    win.appendChild(b1);
    const note = document.createElement('div'); note.className = 'rw-note';
    note.textContent = 'ⓘ This cannot be undone.';
    win.appendChild(note);
    rwOptions(win, st, [['Delete', rwDeleteConfirm], ['Never mind', closeRewindDialog]]);
  } else {
    // 'confirm' = fork + rewind code · 'code' = rewind code only
    const codeOnly = st.phase === 'code';
    title.textContent = codeOnly ? 'Rewind code' : 'Fork and rewind';
    if (!codeOnly) {
      const b1 = document.createElement('div'); b1.className = 'rw-body';
      b1.textContent = 'A new forked conversation will be created after rewinding.';
      win.appendChild(b1);
    }
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
      sum.textContent = '⚠ Could not read the checkpoint (' + st.preview.error + ')'
        + (codeOnly ? '.' : ' — only the conversation will be forked.');
      win.appendChild(sum);
    } else if (st.preview && st.preview.noCheckpoint) {
      sum.textContent = 'This message has no file checkpoint (it was sent before checkpointing was enabled), so code cannot be restored'
        + (codeOnly ? '.' : ' — only the conversation will be forked.');
      win.appendChild(sum);
    } else if (codeOnly) {
      // Nothing to restore (joebiden8): the reference wording, bold and all.
      sum.innerHTML = 'The code <strong>has not changed</strong>, so no code will be restored.';
      win.appendChild(sum);
    } else {
      sum.textContent = 'The files already match this point — only the conversation will be forked.';
      win.appendChild(sum);
    }
    const note = document.createElement('div'); note.className = 'rw-note';
    note.textContent = 'ⓘ Rewinding does not affect files edited manually or via bash.';
    win.appendChild(note);
    rwOptions(win, st, [[codeOnly ? 'Rewind' : 'Continue', rwAccept(st.phase)],
                        ['Never mind', closeRewindDialog]]);
  }
}


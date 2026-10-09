/* clidialogs.js — the dialogs the command menu opens: Slash commands, Memory, Instructions,
   Status, Permissions, Hooks, Sandbox, Export conversation, Claude in Chrome, Output styles,
   Claude Design, Report a problem, signing in and out.

   What is in them is the CLI's: each is drawn from the answer to one of its control
   requests (get_memory_dialog, get_status, …), asked of the tab's own process through
   _cli and answered through window.onCliReply under a token, as the MCP servers window
   asks (mcp.js). So this may have to start the tab's process, as that window may. */

const CLI_TIMEOUT_MS = 45000;
const cliPending = new Map();   // token -> { resolve, reject, timer }
let cliSeq = 0;

window.onCliReply = function(tabId, json) {
  let r; try { r = JSON.parse(json); } catch (e) { return; }
  const p = r && cliPending.get(r.token);
  if (!p) return;
  cliPending.delete(r.token);
  clearTimeout(p.timer);
  if (r.ok) p.resolve(r.response || {});
  else p.reject(new Error(r.error || 'Unknown error'));
};
/** A token for one request, and the promise its answer settles: the response body, or
    what went wrong. An answer that has not come in `timeoutMs` is given up on. */
function cliWaiting(prefix, timeoutMs) {
  const token = prefix + Date.now().toString(36) + '-' + (++cliSeq);
  const done = new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      cliPending.delete(token);
      reject(new Error('Claude Code did not answer in time.'));
    }, timeoutMs || CLI_TIMEOUT_MS);
    cliPending.set(token, { resolve, reject, timer });
  });
  return { token, done };
}
/** One control request to the tab's process; resolves with the CLI's response body.
    `timeoutMs` is for the few that wait on a person (a sign-in in the browser). */
function cliAsk(t, subtype, extra, timeoutMs) {
  if (!window._cli) return Promise.reject(new Error('Not supported by this build.'));
  const w = cliWaiting('c', timeoutMs);
  _cli(t.id, w.token, JSON.stringify(Object.assign({ subtype }, extra || {})), t.sessionId || '',
       t.permMode || permMode, effort, curModel, thinkingOn ? '1' : '0', rootPathOf(t), ultracodeOn);
  return w.done;
}
/** Saves one change a dialog made, with the CLI's own edit subcommand (`claude edit-…`),
    run in the tab's folder; the outcome comes back like any other answer. The CLI's other
    subcommands that are run to their end go the same way (cli_ask.rs). */
function cliEdit(t, subcommand, input) {
  if (!window._cliEdit) return Promise.reject(new Error('Not supported by this build.'));
  const w = cliWaiting('e');
  _cliEdit(t.id, w.token, subcommand, JSON.stringify(input), rootPathOf(t));
  return w.done;
}
/** Asks again until `settled(answer)` holds, a few times: a running process takes a
    moment to see a setting that was just written. Resolves with the last answer. */
function cliAskUntil(t, subtype, settled, tries) {
  return cliAsk(t, subtype).then((res) => {
    if (settled(res) || tries <= 1) return res;
    return new Promise(r => setTimeout(r, 300)).then(() => cliAskUntil(t, subtype, settled, tries - 1));
  });
}
/** The plugin's own errands for these dialogs (ClaudeGuiView#ideErrand). */
function ideErrand(what, a, b) {
  try { return window._ide ? (_ide(what, a === undefined ? '' : a, b === undefined ? '' : b) || '') : ''; }
  catch (e) { return ''; }
}

/* ===================== The window ===================== */
/* One dialog at a time. draw() rebuilds the window from the dialog's state; escape() is
   what the dismiss key does (a view inside a dialog backs out before the dialog closes). */
let cliDlg = null;

function cwEl(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined && text !== null) e.textContent = text;
  return e;
}
function openCliDialog(dlg) {
  closeMenus(); closeSlash();
  if (cliDlg) closeCliDialog();
  cliDlg = dlg;
  dlg.tab = activeTab();
  document.getElementById('cli-overlay').classList.add('open');
  document.getElementById('cli-overlay').classList.toggle('screen', !!dlg.screen);
  document.addEventListener('keydown', cliDlgKey, true);
  registerOverlayCancel(cliDlgCancel, false);   // not tab-owned — no visibility guard
  dlg.draw();
}
function closeCliDialog() {
  if (!cliDlg) return;
  const closing = cliDlg;
  cliDlg = null;
  if (closing.close) closing.close();   // what the dialog had running (a sign-in waiting on the browser)
  document.getElementById('cli-overlay').classList.remove('open', 'screen');
  document.getElementById('cli-win').innerHTML = '';
  document.removeEventListener('keydown', cliDlgKey, true);
  unregisterOverlayCancel(cliDlgCancel);
}
function cliDlgEscape() {
  const d = cliDlg; if (!d) return;
  if (d.escape && d.escape()) return;
  closeCliDialog();
}
/* Eclipse's binding (Esc, or Ctrl+G under Emacs) pops this entry before calling it, so a
   dialog still open afterwards has to put it back. */
function cliDlgCancel() {
  cliDlgEscape();
  if (cliDlg) registerOverlayCancel(cliDlgCancel, false);
}
document.getElementById('cli-overlay').addEventListener('click', (e) => {
  if (e.target.id === 'cli-overlay' && !(cliDlg && cliDlg.screen)) closeCliDialog();   // a screen is not clicked away
});
/** Escape, and the arrow keys over whatever rows the dialog is showing. */
function cliDlgKey(e) {
  if (!cliDlg) return;
  if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); cliDlgEscape(); return; }
  if (e.target && e.target.tagName === 'TEXTAREA') return;
  const rows = [...document.querySelectorAll('#cli-win .cw-item, #cli-win .sc-row:not(.inert)')];
  if (!rows.length) return;
  const at = rows.findIndex(r => r.classList.contains('sel'));
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault(); e.stopPropagation();
    const to = at < 0 ? (e.key === 'ArrowDown' ? 0 : rows.length - 1)
                      : (at + (e.key === 'ArrowDown' ? 1 : -1) + rows.length) % rows.length;
    rows.forEach((r, i) => r.classList.toggle('sel', i === to));
    if (rows[to].scrollIntoView) rows[to].scrollIntoView({ block: 'nearest' });
  } else if (e.key === 'Enter' && at >= 0) {
    e.preventDefault(); e.stopPropagation();
    rows[at].click();
  }
}
/** Empties the window and gives it its head: a back arrow when there is somewhere to go
    back to, the title, the close button. @returns the body to fill */
function cwFrame(title, onBack, wide) {
  const win = document.getElementById('cli-win');
  win.innerHTML = '';
  win.classList.toggle('wide', !!wide);
  const head = cwEl('div', 'cw-head');
  if (onBack) {
    const back = cwEl('span', 'cw-back'); back.innerHTML = ICONS.CHEVRON; back.title = 'Back';
    back.onclick = onBack;
    head.appendChild(back);
  }
  head.appendChild(cwEl('span', 'cw-title', title));
  const x = cwEl('span', 'cw-x'); x.innerHTML = ICONS.X; x.title = 'Close';
  x.onclick = closeCliDialog;
  head.appendChild(x);
  win.appendChild(head);
  const body = cwEl('div', 'cw-body');
  win.appendChild(body);
  return body;
}
function cwBtn(label, cls, onClick) {
  const b = cwEl('button', 'cw-btn' + (cls ? ' ' + cls : ''), label);
  b.type = 'button'; b.onclick = onClick;
  return b;
}
function cwKeys(parts) {
  const k = cwEl('div', 'cw-keys cw-fixed');
  parts.forEach((p, i) => {
    if (i) k.appendChild(document.createTextNode(' · '));
    p[0].forEach(key => k.appendChild(cwEl('kbd', '', key)));
    k.appendChild(document.createTextNode(' ' + p[1]));
  });
  return k;
}
const CW_NAV_KEYS = [[['↑', '↓'], 'to navigate'], [['Enter'], 'to select'], [['Esc'], 'to close']];
/** How long ago, in the words the Memory dialog uses. */
function cwAgo(ms, now) {
  const days = Math.max(0, Math.floor(((now || Date.now()) - ms) / 86400000));
  if (days === 0) return 'today';
  if (days === 1) return 'yesterday';
  if (days < 14) return days + ' days ago';
  if (days < 60) return Math.floor(days / 7) + ' weeks ago';
  if (days < 365) return Math.floor(days / 30) + ' months ago';
  const years = Math.floor(days / 365);
  return years === 1 ? '1 year ago' : years + ' years ago';
}
/** A dialog whose whole content is one answer from the CLI: says "Loading…" until it is
    in, what went wrong if it is not, and hands the answer to `fill` when it is. */
function openAskedDialog(title, subtype, fill, wide) {
  const dlg = {
    data: null, error: null,
    draw() {
      const body = cwFrame(title, null, wide);
      if (dlg.error) { body.appendChild(cwEl('div', 'cw-msg', dlg.error)); return; }
      if (!dlg.data) { body.appendChild(cwEl('div', 'cw-dim', 'Loading…')); return; }
      fill(body, dlg.data, dlg);
    },
  };
  openCliDialog(dlg);
  cliAsk(dlg.tab, subtype).then((res) => { if (cliDlg === dlg) { dlg.data = res; dlg.draw(); } },
                                (err) => { if (cliDlg === dlg) { dlg.error = (err && err.message) || 'Unknown error'; dlg.draw(); } });
  return dlg;
}

/* ===================== Slash commands ===================== */
function openSlashCommandsDialog() {
  const dlg = {
    kind: 'slash', filter: '', skills: null,
    draw() {
      const body = cwFrame('Slash commands', null, false);
      const win = document.getElementById('cli-win');
      const box = cwEl('input', 'cw-input cw-filter cw-fixed');
      box.type = 'text'; box.placeholder = 'Filter commands…'; box.value = dlg.filter;
      box.oninput = () => { dlg.filter = box.value; dlg.list(body); };
      win.insertBefore(box, body);
      dlg.list(body);
      box.focus();
    },
    list(body) {
      const keep = body.scrollTop;
      body.innerHTML = '';
      const q = dlg.filter.trim().toLowerCase().replace(/^\//, '');
      const skills = dlg.skills || [];
      const skillOf = (name) => skills.find(s => s.name === name || s.display_name === name
                                              || (s.handles && s.handles.unqualified_name === name));
      const cmds = allSlashCommands(dlg.tab).filter(c => !q || cmdMatch(q, c.name, c.aliases, c.description) >= 0);
      const listed = new Set(allSlashCommands(dlg.tab).map(c => c.name));
      // A skill that is switched off is no command, but is still a row here.
      const extra = skills.filter(s => !listed.has(s.name) && !listed.has(s.display_name)
        && !(s.handles && listed.has(s.handles.unqualified_name))
        && (!q || cmdMatch(q, s.display_name || s.name, null, s.description) >= 0));
      const loading = !offersFor(dlg.tab) && offersLoading(dlg.tab);
      const list = cwEl('div', 'sc-list');
      const line = (name, skill, inert, desc) => {
        const ln = cwEl('div', 'sc-line');
        const row = cwEl('div', 'sc-row' + (inert ? ' inert' : ''));
        row.appendChild(cwEl('div', 'sc-name', '/' + name));
        if (skill) row.appendChild(cwEl('div', 'sc-sub', skill.source + ' · ~' + skill.tokens + ' tokens'));
        if (desc) row.title = desc;
        if (!inert) row.onclick = () => { closeCliDialog(); applySlash('/' + name); };
        ln.appendChild(row);
        if (skill && skill.state) {
          const word = skill.state === 'on' ? 'On' : skill.state === 'off' ? 'Off' : skill.state;
          const st = cwEl('button', 'sc-state', dlg.saving === skill.name ? 'Saving…' : word);
          st.type = 'button';
          if (skill.locked_by) { st.disabled = true; st.textContent = word + ' · locked'; }
          else {
            st.title = 'Click to change.';
            st.disabled = !!dlg.saving;
            st.onclick = () => dlg.setState(skill, skill.state === 'off' ? 'on' : 'off');
          }
          ln.appendChild(st);
        }
        return ln;
      };
      cmds.forEach(c => list.appendChild(line(c.name, skillOf(c.name), false, c.description)));
      extra.forEach(s => list.appendChild(line(s.display_name || s.name, s, true, s.description)));
      if (loading) {
        const row = cwEl('div', 'loading-row');
        row.innerHTML = '<span class="spin-ring"></span><span></span>'; row.lastChild.textContent = 'Loading…';
        list.appendChild(row);
      }
      dlg.loaderShown = loading;
      if (!cmds.length && !extra.length && !loading) {
        body.appendChild(cwEl('div', 'cw-dim', q ? 'No matching commands.' : 'No slash commands available.'));
      } else {
        body.appendChild(list);
      }
      body.scrollTop = q ? 0 : keep;
      if (dlg.note) {
        const win = document.getElementById('cli-win');
        const old = win.querySelector('.cw-msg'); if (old) old.remove();
        win.insertBefore(cwEl('div', 'cw-msg cw-fixed', dlg.note), body);
      }
    },
    /** Switches a skill on or off: saved by the CLI, then read back from the session. */
    setState(skill, state) {
      const redraw = () => { const body = document.querySelector('#cli-win .cw-body'); if (body) dlg.list(body); };
      dlg.saving = skill.name; dlg.note = null;
      redraw();
      const input = { name: skill.name, state };
      if (skill.handles) input.handles = skill.handles;
      cliEdit(dlg.tab, 'edit-skill-overrides', input)
        .then(() => cliAskUntil(dlg.tab, 'get_skills_dialog',
                                (res) => (res.skills || []).some(s => s.name === skill.name && s.state === state), 6))
        .then((res) => { if (Array.isArray(res.skills)) dlg.skills = res.skills; },
              (err) => { dlg.note = (err && err.message) || 'Unknown error'; })
        .then(() => {
          if (cliDlg !== dlg) return;
          dlg.saving = null;
          redraw();
          requestOffers(dlg.tab, true);   // a skill switched off is no longer a command
        });
    },
  };
  openCliDialog(dlg);
  requestOffers(dlg.tab);
  // Which of them are skills, where each comes from and what it costs to keep loaded.
  cliAsk(dlg.tab, 'get_skills_dialog').then((res) => {
    if (cliDlg !== dlg) return;
    dlg.skills = Array.isArray(res.skills) ? res.skills : [];
    const body = document.querySelector('#cli-win .cw-body');
    if (body) dlg.list(body);
  }, () => {});
}
/** The folder's commands came in while this window was showing its loader. */
function slashDialogOffersChanged() {
  const d = cliDlg;
  if (!d || d.kind !== 'slash' || !d.loaderShown) return;
  const body = document.querySelector('#cli-win .cw-body');
  if (body) d.list(body);
}

/* ===================== Status ===================== */
/* The CLI's labels, except the three the extension rewords. */
const STATUS_LABELS = { cwd: 'Working folder', 'Peer address': 'Messaging address', 'Session kind': 'Session type' };
function openStatusDialog() {
  openAskedDialog('Status', 'get_status', (body, data) => {
    (data.sections || []).forEach(sec => {
      body.appendChild(cwEl('div', 'cw-sec caps', sec.title));
      (sec.rows || []).forEach(row => {
        const kv = cwEl('div', 'cw-kv');
        const label = Array.isArray(row) ? row[0] : row.label;
        kv.appendChild(cwEl('span', 'k', STATUS_LABELS[label] || label));
        kv.appendChild(cwEl('span', 'v', Array.isArray(row) ? row[1] : row.value));
        body.appendChild(kv);
      });
    });
  });
}

/* ===================== Sandbox ===================== */
function openSandboxDialog() {
  openAskedDialog('Sandbox', 'get_sandbox_dialog', (body, d) => {
    if (!d.supported) { body.appendChild(cwEl('div', 'cw-dim', d.unsupported_reason || '')); return; }
    const kv = (k, v) => {
      const row = cwEl('div', 'cw-kv');
      row.appendChild(cwEl('span', 'k', k)); row.appendChild(cwEl('span', 'v', v));
      body.appendChild(row);
    };
    kv('Sandbox', d.enabled ? 'Enabled' : 'Disabled');
    if (d.mode) kv('Mode', String(d.mode));
  });
}

/* ===================== Memory, and Instructions =====================
   One answer (get_memory_dialog) serves both: the memory files and the auto-memory
   switch, or the CLAUDE.md files. A memory file opens inside the window, where it can be
   edited; an instruction file opens in an editor, as the extension opens it. */
function openMemoryDialog(mode) {
  const instructions = mode === 'instructions';
  const dlg = {
    data: null, error: null, file: null, text: null, editing: false, note: null,
    escape() {
      if (dlg.editing) { dlg.editing = false; dlg.draw(); return true; }
      if (dlg.confirmDelete) { dlg.confirmDelete = false; dlg.draw(); return true; }
      if (dlg.file) { dlg.file = null; dlg.text = null; dlg.draw(); return true; }
      return false;
    },
    draw() {
      if (dlg.file) { dlg.drawFile(); return; }
      const body = cwFrame(instructions ? 'Instructions' : 'Memory', null, false);
      const win = document.getElementById('cli-win');
      if (dlg.error) { body.appendChild(cwEl('div', 'cw-msg', dlg.error)); return; }
      if (!dlg.data) {
        body.appendChild(cwEl('div', 'cw-dim', 'Loading…'));
        win.appendChild(cwKeys(CW_NAV_KEYS));
        return;
      }
      const d = dlg.data;
      const list = cwEl('div', 'cw-list');
      if (instructions) {
        (d.files || []).forEach(f => {
          const it = cwEl('div', 'cw-item');
          const top = cwEl('div', 'top');
          top.appendChild(cwEl('span', 'name', f.label));
          top.appendChild(cwEl('span', 'meta', f.kind === 'project-alt' ? 'Alternate project file'
                                              : f.kind === 'local' ? 'Local, not checked in' : (f.description || '')));
          it.appendChild(top);
          it.onclick = () => dlg.openInstructions(f);
          list.appendChild(it);
        });
        body.appendChild(list);
      } else {
        const am = d.auto_memory || {};
        const tg = cwEl('div', 'cw-toggle cw-fixed');
        tg.appendChild(cwEl('span', '', 'Auto-memory'));
        const sw = cwEl('span', 'sw' + (am.enabled ? ' on' : '') + (am.toggleable === false ? ' off-only' : ''));
        sw.title = am.status || '';
        if (am.toggleable !== false) sw.onclick = () => {
          const want = !am.enabled;
          am.enabled = want; dlg.note = null; dlg.draw();   // shown at once, confirmed below
          cliEdit(dlg.tab, 'edit-memory-settings', { autoMemoryEnabled: want })
            .then(() => cliAskUntil(dlg.tab, 'get_memory_dialog',
                                    (res) => !!(res.auto_memory && res.auto_memory.enabled === want), 6))
            .then((res) => { dlg.data = res; }, (err) => { am.enabled = !want; dlg.note = (err && err.message) || 'Unknown error'; })
            .then(() => { if (cliDlg === dlg && !dlg.file) dlg.draw(); });
        };
        tg.appendChild(sw);
        win.insertBefore(tg, body);
        if (dlg.note) win.insertBefore(cwEl('div', 'cw-msg cw-fixed', dlg.note), body);
        (d.memories || []).forEach(mem => {
          const it = cwEl('div', 'cw-item');
          const top = cwEl('div', 'top');
          top.appendChild(cwEl('span', 'name', mem.name));
          top.appendChild(cwEl('span', 'meta', [mem.type || '', cwAgo(mem.modified_ms)].filter(Boolean).join(' · ')));
          it.appendChild(top);
          if (mem.description) it.appendChild(cwEl('div', 'desc', mem.description));
          it.onclick = () => dlg.openMemory(mem);
          list.appendChild(it);
        });
        if ((d.memories || []).length) body.appendChild(list);
        (d.folders || []).forEach(f => {
          const b = cwBtn(f.label, 'left cw-fixed', () => ideErrand('openFolder', f.path));
          if (f.description) b.title = f.description;
          win.appendChild(b);
        });
      }
      win.appendChild(cwKeys(CW_NAV_KEYS));
    },
    openInstructions(f) {
      // A file that is not there yet is made empty, so there is something to open.
      if (f.exists === false) ideErrand('write', f.path, '');
      closeCliDialog();
      if (window._openFileInEditor) _openFileInEditor(f.path, rootPathOf(dlg.tab));
      addSystemTo(dlg.tab, 'Claude reads instruction files when a session starts. After editing, reload to apply changes to the current session.');
      // The notice carries its one action: the tab's process is started afresh on the same
      // conversation, which is when the CLI reads the instruction files again.
      const said = [...dlg.tab.pane.querySelectorAll('.sys')].pop();
      if (said && window._reloadTab) {
        const reload = cwEl('a', 'sys-action', 'Reload Claude');
        const tabId = dlg.tab.id;
        reload.onclick = () => { _reloadTab(tabId); reload.remove(); };
        said.appendChild(reload);
      }
    },
    openMemory(mem) {
      dlg.file = mem; dlg.text = null; dlg.editing = false; dlg.note = null;
      dlg.draw();
      // Read after the view is up, so "Loading…" shows for a file that takes a moment.
      setTimeout(() => {
        if (cliDlg !== dlg || dlg.file !== mem) return;
        const got = ideErrand('read', mem.path);
        dlg.text = got.startsWith('ok:') ? got.slice(3) : null;
        if (dlg.text === null) dlg.note = 'Could not read the file.';
        dlg.draw();
      }, 0);
    },
    drawFile() {
      const mem = dlg.file;
      const body = cwFrame(mem.name, () => dlg.escape(), true);
      const win = document.getElementById('cli-win');
      const meta = cwEl('div', 'cw-note cw-fixed', [mem.type || '', cwAgo(mem.modified_ms)].filter(Boolean).join(' · '));
      win.insertBefore(meta, body);
      if (dlg.note) body.appendChild(cwEl('div', 'cw-msg', dlg.note));
      if (dlg.text === null) { if (!dlg.note) body.appendChild(cwEl('div', 'cw-dim', 'Loading…')); }
      else if (dlg.editing) {
        const ta = cwEl('textarea', 'cw-pre'); ta.value = dlg.text; ta.spellcheck = false;
        body.appendChild(ta);
        win.appendChild(cwBtn('Save', 'primary cw-fixed', () => {
          if (ideErrand('write', mem.path, ta.value) === 'ok') { dlg.text = ta.value; dlg.editing = false; dlg.note = null; }
          else dlg.note = 'Could not save the file.';
          dlg.draw();
        }));
        win.appendChild(cwBtn('Cancel', 'cw-fixed', () => { dlg.editing = false; dlg.draw(); }));
        win.appendChild(cwKeys([[['Esc'], 'to stop editing']]));
        setTimeout(() => ta.focus(), 0);
        return;
      } else {
        body.appendChild(cwEl('pre', 'cw-pre', dlg.text));
        win.appendChild(cwBtn('Edit', 'primary cw-fixed', () => { dlg.editing = true; dlg.draw(); }));
        win.appendChild(cwBtn('Open in editor', 'cw-fixed', () => {
          closeCliDialog();
          if (window._openFileInEditor) _openFileInEditor(mem.path, rootPathOf(dlg.tab));
        }));
        // Deleting takes a second press: the file is gone for good.
        if (dlg.confirmDelete) {
          const yes = cwBtn('Delete', 'cw-fixed', () => {
            if (ideErrand('delete', mem.path) !== 'ok') { dlg.note = 'Could not delete the file.'; dlg.confirmDelete = false; dlg.draw(); return; }
            dlg.file = null; dlg.text = null; dlg.confirmDelete = false;
            if (dlg.data && dlg.data.memories) dlg.data.memories = dlg.data.memories.filter(m => m.path !== mem.path);
            dlg.draw();
          });
          yes.style.color = 'var(--red)'; yes.style.borderColor = 'var(--red)';
          win.appendChild(yes);
          win.appendChild(cwBtn('Cancel', 'cw-fixed', () => { dlg.confirmDelete = false; dlg.draw(); }));
        } else {
          const del = cwBtn('Delete', 'cw-fixed', () => { dlg.confirmDelete = true; dlg.draw(); });
          del.style.color = 'var(--red)';
          win.appendChild(del);
        }
      }
      win.appendChild(cwKeys([[['Esc'], 'to go back']]));
    },
  };
  openCliDialog(dlg);
  cliAsk(dlg.tab, 'get_memory_dialog').then((res) => { if (cliDlg === dlg) { dlg.data = res; dlg.draw(); } },
    (err) => { if (cliDlg === dlg) { dlg.error = (err && err.message) || 'Unknown error'; dlg.draw(); } });
}

/* ===================== Permission rules =====================
   The rules come from the running session (list_permission_rules); a rule added or taken
   out here is written by the CLI's own `claude edit-permission-rules`, to the settings
   file chosen, and the list is then read back from the session. */
const RULE_SOURCES = { localSettings: 'From project local settings', projectSettings: 'From project settings',
                       userSettings: 'From user settings', policySettings: 'From managed settings',
                       flagSettings: 'From command line arguments', session: 'For this session' };
const RULE_DESTINATIONS = [['localSettings', 'this project (just you)'], ['projectSettings', 'this project (shared)'],
                           ['userSettings', 'all your projects']];
const RULE_BEHAVIORS = [['allow', 'ALLOW'], ['ask', 'ASK'], ['deny', 'DENY']];
function openPermissionsDialog() {
  const dlg = {
    data: null, error: null, note: null, view: 'list', behavior: 'allow', rule: null, busy: false,
    text: '', destination: 'localSettings',
    escape() {
      if (dlg.busy) return true;
      if (dlg.view !== 'list') { dlg.view = 'list'; dlg.note = null; dlg.draw(); return true; }
      return false;
    },
    reload(settled) {
      return cliAskUntil(dlg.tab, 'list_permission_rules', settled || (() => true), settled ? 6 : 1)
        .then((res) => { dlg.data = res; dlg.error = null; },
              (err) => { dlg.error = (err && err.message) || 'Unknown error'; })
        .then(() => { if (cliDlg === dlg) dlg.draw(); });
    },
    rules() { return (dlg.data && dlg.data.state && dlg.data.state.rules) || []; },
    draw() {
      if (dlg.view === 'add') { dlg.drawAdd(); return; }
      if (dlg.view === 'remove') { dlg.drawRemove(); return; }
      const body = cwFrame('Permission rules', null, true);
      if (dlg.error) { body.appendChild(cwEl('div', 'cw-msg', dlg.error)); return; }
      if (!dlg.data) { body.appendChild(cwEl('div', 'cw-dim', 'Loading…')); return; }
      if (dlg.note) body.appendChild(cwEl('div', 'cw-msg', dlg.note));
      const st = dlg.data.state || {};
      const editable = !st.managedOnly;
      RULE_BEHAVIORS.forEach(([behavior, title]) => {
        const mine = dlg.rules().filter(r => r.behavior === behavior);
        const head = cwEl('div', 'cw-sec caps');
        head.appendChild(cwEl('span', '', title + ' (' + mine.length + ')'));
        if (editable) {
          const add = cwEl('span', 'cw-link', 'Add rule…');
          add.onclick = () => { dlg.view = 'add'; dlg.behavior = behavior; dlg.text = ''; dlg.note = null; dlg.draw(); };
          head.appendChild(add);
        }
        body.appendChild(head);
        // Drawn a screenful at a time: a list of several hundred rules must not hold the page.
        let shown = 0;
        const more = cwEl('div', 'cw-link', '');
        const draw = () => {
          mine.slice(shown, shown + 60).forEach(r => {
            const box = cwEl('div', 'rule');
            const top = cwEl('div', 'top');
            top.appendChild(cwEl('span', 'text', r.rule));
            const src = cwEl('span', 'src', RULE_SOURCES[r.source] || r.source || '');
            if (editable && r.editability !== 'readOnly') {
              const rm = cwEl('span', 'cw-link danger', 'Remove'); rm.style.marginLeft = '8px';
              rm.onclick = () => { dlg.view = 'remove'; dlg.rule = r; dlg.note = null; dlg.draw(); };
              src.appendChild(rm);
            }
            top.appendChild(src);
            box.appendChild(top);
            body.insertBefore(box, more);
          });
          shown = Math.min(mine.length, shown + 60);
          more.textContent = shown < mine.length ? 'Show more (' + (mine.length - shown) + ')' : '';
          more.style.display = shown < mine.length ? '' : 'none';
        };
        more.onclick = draw;
        body.appendChild(more);
        draw();
      });
      const dirs = st.workspaceDirectories || [];
      body.appendChild(cwEl('div', 'cw-sec caps', 'WORKSPACE (' + dirs.length + ')'));
      dirs.forEach(dir => {
        const box = cwEl('div', 'rule');
        const top = cwEl('div', 'top');
        top.appendChild(cwEl('span', 'text', typeof dir === 'string' ? dir : (dir.path || dir.directory || '')));
        box.appendChild(top);
        body.appendChild(box);
      });
      body.appendChild(cwEl('div', 'cw-dim',
        'Rules and workspace directories come from the running session, including approvals made for this session only '
        + 'and rules set when the session started. Rules you add or remove here are written to the settings file you '
        + 'choose. Workspace directories are listed here for reference.'));
    },
    drawAdd() {
      const body = cwFrame('Permission rules', () => dlg.escape(), true);
      body.appendChild(cwEl('div', 'cw-sec', 'Add ' + dlg.behavior + ' rule'));
      body.appendChild(cwEl('div', 'cw-dim',
        'A permission rule is a tool name, optionally followed by a specifier in parentheses, e.g. WebFetch or Bash(ls *).'));
      if (dlg.note) body.appendChild(cwEl('div', 'cw-msg', dlg.note));
      const box = cwEl('input', 'cw-input'); box.type = 'text'; box.placeholder = 'Enter permission rule…'; box.value = dlg.text;
      box.oninput = () => { dlg.text = box.value; };
      box.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); save(); } };
      body.appendChild(box);
      const to = cwEl('div', 'cw-note'); to.appendChild(document.createTextNode('Save to: '));
      const sel = cwEl('select', 'cw-input'); sel.style.width = 'auto'; sel.style.padding = '2px 6px';
      RULE_DESTINATIONS.forEach(([v, label]) => { const o = cwEl('option', '', label); o.value = v; sel.appendChild(o); });
      sel.value = dlg.destination; sel.onchange = () => { dlg.destination = sel.value; };
      to.appendChild(sel);
      body.appendChild(to);
      const save = () => {
        const rule = dlg.text.trim();
        if (!rule || dlg.busy) return;
        const before = dlg.rules().length;
        dlg.busy = true; dlg.note = null; dlg.draw();
        cliEdit(dlg.tab, 'edit-permission-rules', { op: 'add', rules: [rule], behavior: dlg.behavior, destination: dlg.destination })
          .then(() => { dlg.view = 'list'; dlg.busy = false;
                        return dlg.reload((res) => ((res.state && res.state.rules) || []).length !== before); },
                (err) => { dlg.busy = false; dlg.note = (err && err.message) || 'Unknown error'; if (cliDlg === dlg) dlg.draw(); });
      };
      const add = cwBtn(dlg.busy ? 'Adding…' : 'Add rule', 'primary', save); add.disabled = dlg.busy;
      body.appendChild(add);
      const cancel = cwBtn('Cancel', '', () => dlg.escape()); cancel.disabled = dlg.busy;
      body.appendChild(cancel);
      setTimeout(() => box.focus(), 0);
    },
    drawRemove() {
      const r = dlg.rule;
      const body = cwFrame('Permission rules', () => dlg.escape(), true);
      body.appendChild(cwEl('div', 'cw-sec', 'Remove ' + r.behavior + ' rule?'));
      const box = cwEl('div', 'rule');
      const top = cwEl('div', 'top');
      top.appendChild(cwEl('span', 'text', r.rule));
      box.appendChild(top);
      box.appendChild(cwEl('div', 'say', RULE_SOURCES[r.source] || r.source || ''));
      body.appendChild(box);
      if (dlg.note) body.appendChild(cwEl('div', 'cw-msg', dlg.note));
      body.appendChild(cwEl('div', 'cw-dim',
        'The rule is deleted from its settings file and stops applying to this session once Claude Code has re-read its settings.'));
      const before = dlg.rules().length;
      const rm = cwBtn(dlg.busy ? 'Removing…' : 'Remove rule', 'primary', () => {
        if (dlg.busy) return;
        dlg.busy = true; dlg.note = null; dlg.draw();
        cliEdit(dlg.tab, 'edit-permission-rules', { op: 'remove', rule: r.rule, behavior: r.behavior, source: r.source })
          .then(() => { dlg.view = 'list'; dlg.busy = false;
                        return dlg.reload((res) => ((res.state && res.state.rules) || []).length !== before); },
                (err) => { dlg.busy = false; dlg.note = (err && err.message) || 'Unknown error'; if (cliDlg === dlg) dlg.draw(); });
      });
      rm.disabled = dlg.busy;
      body.appendChild(rm);
      const cancel = cwBtn('Cancel', '', () => dlg.escape()); cancel.disabled = dlg.busy;
      body.appendChild(cancel);
    },
  };
  openCliDialog(dlg);
  dlg.reload();
}

/* ===================== Hooks =====================
   The listing is the session's (get_hooks_listing); a hook added, changed or removed here
   is written by the CLI's own `claude edit-hook`, and the listing is then read back. The
   form's fields are the extension's, type for type. */
const HOOKS_DOCS_URL = 'https://code.claude.com/docs/en/hooks';
const HOOK_SOURCES = [
  ['localSettings', 'Local', 'Private to you in this project (.claude/settings.local.json)'],
  ['userSettings', 'User', 'Available in all your projects (~/.claude/settings.json)'],
  ['projectSettings', 'Project', 'Shared via .claude/settings.json in this project'],
];
const HOOK_TYPES = [
  ['command', 'Command', 'Runs a shell command'],
  ['prompt', 'Prompt', 'Asks a model to evaluate a prompt'],
  ['agent', 'Agent', 'Runs an agent to verify something'],
  ['http', 'HTTP', 'Posts the hook input to a URL'],
  ['mcp_tool', 'MCP tool', 'Calls a tool on a configured MCP server'],
];
/* kind: textarea | text | json (array or object) | check | choice | lines | number */
const HOOK_FIELDS = {
  command: [
    { key: 'command', label: 'Command', kind: 'textarea', required: true },
    { key: 'args', label: 'Arguments (JSON array, optional; no shell when set)', kind: 'json', json: 'array' },
    { key: 'shell', label: 'Shell', kind: 'choice', choices: [
        ['', 'Default', 'bash where available, PowerShell on Windows without Git Bash'], ['bash', 'bash', ''], ['powershell', 'PowerShell', '']] },
    { key: 'async', label: 'Run in background', kind: 'check' },
  ],
  prompt: [
    { key: 'prompt', label: 'Prompt', kind: 'textarea', required: true },
    { key: 'model', label: 'Model (optional)', kind: 'text' },
    { key: 'continueOnBlock', label: 'Keep going when this prompt blocks, instead of ending the turn', kind: 'check' },
  ],
  agent: [
    { key: 'prompt', label: 'Prompt', kind: 'textarea', required: true },
    { key: 'model', label: 'Model (optional)', kind: 'text' },
  ],
  http: [
    { key: 'url', label: 'URL', kind: 'text', required: true, placeholder: 'https://example.com/hook' },
    { key: 'allowedEnvVars', label: 'Environment variables allowed in headers (one per line)', kind: 'lines' },
  ],
  mcp_tool: [
    { key: 'server', label: 'MCP server', kind: 'text', required: true },
    { key: 'tool', label: 'Tool', kind: 'text', required: true },
    { key: 'input', label: 'Input (JSON object, optional)', kind: 'json', json: 'object' },
  ],
};
const HOOK_COMMON_FIELDS = [
  { key: 'if', label: 'Only when the tool call matches (optional)', kind: 'text', placeholder: 'Bash(git *)' },
  { key: 'timeout', label: 'Timeout in seconds (optional)', kind: 'number' },
  { key: 'statusMessage', label: 'Status message while running (optional)', kind: 'text' },
];
/** The hook as it is saved, from the form's fields; or what is wrong with them. */
function hookFromForm(form) {
  const hook = { type: form.type };
  for (const f of HOOK_FIELDS[form.type].concat(HOOK_COMMON_FIELDS)) {
    const raw = form.fields[f.key];
    if (f.kind === 'check') { if (raw) hook[f.key] = true; continue; }
    const text = String(raw === undefined || raw === null ? '' : raw).trim();
    if (!text) {
      if (f.required) return { error: f.label + ' is required' };
      continue;
    }
    if (f.kind === 'json') {
      let v; try { v = JSON.parse(text); } catch (e) { return { error: f.label + ': not valid JSON' }; }
      if (f.json === 'array' ? !Array.isArray(v) : (!v || typeof v !== 'object' || Array.isArray(v))) {
        return { error: f.label + ': not a JSON ' + f.json };
      }
      hook[f.key] = v;
    } else if (f.kind === 'number') {
      const n = Number(text);
      if (!isFinite(n) || n <= 0) return { error: f.label + ': not a number' };
      hook[f.key] = n;
    } else if (f.kind === 'lines') {
      hook[f.key] = text.split(/\r?\n/).map(s => s.trim()).filter(Boolean);
    } else {
      hook[f.key] = text;
    }
  }
  return { hook };
}
/** The form's fields, from a hook as it is saved. */
function formFromHook(h) {
  const config = h.config || {};
  const fields = {};
  Object.keys(config).forEach(k => {
    if (k === 'type') return;
    const v = config[k];
    fields[k] = Array.isArray(v) && (k === 'allowedEnvVars') ? v.join('\n') : (v && typeof v === 'object') ? JSON.stringify(v) : v;
  });
  return { source: h.source || 'localSettings', event: h.event || '', matcher: h.matcher || '',
           type: HOOK_FIELDS[config.type] ? config.type : 'command', fields };
}
function openHooksDialog() {
  const dlg = {
    data: null, error: null, note: null, view: 'list', editing: null, form: null, busy: false, confirm: false,
    escape() {
      if (dlg.busy) return true;
      if (dlg.confirm) { dlg.confirm = false; dlg.draw(); return true; }
      if (dlg.view !== 'list') { dlg.view = 'list'; dlg.note = null; dlg.draw(); return true; }
      return false;
    },
    reload(settled) {
      return cliAskUntil(dlg.tab, 'get_hooks_listing', settled || (() => true), settled ? 6 : 1)
        .then((res) => { dlg.data = res; dlg.error = null; },
              (err) => { dlg.error = (err && err.message) || 'Unknown error'; })
        .then(() => { if (cliDlg === dlg) dlg.draw(); });
    },
    hooks() { return (dlg.data && dlg.data.hooks) || []; },
    events() { return (dlg.data && dlg.data.eventCatalog) || []; },
    save(edit) {
      const before = JSON.stringify(dlg.hooks());
      dlg.busy = true; dlg.note = null; dlg.draw();
      cliEdit(dlg.tab, 'edit-hook', edit)
        .then(() => { dlg.view = 'list'; dlg.busy = false; dlg.confirm = false;
                      return dlg.reload((res) => JSON.stringify(res.hooks || []) !== before); },
              (err) => { dlg.busy = false; dlg.note = (err && err.message) || 'Failed to save hook'; if (cliDlg === dlg) dlg.draw(); });
    },
    draw() {
      if (dlg.view === 'form') { dlg.drawForm(); return; }
      const body = cwFrame('Hooks', null, true);
      const win = document.getElementById('cli-win');
      if (dlg.error) { body.appendChild(cwEl('div', 'cw-msg', dlg.error)); return; }
      if (!dlg.data) { body.appendChild(cwEl('div', 'cw-dim', 'Loading…')); return; }
      const hooks = dlg.hooks();
      const policy = dlg.data.policy || {};
      const editable = !policy.managedOnly && !policy.disabledByPolicy;
      const add = () => {
        dlg.view = 'form'; dlg.editing = null; dlg.note = null;
        dlg.form = { source: 'localSettings', event: (dlg.events()[0] || {}).name || 'PreToolUse', matcher: '', type: 'command', fields: {} };
        dlg.draw();
      };
      if (!hooks.length) {
        const empty = cwEl('div', 'cw-dim'); empty.style.textAlign = 'center';
        empty.appendChild(cwEl('div', '', 'No hooks configured'));
        empty.appendChild(cwEl('div', '', 'Hooks run your commands, prompts, or HTTP calls in response to Claude Code events like tool calls and prompt submission.'));
        body.appendChild(empty);
        if (editable) body.appendChild(cwBtn('Add hook', '', add));
      } else {
        hooks.forEach(h => {
          const config = h.config || {};
          const box = cwEl('div', 'rule'); box.style.cursor = 'pointer';
          const top = cwEl('div', 'top');
          top.appendChild(cwEl('span', 'text', [h.event, h.matcher].filter(Boolean).join(' · ')));
          top.appendChild(cwEl('span', 'src', (HOOK_SOURCES.find(s => s[0] === h.source) || [0, h.source || ''])[1]));
          box.appendChild(top);
          const what = config.command || config.prompt || config.url || [config.server, config.tool].filter(Boolean).join(' · ') || '';
          box.appendChild(cwEl('div', 'say', (config.type ? config.type + ': ' : '') + String(what)));
          const mine = HOOK_SOURCES.some(s => s[0] === h.source);
          if (editable && mine) box.onclick = () => { dlg.view = 'form'; dlg.editing = h; dlg.note = null; dlg.confirm = false; dlg.form = formFromHook(h); dlg.draw(); };
          else box.style.cursor = 'default';
          body.appendChild(box);
        });
        if (editable) body.appendChild(cwBtn('Add hook', '', add));
      }
      const foot = cwEl('div', 'cw-dim cw-fixed');
      foot.appendChild(cwEl('div', '', 'Click a hook to view, edit, or remove it. Managed, plugin, session, and built-in hooks are read-only.'));
      const more = cwEl('a', 'cw-link', 'Learn more about hooks'); more.href = HOOKS_DOCS_URL;
      foot.appendChild(more);
      win.appendChild(foot);
    },
    drawForm() {
      const f = dlg.form, old = dlg.editing;
      const body = cwFrame('Hooks', null, true);
      const back = cwEl('a', 'cw-link', '← Back to list'); back.onclick = () => dlg.escape();
      body.appendChild(back);
      body.appendChild(cwEl('div', 'cw-sec', old ? 'Edit hook' : 'Add hook'));
      if (dlg.note) body.appendChild(cwEl('div', 'cw-msg', dlg.note));
      const options = (label, list, value, pick) => {
        body.appendChild(cwEl('div', 'cw-sec', label));
        list.forEach(([v, name, desc]) => {
          const o = cwEl('div', 'cw-opt' + (v === value ? ' selected' : ''));
          o.appendChild(cwEl('div', 'name', name));
          if (desc) o.appendChild(cwEl('div', 'desc', desc));
          o.onclick = () => { pick(v); dlg.draw(); };
          body.appendChild(o);
        });
      };
      if (!old) options('Save to', HOOK_SOURCES, f.source, (v) => { f.source = v; });
      body.appendChild(cwEl('div', 'cw-sec', 'Event'));
      const ev = cwEl('select', 'cw-input mono');
      dlg.events().forEach(e => { const o = cwEl('option', '', e.name + ' (' + e.summary + ')'); o.value = e.name; ev.appendChild(o); });
      if (f.event && !dlg.events().some(e => e.name === f.event)) { const o = cwEl('option', '', f.event); o.value = f.event; ev.appendChild(o); }
      ev.value = f.event; ev.onchange = () => { f.event = ev.value; dlg.draw(); };
      body.appendChild(ev);
      const event = dlg.events().find(e => e.name === f.event);
      if ((event && event.supportsMatcher) || f.matcher) {
        body.appendChild(cwEl('div', 'cw-sec', 'Matcher (leave empty to match everything)'));
        const m = cwEl('input', 'cw-input mono'); m.type = 'text'; m.placeholder = 'Bash'; m.value = f.matcher;
        m.oninput = () => { f.matcher = m.value; };
        body.appendChild(m);
      }
      options('Type', HOOK_TYPES, f.type, (v) => { f.type = v; });
      HOOK_FIELDS[f.type].concat(HOOK_COMMON_FIELDS).forEach(fd => {
        const cur = f.fields[fd.key];
        if (fd.kind === 'check') {
          const row = cwEl('label', 'cw-check');
          const cb = cwEl('input'); cb.type = 'checkbox'; cb.checked = !!cur; cb.onchange = () => { f.fields[fd.key] = cb.checked; };
          row.appendChild(cb); row.appendChild(document.createTextNode(' ' + fd.label));
          body.appendChild(row);
          return;
        }
        if (fd.kind === 'choice') {
          options(fd.label, fd.choices, cur === undefined ? '' : cur, (v) => { f.fields[fd.key] = v; });
          return;
        }
        body.appendChild(cwEl('div', 'cw-sec', fd.label));
        const big = fd.kind === 'textarea' || fd.kind === 'json' || fd.kind === 'lines';
        const inp = cwEl(big ? 'textarea' : 'input', 'cw-input mono');
        if (!big) inp.type = 'text';
        if (fd.placeholder) inp.placeholder = fd.placeholder;
        inp.value = cur === undefined || cur === null ? '' : String(cur);
        inp.oninput = () => { f.fields[fd.key] = inp.value; };
        body.appendChild(inp);
      });
      const submit = () => {
        if (dlg.busy) return;
        const built = hookFromForm(f);
        if (built.error) { dlg.note = built.error; dlg.draw(); return; }
        const matcher = (event && event.supportsMatcher) || (old && f.event === old.event) ? f.matcher : '';
        dlg.save(old
          ? { op: 'replace', source: old.source, event: f.event, matcher, hook: built.hook,
              target: { event: old.event, matcher: old.matcher, hook: old.config } }
          : { op: 'add', source: f.source, event: f.event, matcher, hook: built.hook });
      };
      const save = cwBtn(dlg.busy ? 'Saving…' : (old ? 'Save hook' : 'Add hook'), 'primary', submit); save.disabled = dlg.busy;
      body.appendChild(save);
      if (old) {
        if (dlg.confirm) {
          body.appendChild(cwEl('div', 'cw-note', 'Remove this hook?'));
          const yes = cwBtn(dlg.busy ? 'Removing…' : 'Remove hook', '', () => dlg.save(
            { op: 'remove', source: old.source, event: old.event, matcher: old.matcher, hook: old.config }));
          yes.disabled = dlg.busy;
          body.appendChild(yes);
          const no = cwBtn('Cancel', '', () => { dlg.confirm = false; dlg.draw(); }); no.disabled = dlg.busy;
          body.appendChild(no);
        } else {
          const rm = cwBtn('Remove', '', () => { dlg.confirm = true; dlg.draw(); }); rm.disabled = dlg.busy;
          rm.style.color = 'var(--red)';
          body.appendChild(rm);
        }
      }
    },
  };
  openCliDialog(dlg);
  dlg.reload();
}

/* ===================== Export conversation ===================== */
function openExportDialog() {
  let said = '';
  openAskedDialog('Export conversation', 'export_conversation', (body, d, dlg) => {
    const text = String(d.text || '');
    body.appendChild(cwEl('div', 'cw-text', 'The whole conversation as plain text, ' + text.length.toLocaleString() + ' characters.'));
    if (said) body.appendChild(cwEl('div', 'cw-note', said));
    body.appendChild(cwBtn('Copy to clipboard', 'primary', () => {
      if (window._clipSet) { _clipSet(text); said = 'Copied to clipboard'; }
      else said = 'Could not copy to clipboard';
      dlg.draw();
    }));
    body.appendChild(cwBtn('Save to file', 'left', () => {
      closeCliDialog();
      if (window._openTextInEditor) _openTextInEditor(text);
    }));
    body.appendChild(cwBtn('Close', 'left', closeCliDialog));
  });
}

/* ===================== Claude in Chrome ===================== */
function openChromeDialog() {
  openAskedDialog('Claude in Chrome', 'get_chrome_dialog', (body, d, dlg) => {
    const open = (url) => { if (url && window._openExternal) _openExternal(url); };
    body.appendChild(cwEl('div', 'cw-text',
      'Claude in Chrome works with the Chrome extension to let you control your browser directly from Claude Code. '
      + 'Navigate websites, fill forms, capture screenshots, record GIFs, and debug with console logs and network requests.'));
    body.appendChild(cwEl('div', 'cw-sec', 'Status'));
    const line = (label, value, ok) => {
      const row = cwEl('div', 'cw-text'); row.style.marginBottom = '4px';
      row.appendChild(document.createTextNode(label + ' '));
      row.appendChild(cwEl('span', ok ? 'cw-status-ok' : 'cw-status-off', value));
      body.appendChild(row);
    };
    const t = dlg.tab;
    const inSession = !!(t && t.browserOn);
    line('Claude in Chrome in this session:', inSession || d.connected ? 'Enabled' : 'Disabled', inSession || d.connected);
    line('Extension:', d.installed ? 'Installed' : 'Not installed', !!d.installed);
    body.appendChild(cwEl('div', 'cw-sec', 'Extension'));
    const urls = d.urls || {};
    if (!d.installed && urls.install) body.appendChild(cwBtn('Install extension', '', () => open(urls.install)));
    body.appendChild(cwBtn('Manage permissions', '', () => open(urls.permissions)));
    body.appendChild(cwBtn('Reconnect extension', '', () => open(urls.reconnect)));
    body.appendChild(cwEl('div', 'cw-sec', 'Setting'));
    const tg = cwEl('div', 'cw-toggle');
    tg.appendChild(cwEl('span', '', 'Enabled by default'));
    const sw = cwEl('span', 'sw' + (d.enabled_by_default ? ' on' : ''));
    sw.onclick = () => {
      const want = !d.enabled_by_default;
      d.enabled_by_default = want; dlg.error = null; dlg.draw();   // shown at once, confirmed below
      cliEdit(dlg.tab, 'edit-chrome-settings', { enabledByDefault: want })
        .then(() => cliAskUntil(dlg.tab, 'get_chrome_dialog', (res) => res.enabled_by_default === want, 6))
        .then((res) => { dlg.data = res; }, (err) => { d.enabled_by_default = !want; dlg.note = (err && err.message) || 'Unknown error'; })
        .then(() => { if (cliDlg === dlg) dlg.draw(); });
    };
    tg.appendChild(sw);
    body.appendChild(tg);
    if (dlg.note) { body.appendChild(cwEl('div', 'cw-msg', dlg.note)); dlg.note = null; }
    body.appendChild(cwEl('div', 'cw-dim',
      'When on, Claude in Chrome connects in the sessions open in this window and in every new session, here and '
      + 'outside this editor. When off, it connects here only when you use @browser.'));
    body.appendChild(cwEl('div', 'cw-dim',
      'Site-level permissions are inherited from the Chrome extension. Manage permissions in the Chrome extension '
      + 'settings to control which sites Claude can browse, click, and type on.'));
  });
}

/* ===================== Output styles =====================
   The styles and the one in use come with the folder's commands (cmdmenu.js). Picking one
   has the CLI save it as this folder's setting for this user (update_settings on the local
   layer, where the extension saves it), which the running conversation then follows.
   "Build a custom style" writes a style file the way the CLI reads one: a
   Markdown file with a name, a description and whether the coding instructions stay. */
function openOutputStyles() {
  const t = activeTab();
  const o = offersFor(t) || {};
  const styles = () => Array.isArray(o.available_output_styles) ? o.available_output_styles : [];
  const pick = (name) => {
    const was = o.output_style;
    o.output_style = name;   // shown at once, confirmed below
    cliAsk(t, 'update_settings', { source: 'localSettings', settings: { outputStyle: name } })
      .then(() => requestOffers(t, true),
            (err) => {
              o.output_style = was;
              addSystemTo(t, "Couldn't save the output style: " + ((err && err.message) || 'Unknown error'));
              if (cliDlg === dlg) dlg.draw();
            });
  };
  const dlg = {
    step: -1,   // -1 the chooser; 0..3 the four steps of a new style
    draft: { name: '', description: '', instructions: '', keep: true }, level: 'project', switchNow: true, note: null,
    escape() {
      if (dlg.step > 0) { dlg.step--; dlg.note = null; dlg.draw(); return true; }
      if (dlg.step === 0) { dlg.step = -1; dlg.note = null; dlg.draw(); return true; }
      return false;
    },
    fileName() { return dlg.draft.name.trim() + '.md'; },
    paths() {
      const sep = rootPathOf(t).includes('\\') ? '\\' : '/';
      const home = ideErrand('home');
      return { sep, project: rootPathOf(t) + sep + '.claude' + sep + 'output-styles' + sep + dlg.fileName(),
               user: home ? home + sep + '.claude' + sep + 'output-styles' + sep + dlg.fileName() : '' };
    },
    draw() {
      if (dlg.step >= 0) { dlg.drawStep(); return; }
      const body = cwFrame('Output styles', null, false);
      if (!styles().length) {
        body.appendChild(cwEl('div', 'cw-dim', offersLoading(t) ? 'Loading output styles…' : 'No output styles available'));
      } else {
        body.appendChild(cwEl('div', 'cw-note', 'Select an output style'));
        const list = cwEl('div', 'cw-list');
        styles().forEach(name => {
          const it = cwEl('div', 'cw-item');
          const top = cwEl('div', 'top');
          top.appendChild(cwEl('span', 'name', name));
          const mark = cwEl('span', 'meta'); if (name === o.output_style) mark.innerHTML = ICONS.CHECK;
          top.appendChild(mark);
          it.appendChild(top);
          it.onclick = () => { closeCliDialog(); pick(name); };
          list.appendChild(it);
        });
        body.appendChild(list);
      }
      const build = cwBtn('Build a custom style', 'left', () => { dlg.step = 0; dlg.note = null; dlg.draw(); });
      body.appendChild(build);
    },
    drawStep() {
      const d = dlg.draft;
      const body = cwFrame('Build a custom style', null, false);
      body.appendChild(cwEl('div', 'cw-note', 'Step ' + (dlg.step + 1) + ' of 4'));
      if (dlg.note) body.appendChild(cwEl('div', 'cw-msg', dlg.note));
      const nameOk = () => /^[A-Za-z0-9 _-]+$/.test(d.name.trim());
      let next = null, first = null;
      const field = (label, big, value, placeholder, help, set) => {
        body.appendChild(cwEl('div', 'cw-sec', label));
        const inp = cwEl(big ? 'textarea' : 'input', 'cw-input');
        if (!big) inp.type = 'text'; else inp.style.minHeight = '110px';
        inp.placeholder = placeholder; inp.value = value;
        inp.oninput = () => { set(inp.value); if (next && dlg.step === 0) next.disabled = !nameOk(); };
        if (!big) inp.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); if (next && !next.disabled) next.click(); } };
        body.appendChild(inp);
        body.appendChild(cwEl('div', 'cw-dim', help));
        first = first || inp;
      };
      const check = (label, help, value, set) => {
        const row = cwEl('label', 'cw-check');
        const cb = cwEl('input'); cb.type = 'checkbox'; cb.checked = value; cb.onchange = () => set(cb.checked);
        row.appendChild(cb); row.appendChild(document.createTextNode(' ' + label));
        body.appendChild(row);
        if (help) body.appendChild(cwEl('div', 'cw-dim', help));
      };
      if (dlg.step === 0) {
        field('Name', false, d.name, 'Diagrams first', 'Shows in the Output styles menu and becomes the file name', v => { d.name = v; });
      } else if (dlg.step === 1) {
        field('Description', false, d.description, 'Lead every explanation with a diagram', 'Optional. One line about what the style does', v => { d.description = v; });
      } else if (dlg.step === 2) {
        field('Instructions', true, d.instructions,
              'When explaining code, architecture, or data flow, start with a diagram, then explain in prose.',
              "Added to Claude's system prompt whenever this style is active", v => { d.instructions = v; });
        check('Include the coding instructions',
              'Keeps the default coding instructions alongside your style. Turn off for styles that are not about writing code.',
              d.keep, v => { d.keep = v; });
      } else {
        const p = dlg.paths();
        body.appendChild(cwEl('div', 'cw-sec', 'Save to'));
        [['project', 'Project', p.project, 'This project only'], ['user', 'User', p.user, 'All your projects']].forEach(([level, name, path, help]) => {
          if (!path) return;
          const opt = cwEl('div', 'cw-opt' + (dlg.level === level ? ' selected' : ''));
          opt.appendChild(cwEl('div', 'name', name));
          opt.appendChild(cwEl('div', 'desc', path + ' · ' + help));
          opt.onclick = () => { dlg.level = level; dlg.draw(); };
          body.appendChild(opt);
        });
        check('Switch to this style now', '', dlg.switchNow, v => { dlg.switchNow = v; });
      }
      const last = dlg.step === 3;
      next = cwBtn(last ? 'Save' : 'Next', 'primary', () => {
        if (dlg.step === 2 && !d.instructions.trim()) { dlg.note = 'Enter the instructions'; dlg.draw(); return; }
        if (!last) { dlg.step++; dlg.note = null; dlg.draw(); return; }
        dlg.save();
      });
      if (dlg.step === 0) next.disabled = !nameOk();
      body.appendChild(next);
      if (dlg.step > 0) body.appendChild(cwBtn('Back', '', () => dlg.escape()));
      if (first) setTimeout(() => first.focus(), 0);
    },
    save() {
      const d = dlg.draft, name = d.name.trim();
      const path = dlg.paths()[dlg.level];
      if (ideErrand('read', path).startsWith('ok:')) {
        dlg.note = 'A style file named ' + dlg.fileName() + ' already exists here.';
        dlg.draw();
        return;
      }
      const head = ['---', 'name: ' + name];
      if (d.description.trim()) head.push('description: ' + d.description.trim());
      head.push('keep-coding-instructions: ' + (d.keep ? 'true' : 'false'), '---', '');
      if (ideErrand('write', path, head.join('\n') + '\n' + d.instructions.trim() + '\n') !== 'ok') {
        dlg.note = "Couldn't save the style.";
        dlg.draw();
        return;
      }
      if (!styles().includes(name)) { o.available_output_styles = styles().concat([name]); }
      dlg.step = -1; dlg.note = null;
      if (dlg.switchNow) pick(name);
      dlg.draw();   // back on the chooser, the new style in it
    },
  };
  openCliDialog(dlg);
  requestOffers(t);
}

/* ===================== Sign out =====================
   The CLI's own `claude auth logout`. It signs the computer out, not this view alone, so
   it is asked about first. The two buttons are the MCP servers window's confirm row. */
function cliSignOut(t) { return cliEdit(t, 'auth-logout', {}); }
function openSignOutDialog() {
  const dlg = {
    busy: false, note: null,
    escape() { return dlg.busy; },   // the dismiss key waits while it is signing out
    draw() {
      const body = cwFrame('Sign out of Claude?', null, false);
      if (dlg.note) body.appendChild(cwEl('div', 'cw-msg', dlg.note));
      body.appendChild(cwEl('div', 'cw-text',
        'This signs Claude Code out on this computer, not just in this view. The Claude Terminal and any other '
        + 'editor or terminal that shares this sign-in are signed out with it.'));
      body.appendChild(cwEl('div', 'cw-text', 'You will need to sign in again to keep chatting.'));
      const row = cwEl('div', 'cw-confirm');
      const out = cwBtn(dlg.busy ? 'Signing out…' : 'Sign out', 'danger', () => {
        if (dlg.busy) return;
        dlg.busy = true; dlg.note = null; dlg.draw();
        const tab = dlg.tab;
        cliSignOut(tab).then(() => {
          addSystemTo(tab, 'Successfully logged out from Claude');
          openSignInDialog(false);
        }, (err) => {
          // Nothing ran at all when the build cannot do this; otherwise the CLI was run and gave up.
          const why = (err && err.message) || '';
          dlg.busy = false;
          dlg.note = why === 'Not supported by this build.' || why === 'Invalid request.' ? 'Not supported by this build.'
                                                                                         : 'Failed to logout completely. Some credentials may remain.';
          if (cliDlg === dlg) dlg.draw();
        });
      });
      const cancel = cwBtn('Cancel', '', closeCliDialog);
      out.disabled = cancel.disabled = dlg.busy;
      row.appendChild(out); row.appendChild(cancel);
      body.appendChild(row);
      if (!dlg.busy) setTimeout(() => cancel.focus(), 0);   // Enter gives the answer that changes nothing
    },
  };
  openCliDialog(dlg);
}

/* ===================== Sign in =====================
   "Switch account", and what follows signing out. The CLI does the signing in: asked to
   (claude_authenticate) it hands back the address to open, and it says when the browser
   has finished (claude_oauth_wait_for_completion). A code from the page that shows one
   can be pasted back instead (claude_oauth_callback). */
const SIGN_IN_WAIT_MS = 10 * 60 * 1000;   // the browser takes as long as its user does
const THIRD_PARTY_DOCS_URL = 'https://code.claude.com/docs/en/third-party-integrations';
/** The picture over the login screen: this plug-in's own sleepy moon (ICONS.MASCOT) in a
    night sky of its own, with the sun it eclipses coming up over the horizon. Drawn on a
    grid of whole units, like the moon, so it scales in blocks. */
function signInArt() {
  const star = (x, y) => '<path d="M' + (x + 1) + ' ' + y + 'h1v1h1v1h-1v1h-1v-1h-1v-1h1z"/>';
  const dot = (x, y) => '<rect x="' + x + '" y="' + y + '" width="1" height="1"/>';
  const cloud = (x, y, w) => '<path d="M' + (x + 5) + ' ' + y + 'h' + (w - 11) + 'v2h4v2h2v2h-' + w + 'v-2h2v-2h3z"/>';
  const moon = ICONS.MASCOT.replace(/^<svg[^>]*>/, '<svg x="88" y="3" width="25" height="25" viewBox="0 0 25 25">');
  return '<svg viewBox="0 0 132 46" shape-rendering="crispEdges" role="img" aria-label="A sleepy moon over the horizon at sunrise">'
    + '<g fill="currentColor" opacity=".38">' + cloud(22, 12, 26) + cloud(60, 27, 22) + '</g>'
    + '<g fill="currentColor">' + star(10, 22) + star(50, 9) + star(78, 5) + star(118, 33) + dot(34, 4) + dot(4, 8)
    + dot(66, 18) + dot(44, 30) + dot(100, 36) + dot(124, 12) + '</g>'
    + '<g fill="#f5a623"><path d="M30 34h5v1h2v1h1v2h1v3h-13v-3h1v-2h1v-1h2z"/>'
    + '<rect x="32" y="29" width="1" height="3"/><rect x="24" y="31" width="1" height="1"/><rect x="25" y="32" width="1" height="1"/>'
    + '<rect x="40" y="31" width="1" height="1"/><rect x="39" y="32" width="1" height="1"/>'
    + '<rect x="20" y="37" width="3" height="1"/><rect x="42" y="37" width="3" height="1"/></g>'
    + '<path d="M0 41.5h132" stroke="currentColor" stroke-width="1" stroke-dasharray="1 1" opacity=".7"/>'
    + moon + '</svg>';
}
/** Whether anyone is signed in, as the CLI says (`claude auth status`). Rejects when it
    cannot be told, which is not the same as nobody being signed in. */
function signedInNow(t) {
  return cliEdit(t, 'auth-status', {}).then((res) => {
    const said = JSON.parse(String(res.output || ''));
    if (typeof said.loggedIn !== 'boolean') throw new Error('unknown');
    return said.loggedIn;
  });
}
/** Puts the login screen up when nobody is signed in and takes it down when somebody is:
    a sign-in or a sign-out done anywhere on this computer is this view's too. */
function checkSignedIn() {
  const t = activeTab();
  if (!t) return;
  signedInNow(t).then((yes) => {
    const up = !!(cliDlg && cliDlg.screen);
    if (yes && up) { closeCliDialog(); requestOffers(t, true); }
    else if (!yes && !up && !(cliDlg && cliDlg.signIn)) openSignInDialog(false);
  }, () => {});
}
const SIGNED_IN_RECHECK_MS = 30000;
function signInWatchInit() {
  setTimeout(checkSignedIn, 1500);   // once the view is drawn
  // While the screen is up, a sign-in done elsewhere (a terminal) is looked for.
  const whileUp = () => { if (cliDlg && cliDlg.screen && cliDlg.view === 'choose') checkSignedIn(); };
  window.addEventListener('focus', whileUp);
  setInterval(whileUp, SIGNED_IN_RECHECK_MS);
}
/** @param {boolean} canCancel offered from the menu (true: a dialog), or because nobody is
    signed in (false: the login screen, which fills the view and stays until somebody is) */
function openSignInDialog(canCancel) {
  const dlg = {
    signIn: true, screen: !canCancel,
    view: 'choose', error: null, url: '', code: '', attempt: 0,
    escape() { if (dlg.view !== 'choose') { dlg.back(); return true; } return dlg.screen; },
    back() { dlg.attempt++; dlg.view = 'choose'; dlg.url = ''; dlg.code = ''; dlg.draw(); },
    login(withClaudeAi) {
      const mine = ++dlg.attempt, tab = dlg.tab;
      const current = () => mine === dlg.attempt && cliDlg === dlg;
      dlg.view = 'signing'; dlg.error = null; dlg.url = ''; dlg.code = ''; dlg.draw();
      cliAsk(tab, 'claude_authenticate', { loginWithClaudeAi: withClaudeAi }).then((res) => {
        if (!current()) return null;
        dlg.url = String(res.manualUrl || ''); dlg.view = 'browser'; dlg.draw();
        if (res.automaticUrl && window._openExternal) _openExternal(String(res.automaticUrl));
        return cliAsk(tab, 'claude_oauth_wait_for_completion', null, SIGN_IN_WAIT_MS).then(() => {
          // Back on the conversation, as it was. That goes for an attempt given up with Back
          // that the browser finished all the same: a chooser still asking how to log in has
          // nothing left to ask. One with a later attempt under way is left to that attempt.
          if (cliDlg === dlg && (mine === dlg.attempt || dlg.view === 'choose')) closeCliDialog();
          requestOffers(tab, true);  // what the folder offers can differ by account
        });
      }).catch((err) => {
        if (!current()) return;
        dlg.view = 'choose'; dlg.error = (err && err.message) || 'Unknown error'; dlg.draw();
      });
    },
    submitCode() {
      const code = dlg.code.trim();
      if (!code) return;
      // The page gives the code and its state as one text, joined by a #.
      const hash = code.indexOf('#');
      cliAsk(dlg.tab, 'claude_oauth_callback', { authorizationCode: hash >= 0 ? code.slice(0, hash) : code,
                                                 state: hash >= 0 ? code.slice(hash + 1) : '' }).catch(() => {});
    },
    draw() {
      const title = dlg.view === 'signing' ? 'Signing in…' : dlg.view === 'browser' ? 'Continue in browser' : 'How do you want to log in?';
      const body = cwFrame(title, null, false);
      // The screen has no title bar: what a dialog says there is said in its place in the text.
      if (dlg.screen && dlg.view !== 'choose') body.appendChild(cwEl('div', 'cw-sec', title));
      if (dlg.view === 'signing') {
        body.appendChild(cwBtn('Back', '', () => dlg.back()));
      } else if (dlg.view === 'browser') {
        body.appendChild(cwEl('div', 'cw-text', "If the browser didn't open, visit this URL:"));
        const row = cwEl('div', 'cw-row');
        const url = cwEl('input', 'cw-input'); url.type = 'text'; url.readOnly = true; url.value = dlg.url;
        url.onclick = () => url.select();
        row.appendChild(url);
        const copy = cwEl('span', 'cw-copy'); copy.innerHTML = ICONS.COPY; copy.title = 'Copy to clipboard';
        copy.onclick = () => { if (window._clipSet) _clipSet(dlg.url); };
        row.appendChild(copy);
        body.appendChild(row);
        body.appendChild(cwEl('div', 'cw-text', 'Or, paste your authorization code manually:'));
        const code = cwEl('input', 'cw-input'); code.type = 'text'; code.placeholder = '012345'; code.value = dlg.code;
        const go = cwBtn('Continue', 'primary', () => dlg.submitCode());
        go.disabled = !dlg.code.trim();
        code.oninput = () => { dlg.code = code.value; go.disabled = !dlg.code.trim(); };
        code.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); dlg.submitCode(); } };
        body.appendChild(code);
        body.appendChild(go);
        body.appendChild(cwBtn('Back', '', () => dlg.back()));
      } else {
        if (dlg.screen) { const art = cwEl('div', 'signin-art'); art.innerHTML = signInArt(); body.appendChild(art); }
        body.appendChild(cwEl('div', 'cw-text',
          'Claude Code can be used with your Claude subscription or billed based on API usage through your Console account.'));
        if (dlg.screen) body.appendChild(cwEl('div', 'cw-text', title));
        if (dlg.error) body.appendChild(cwEl('div', 'cw-msg', dlg.error));
        const method = (label, cls, note, run) => {
          const b = cwBtn(label, cls, run);
          body.appendChild(b);
          body.appendChild(cwEl('div', 'cw-under', note));
          return b;
        };
        method('Claude.ai Subscription', 'primary', 'Use your Claude Pro, Team, or Enterprise subscription', () => dlg.login(true))
          .title = 'Use your Claude Pro, Team, or Enterprise subscription';
        method('Anthropic Console', '', 'Pay for API usage through your Console account', () => dlg.login(false));
        method('Bedrock, Foundry, or Vertex', '', 'Instructions on how to use API keys or third-party providers.',
               () => { if (window._openExternal) _openExternal(THIRD_PARTY_DOCS_URL); });
      }
      if (canCancel) body.appendChild(cwBtn('Cancel', '', closeCliDialog));
    },
  };
  openCliDialog(dlg);
}

/* ===================== Report a problem =====================
   The CLI sends the report (submit_feedback): the words typed here, what it knows of the
   environment, and the conversation's transcript. Where its organization has reports kept
   on the computer instead (feedback_mode "bundle"), it writes one file and sends nothing. */
const DATA_USAGE_URL = 'https://code.claude.com/docs/en/data-usage';
const FEEDBACK_WAIT_MS = 120000;   // a transcript can be long
function feedbackMode(t) { const o = offersFor(t); return (o && o.feedback_mode && o.feedback_mode.kind) || ''; }
/** Why a report was not sent, in the extension's words for the CLI's reasons. */
function feedbackFailure(res) {
  if (res.unavailable_reason) return String(res.unavailable_reason);
  const why = res.failure_reason;
  if (!why) return null;
  if (why === 'zdr_org') return 'feedback collection is not available for organizations with custom data retention policies';
  if (why === 'auth_error') return 'not signed in';
  if (why === 'http_error') return res.status_code ? 'server returned ' + res.status_code : 'server error';
  if (why === 'timeout') return 'request timed out';
  if (why === 'network_error') return "couldn't reach the service";
  if (/^payload_too_large_/.test(why)) return 'session transcript is too large to upload';
  if (why === 'bundle_write_failed') {
    const home = ideErrand('home'), sep = home.includes('\\') ? '\\' : '/';
    return 'nothing was written under ' + (home ? home + sep + '.claude' + sep + 'feedback-bundles' : 'feedback-bundles');
  }
  return String(why);
}
function sendFeedback(t, description, local) {
  cliAsk(t, 'submit_feedback', { description: description, surface: 'ide', save_locally: local }, FEEDBACK_WAIT_MS)
    .then((res) => ({ error: feedbackFailure(res), id: res.feedback_id, path: res.bundle_path }),
          (err) => ({ error: (err && err.message) || 'Unknown error' }))
    .then((r) => {
      if (r.error) addSystemTo(t, (local ? "Couldn't save the report to this computer: " : "Couldn't send feedback: ") + r.error);
      else if (r.path) addSystemTo(t, 'Saved your report to ' + r.path + '. Nothing was sent. Send this file to your '
                                      + 'Anthropic account representative or attach it to your support request.');
      else addSystemTo(t, r.id ? 'Thank you for your report! Feedback ID: ' + r.id : 'Thank you for your report!');
    });
}
function openFeedbackDialog(initialText) {
  const dlg = {
    text: typeof initialText === 'string' ? initialText : '',
    draw() {
      const local = feedbackMode(dlg.tab) === 'bundle';
      const body = cwFrame('What went wrong?', null, false);
      const box = cwEl('textarea', 'cw-input'); box.rows = 4; box.placeholder = 'Tell us more (optional)'; box.value = dlg.text;
      const send = () => { const tab = dlg.tab, said = box.value.trim(); closeCliDialog(); sendFeedback(tab, said, local); };
      box.oninput = () => { dlg.text = box.value; };
      box.onkeydown = (e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && !e.isComposing) { e.preventDefault(); send(); } };
      body.appendChild(box);
      const hint = cwEl('div', 'cw-dim');
      if (local) {
        hint.textContent = 'Nothing is sent. Your feedback, environment info, and the current session transcript are saved on this '
          + 'computer as one file, with known API keys and tokens removed. Send that file to your Anthropic account representative '
          + 'or attach it to your support request.';
      } else {
        hint.appendChild(document.createTextNode('This report includes your feedback, environment info, and the current session '
          + 'transcript. We may use these to debug related issues and improve Claude Code. '));
        const more = cwEl('a', 'cw-a', 'Learn more');
        more.onclick = () => { if (window._openExternal) _openExternal(DATA_USAGE_URL); };
        hint.appendChild(more);
      }
      body.appendChild(hint);
      const row = cwEl('div', 'cw-confirm');
      row.appendChild(cwBtn('Skip', '', closeCliDialog));
      row.appendChild(cwBtn(local ? 'Save report' : 'Send feedback', 'primary', send));
      body.appendChild(row);
      setTimeout(() => box.focus(), 0);
    },
  };
  openCliDialog(dlg);
}

/* ===================== Claude Design =====================
   Authorizing design-system access is a sign-in of its own, the CLI's `design-login`
   (design_login.rs): whether it is there is asked once, and signing in is a start, a wait
   for the browser, and maybe a code pasted back from a page that shows one. */
function cliDesign(t, op, arg, timeoutMs) {
  if (!window._designLogin) return Promise.reject(new Error('Not supported by this build.'));
  const w = cliWaiting('d', timeoutMs);
  _designLogin(t.id, w.token, op, arg || '', rootPathOf(t));
  return w.done;
}
const DESIGN_WAIT_MS = 7 * 60 * 1000;   // the CLI gives a sign-in six minutes
function openDesignDialog() {
  const dlg = {
    state: null, error: null, notice: null, waiting: false, pages: null, code: '', sending: false,
    close() { if (dlg.waiting) cliDesign(dlg.tab, 'cancel').catch(() => {}); },
    /** Why signing in is not offered here, or null when it is. */
    unavailable() {
      const s = dlg.state;
      if (!s.available) return 'Claude Design sync is not available in this session.';
      if (!s.can_sign_in_here) return s.reason || 'The sign-in cannot complete from this session.';
      return null;
    },
    signIn() {
      if (dlg.waiting) return;
      const tab = dlg.tab, here = () => cliDlg === dlg;
      dlg.error = null; dlg.notice = null; dlg.pages = null; dlg.code = ''; dlg.waiting = true; dlg.draw();
      cliDesign(tab, 'start').then((start) => {
        if (!start.ok) throw new Error(start.message || 'The sign-in did not complete.');
        if (!here()) { cliDesign(tab, 'cancel').catch(() => {}); return null; }   // closed while it was starting
        dlg.pages = start;
        const page = start.manualFirst ? start.manualUrl : start.url;
        if (page && window._openExternal) _openExternal(page);
        dlg.draw();
        return cliDesign(tab, 'wait', '', DESIGN_WAIT_MS);
      }).then((outcome) => {
        if (!outcome || !here()) return;
        if (outcome.ok) { dlg.error = null; dlg.notice = 'Design-system access authorized.'; if (dlg.state) dlg.state.signed_in = true; }
        else dlg.error = outcome.message || 'The sign-in did not complete.';
      }, (err) => { dlg.error = (err && err.message) || 'The sign-in did not complete.'; })
        .then(() => { dlg.waiting = false; dlg.pages = null; if (here()) dlg.draw(); });
    },
    submitCode() {
      const code = dlg.code.trim();
      if (!code || dlg.sending) return;
      dlg.sending = true; dlg.draw();
      cliDesign(dlg.tab, 'code', code).then((res) => {
        if (res.ok) { dlg.code = ''; dlg.error = null; } else dlg.error = res.message || 'Unknown error';
      }, (err) => { dlg.error = (err && err.message) || 'Unknown error'; })
        .then(() => { dlg.sending = false; if (cliDlg === dlg) dlg.draw(); });
    },
    draw() {
      const open = (url) => { if (url && window._openExternal) _openExternal(url); };
      const link = (text, url) => { const a = cwEl('a', 'cw-a', text); a.onclick = () => open(url); return a; };
      const body = cwFrame('Claude Design', null, true);
      body.appendChild(cwEl('div', 'cw-text',
        "Authorize design-system access with your claude.ai account. This is separate from this session's sign-in and "
        + 'changes nothing else. The authorization is stored on this machine and used by every session on it.'));
      if (dlg.error) body.appendChild(cwEl('div', 'cw-msg', dlg.error));
      if (dlg.notice) body.appendChild(cwEl('div', 'cw-ok', dlg.notice));
      const s = dlg.state;
      if (!s) { if (!dlg.error) body.appendChild(cwEl('div', 'cw-dim', 'Loading…')); return; }
      const status = cwEl('div', 'cw-text'); status.appendChild(document.createTextNode('Status: '));
      status.appendChild(cwEl('span', s.signed_in ? 'cw-status-ok' : 'cw-status-off', s.signed_in ? 'Authorized' : 'Not authorized'));
      body.appendChild(status);
      const why = dlg.unavailable();
      if (why) { body.appendChild(cwEl('div', 'cw-dim', why)); return; }
      const go = cwBtn(dlg.waiting ? 'Waiting for the sign-in in your browser…' : s.signed_in ? 'Sign in again' : 'Sign in with your claude.ai account',
                       'primary', () => dlg.signIn());
      go.disabled = dlg.waiting;
      body.appendChild(go);
      if (dlg.waiting) {
        const p = dlg.pages;
        const manualFirst = !!(p && p.manualFirst && p.manualUrl);
        const page = p ? (manualFirst ? p.manualUrl : p.url) : '';
        const help = cwEl('div', 'cw-dim');
        help.appendChild(document.createTextNode(manualFirst
          ? 'Complete the sign-in in the browser window that opened; it ends on a page that shows a code to paste below. You have five minutes; closing this dialog cancels it.'
          : 'Complete the sign-in in the browser window that opened. You have five minutes; closing this dialog cancels it.'));
        if (page) {
          help.appendChild(document.createTextNode(' If no window opened, '));
          help.appendChild(link('open the sign-in page', page));
          help.appendChild(document.createTextNode('.'));
        }
        body.appendChild(help);
        if (p && p.manualUrl) {
          const ask = cwEl('div', 'cw-dim');
          if (manualFirst) ask.textContent = 'Paste the code here:';
          else {
            ask.appendChild(document.createTextNode('Or '));
            ask.appendChild(link('sign in on a page that shows a code', p.manualUrl));
            ask.appendChild(document.createTextNode(', and paste the code here:'));
          }
          body.appendChild(ask);
          const row = cwEl('div', 'cw-row');
          const code = cwEl('input', 'cw-input'); code.type = 'text'; code.placeholder = 'Authorization code';
          code.value = dlg.code; code.disabled = dlg.sending;
          const submit = cwBtn('Submit', '', () => dlg.submitCode());
          submit.disabled = dlg.sending || !dlg.code.trim();
          code.oninput = () => { dlg.code = code.value; submit.disabled = dlg.sending || !dlg.code.trim(); };
          code.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); dlg.submitCode(); } };
          row.appendChild(code); row.appendChild(submit);
          body.appendChild(row);
        }
      } else if (s.signed_in) {
        body.appendChild(cwEl('div', 'cw-dim', 'Signing in again replaces the stored authorization.'));
      }
    },
  };
  openCliDialog(dlg);
  cliEdit(dlg.tab, 'design-login-status', {}).then((res) => {
    // The CLI prints the state as the last line of what it says.
    let s = null; try { s = JSON.parse(String(res.output || '').trim().split('\n').pop()); } catch (e) {}
    if (!s || typeof s.available !== 'boolean' || typeof s.signed_in !== 'boolean' || typeof s.can_sign_in_here !== 'boolean')
      throw new Error('The Claude Design sign-in state could not be read.');
    dlg.state = s;
  }).catch((err) => { dlg.error = (err && err.message) || 'The Claude Design sign-in state could not be read.'; })
    .then(() => { if (cliDlg === dlg) dlg.draw(); });
}

/* ===================== Switch models when a message is flagged =====================
   A switch in the menu. What it shows comes with the folder's offers (cmdmenu.js): the
   setting in effect there, which unset counts as on. Switching it saves the setting in
   the user's own settings file and tells the conversations that are running (_userSetting). */
let flaggedSwitchSet = null;      // what was switched here, until the offers say so themselves
let flaggedSwitchSavedAt = 0;
function cliUserSetting(t, key, value) {
  if (!window._userSetting) return Promise.reject(new Error('Not supported by this build.'));
  const w = cliWaiting('s');
  _userSetting(t.id, w.token, key, JSON.stringify(value));
  return w.done;
}
function flaggedSwitchOn(t) {
  if (flaggedSwitchSet !== null) return flaggedSwitchSet;
  const o = offersFor(t);
  return !(o && o.switch_models_on_flag === false);
}
/** The offers asked for at `askedAt` are in: they are the truth once they are newer than the switching. */
function flaggedSwitchHeard(askedAt) {
  if (flaggedSwitchSet !== null && askedAt && askedAt >= flaggedSwitchSavedAt && flaggedSwitchSavedAt) flaggedSwitchSet = null;
}
function setFlaggedSwitch(t, want) {
  const was = flaggedSwitchOn(t);
  flaggedSwitchSet = want; flaggedSwitchSavedAt = 0;
  cliUserSetting(t, 'switchModelsOnFlag', want).then(() => {
    const o = offersFor(t); if (o) o.switch_models_on_flag = want;
    flaggedSwitchSavedAt = Date.now();
    requestOffers(t, true);
  }, (err) => {
    flaggedSwitchSet = was;
    const sw = document.querySelector('#actions-rows .item[data-id="flagswitch"] .sw');
    if (sw) sw.classList.toggle('on', was);
    addSystemTo(t, "Couldn't save the setting: " + ((err && err.message) || 'Unknown error'));
  });
}

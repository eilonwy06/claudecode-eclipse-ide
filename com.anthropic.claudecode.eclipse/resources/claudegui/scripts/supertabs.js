/* supertabs.js — Root ("supertab") row: the folder each conversation runs in.

   A supertab is a WORKING ROOT: an absolute directory that claude is spawned in
   (Rust does cmd.current_dir(workspace_root); the root has always been a parameter,
   so a per-tab root costs nothing at the native boundary). Every Tab carries a
   rootId; #tabs shows only the tabs of the ACTIVE root, so the two rows read as
   one hierarchy — roots on top, that root's conversations below.

   Roots are STORED, not derived: the user creates them by picking a folder, they
   may sit outside the workspace, they reorder independently and each remembers its
   own last-active tab. The workspace root is where the view opens, and is closable
   like any other — closing the LAST one closes the view itself, after confirming. */

/**
 * One working root.
 * @typedef {Object} Root
 * @property {string} id           "root1", "root2", …
 * @property {string} path         absolute OS path — claude's cwd for its tabs
 * @property {string} name         label shown on the chip (basename, disambiguated)
 * @property {boolean} fixed       true for the workspace root — labels it in the
 *                                 tooltip; it is closable like any other root
 * @property {string} activeTabId  the tab to return to when this root is re-selected
 */
/** @type {Root[]} */
let roots = [], activeRootId = null, rootSeq = 0;
let supertabsVisible = true;

/** @returns {Root|null} */
function activeRoot() { return roots.find(r => r.id === activeRootId) || null; }
/** @param {string} id @returns {Root|null} */
function rootById(id) { return roots.find(r => r.id === id) || null; }
/** Absolute path for a tab — falls back to the active root, then the workspace root. */
function rootPathOf(t) {
  const r = (t && rootById(t.rootId)) || activeRoot() || roots[0];
  return r ? r.path : '';
}
/** Tabs belonging to a root, in strip order. @returns {Tab[]} */
function tabsOfRoot(id) { return tabs.filter(t => t.rootId === id); }

/* Path helpers. Windows and POSIX separators both appear (Eclipse hands us OS
   paths; the CLI's own config uses forward slashes), so both are treated as one. */
/** Path split into its segments, ORIGINAL case kept — these are shown to the user. */
function pathSegs(p) {
  return String(p || '').replace(/[\\/]+$/, '').split(/[\\/]/).filter(Boolean);
}
function baseName(p) {
  const segs = pathSegs(p);
  return segs.length ? segs[segs.length - 1] : (p || '?');
}
/** Same normal form on both sides of every comparison (case-insensitive on Windows). */
function normPath(p) {
  const s = String(p || '').replace(/[\\/]+/g, '/').replace(/\/+$/, '');
  return /^[a-zA-Z]:/.test(s) ? s.toLowerCase() : s;
}
function rootByPath(p) { const n = normPath(p); return roots.find(r => normPath(r.path) === n) || null; }

/* Sibling folders collide on basename (web/src and api/src both read "src"), so a
   colliding chip grows leftwards one path segment at a time until it is unique —
   the shortest suffix that still tells the two apart. */
function labelFor(path) {
  const segs = pathSegs(path);
  if (!segs.length) return path || '?';
  // Split the ORIGINAL path, compare case-insensitively: normPath's lower-casing is
  // for matching only — building the label out of it would show "myproject" for a
  // folder actually named MyProject. Two folders differing only in case therefore
  // read as colliding, which merely lengthens a label nobody has.
  const others = roots.filter(r => normPath(r.path) !== normPath(path)).map(r => pathSegs(r.path));
  let take = 1;
  while (take < segs.length) {
    const mine = segs.slice(-take).join('/').toLowerCase();
    if (!others.some(o => o.slice(-take).join('/').toLowerCase() === mine)) break;
    take++;
  }
  return segs.slice(-take).join('/');
}
/* Every chip is relabelled after the set changes — a new root can make an existing
   label ambiguous, and removing one can make a disambiguated label needlessly long. */
function relabelRoots() { roots.forEach(r => { r.name = labelFor(r.path); }); }

/* ===================== Create / switch / close ===================== */
/**
 * Adds a root (or returns the existing one for that path) and selects it.
 * @param {string} path absolute folder path
 * @param {{fixed?: boolean, select?: boolean, withTab?: boolean}} [opts] withTab only
 *   affects an EXISTING root — a newly created one always opens with one conversation.
 * @returns {Root}
 */
function addRoot(path, opts) {
  opts = opts || {};
  const existing = rootByPath(path);
  if (existing) {
    if (opts.select !== false) switchRoot(existing.id);
    if (opts.withTab) createTab({ rootId: existing.id });
    return existing;
  }
  const r = { id: 'root' + (++rootSeq), path: path, name: baseName(path),
              fixed: !!opts.fixed, activeTabId: null };
  roots.push(r);
  relabelRoots();
  if (opts.select !== false) {
    activeRootId = r.id;
    // A root with no conversation renders an empty strip, so it always opens with one.
    createTab({ rootId: r.id });
  } else {
    renderSupertabs();
  }
  return r;
}
/** Selects a root and restores the tab the user last had open in it. */
function switchRoot(id) {
  const r = rootById(id); if (!r || id === activeRootId) return;
  const prev = activeRoot();
  if (prev) prev.activeTabId = activeId;
  activeRootId = id;
  const own = tabsOfRoot(id);
  if (!own.length) { createTab({ rootId: id }); return; }   // createTab renders both rows
  const want = own.find(t => t.id === r.activeTabId) || own[0];
  switchTab(want.id);          // renders #tabs
  renderSupertabs();
}
/**
 * Closes a root and every conversation under it.
 *
 * Closing the LAST root leaves nothing for the view to show — no folder to run in,
 * no strip to render — so it asks first, and on "yes" the view itself closes. The
 * question goes to Java, which puts it in a real SWT dialog: this panel is often
 * docked narrow enough that an in-page modal has nowhere to lay its buttons out.
 *
 * Nothing is mutated on that branch. The dialog is asynchronous and a cancel has to
 * leave the page exactly as it was, so the teardown below is never started for it.
 */
function closeRoot(id) {
  const r = rootById(id); if (!r) return;
  if (roots.length === 1) {
    try { if (window._confirmCloseView) window._confirmCloseView(); } catch (e) {}
    return;
  }
  const idx = roots.findIndex(x => x.id === id);
  // Snapshot first: closeTab mutates `tabs` and would re-enter the per-root refill
  // below if it were allowed to see an about-to-be-removed root as active.
  const doomed = tabsOfRoot(id).map(t => t.id);
  roots.splice(idx, 1);
  relabelRoots();
  const wasActive = (activeRootId === id);
  if (wasActive) activeRootId = (roots[Math.min(idx, roots.length - 1)] || roots[0]).id;
  doomed.forEach(tid => closeTab(tid, { keepRoot: true }));
  const own = tabsOfRoot(activeRootId);
  if (!own.length) { createTab({ rootId: activeRootId }); return; }
  const nr = activeRoot();
  switchTab((own.find(t => t.id === (nr && nr.activeTabId)) || own[0]).id);
  renderSupertabs();
}

/* ===================== Rendering ===================== */
let dragRootId = null;
function clearRootDropMarks() {
  document.querySelectorAll('#supertabs .stab.drop-before, #supertabs .stab.drop-after')
    .forEach(el => el.classList.remove('drop-before', 'drop-after'));
}
/* Reorder within the root row. Mirrors moveTab, kept separate so a conversation
   and a root can never land in each other's list. */
function moveRoot(fromId, toId, after) {
  if (fromId === toId) return;
  const fromIdx = roots.findIndex(r => r.id === fromId);
  if (fromIdx < 0) return;
  const [moved] = roots.splice(fromIdx, 1);
  let toIdx = roots.findIndex(r => r.id === toId);
  if (toIdx < 0) { roots.splice(fromIdx, 0, moved); return; }
  if (after) toIdx += 1;
  roots.splice(toIdx, 0, moved);
  renderSupertabs();
}
function renderSupertabs() {
  const row = document.getElementById('supertab-row');
  if (row) row.style.display = supertabsVisible ? '' : 'none';
  // Exactly one of the two is up at any time, so both are driven from here — giving
  // the strip its own toggle path is how they end up both visible or both hidden.
  const cwd = document.getElementById('cwd-row');
  if (cwd) {
    cwd.style.display = supertabsVisible ? 'none' : 'flex';
    const ar = activeRoot();
    cwd.querySelector('.ct').textContent = ar ? ar.name : '';
    cwd.title = ar ? ar.path : '';
  }
  syncSupertabToggle();
  const c = document.getElementById('supertabs'); if (!c) return;
  c.innerHTML = '';
  roots.forEach(r => {
    const el = document.createElement('div');
    el.className = 'stab' + (r.id === activeRootId ? ' active' : '') + (r.fixed ? ' fixed' : '');
    el.draggable = true; el.dataset.id = r.id;
    el.innerHTML = '<span class="si">' + ICONS.FOLDER + '</span><span class="stt"></span>' +
      '<span class="stab-close">' + ICONS.X + '</span>';
    el.querySelector('.stt').textContent = r.name;
    // The chip shows the shortest unique suffix; the tooltip always shows the whole
    // path, which is the only place the real cwd is spelled out in full.
    el.title = r.path + (r.fixed ? '  (workspace root)' : '');
    el.onclick = (e) => { if (e.target.closest('.stab-close')) closeRoot(r.id); else switchRoot(r.id); };
    /* Roots reorder among ROOTS only. A conversation dragged up here is refused by
       simply never calling preventDefault on its dragover — the browser then shows
       the no-drop cursor and fires no drop, so the rule needs no error path. */
    el.addEventListener('dragstart', (e) => {
      dragRootId = r.id; el.classList.add('dragging');
      e.dataTransfer.effectAllowed = 'move';
      try { e.dataTransfer.setData('text/plain', r.id); } catch (_) {}
    });
    el.addEventListener('dragend', () => { dragRootId = null; el.classList.remove('dragging'); clearRootDropMarks(); });
    el.addEventListener('dragover', (e) => {
      if (dragRootId === null || dragRootId === r.id) return;   // incl. a dragged CONVERSATION
      e.preventDefault(); e.dataTransfer.dropEffect = 'move';
      const rc = el.getBoundingClientRect();
      const after = (e.clientX - rc.left) > rc.width / 2;
      clearRootDropMarks();
      el.classList.add(after ? 'drop-after' : 'drop-before');
    });
    el.addEventListener('dragleave', () => el.classList.remove('drop-before', 'drop-after'));
    el.addEventListener('drop', (e) => {
      e.preventDefault();
      if (dragRootId === null) return;
      const rc = el.getBoundingClientRect();
      const after = (e.clientX - rc.left) > rc.width / 2;
      clearRootDropMarks();
      moveRoot(dragRootId, r.id, after);
    });
    c.appendChild(el);
  });
  const a = c.querySelector('.stab.active');
  if (a) {
    const al = a.offsetLeft, ar = al + a.offsetWidth;
    if (ar > c.scrollLeft + c.clientWidth) c.scrollLeft = ar - c.clientWidth;
    else if (al < c.scrollLeft) c.scrollLeft = al;
  }
}

/* ===================== Row visibility ===================== */
/* The toggle lives on the row it hides, so it has a second home: expanded it sits
   to the right of "New Claude root directory", collapsed it is the lone button in
   the conversation toolbar. One control, two slots — without that, hiding the row
   would hide the only way to bring it back. */
function syncSupertabToggle() {
  const inRow = document.getElementById('supertab-toggle');
  const inBar = document.getElementById('supertab-toggle-collapsed');
  if (inRow) {
    inRow.innerHTML = ICONS.CHEVDOWN;                 // visible → points down
    inRow.title = 'Hide root directories';
  }
  if (inBar) {
    inBar.innerHTML = ICONS.CHEVUP;                   // hidden → points up
    inBar.title = 'Show root directories';
    inBar.style.display = supertabsVisible ? 'none' : '';
  }
}
function toggleSupertabs() {
  supertabsVisible = !supertabsVisible;
  renderSupertabs();
}

/* ===================== New root (folder picker) ===================== */
/* Java opens the SWT DirectoryDialog off this call and answers asynchronously via
   onDirectoryPicked — a modal opened synchronously inside a BrowserFunction, while
   WebView2 is still inside the JS call that made it, is the classic Windows deadlock. */
function newRootDirectory() {
  closeMenus();
  if (!window._pickDirectory) return;
  try { window._pickDirectory(); } catch (e) {}
}
window.onDirectoryPicked = function(path) {
  if (!path) return;                        // dialog cancelled
  openRootDirectory(path);
};

/**
 * Opens a folder as a working root: selects it if it already has one, otherwise
 * creates the root and a conversation under it. Gated on trust for a folder we
 * have not run in before. Also the entry point Java calls for "Open Claude Code Here".
 */
function openRootDirectory(path) {
  if (!path) return;
  // Every "already have this root" case goes through the SAME call, so the gesture
  // means one thing regardless of whether the raw path or only its canonical form
  // matched an existing root: select it and open a conversation in it.
  if (rootByPath(path)) { addRoot(path, { withTab: true }); return; }
  let info = {};
  try { info = JSON.parse((window._folderInfo && window._folderInfo(path)) || '{}') || {}; } catch (e) {}
  const target = info.path || path;
  if (rootByPath(target) || info.trusted) { addRoot(target, { withTab: true }); return; }
  openTrustDialog(target, info);
}

/* ===================== Trust window ===================== */
/* The CLI only asks "do you trust the files in this folder?" interactively — a
   stream-json run in an unknown folder starts with no prompt at all. So the gate is
   ours: this window is the plugin asking, and the answer is stored on the plugin
   side. A folder already trusted through the terminal reads as trusted here. */
function openTrustDialog(path, info) {
  info = info || {};
  const win = document.getElementById('trust-win');
  const name = escapeHtml(baseName(path));
  let h = '<div class="aw-head"><span class="t">Trust this folder?</span>' +
          '<span class="x" onclick="closeTrust()">' + ICONS.X + '</span></div>';
  h += '<div class="aw-sec">Folder</div>';
  h += '<div class="aw-row"><span class="k">Name</span><span class="v">' + name + '</span></div>';
  h += '<div class="aw-row"><span class="k">Path</span><span class="v">' + escapeHtml(path) + '</span></div>';
  if (info.hasClaudeMd) h += '<div class="aw-row"><span class="k">CLAUDE.md</span><span class="v">Found</span></div>';
  if (info.inWorkspace === false)
    h += '<div class="aw-note">This folder is outside the Eclipse workspace.</div>';
  h += '<div class="aw-note">Claude Code will run in this folder and can read and change the files ' +
       'in it. Only continue if you trust its contents.</div>';
  h += '<div class="aw-btns">' +
       '<span class="aw-btn" onclick="closeTrust()">Cancel</span>' +
       '<span class="aw-btn primary" onclick="confirmTrust()">Yes, I trust this folder</span>' +
       '</div>';
  win.innerHTML = h;
  win.dataset.path = path;
  document.getElementById('trust-overlay').classList.add('open');
  // Same two cancel routes as every other overlay: the in-page listener for the
  // default scheme, and Eclipse's own binding for Emacs, where Esc is a prefix.
  document.addEventListener('keydown', trustKey, true);
  registerOverlayCancel(closeTrust, false);
}
function trustKey(e) {
  if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); closeTrust(); }
}
function closeTrust() {
  document.getElementById('trust-overlay').classList.remove('open');
  document.removeEventListener('keydown', trustKey, true);
  unregisterOverlayCancel();
}
function confirmTrust() {
  const win = document.getElementById('trust-win');
  const path = win ? (win.dataset.path || '') : '';
  closeTrust();
  if (!path) return;
  try { if (window._trustFolder) window._trustFolder(path); } catch (e) {}
  addRoot(path, { withTab: true });
}

/* ===================== Boot ===================== */
/* The workspace root is roots[0] and always exists, so there is a root to hang the
   first conversation on before init.js calls createTab(). */
function initRoots() {
  let path = '';
  try { path = (window._defaultRoot && window._defaultRoot()) || ''; } catch (e) {}
  const r = { id: 'root' + (++rootSeq), path: path, name: baseName(path) || 'Workspace',
              fixed: true, activeTabId: null };
  roots.push(r);
  activeRootId = r.id;
  renderSupertabs();
}

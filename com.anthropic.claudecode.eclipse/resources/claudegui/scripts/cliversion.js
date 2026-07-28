/* cliversion.js — reconciles the INSTALLED Claude Code CLI with the latest release.

   The model chooser is built from the ACCOUNT's available models (/v1/models),
   which says nothing about what the installed binary understands: a CLI older
   than a model release rejects it, and because aliases ("opus"/"sonnet") resolve
   inside the CLI, an old binary silently resolves them to an OLDER model rather
   than erroring. Rather than trying to enumerate what the binary supports (the
   ids are compiled into a ~265MB executable and scraping them yields false
   positives), we surface the version: a dismissible banner when an update is
   available, and a hint appended to any model-rejection error. */

/** {installed, latest, updateAvailable} — pushed by Java (onCliVersion). */
let cliVersion = null;

window.onCliVersion = function (json) {
  let info;
  try { info = JSON.parse(json); } catch (e) { return; }
  if (!info) return;
  cliVersion = info;
  renderUpdateBanner();
};

/* Dismissal is per panel-load only (no pref): the banner shouldn't nag within a
   session, but a genuinely outdated CLI is worth re-surfacing next time. */
let updateBannerDismissed = false;

/* null once an update has been started here: 'running' | 'done' | 'failed'.
   From that point the banner is a RESULT notice and belongs to the user — it
   stays until they close it. Without this the version re-check that follows a
   successful update reports "up to date" and hides the banner immediately,
   taking the "restart Eclipse" instruction with it. */
let updateRunState = null;

function renderUpdateBanner() {
  const bar = document.getElementById('update-banner');
  if (!bar) return;
  if (updateBannerDismissed) { bar.classList.remove('show'); return; }
  // Sticky: keep whatever text/buttons the run left in place, and never re-hide.
  if (updateRunState) { bar.classList.add('show'); return; }
  const show = !!(cliVersion && cliVersion.updateAvailable);
  bar.classList.toggle('show', show);
  if (!show) return;
  const txt = bar.querySelector('.ub-text');
  if (txt) {
    txt.textContent = 'Claude Code ' + cliVersion.latest + ' is available (you have '
                    + cliVersion.installed + '). Update to use the newest models.';
  }
}

function dismissUpdateBanner() {
  updateBannerDismissed = true;
  renderUpdateBanner();
}

/** Runs `claude update` (the CLI's own updater — install-method agnostic). */
function runCliUpdate() {
  const bar = document.getElementById('update-banner');
  if (!bar) return;
  updateRunState = 'running';   // from here the banner is ours until dismissed
  const btn = bar.querySelector('.ub-btn');
  if (btn) { btn.classList.add('busy'); btn.textContent = 'Updating…'; }
  const txt = bar.querySelector('.ub-text');
  if (txt) txt.textContent = 'Running claude update — this can take a minute.';
  try { if (window._updateCli) window._updateCli(); } catch (e) {}
}

window.onCliUpdateDone = function (json) {
  let res;
  try { res = JSON.parse(json); } catch (e) { res = null; }
  const bar = document.getElementById('update-banner');
  if (!bar) return;
  const btn = bar.querySelector('.ub-btn');
  const txt = bar.querySelector('.ub-text');
  if (res && res.ok) {
    // Restart matters: long-lived per-tab processes keep running the OLD binary.
    // Stays on screen (updateRunState) until the user closes it, so this doesn't
    // flash past when the follow-up version check says "up to date".
    updateRunState = 'done';
    if (btn) { btn.classList.remove('busy'); btn.style.display = 'none'; }
    if (txt) txt.textContent = 'Claude Code updated. Restart Eclipse (or open a new tab) to use it.';
    return;
  }
  updateRunState = 'failed';
  if (btn) { btn.classList.remove('busy'); btn.textContent = 'Retry'; }
  if (txt) {
    const detail = (res && res.output) ? (' — ' + String(res.output).split('\n')[0]) : '';
    txt.textContent = 'Update failed' + detail + '. You can also run "claude update" in a terminal.';
  }
};

/** Model ids the CLI rejects produce an error naming the model; add the version hint. */
function augmentError(msg) {
  const m = String(msg || '');
  if (!cliVersion || !cliVersion.installed) return m;
  // Only annotate errors that actually look like a model rejection.
  if (!/model/i.test(m)) return m;
  if (!/(unknown|not (a )?(valid|supported|recognized|found))|invalid|unsupported/i.test(m)) return m;
  let hint = ' (Your installed Claude Code is ' + cliVersion.installed;
  hint += (cliVersion.updateAvailable && cliVersion.latest)
        ? '; ' + cliVersion.latest + ' is available — updating may add this model.)'
        : '. This model may need a newer Claude Code.)';
  return m + hint;
}

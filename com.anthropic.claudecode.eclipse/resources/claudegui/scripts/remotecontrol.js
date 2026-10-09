/* remotecontrol.js — /remote-control, and the state it drives.

   Remote Control makes a conversation the SAME conversation everywhere: the CLI
   opens an outbound bridge, and anything typed here, on claude.ai, or on the
   phone lands in all of them.

   It is not teleport. Teleport takes a one-way copy of a web conversation and
   then diverges from it; this keeps one conversation with several front doors.

   The toggle is a control request the CLI answers asynchronously, so nothing is
   rendered optimistically — every line below is written when the CLI confirms,
   which is also the only moment the session's web address exists. */

/* The transcript line, verbatim from the CLI, with the address as a real link. */
const RC_ACTIVE_PREFIX = 'Remote Control is active · Continue here, on your phone, or at ';
const RC_LINK_TEXT = 'claude.ai/code';
const RC_DISABLED = 'Remote Control disabled.';

/* /remote-control — toggles the bridge for the tab it was typed in.
   Each tab is its own conversation, so each has its own bridge. */
function toggleRemoteControl() {
  const t = activeTab();
  if (!t) return true;
  if (!window._remoteControl) {
    addSystemTo(t, 'Remote Control is not available in this build.');
    return true;
  }
  // A switch-off owns the tab until the CLI answers it. Pressing again inside
  // that window used to send a SECOND disable, and everything downstream of that
  // was wrong: the CLI's answer to it is a bare "not enabled" — the very same
  // shape as a refused switch-on — so it read as a fresh failure, and its bridge
  // teardown could land after the NEXT enable had already settled, switching that
  // one back off. The page then said "disabled" over a bridge that was still up,
  // and never agreed with the CLI again. One request at a time is the whole fix.
  if (t.rcDisconnecting) return true;
  // Connecting counts as on for the purposes of the toggle. Reading the
  // direction from the url alone meant a second press mid-connect asked to turn
  // it ON again and re-armed the wait, which is the opposite of what pressing it
  // again means — and left the composer shut until the ceiling expired.
  const turningOn = !t.remoteControlUrl && !t.rcConnecting;
  if (turningOn) beginConnecting(t);
  // Switching off, which includes cancelling a connect that has not landed yet.
  else beginDisconnecting(t);
  // Launch settings ride along because this may have to START the tab's process:
  // the CLI answers a control request before any turn, so Remote Control needs a
  // process but never a conversation. Nothing is reported back synchronously —
  // success arrives as the CLI's reply, failure as an error on the same channel.
  rcSend(t, turningOn);
  return true;
}

/* Sends one toggle and records that an answer is owed for it.
   Every url-shaped event on this channel is an answer to a request we made —
   the CLI has no unsolicited form of it — so the tab consumes exactly one, and
   anything after that is a straggler from a toggle already accounted for. That
   is what used to print "Remote Control could not start." unprompted.

   The launch settings are the TAB's, not the composer's: the composer mirrors the
   tab in front, and this is also called for one that is not (Remote Control on
   startup, the claudeCodeEclipse tool). Sent from the composer, a background tab's
   process started on the front tab's model, effort and thinking, and its next
   message — carrying its own — replaced that process and the bridge with it.

   The tab's folder goes with them for the same reason: without it the process of a
   tab nothing has been sent from yet starts in the workspace root, and the tab's
   first message, which does name its folder, replaces it the same way. */
function rcSend(t, enabled) {
  t.rcAckPending = true;
  _remoteControl(t.id, enabled, t.sessionId || '', t.permMode || permMode,
                 EFFORTS[t.effortIdx] || effort, t.model !== undefined ? t.model : curModel,
                 (t.thinking !== undefined ? t.thinking : thinkingOn) ? '1' : '0', rootPathOf(t),
                 t.ultracode !== undefined ? t.ultracode : ultracodeOn);
}

/* How long to wait for a bridge before giving up.
   Generous because the FIRST connection of a session is much slower than any
   later toggle, and a ceiling that fits the slow case still beats a composer
   that stays shut forever if nothing ever answers. */
const RC_CONNECT_TIMEOUT_MS = 90000;

/* And how long to wait for the CLI to confirm a switch-off. Far shorter, because
   tearing a bridge down is local work with nothing to negotiate — and because the
   composer is shut for the duration, so this ceiling exists to reopen it rather
   than to guess at how long the CLI might take. */
const RC_DISCONNECT_TIMEOUT_MS = 10000;

/* A teardown arriving this soon after the bridge reported itself connected is
   read as a straggler from the switch-off before it, not as this bridge going
   away — nothing that has just connected disconnects a second later. Narrow on
   purpose: it costs a genuinely instant drop being noticed late, and buys the
   tab never again being told it is off while the bridge is still carrying. */
const RC_TEARDOWN_GRACE_MS = 2000;

/* Shuts the composer and starts the "Establishing connection…" indicator. */
function beginConnecting(t) {
  t.rcConnecting = true;
  // The other devices have no thinking toggle of their own, so a conversation they can
  // reach keeps thinking on. After the flag above: the lock reads it.
  if (typeof forceThinkingOn === "function") forceThinkingOn(t);
  t.rcDisconnecting = false;
  t.rcPendingUrl = null;
  t.rcBridgeConnected = false;
  t.rcDeadline = Date.now() + RC_CONNECT_TIMEOUT_MS;
  syncComposer();
  // For THIS tab, not the tab in front: with Remote Control on startup this runs
  // for a tab being created and for every restored conversation, none of which is
  // the render target yet.
  showWorkingFor(t);
  if (t.rcTimer) clearTimeout(t.rcTimer);
  t.rcTimer = setTimeout(() => rcExpire(t), RC_CONNECT_TIMEOUT_MS);
}

/* Shuts the composer for a switch-off and waits for the CLI to confirm it.
   No indicator: nothing is being waited on remotely, and the composer says what
   is happening. */
function beginDisconnecting(t) {
  const wasConnecting = !!t.rcConnecting;   // then the indicator up is the pinned "Establishing connection…"
  if (t.rcTimer) { clearTimeout(t.rcTimer); t.rcTimer = null; }
  t.rcConnecting = false;
  t.rcPendingUrl = null;
  t.rcBridgeConnected = false;
  t.rcDisconnecting = true;
  t.rcDeadline = Date.now() + RC_DISCONNECT_TIMEOUT_MS;
  syncComposer();
  // A turn already running keeps its own gerund: it is not this switch-off's to remove.
  if (wasConnecting || !t.streaming) stopWorkingFor(t);
  if (wasConnecting) resumeWorkingFor(t);
  t.rcTimer = setTimeout(() => rcExpire(t), RC_DISCONNECT_TIMEOUT_MS);
}

/* The switch-off has landed — or its ceiling expired — so the tab is off, once.
   Idempotent by the flag: the CLI answers a disable with a reply AND, a moment
   later, a bridge teardown, and both funnel here. The first one settles it and
   the second finds nothing to do, so "Remote Control disabled." is written once
   however many signals arrive. */
function finishDisconnect(t) {
  if (!t.rcDisconnecting) return;
  if (t.rcTimer) { clearTimeout(t.rcTimer); t.rcTimer = null; }
  t.rcDisconnecting = false;
  t.rcDeadline = 0;
  t.remoteControlUrl = null;
  t.rcBridgeConnected = false;
  t.rcOnAt = 0;
  syncComposer();
  addSystemTo(t, RC_DISABLED);
}

/* Reopens the composer and stops the indicator, however the attempt ended. */
function endConnecting(t) {
  if (t.rcTimer) { clearTimeout(t.rcTimer); t.rcTimer = null; }
  t.rcConnecting = false;
  t.rcPendingUrl = null;
  t.rcDeadline = 0;
  syncComposer();
  stopWorkingFor(t);
  resumeWorkingFor(t);   // a turn already running when the wait began still has its gerund
}

/* Whichever wait this tab is in has run out of time. */
function rcExpire(t) {
  if (t.rcDisconnecting) {
    // Reopen regardless of what the CLI did or didn't say. Being told it is off
    // when it might not be beats a composer that never comes back.
    finishDisconnect(t);
    return;
  }
  if (!t.rcConnecting) return;
  endConnecting(t);
  addSystemTo(t, 'Remote Control could not start — the bridge did not connect.');
}

/* Wall-clock backstop for both ceilings. The timers above are the normal route;
   this is here because "the composer never came back, close the tab" is the one
   failure with no way out of it, and a deadline that is re-read from the clock
   survives a timer that never fires for whatever reason. Costs one comparison per
   tab every two seconds. */
function rcSweepDeadlines() {
  if (typeof tabs === 'undefined' || !tabs) return;
  const now = Date.now();
  tabs.forEach(t => {
    if (!t || !t.rcDeadline || now < t.rcDeadline) return;
    if (!t.rcConnecting && !t.rcDisconnecting) { t.rcDeadline = 0; return; }
    rcExpire(t);
  });
}
setInterval(rcSweepDeadlines, 2000);

/* Announces Remote Control only once BOTH halves have arrived: the url, which
   only the control reply carries, and a bridge that is actually connected.
   Announcing on the reply alone was a lie — the reply comes back before the
   bridge is up, and a message sent in that window never reaches the phone. */
function settleConnected(t) {
  if (t.rcDisconnecting) return;
  if (!t.rcPendingUrl || !t.rcBridgeConnected) return;
  const url = t.rcPendingUrl;
  const epoch = t.rcPendingEpoch;
  endConnecting(t);
  t.remoteControlUrl = url;
  t.rcEpoch = epoch;
  t.rcOnAt = Date.now();
  addRemoteControlLine(t, url);
}

/* The CLI's answer. Two shapes arrive here (see NativeCore.ChatCallbacks):
   the toggle's reply, which is the only carrier of the session url, and the
   bridge's own state signal, which does not repeat it. */
window.onRemoteControl = function (tabId, json) {
  const t = tabs.find(x => x.id === tabId);
  if (!t) return;
  let d = null;
  try { d = JSON.parse(json || '{}'); } catch (e) { d = null; }
  if (!d) return;

  if (Object.prototype.hasOwnProperty.call(d, 'url')) {
    // Answers only. One request, one answer; anything past that belongs to a
    // toggle whose outcome this tab has already acted on, and acting on it twice
    // is what put the tab permanently out of step with the bridge.
    if (!t.rcAckPending) return;
    t.rcAckPending = false;
    if (d.enabled && d.url) {
      // Held, not announced: the bridge is not up yet. settleConnected writes
      // the line once bridge_state says it genuinely is.
      if (t.rcDisconnecting) return;   // asked to go down since; do not resurrect
      t.rcPendingUrl = d.url;
      t.rcPendingEpoch = d.bridgeEpoch;
      settleConnected(t);
      return;
    }
    // enabled:false carries nothing that says which it is: the CLI answers a
    // switch-off and a refused switch-on with the same shape. What this tab
    // asked for is the only thing that tells them apart.
    if (t.rcDisconnecting) { finishDisconnect(t); return; }
    if (t.rcConnecting) {
      endConnecting(t);
      t.remoteControlUrl = null;
      // A REFUSAL is not the same as switching it off, and must not read like
      // one: an org policy can forbid Remote Control outright, and an API-key
      // login cannot use it at all. Saying "disabled" to those users hides the
      // only thing that would tell them why it will never work.
      const reason = rcErrorText(d.error);
      if (reason) addSystemTo(t, 'Remote Control could not start — ' + reason);
      else addSystemTo(t, 'Remote Control could not start.');
      return;
    }
    // Owed an answer but waiting on neither — a ceiling fired first, and its
    // verdict is already on screen. Nothing to add.
    return;
  }

  if (!d.bridgeState) return;
  if (d.bridgeState === 'connected') {
    // A bridge reporting itself connected does not undo a switch-off that is
    // already in flight.
    if (t.rcDisconnecting) return;
    t.rcBridgeConnected = true;
    // A bridge taken back over by a replacement process reports its own epoch;
    // a failure is matched against the newest one.
    if (t.remoteControlUrl && d.bridgeEpoch != null) t.rcEpoch = d.bridgeEpoch;
    settleConnected(t);
    return;
  }
  // "ready" means the bridge exists but is not carrying anything yet — keep
  // waiting rather than claiming either outcome.
  if (d.bridgeState === 'ready') return;
  // A switch-off in flight settles on any teardown: it is the answer being waited for.
  if (t.rcDisconnecting) { t.rcBridgeConnected = false; finishDisconnect(t); return; }
  // Otherwise only "failed" ends a bridge — the VS Code extension's rule. The CLI
  // passes through other states on its way back up (reconnecting and the like), and
  // reading those as the bridge going away switched the tab off over a link that was
  // still carrying.
  if (d.bridgeState !== 'failed') return;
  if (t.rcConnecting) {
    t.rcBridgeConnected = false;
    endConnecting(t);
    addSystemTo(t, 'Remote Control could not start.');
    return;
  }
  if (t.remoteControlUrl) {
    // The failure of an older bridge than the one this tab shows is not this one's.
    if (d.bridgeEpoch != null && t.rcEpoch != null && d.bridgeEpoch !== t.rcEpoch) return;
    // The grace is for failures the bridge reports. One with no epoch comes from the
    // plugin itself (the process holding the bridge is gone) and is never a straggler.
    if (d.bridgeEpoch != null && t.rcOnAt && Date.now() - t.rcOnAt < RC_TEARDOWN_GRACE_MS) return;
    t.rcBridgeConnected = false;
    t.remoteControlUrl = null;
    t.rcOnAt = 0;
    syncComposer();
    addSystemTo(t, RC_DISABLED);
    return;
  }
  // Already off. A second signal for a bridge this tab has stopped tracking.
  t.rcBridgeConnected = false;
};

/* The CLI's refusal, as one readable clause, or '' when it gave none.
   Shapes vary — a bare string, or an object with message/error/detail — so it
   is unwrapped rather than stringified, which would print "[object Object]" at
   exactly the moment the user most needs to read it. */
function rcErrorText(err) {
  if (!err) return '';
  if (typeof err === 'string') return err.trim();
  if (typeof err === 'object') {
    const m = err.message || err.error || err.detail || err.reason;
    if (typeof m === 'string' && m.trim()) return m.trim();
  }
  return '';
}

/* Like addSystemToPane, but the address is a link. Built as nodes rather than
   markup so the url — which comes from the server — can never be parsed as
   anything but text. */
function addRemoteControlLine(t, url) {
  if (!t || !t.pane) return;
  const turn = document.createElement('div');
  turn.className = 'turn';
  const item = document.createElement('div');
  item.className = 'a-item muted';
  const dot = document.createElement('span');
  dot.className = 'dot gray';
  const sys = document.createElement('span');
  sys.className = 'sys';
  sys.appendChild(document.createTextNode(RC_ACTIVE_PREFIX));
  const a = document.createElement('a');
  a.className = 'rc-link';
  a.href = '#';
  a.textContent = RC_LINK_TEXT;
  a.title = url;
  a.onclick = (e) => {
    e.preventDefault();
    if (window._openExternal) _openExternal(url);
  };
  sys.appendChild(a);

  // Chevron → the QR for this session. Collapsed by default: the address is
  // enough for most people, and the code is only wanted when a phone is
  // actually being pointed at the screen.
  const chev = document.createElement('span');
  chev.className = 'rc-chev';
  chev.innerHTML = ICONS.CHEVRON;
  chev.title = 'Show QR code';
  sys.appendChild(chev);

  item.appendChild(dot);
  item.appendChild(sys);
  turn.appendChild(item);

  const qrBox = document.createElement('div');
  qrBox.className = 'rc-qr';
  turn.appendChild(qrBox);

  chev.onclick = (e) => {
    e.stopPropagation();
    const open = qrBox.classList.toggle('open');
    chev.classList.toggle('open', open);
    chev.title = open ? 'Hide QR code' : 'Show QR code';
    // Rendered on first reveal and kept: a few KB of markup nobody asked for is
    // not worth generating for every session that is merely switched on.
    if (open && !qrBox.dataset.rendered) renderQr(qrBox, url);
    if (open && t.pane === (activeTab() && activeTab().pane)) scrollBottom();
  };

  t.pane.appendChild(turn);
  if (t.pane === (activeTab() && activeTab().pane)) scrollBottom();
}

/* Draws the QR, with the caption the CLI's own terminal UI uses. */
function renderQr(box, url) {
  box.dataset.rendered = '1';
  let svg = '';
  try { svg = window._remoteControlQr ? _remoteControlQr(url) : ''; } catch (e) { svg = ''; }
  if (!svg) {
    const err = document.createElement('div');
    err.className = 'rc-qr-cap';
    err.textContent = 'Couldn’t render a QR code for this session.';
    box.appendChild(err);
    return;
  }
  const frame = document.createElement('div');
  frame.className = 'rc-qr-img';
  // The SVG is generated by our own core from a url the bridge minted; it
  // contains no text from anywhere else.
  frame.innerHTML = svg;
  const cap = document.createElement('div');
  cap.className = 'rc-qr-cap';
  cap.textContent = 'Scan with your phone to open this session';
  box.appendChild(frame);
  box.appendChild(cap);
}

/* Whether the active tab has Remote Control on — for anything that wants to
   reflect it without reaching into tab state itself. */
function remoteControlActive() {
  const t = activeTab();
  return !!(t && t.remoteControlUrl);
}

/* A message typed on another device — phone, claude.ai, another editor — that
   reached this conversation over the bridge.

   Rendered as an ordinary user bubble, because that is what it is: the same
   conversation, a different keyboard. It goes to the tab that owns the bridge
   rather than whichever tab is in front, so a background conversation still
   receives it and the view being read does not jump. */
window.onRemoteMessage = function (tabId, text) {
  if (!text) return;
  const t = tabs.find(x => x.id === tabId);
  if (!t || !t.pane) return;
  addUserMessage(text, null, null, null, nowIso(), t.pane);
};

/* Remote Control on startup (a preference, off by default).

   When set, every conversation in this view comes up already reachable from a
   phone — so a new tab starts on "Establishing connection…" rather than needing
   the command typed first. Read once per call rather than cached, so toggling
   the preference takes effect on the next tab without a restart. */
function remoteControlOnStartupEnabled() {
  try { return !!(window._remoteControlOnStartup && _remoteControlOnStartup()); }
  catch (e) { return false; }
}

/* Switches Remote Control on for a tab that does not already have it.
   Silent about a tab that is already on or already connecting — this runs on
   tab creation and on view load, and must be safe to call more than once. */
function autoEnableRemoteControl(t) {
  if (!t || t.remoteControlUrl || t.rcConnecting || t.rcDisconnecting) return;
  if (!window._remoteControl) return;
  if (!remoteControlOnStartupEnabled()) return;
  beginConnecting(t);
  rcSend(t, true);
}

/* /clear starts a new conversation in the same tab, and its process — which held
   the bridge — is gone with the old one. So the new conversation starts the way the
   VS Code extension starts one: Remote Control off, or connecting again when
   Remote Control on startup is set. Leaving the old state in place had the tab
   saying Remote Control was active over a bridge nothing held. */
function resetRemoteControlForNewConversation(t) {
  if (!t) return;
  if (t.rcTimer) { clearTimeout(t.rcTimer); t.rcTimer = null; }
  t.rcConnecting = false;
  t.rcDisconnecting = false;
  t.rcAckPending = false;
  t.rcPendingUrl = null;
  t.rcPendingEpoch = null;
  t.rcBridgeConnected = false;
  t.remoteControlUrl = null;
  t.rcEpoch = null;
  t.rcOnAt = 0;
  t.rcDeadline = 0;
  stopWorkingFor(t);
  syncComposer();
  autoEnableRemoteControl(t);
}

/* Every conversation already open — used once the page is ready. */
function autoEnableRemoteControlAll() {
  if (!remoteControlOnStartupEnabled()) return;
  (tabs || []).forEach(autoEnableRemoteControl);
}

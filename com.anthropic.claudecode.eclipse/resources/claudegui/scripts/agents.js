/* agents.js — "Agent map" popup: three views in one modal.
     list      — every Agent tool call in the CURRENT tab's own transcript, most recent
                 first (collectAgents scans the rendered DOM — same reasoning as before:
                 survives restart/reload/per-tab scoping without a separate registry that
                 could drift out of sync or go empty on its own).
     detail    — click a card: status/type/model/mode, "Spawned by the main agent",
                 duration+tokens, a collapsible Prompt, a collapsible Tool-calls list.
     transcript — "Open transcript": the agent's own full nested conversation, rendered
                 with chat.js's buildAgentLogItemEl so it looks exactly like the main
                 transcript's own cards (Bash IN/OUT boxes, Read lines, Thinking markers).
   Duration/tokens/model/prompt/steps all come from chat.js's agentLogs — fed live by
   chat.rs's onAgentActivity relay for the current session, and by history.js reading
   session.rs's reconstructed agentLog field for a reloaded one, so both look the same
   here regardless of which populated them. Same modal-overlay idiom as
   #rewind-overlay/#account-overlay (scrim + centered card) — opened via the /agents slash
   command (slash.js); no toolbar entry point yet. */

const agentsOverlayEl = document.getElementById('agents-overlay');
const agentsWinEl = document.getElementById('agents-win');

let agentsView = 'list';       // 'list' | 'detail' | 'transcript'
let agentsSelectedId = null;   // the agent's own tool_use id (.tool-line's data-tuid)

/** "2m 38s" / "48s" — matches the reference VSCode Agent map's compact duration format. */
function formatAgentDuration(ms) {
  const s = Math.max(0, Math.round(ms / 1000));
  const m = Math.floor(s / 60);
  return m > 0 ? m + 'm ' + (s % 60) + 's' : s + 's';
}
/** "36.3k tokens" / "823 tokens". */
function formatAgentTokens(n) {
  const t = n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n);
  return t + ' token' + (n === 1 ? '' : 's');
}
function agentDurationMs(a) {
  if (!a.log.startedAt) return 0;
  return (a.log.endedAt || Date.now()) - a.log.startedAt;
}
/** Whether an agent should be treated as still actively running — NOT simply
 *  `agent.status === 'running'` (the DOM dot's own state). A BACKGROUND agent's
 *  top-level tool_result fires almost immediately as a "kicked off" ack (chat.js's
 *  applyToolResult), turning that dot green well before its real work is done; an
 *  is_error dot from that SAME early ack, though, means the launch itself failed —
 *  genuinely over, not "still starting". The only trustworthy "is it still going"
 *  signal left for a background agent is the absence of endedAt, only ever set by
 *  the real completion/stop signal (chat.rs's task_notification handling). */
function agentIsActive(agent) {
  if (agent.status === 'running') return true;
  if (agent.status === 'error') return false;
  return !!(agent.log.input && agent.log.input.run_in_background && !agent.log.endedAt);
}
/** 'done'|'red'|'spin' dot class, correcting for the same early-ack quirk agentIsActive
 *  accounts for — shared by the list row and the detail head so the two never disagree. */
function agentDotClass(agent) {
  // Checked before agentIsActive: once the "finished" signal has actually arrived
  // (see applyAgentActivity), whether the user stopped it or it completed on its own
  // is known regardless of whatever the DOM dot's own (possibly still-early-ack)
  // status says — a stop gets its own distinct color, not lumped in with either a
  // genuine failure (red) or a clean finish (grey).
  if (agent.log.finishStatus === 'stopped') return 'stopped';
  if (agentIsActive(agent)) return 'spin';
  return agent.status === 'error' ? 'red' : 'done';
}
/** The agent's own final answer, in its own words — otherwise never shown anywhere: the
 *  top-level model reads it as context and relays it in ITS OWN words in the main chat
 *  (see chat.rs's task_notification comment), so without this the actual response text
 *  is only visible buried inside "Open transcript"'s full nested log. Prefers the
 *  authoritative `summary` a background agent's completion notification carries; falls
 *  back to the last text-kind item (a foreground agent, or history reconstruction,
 *  never gets a `summary` — its own final "assistant" text message IS the response). */
function agentResponseText(log) {
  // "summary" only carries the agent's genuine final answer for a NATURAL completion
  // (chat.rs's task_notification, status:"completed") — for a user-initiated stop, the
  // CLI has been observed sending it back as a bare echo of the ORIGINAL description
  // (confirmed: a stopped agent with 0 tokens and 0 tool calls still had a "summary"
  // identical to its own description) rather than anything the agent actually said.
  if (log.summary && log.finishStatus !== 'stopped') return log.summary;
  for (let i = log.items.length - 1; i >= 0; i--) {
    if (log.items[i].kind === 'text') return log.items[i].text;
  }
  return log.finishStatus === 'stopped' ? 'Stopped before it produced a response.' : '';
}

/** Scans the active tab's own pane for Agent tool-line cards and reads their current state
 *  straight off the rendered markup — the same .tname/.tagent-type/.tdesc/.dot a person
 *  looking at the transcript itself would see, so this can never drift out of sync with it.
 *  Duration/tokens/model/prompt/steps come from chat.js's agentLogs, keyed by the same id
 *  each line already carries as data-tuid. */
function collectAgents() {
  const pane = activeTab() ? activeTab().pane : null;
  if (!pane) return [];
  const agents = [];
  pane.querySelectorAll('.tool-line').forEach(line => {
    if (!AGENT_KEYS.has(line.dataset.tname)) return;
    const dot = line.querySelector('.dot');
    const status = dot && dot.classList.contains('done') ? 'done'
        : dot && dot.classList.contains('red') ? 'error' : 'running';
    const typeEl = line.querySelector('.tagent-type');
    const descEl = line.querySelector('.tdesc');
    const type = typeEl ? typeEl.textContent.replace(/^\(|\)$/g, '') : '';
    const description = (descEl && descEl.textContent) || 'Agent';
    const id = line.dataset.tuid || '';
    const log = (id && agentLogs.get(id))
        || { items: [], tokens: 0, model: '', startedAt: 0, endedAt: 0, input: {} };
    agents.push({ id, type, description, status, log });
  });
  return agents;
}

function renderAgentsWin() {
  agentsWinEl.classList.remove('ap-transcript-mode');
  agentsWinEl.innerHTML = '';
  const agents = collectAgents();
  if (agentsView !== 'list') {
    const agent = agents.find(a => a.id === agentsSelectedId);
    if (!agent) {
      agentsView = 'list';   // the line it pointed at is gone somehow — fall back safely
    } else if (agentsView === 'detail') {
      renderAgentDetail(agent); return;
    } else if (agentsView === 'transcript') {
      renderAgentTranscript(agent); return;
    }
  }
  renderAgentsList(agents);
}

function renderAgentsList(agents) {
  const head = document.createElement('div'); head.className = 'ap-head';
  const title = document.createElement('span'); title.className = 'ap-title'; title.textContent = 'Agent map';
  const x = document.createElement('span'); x.className = 'ap-x'; x.innerHTML = ICONS.X;
  x.onclick = () => closeAgentsPanel();
  head.appendChild(title); head.appendChild(x);
  agentsWinEl.appendChild(head);

  const sub = document.createElement('div'); sub.className = 'ap-sub';
  sub.textContent = agents.length
      ? agents.length + ' agent' + (agents.length === 1 ? '' : 's') + ' · click an agent for details'
      : 'No agents in this conversation yet.';
  agentsWinEl.appendChild(sub);

  if (!agents.length) return;
  const list = document.createElement('div'); list.className = 'ap-list';
  // Most recent first — the one just kicked off is what you're watching.
  agents.slice().reverse().forEach(a => {
    const row = document.createElement('div'); row.className = 'ap-row';
    row.onclick = () => { agentsView = 'detail'; agentsSelectedId = a.id; renderAgentsWin(); };
    const dot = document.createElement('span');
    dot.className = 'dot ' + agentDotClass(a);
    const body = document.createElement('div'); body.className = 'ap-body';
    const label = document.createElement('span'); label.className = 'ap-label';
    label.textContent = a.description;
    body.appendChild(label);
    const dur = agentDurationMs(a);
    if (dur || a.log.tokens) {
      const meta = document.createElement('span'); meta.className = 'ap-step';
      meta.textContent = formatAgentDuration(dur) + ' · ' + formatAgentTokens(a.log.tokens);
      body.appendChild(meta);
    }
    row.appendChild(dot); row.appendChild(body);
    list.appendChild(row);
  });
  agentsWinEl.appendChild(list);
}

/** Shared by detail/transcript: back arrow (CHEVRON rotated 180deg via CSS) + status dot +
 *  title + close. `onBack` differs per view (detail → list, transcript → detail). */
function makeApDetailHead(agent, onBack) {
  const head = document.createElement('div'); head.className = 'ap-head ap-detail-head';
  const back = document.createElement('span'); back.className = 'ap-back'; back.innerHTML = ICONS.CHEVRON;
  back.onclick = onBack;
  const dot = document.createElement('span');
  dot.className = 'dot ' + agentDotClass(agent);
  const title = document.createElement('span'); title.className = 'ap-title ap-detail-title';
  title.textContent = agent.description;
  const x = document.createElement('span'); x.className = 'ap-x'; x.innerHTML = ICONS.X;
  x.onclick = () => closeAgentsPanel();
  head.appendChild(back); head.appendChild(dot); head.appendChild(title); head.appendChild(x);
  return head;
}

/** A collapsible section for the detail view — same fold idiom as chat.js's own
 *  .agent-log, built lazily (buildBody runs once, the first time it's opened). */
function makeApFold(label, buildBody) {
  const wrap = document.createElement('div'); wrap.className = 'ap-fold';
  const head = document.createElement('div'); head.className = 'ap-fold-head';
  head.innerHTML = '<span class="chev">' + ICONS.CHEVRON + '</span><span>' + label + '</span>';
  const body = document.createElement('div'); body.className = 'ap-fold-body';
  head.onclick = () => {
    wrap.classList.toggle('open');
    if (wrap.classList.contains('open') && !body.childNodes.length) buildBody(body);
  };
  wrap.appendChild(head); wrap.appendChild(body);
  return wrap;
}

function renderAgentDetail(agent) {
  agentsWinEl.appendChild(makeApDetailHead(agent, () => { agentsView = 'list'; renderAgentsWin(); }));

  // Everything below the head goes in its OWN scrolling container — like .ap-list and
  // .ap-transcript-body already do — instead of directly into #agents-win, which is
  // itself overflow:hidden with a capped max-height. Without this, expanding the Prompt
  // fold just pushed Tool calls/Open transcript past that cap with nothing to scroll
  // them back into view: the popup clipped them outright instead of growing a scrollbar.
  const body = document.createElement('div'); body.className = 'ap-detail-body';
  agentsWinEl.appendChild(body);

  const statusWord = agent.log.finishStatus === 'stopped' ? 'Stopped'
      : agentIsActive(agent) ? 'Running'
      : agent.status === 'error' ? 'Failed' : 'Finished';
  const parts = [statusWord];
  if (agent.type) parts.push(agent.type);
  if (agent.log.model) parts.push(agent.log.model);
  if (agent.log.input && agent.log.input.run_in_background) parts.push('background');
  const meta = document.createElement('div'); meta.className = 'ap-meta'; meta.textContent = parts.join(' · ');
  body.appendChild(meta);

  // Always "the main agent": this session's Agent map only tracks TOP-level Agent calls —
  // one launched BY a running subagent (agent-of-agent) is currently surfaced as generic
  // activity under its parent instead of getting its own top-level card, so every card
  // that reaches this popup at all was, by construction, spawned by the top-level turn.
  const spawned = document.createElement('div'); spawned.className = 'ap-meta';
  spawned.textContent = 'Spawned by the main agent';
  body.appendChild(spawned);

  const time = document.createElement('div'); time.className = 'ap-meta';
  time.textContent = formatAgentDuration(agentDurationMs(agent)) + ' · ' + formatAgentTokens(agent.log.tokens);
  body.appendChild(time);

  // Always visible, not folded like Prompt/Tool calls below — the actual point of
  // clicking into an agent's detail is almost always "what did it say", not its inputs.
  const response = agentResponseText(agent.log);
  if (response) {
    const label = document.createElement('div'); label.className = 'ap-response-label'; label.textContent = 'Response';
    // a-body (also used by chat.js's own assistant text) is what gets renderMarkdown's
    // p/ul/li/pre margins for free — this response is the same shape of content (the
    // subagent's own final assistant text) and deserves the same treatment, not raw
    // "**bold**"/code-fence characters shown literally.
    const respBody = document.createElement('div'); respBody.className = 'ap-response a-body';
    respBody.innerHTML = renderMarkdown(response);
    respBody.appendChild(makeCopyBtn(() => response));   // hover-revealed, chat.css's #agents-win .ap-response rule
    body.appendChild(label);
    body.appendChild(respBody);
  }

  const prompt = agent.log.input && agent.log.input.prompt;
  if (prompt) {
    body.appendChild(makeApFold('Prompt', (foldBody) => {
      const pre = document.createElement('pre'); pre.className = 'ap-prompt'; pre.textContent = prompt;
      foldBody.appendChild(pre);
    }));
  }

  const toolCalls = agent.log.items.filter(it => it.kind === 'tool');
  body.appendChild(makeApFold('Tool calls (' + toolCalls.length + ')', (foldBody) => {
    if (!toolCalls.length) {
      const empty = document.createElement('div'); empty.className = 'ap-empty';
      empty.textContent = 'No tool calls recorded.';
      foldBody.appendChild(empty);
      return;
    }
    toolCalls.forEach(it => {
      const row = document.createElement('div'); row.className = 'ap-row ap-toolrow';
      const dot = document.createElement('span');
      dot.className = 'dot ' + (it.status === 'done' ? 'done' : it.status === 'interrupted' ? 'red' : 'spin');
      const label = document.createElement('span'); label.className = 'ap-label'; label.textContent = toolLabel(it.name);
      row.appendChild(dot); row.appendChild(label);
      foldBody.appendChild(row);
    });
  }));

  const btnRow = document.createElement('div'); btnRow.className = 'ap-btn-row';
  const openBtn = document.createElement('button'); openBtn.type = 'button'; openBtn.className = 'ap-open-btn';
  openBtn.textContent = 'Open transcript';
  openBtn.onclick = () => { agentsView = 'transcript'; renderAgentsWin(); };
  btnRow.appendChild(openBtn);

  // Only while genuinely running, and only once taskId has actually arrived
  // (chat.rs's task_started — captured shortly after the agent starts, not
  // necessarily the exact same instant its tool line appears): nothing to send a
  // stop_task control_request against otherwise.
  if (agentIsActive(agent) && agent.log.taskId) {
    const stopBtn = document.createElement('button'); stopBtn.type = 'button'; stopBtn.className = 'ap-stop-btn';
    stopBtn.textContent = 'Stop agent';
    stopBtn.onclick = () => {
      stopBtn.disabled = true; stopBtn.textContent = 'Stopping…';
      const t = activeTab();
      if (window._stopAgentTask && t) window._stopAgentTask(t.id, agent.log.taskId);
    };
    btnRow.appendChild(stopBtn);
  }
  body.appendChild(btnRow);
}

/** The agent's own full nested conversation — rendered with chat.js's buildAgentLogItemEl
 *  (the SAME builder the live inline collapsible under its tool line uses), so a subagent's
 *  work looks exactly like the main transcript's own cards wherever it's viewed. Widened via
 *  .ap-transcript-mode (panels.css) — the compact 360px list/detail card has no room for a
 *  real transcript. */
function renderAgentTranscript(agent) {
  agentsWinEl.classList.add('ap-transcript-mode');
  agentsWinEl.appendChild(makeApDetailHead(agent, () => { agentsView = 'detail'; renderAgentsWin(); }));

  const body = document.createElement('div'); body.className = 'ap-transcript-body';
  const prompt = agent.log.input && agent.log.input.prompt;
  if (prompt) {
    const promptEl = document.createElement('div'); promptEl.className = 'ap-transcript-prompt';
    promptEl.textContent = prompt;
    body.appendChild(promptEl);
  }
  if (!agent.log.items.length) {
    const empty = document.createElement('div'); empty.className = 'ap-empty';
    empty.textContent = 'No activity recorded for this agent.';
    body.appendChild(empty);
  } else {
    agent.log.items.forEach(item => body.appendChild(buildAgentLogItemEl(item)));
  }
  agentsWinEl.appendChild(body);
}

/* Re-renders in place while open — called by chat.js whenever an agent's state changes (a
   new Agent call starts, one finishes, or its own activity streams in). A no-op while
   closed; opening always renders fresh. */
function renderAgentsPanel() {
  updateAgentsBtn();
  if (!agentsOverlayEl.classList.contains('open')) return;
  renderAgentsWin();
  // An agent starting AFTER the popup was already open (nothing running yet when it
  // opened) needs the ticker started here too — openAgentsPanel only covers the
  // already-running-at-open-time case.
  if (!agentsTicker && agentsHasRunning()) startAgentsTicker();
}

/** The composer toolbar's own "● N agents" pill (claudegui.html's #agents-btn) — kept in
 *  sync with the SAME collectAgents() the popup itself reads, so the two never disagree.
 *  Hidden entirely until this (the ACTIVE tab's) conversation has at least one agent —
 *  never shown as "0 agents". Called from renderAgentsPanel's own refresh points AND
 *  tabs.js's switchTab, since collectAgents() is scoped to whichever tab is active. */
function updateAgentsBtn() {
  const btn = document.getElementById('agents-btn');
  if (!btn) return;
  const agents = collectAgents();
  if (!agents.length) { btn.style.display = 'none'; return; }
  btn.style.display = '';
  const dot = document.getElementById('agents-dot');
  if (dot) dot.classList.toggle('running', agents.some(agentIsActive));
  const count = document.getElementById('agents-count');
  if (count) count.textContent = agents.length + ' agent' + (agents.length === 1 ? '' : 's');
}
window.updateAgentsBtn = updateAgentsBtn;

// Duration only actually CHANGES when chat.js pokes renderAgentsPanel on a real event
// (a new step starting/finishing) — between those, a still-running agent's shown time
// was frozen at whatever it was on the last one, which reads as "stuck"/broken for
// anything slower than rapid-fire tool calls. This ticks it forward once a second like
// an actual stopwatch, same as any other live timer, for as long as something is running.
let agentsTicker = null;
function agentsHasRunning() {
  return collectAgents().some(agentIsActive);
}
function startAgentsTicker() {
  stopAgentsTicker();
  agentsTicker = setInterval(() => {
    if (!agentsHasRunning()) { stopAgentsTicker(); return; }
    renderAgentsWin();
  }, 1000);
}
function stopAgentsTicker() {
  if (agentsTicker) { clearInterval(agentsTicker); agentsTicker = null; }
}
function openAgentsPanel() {
  agentsView = 'list'; agentsSelectedId = null;   // always the map, never wherever it was left
  renderAgentsWin();
  agentsOverlayEl.classList.add('open');
  document.addEventListener('keydown', onAgentsKey, true);
  if (agentsHasRunning()) startAgentsTicker();
}
function closeAgentsPanel() {
  agentsOverlayEl.classList.remove('open');
  document.removeEventListener('keydown', onAgentsKey, true);
  stopAgentsTicker();
}
function toggleAgentsPanel() {
  if (agentsOverlayEl.classList.contains('open')) closeAgentsPanel(); else openAgentsPanel();
}
function onAgentsKey(e) {
  if (e.key !== 'Escape') return;
  e.preventDefault();
  // Escape steps back a level before it closes the whole popup — same expectation as
  // any drill-down UI (a modal's own back button, a browser's back gesture).
  if (agentsView === 'transcript') { agentsView = 'detail'; renderAgentsWin(); }
  else if (agentsView === 'detail') { agentsView = 'list'; renderAgentsWin(); }
  else closeAgentsPanel();
}
// Same idiom as rewind.js: clicking the scrim itself (not the card) closes the popup.
agentsOverlayEl.addEventListener('click', (e) => {
  if (e.target.id === 'agents-overlay') closeAgentsPanel();
});
// Called from chat.js (addToolLine/applyToolResult/applyAgentActivity) via
// window.renderAgentsPanel — the indirection lets chat.js fire this before agents.js has
// necessarily finished defining it on a very first paint, and lets it stay a no-op if this
// script somehow isn't loaded.
window.renderAgentsPanel = renderAgentsPanel;
window.toggleAgentsPanel = toggleAgentsPanel;

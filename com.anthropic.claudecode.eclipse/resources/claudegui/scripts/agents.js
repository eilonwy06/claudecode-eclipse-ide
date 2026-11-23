/* agents.js — "Agent map" popup: three views in one modal.
     list      — the map itself: the CURRENT tab's conversation at the root of a tree, every
                 Agent tool call in its own transcript branching off it (most recent first)
                 and the agents those started off them (collectAgents scans the rendered DOM — same reasoning as before:
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
   command (slash.js) or the composer bar's "● N agents" pill (claudegui.html's #agents-btn).
   Below the agents, the tab's background tasks (shells above all), from the CLI's own list
   (chat.rs relays system/background_tasks_changed); click one for its view:
     task       — how long it has run, what it has written so far (a shell's output, asked
                 of the CLI with get_task_output while the view is open) and a Stop button. */

const agentsOverlayEl = document.getElementById('agents-overlay');
const agentsWinEl = document.getElementById('agents-win');

let agentsView = 'list';       // 'list' | 'detail' | 'transcript' | 'task'
let agentsSelectedId = null;   // the agent's own tool_use id (.tool-line's data-tuid)
let agentsSelectedTask = null; // the background task's id, in the 'task' view

/** "2m 38s" / "48s" — the reference VSCode Agent map's compact duration format, which counts
    whole seconds (6m 19.9s is "6m 19s", not "6m 20s"). */
function formatAgentDuration(ms) {
  const s = Math.max(0, Math.floor(ms / 1000));
  const m = Math.floor(s / 60);
  return m > 0 ? m + 'm ' + (s % 60) + 's' : s + 's';
}
/** "296.1k tokens" / "1.2M tokens" / "823 tokens". */
function formatAgentTokens(n) {
  const t = n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n);
  return t + ' tokens';
}
/** What an agent's line says of its run, as the extension's does: how long, if a second or more,
    and how many tokens, if any; nothing for either it does not have. */
function agentMeta(agent) {
  const ms = agentDurationMs(agent);
  const parts = [];
  if (ms >= 1000) parts.push(formatAgentDuration(ms));
  if (agent.log.tokens > 0) parts.push(formatAgentTokens(agent.log.tokens));
  return parts.join(' · ');
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
  // The CLI's own word that it ended wins over its line's dot: the result of the call that
  // started it comes separately, and may not have come yet.
  if (agent.log.finishStatus) return false;
  if (agent.status === 'running') return true;
  if (agent.status === 'error') return false;
  // The CLI told us it started and has not told us it ended, so it is going, whatever its
  // line's dot says: a later tool call greens the dot of every one before it (chat.js,
  // markToolsDone), which several agents started together all run into. One that is not in
  // the background cannot outlast the turn that started it, so it is going only while that runs.
  if (agent.log.taskId && !agent.log.endedAt) {
    if (agentIsBackgrounded(agent) !== false) return true;
    const t = activeTab();
    return !!(t && t.streaming);
  }
  return agentIsBackgrounded(agent) === true && !agent.log.endedAt;
}
/* An agent is in one of five states, as in the VS Code extension's Agent map. */
const AGENT_STATUS_LABEL = { working: 'Working', waiting: 'Waiting for your permission',
                             finished: 'Finished', failed: 'Failed', stopped: 'Stopped' };
/** The dot class of each state. The extension colours a stopped agent as it does a failed one. */
const AGENT_STATUS_DOT = { working: 'spin', waiting: 'wait', finished: 'done', failed: 'red', stopped: 'red' };

/** The permission request of the tab in front that this agent is held up on, if there is one.
 *  The CLI names the agent a request comes from by its task id (chat.rs, agentPermission). */
function agentWaitingAsk(agent) {
  const t = activeTab();
  const id = agent.log && agent.log.taskId;
  if (!t || !t.agentAsks || !id) return undefined;
  for (const ask of t.agentAsks.values()) if (ask.taskId === id) return ask;
  return undefined;
}
/** 'working' | 'waiting' | 'finished' | 'failed' | 'stopped'.
 *  The "finished" signal, once it has arrived (see applyAgentActivity), says how the agent
 *  ended whatever the DOM dot's own, possibly still-early-ack, state says. */
function agentStatus(agent) {
  const how = agent.log.finishStatus;
  if (how === 'stopped' || how === 'killed') return 'stopped';
  if (how === 'failed') return 'failed';
  if (agentIsActive(agent)) return agentWaitingAsk(agent) ? 'waiting' : 'working';
  return agent.status === 'error' ? 'failed' : 'finished';
}
/** The dot class of an agent — shared by the list row, the detail head and the tool calls. */
function agentDotClass(agent) { return AGENT_STATUS_DOT[agentStatus(agent)]; }

/** What the composer's agents pill shows for all of them, as the extension's does: waiting
 *  when any agent is held up on a permission, else running while any works, else failed
 *  when one failed, else idle. */
function agentsOverallState(agents) {
  let running = false, failed = false;
  for (const a of agents) {
    const st = agentStatus(a);
    if (st === 'waiting') return 'waiting';
    if (st === 'working') running = true;
    else if (st === 'failed') failed = true;
  }
  return running ? 'running' : failed ? 'failed' : 'idle';
}
const AGENTS_PILL_TITLE = {
  waiting: 'An agent is waiting for your permission · Click to open the agent map',
  running: 'Agents are working · Click to open the agent map',
  failed: 'An agent failed · Click to open the agent map',
  idle: 'Click to open the agent map',
};

/** An agent has ended, so whatever request of its was recorded is over: dropped, so that none
 *  can outlive it (a card taken down for another reason never says so, and the agent would
 *  read as waiting on it). */
function dropAgentAsks(taskId, t) {
  if (!t || !t.agentAsks || !taskId) return;
  [...t.agentAsks].forEach(([id, ask]) => { if (ask.taskId === taskId) t.agentAsks.delete(id); });
}
/** The tab in front is told an agent is, or no longer is, held up on a permission request
 *  (chat.js, applyAgentActivity). Kept by request id, as the requests are what come and go. */
function applyAgentPermission(info, t) {
  if (!t || !info || !info.requestId) return;
  if (!t.agentAsks) t.agentAsks = new Map();
  if (info.waiting) t.agentAsks.set(info.requestId, { taskId: info.taskId || '', toolName: info.toolName || '', toolUseId: info.toolUseId || '' });
  else t.agentAsks.delete(info.requestId);
  if (window.renderAgentsPanel) window.renderAgentsPanel();
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
    // One in an agent's own inline log is a call that agent made; addNestedAgents lists it.
    if (line.closest('.agent-log')) return;
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
    const agent = { id, type, description, status, log, parentId: '', depth: 0 };
    agents.push(agent);
    addNestedAgents(agent, agents, new Set([id]));
  });
  return agents;
}
/** The agents an agent started itself, found in its own log: a subagent's Agent call is one of
 *  its tool calls, not a line of the main transcript, so the transcript scan above never sees
 *  it. Each is read from that call (what it was told, and how it ended) and from the log the
 *  CLI's own task events made for it, which a reopened conversation does not have. Listed
 *  after the one that started it, one step further in. */
function addNestedAgents(parent, agents, seen) {
  parent.log.items.forEach(it => {
    if (it.kind !== 'tool' || !it.id || seen.has(it.id) || !AGENT_KEYS.has(String(it.name || '').toLowerCase())) return;
    seen.add(it.id);
    const input = it.input && typeof it.input === 'object' ? it.input : {};
    let log = agentLogs.get(it.id);
    if (log) {
      if (!log.input || !Object.keys(log.input).length) log.input = input;
    } else {
      // Not registered: a reopened conversation has no log of its own for it, and one made
      // here would start counting its duration from now.
      log = { items: [], tokens: 0, model: '', startedAt: 0, endedAt: 0, input, taskId: '', finishStatus: '' };
    }
    const status = it.status === 'done' ? 'done' : it.status === 'interrupted' ? 'error' : 'running';
    const nested = { id: it.id, type: input.subagent_type || '', description: input.description || 'Agent',
                     status, log, parentId: parent.id, depth: parent.depth + 1 };
    agents.push(nested);
    addNestedAgents(nested, agents, seen);
  });
}

/* ===================== Stopping and removing agents ===================== */

/** How long a card says "Stopping…" after Stop, waiting for the CLI to say the agent ended:
 *  past this, Stop is offered again rather than leaving a stop that never took stuck. */
const AGENT_STOP_WAIT_MS = 10000;
/** Only an agent the CLI has given a task id can be stopped: stop_task names it by that id
 *  (chat.rs's task_started — it comes shortly after the agent's own line does). */
function agentStoppable(agent) { return agentIsActive(agent) && !!agent.log.taskId; }
function agentStopping(agent) {
  return agentIsActive(agent) && agent.log.stopRequestedAt > 0
      && Date.now() - agent.log.stopRequestedAt < AGENT_STOP_WAIT_MS;
}
function stopAgent(agent) {
  const t = activeTab();
  if (!t || !window._stopAgentTask || !agent.log.taskId) return;
  agent.log.userStopped = true;   // what its hand-back says, once it is over (applyAgentActivity)
  agent.log.stopRequestedAt = Date.now();
  window._stopAgentTask(t.id, agent.log.taskId);
  renderAgentsPanel();
}

/* An agent taken off the map, once it is over and its work has been looked at, stays off it:
   its tool_use id (unique to it, whichever conversation or tab shows it) is remembered in
   localStorage, as a per-machine nicety like the changelog's "seen" mark — losing it only
   puts the agent back on the map. Nothing is removed from the conversation itself. */
const AGENTS_REMOVED_KEY = 'claude.agentMap.removed';
const AGENTS_REMOVED_KEPT = 2000;   // the newest this many; an old conversation's are let go
let agentsRemoved = null;           // Set of tool_use ids, oldest first; read on first use
function removedAgentIds() {
  if (!agentsRemoved) {
    let ids = [];
    try { ids = JSON.parse(localStorage.getItem(AGENTS_REMOVED_KEY) || '[]'); } catch (e) {}
    agentsRemoved = new Set(Array.isArray(ids) ? ids.filter(id => typeof id === 'string' && id) : []);
  }
  return agentsRemoved;
}
function saveRemovedAgents() {
  const ids = [...removedAgentIds()].slice(-AGENTS_REMOVED_KEPT);
  agentsRemoved = new Set(ids);
  try { localStorage.setItem(AGENTS_REMOVED_KEY, JSON.stringify(ids)); } catch (e) {}
}
/** The agent and every agent it started, at any depth. */
function agentSubtree(agent, agents) {
  const out = [agent];
  for (let i = 0; i < out.length; i++) agents.forEach(a => { if (a.parentId && a.parentId === out[i].id) out.push(a); });
  return out;
}
/** Whether it can come off the map: it and everything it started are over. */
function agentDismissable(agent, agents) {
  return !!agent.id && agentSubtree(agent, agents).every(a => !agentIsActive(a));
}
function dismissAgents(list) {
  const removed = removedAgentIds();
  list.forEach(a => { if (a.id) removed.add(a.id); });
  saveRemovedAgents();
  renderAgentsPanel();
}
function undismissAgents(list) {
  const removed = removedAgentIds();
  list.forEach(a => removed.delete(a.id));
  saveRemovedAgents();
  renderAgentsPanel();
}
/** The agents the map and its pill show: all but those taken off it, and what they started. */
function shownAgents(agents) {
  const removed = removedAgentIds();
  const hidden = new Set();
  agents.forEach(a => { if (a.id && (removed.has(a.id) || (a.parentId && hidden.has(a.parentId)))) hidden.add(a.id); });
  return agents.filter(a => !hidden.has(a.id));
}

function renderAgentsWin() {
  if (agentsView === 'task') {
    const t = activeTab();
    const task = bgTaskOf(t, agentsSelectedTask);
    if (task) {
      // Brought up to date where it stands while it is already showing, so the output
      // keeps its scroll position and the Stop button its state between the ticks.
      if (bgView && bgView.taskId === task.taskId && agentsWinEl.contains(bgView.meta)) updateTaskDetail(t, task);
      else { agentsWinEl.classList.remove('ap-transcript-mode', 'ap-map-mode'); agentsWinEl.innerHTML = ''; renderTaskDetail(t, task); }
      return;
    }
    agentsView = 'list';       // a task this tab no longer knows — back to the map
  }
  stopTaskOutputPoll();
  bgView = null;
  // The map is drawn again every second while something runs (startAgentsTicker); without
  // this, a map taller than the window would jump back to its top on each tick.
  const keptScroll = agentsView === 'list'
      ? [...agentsWinEl.querySelectorAll('.ap-list')].map(el => [el.scrollTop, el.scrollLeft]) : null;
  agentsWinEl.classList.remove('ap-transcript-mode', 'ap-map-mode');
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
  if (keptScroll && agentsView === 'list') {
    agentsWinEl.querySelectorAll('.ap-list').forEach((el, i) => {
      if (keptScroll[i]) { el.scrollTop = keptScroll[i][0]; el.scrollLeft = keptScroll[i][1]; }
    });
  }
}

function renderAgentsList(all) {
  const head = document.createElement('div'); head.className = 'ap-head';
  const title = document.createElement('span'); title.className = 'ap-title'; title.textContent = 'Agent map';
  const x = document.createElement('span'); x.className = 'ap-x'; x.innerHTML = ICONS.X;
  x.onclick = () => closeAgentsPanel();
  head.appendChild(title); head.appendChild(x);
  agentsWinEl.appendChild(head);

  // The map leaves out the agents the user has taken off it (and what they started).
  const agents = shownAgents(all);
  const removed = all.length - agents.length;
  const sub = document.createElement('div'); sub.className = 'ap-sub';
  const subText = document.createElement('span'); subText.className = 'ap-sub-text';
  subText.textContent = agents.length
      ? agents.length + ' agent' + (agents.length === 1 ? '' : 's') + ' · click an agent for details'
      : removed ? 'Every agent of this conversation has been removed from the map.'
      : 'No agents in this conversation yet.';
  sub.appendChild(subText);
  // Every finished one at once — the agents that ran before the ones just started, say.
  const finished = agents.filter(a => a.depth === 0 && agentDismissable(a, all));
  if (finished.length) {
    sub.appendChild(makeApSubLink('Clear finished', 'Remove the ' + finished.length + ' finished agent'
        + (finished.length === 1 ? '' : 's') + ' from the map',
        () => dismissAgents(finished.reduce((out, a) => out.concat(agentSubtree(a, all)), []))));
  }
  if (removed) {
    sub.appendChild(makeApSubLink('Show ' + removed + ' removed', 'Put the agents removed from the map back on it',
        () => undismissAgents(all)));
  }
  agentsWinEl.appendChild(sub);

  if (!agents.length) { renderBackgroundTasks(); return; }
  // A tree, as the extension's map draws it: the conversation itself on the left, the agents
  // it started to its right, each joined to it by a line, and any an agent started in turn to
  // the right of that one. The newest of the conversation's own come first — the one just
  // kicked off is what you're watching. On a narrow view the same tree stacks downwards
  // (panels.css), since side by side would leave each card too little room to say anything.
  agentsWinEl.classList.add('ap-map-mode');   // two columns of cards need more than the list's width
  const list = document.createElement('div'); list.className = 'ap-list ap-map';
  const root = makeApNode(makeApRootRow());
  root.appendChild(makeApChildren(agents.filter(a => a.depth === 0).reverse(), agents));
  list.appendChild(root);
  agentsWinEl.appendChild(list);
  renderBackgroundTasks();
}
/** One box of the map, with the boxes of what it started beside it. */
function makeApNode(row) {
  const node = document.createElement('div'); node.className = 'ap-node';
  node.appendChild(row);
  return node;
}
function makeApChildren(children, agents) {
  const col = document.createElement('div'); col.className = 'ap-children';
  children.forEach(a => {
    const node = makeApNode(makeApAgentRow(a, agents));
    const own = agents.filter(c => c.parentId === a.id);
    if (own.length) node.appendChild(makeApChildren(own, agents));
    col.appendChild(node);
  });
  return col;
}
function makeApRow(dotClass, labelText, metaText) {
  const row = document.createElement('div'); row.className = 'ap-row';
  row.title = labelText;   // the label is cut short with an ellipsis; this is the whole of it
  const dot = document.createElement('span'); dot.className = 'dot ' + dotClass;
  const body = document.createElement('div'); body.className = 'ap-body';
  const label = document.createElement('span'); label.className = 'ap-label';
  label.textContent = labelText;
  body.appendChild(label);
  if (metaText) {
    const meta = document.createElement('span'); meta.className = 'ap-step';
    meta.textContent = metaText;
    body.appendChild(meta);
  }
  row.appendChild(dot); row.appendChild(body);
  return row;
}
function makeApAgentRow(a, agents) {
  const row = makeApRow(agentDotClass(a), a.description, agentMeta(a));
  row.dataset.agentStatus = agentStatus(a);
  row.onclick = () => { agentsView = 'detail'; agentsSelectedId = a.id; renderAgentsWin(); };
  // Its own button, on the right of the card: Stop while it runs, and once it and every
  // agent it started are over, remove it from the map.
  if (agentStoppable(a)) {
    const stopping = agentStopping(a);
    const stop = makeApRowAction(ICONS.STOP, stopping ? 'Stopping…' : 'Stop agent', () => stopAgent(a));
    stop.classList.add('ap-act-stop');
    stop.disabled = stopping;
    if (stopping) row.classList.add('ap-stopping');
    row.appendChild(stop);
  } else if (agentDismissable(a, agents)) {
    row.appendChild(makeApRowAction(ICONS.X, 'Remove from the map', () => dismissAgents(agentSubtree(a, agents))));
  }
  return row;
}
function makeApRowAction(icon, label, onClick) {
  const b = document.createElement('button'); b.type = 'button'; b.className = 'ap-row-act';
  b.innerHTML = icon; b.title = label; b.setAttribute('aria-label', label);
  b.onclick = (e) => { e.stopPropagation(); onClick(); };   // not the card's own click as well
  return b;
}
function makeApSubLink(text, tip, onClick) {
  const l = document.createElement('span'); l.className = 'ap-sub-link';
  l.textContent = text; l.title = tip; l.onclick = onClick;
  return l;
}
/** The conversation the agents belong to, at the root of the map: its title, and the model it
 *  runs on with what its context holds, as the extension's says "Opus 5.5 · 178.8k tokens in
 *  context". Only the agents open a view of their own, so it is not a button. */
function makeApRootRow() {
  const t = activeTab();
  const row = makeApRow(t && t.streaming ? 'spin' : 'done', (t && t.title) || 'Claude Code', apRootMeta(t));
  row.classList.add('ap-root');
  return row;
}
function apRootMeta(t) {
  if (!t) return '';
  const parts = [modelLabelFor(t.model !== undefined ? t.model : curModel)];
  const tokens = apContextTokens(t);
  if (tokens > 0) parts.push(formatAgentTokens(tokens) + ' in context');
  return parts.join(' · ');
}
/** What the conversation's context held at its last finished turn: the prompt the CLI sent,
 *  counting what was read from or written to the cache — the same status the context ring
 *  (controls.js) is drawn from. 0 until a turn of this tab has finished. */
function apContextTokens(t) {
  const s = ctxRingByTab[t.id];
  if (!s) return 0;
  return (s.inputTokens || 0) + (s.cacheReadTokens || 0) + (s.cacheCreationTokens || 0);
}

/* ===================== Background tasks ===================== */

/** What each kind of background task is called where it has no description of its own. */
const BG_TASK_KINDS = {
  local_agent: 'subagent', local_workflow: 'workflow', local_bash: 'shell', monitor_mcp: 'monitor',
  monitor_ws: 'monitor', mcp_task: 'MCP task', in_process_teammate: 'teammate', dream: 'dream',
  auto_mode_scan: 'auto-mode scan', local_memory_import: 'memory import', remote_agent: 'cloud session',
};
const BG_ENDED_KEPT = 20;          // ended tasks remembered per tab, for their own view
const BG_OUTPUT_POLL_MS = 1000;    // how often a running shell's output is asked for
const BG_STOP_WAIT_MS = 5000;      // how long a task may stay listed after Stop before it is said to have failed

function bgKindName(task) { return BG_TASK_KINDS[task.taskType] || 'task'; }
function bgTitle(task) { return task.description || bgKindName(task); }
/** "shell · 12s": its kind, and how long it has run once that is a second or more. */
function bgMeta(task, now) {
  const ran = task.startedAt ? (task.endedAt || now) - task.startedAt : 0;
  return ran >= 1000 ? bgKindName(task) + ' · ' + formatAgentDuration(ran) : bgKindName(task);
}

/** The tab's whole list of background tasks, as the CLI gave it this time. A task gone from
 *  it has ended; it is remembered a while so its own view can still say how. */
function applyBackgroundTasks(list, t) {
  if (!t) return;
  const before = t.bgTasks || new Map();
  const now = new Map();
  (Array.isArray(list) ? list : []).forEach(x => {
    if (!x || !x.taskId) return;
    const had = before.get(x.taskId);
    now.set(x.taskId, { taskId: x.taskId, taskType: x.taskType || '', description: x.description || '',
        startedAt: had ? had.startedAt : Date.now(), stopping: had ? had.stopping : 0 });
  });
  before.forEach((task, id) => {
    if (now.has(id)) return;
    if (!t.bgEnded) t.bgEnded = new Map();
    t.bgEnded.set(id, Object.assign({}, task, { endedAt: Date.now(), stopping: 0 }));
    if (t.bgEnded.size > BG_ENDED_KEPT) t.bgEnded.delete(t.bgEnded.keys().next().value);
  });
  t.bgTasks = now;
  if (window.renderAgentsPanel) window.renderAgentsPanel();
}
function bgTasksOf(t) { return t && t.bgTasks ? [...t.bgTasks.values()] : []; }
function bgTaskOf(t, id) {
  if (!t || !id) return null;
  return (t.bgTasks && t.bgTasks.get(id)) || (t.bgEnded && t.bgEnded.get(id)) || null;
}
/** How an ended task ended, from the CLI's own task_notification for it (chat.rs relays it
 *  to the log of the tool call that started it, with the task's id from task_started). */
function bgOutcome(task) {
  for (const log of agentLogs.values()) {
    if (log.taskId !== task.taskId) continue;
    if (log.finishStatus === 'stopped' || log.finishStatus === 'killed') return 'Stopped';
    if (log.finishStatus === 'failed') return 'Failed';
    if (log.finishStatus) return 'Finished';
  }
  return 'Ended';
}
function bgDotClass(task, running) {
  if (running) return 'spin';
  const how = bgOutcome(task);
  return how === 'Stopped' || how === 'Failed' ? 'red' : 'done';
}

/** The list under the agents: one row per task running in this tab. */
function renderBackgroundTasks() {
  const tasks = bgTasksOf(activeTab());
  if (!tasks.length) return;
  const head = document.createElement('div'); head.className = 'ap-sub ap-tasks-head';
  head.textContent = tasks.length + ' background task' + (tasks.length === 1 ? '' : 's') + ' · click a task for details';
  agentsWinEl.appendChild(head);
  const list = document.createElement('div'); list.className = 'ap-list ap-tasks';
  const now = Date.now();
  tasks.forEach(task => {
    const row = document.createElement('div'); row.className = 'ap-row ap-task-row';
    row.dataset.taskType = task.taskType;
    row.title = bgTitle(task);
    row.onclick = () => { agentsView = 'task'; agentsSelectedTask = task.taskId; renderAgentsWin(); };
    const dot = document.createElement('span'); dot.className = 'dot spin';
    const body = document.createElement('div'); body.className = 'ap-body';
    const label = document.createElement('span'); label.className = 'ap-label'; label.textContent = bgTitle(task);
    const meta = document.createElement('span'); meta.className = 'ap-step'; meta.textContent = bgMeta(task, now);
    body.appendChild(label); body.appendChild(meta);
    row.appendChild(dot); row.appendChild(body);
    list.appendChild(row);
  });
  agentsWinEl.appendChild(list);
}

/** The parts of the task view that change while it shows, kept to bring them up to date. */
let bgView = null;
let bgPoll = null;

function renderTaskDetail(t, task) {
  const running = !!(t.bgTasks && t.bgTasks.has(task.taskId));
  const head = document.createElement('div'); head.className = 'ap-head ap-detail-head';
  const back = document.createElement('span'); back.className = 'ap-back'; back.innerHTML = ICONS.CHEVRON;
  back.onclick = () => { agentsView = 'list'; renderAgentsWin(); };
  const dot = document.createElement('span');
  const title = document.createElement('span'); title.className = 'ap-title ap-detail-title'; title.textContent = bgTitle(task);
  const x = document.createElement('span'); x.className = 'ap-x'; x.innerHTML = ICONS.X;
  x.onclick = () => closeAgentsPanel();
  head.appendChild(back); head.appendChild(dot); head.appendChild(title); head.appendChild(x);
  agentsWinEl.appendChild(head);

  const body = document.createElement('div'); body.className = 'ap-detail-body';
  agentsWinEl.appendChild(body);
  const meta = document.createElement('div'); meta.className = 'ap-meta';
  body.appendChild(meta);

  let output = null;
  if (task.taskType === 'local_bash') {
    output = document.createElement('div'); output.className = 'ap-fold open ap-output-fold';
    const fh = document.createElement('div'); fh.className = 'ap-fold-head';
    fh.innerHTML = '<span class="chev">' + ICONS.CHEVRON + '</span><span>Output</span>';
    const fb = document.createElement('div'); fb.className = 'ap-fold-body';
    fh.onclick = () => { output.classList.toggle('open'); if (output.classList.contains('open')) startTaskOutputPoll(t, task.taskId); };
    output.appendChild(fh); output.appendChild(fb);
    body.appendChild(output);
  }

  const btnRow = document.createElement('div'); btnRow.className = 'ap-btn-row';
  const stop = document.createElement('button'); stop.type = 'button'; stop.className = 'ap-stop-btn';
  stop.onclick = () => {
    const live = t.bgTasks && t.bgTasks.get(task.taskId);
    if (!live || !window._stopAgentTask) return;
    live.stopping = Date.now();
    window._stopAgentTask(t.id, task.taskId);
    updateTaskDetail(t, live);
    // Still listed once the CLI has had time to end it: it could not be. (Each new list
    // makes new entries, so it is looked up again by its id.)
    setTimeout(() => {
      const still = t.bgTasks && t.bgTasks.get(task.taskId);
      if (still && still.stopping > 0) { still.stopping = -1; if (agentsOverlayEl.classList.contains('open')) renderAgentsWin(); }
    }, BG_STOP_WAIT_MS);
  };
  btnRow.appendChild(stop);
  body.appendChild(btnRow);
  const failure = document.createElement('div'); failure.className = 'ap-meta ap-failure';
  failure.textContent = 'The task could not be stopped. It may have finished already.';
  body.appendChild(failure);

  bgView = { taskId: task.taskId, dot, meta, output, btnRow, stop, failure, stick: true, shown: null };
  updateTaskDetail(t, task);
  if (running && output) startTaskOutputPoll(t, task.taskId);
  else if (output) startTaskOutputPoll(t, task.taskId, true);
}

function updateTaskDetail(t, task) {
  const v = bgView; if (!v) return;
  const running = !!(t.bgTasks && t.bgTasks.has(task.taskId));
  const stopping = running && task.stopping > 0;
  v.dot.className = 'dot ' + (stopping ? 'spin' : bgDotClass(task, running));
  const status = stopping ? 'Stopping…' : running ? 'Running' : bgOutcome(task);
  v.meta.textContent = status + ' · ' + bgMeta(task, Date.now());
  v.btnRow.style.display = running ? '' : 'none';
  v.stop.disabled = stopping;
  v.stop.textContent = stopping ? 'Stopping…' : 'Stop';
  v.failure.style.display = running && task.stopping === -1 ? '' : 'none';
  if (v.output) drawTaskOutput(t, task, running);
}

/** A shell's output as the CLI keeps it: when only its end is sent, the cut first line goes;
 *  colour codes go; a line a program kept rewriting (a progress bar) shows what it ended on. */
function bgOutputText(output, truncated) {
  let s = String(output || '');
  if (truncated) {
    const nl = s.indexOf('\n');
    s = (nl !== -1 && nl < s.length - 1) ? s.slice(nl + 1) : s.replace(/^�+/, '');
  }
  s = s.replace(/\x1b\[[0-9;?]*[ -\/]*[@-~]/g, '');
  return s.split('\n').map(line => {
    const l = line.endsWith('\r') ? line.slice(0, -1) : line;
    return l.slice(l.lastIndexOf('\r') + 1);
  }).join('\n');
}
function bgBytes(n) {
  const kb = n / 1024;
  if (kb < 1) return n + ' bytes';
  const fmt = (v, u) => v.toFixed(1).replace(/\.0$/, '') + u;
  if (kb < 1024) return fmt(kb, 'KB');
  const mb = kb / 1024;
  return mb < 1024 ? fmt(mb, 'MB') : fmt(mb / 1024, 'GB');
}
function drawTaskOutput(t, task, running) {
  const v = bgView;
  const fb = v.output.querySelector('.ap-fold-body');
  const got = t.bgOutput && t.bgOutput.get(task.taskId);
  const key = got ? (got.error ? 'error' : got.text + '|' + got.truncated + '|' + got.totalBytes) : 'none';
  if (v.shown === key + '|' + running) return;    // nothing new: leave the scroll where it is
  v.shown = key + '|' + running;
  const had = fb.querySelector('pre.ap-output');
  if (had && got && !got.error && got.text !== '') {
    // More of the same output: written into the box already there, which keeps where the
    // reader has scrolled to.
    const top = had.scrollTop;
    had.textContent = got.text;
    had.scrollTop = v.stick ? had.scrollHeight : top;
    const note = fb.querySelector('.ap-meta');
    if (note) note.remove();
    if (got.truncated) fb.appendChild(apNote('Showing the end of ' + bgBytes(got.totalBytes) + ' of output.'));
    return;
  }
  fb.innerHTML = '';
  if (!got) return;
  if (got.error) { fb.appendChild(apNote('Output is not available.')); return; }
  if (got.text === '') { fb.appendChild(apNote(running ? 'No output yet.' : 'No output.')); return; }
  const pre = document.createElement('pre'); pre.className = 'ap-prompt ap-output'; pre.tabIndex = 0;
  pre.textContent = got.text;
  // Follows the end like a terminal, until scrolled up; back at the end, it follows again.
  pre.onscroll = () => { v.stick = pre.scrollHeight - pre.scrollTop - pre.clientHeight < 4; };
  fb.appendChild(pre);
  if (v.stick) pre.scrollTop = pre.scrollHeight;
  if (got.truncated) fb.appendChild(apNote('Showing the end of ' + bgBytes(got.totalBytes) + ' of output.'));
}
function apNote(text) { const d = document.createElement('div'); d.className = 'ap-meta'; d.textContent = text; return d; }

/** Asks the CLI for the shell's output now, and again each second while it runs and its view
 *  is open with Output unfolded. `once`: an ended task's, asked for one time. */
function startTaskOutputPoll(t, taskId, once) {
  stopTaskOutputPoll();
  const ask = () => {
    bgPoll = null;
    if (!bgView || bgView.taskId !== taskId || !bgView.output || !bgView.output.classList.contains('open')) return;
    if (typeof cliAsk !== 'function') return;
    cliAsk(t, 'get_task_output', { task_id: taskId }).then((r) => {
      if (!t.bgOutput) t.bgOutput = new Map();
      t.bgOutput.set(taskId, { text: bgOutputText(r && r.output, !!(r && r.truncated)),
          totalBytes: (r && r.total_bytes) || 0, truncated: !!(r && r.truncated) });
    }, () => {
      if (!t.bgOutput) t.bgOutput = new Map();
      const had = t.bgOutput.get(taskId);
      if (!had || had.error) t.bgOutput.set(taskId, { error: true });   // keep what was shown before
    }).then(() => {
      const task = bgTaskOf(t, taskId);
      if (!task || !bgView || bgView.taskId !== taskId) return;
      updateTaskDetail(t, task);
      const running = !!(t.bgTasks && t.bgTasks.has(taskId));
      if (running && !once) bgPoll = setTimeout(ask, BG_OUTPUT_POLL_MS);
      // Ended between two asks: its last words are asked for once more.
      else if (!once) bgPoll = setTimeout(() => startTaskOutputPoll(t, taskId, true), 0);
    });
  };
  ask();
}
function stopTaskOutputPoll() { if (bgPoll) { clearTimeout(bgPoll); bgPoll = null; } }

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

/** The last part of a path, whichever way its separators lean. */
function agentBaseName(p) { return String(p || '').split(/[\\/]/).pop(); }
/** What a tool call in an agent's "Tool calls" list says, as the extension words it: the
 *  tool's name, and after it what it was doing, one line. `{name, detail}`; detail is ''
 *  when the call has nothing more to say. Read from the call's own input, which is all an
 *  agent's log keeps of it. */
function agentToolHeader(rawName, rawInput) {
  const input = rawInput && typeof rawInput === 'object' ? rawInput : {};
  const raw = String(rawName || '');
  const mcp = raw.indexOf('mcp__') === 0;
  const key = (mcp ? raw.split('__').pop() : raw).toLowerCase();
  const label = toolLabel(rawName);
  const named = (name, detail) => ({ name: name, detail: detail || '' });
  const file = (p) => agentBaseName(p);
  switch (key) {
    case 'bash':
      return named(label, input.description);
    case 'read': {
      const from = Number(input.offset), count = Number(input.limit);
      let range = '';
      if (input.offset !== undefined && input.limit !== undefined) range = ' (lines ' + (from + 1) + '-' + (from + count) + ')';
      else if (input.offset !== undefined) range = ' (from line ' + (from + 1) + ')';
      const f = file(input.file_path);
      return named(label, f ? f + range : '');
    }
    case 'edit': case 'multiedit': case 'write':
      return named(label, file(input.file_path));
    case 'notebookedit': {
      const f = file(input.notebook_path);
      return named('Edit Notebook Cell', f ? f + (input.cell_id ? ':' + input.cell_id : '') : '');
    }
    case 'grep': {
      const scope = [];
      if (input.path) scope.push('in ' + input.path);
      if (input.glob) scope.push('glob: ' + input.glob);
      if (input.type) scope.push('type: ' + input.type);
      return named('Grep', '"' + (input.pattern || '') + '"' + (scope.length ? ' (' + scope.join(', ') + ')' : ''));
    }
    case 'glob':
      return named('Glob', 'pattern: "' + (input.pattern || '') + '"');
    case 'webfetch':
      return named('Web Fetch', input.url);
    case 'websearch':
      return named('Web Search', input.query);
    case 'agent': case 'task':
      return named('Agent:', input.description);
    case 'todowrite':
      return named('Update Todos', '');
    case 'toolsearch':
      return named('Search tools', '"' + (input.query || '…') + '"');
    case 'skill':
      return named(String(input.skill || '').replace(/^\//, '') || label, input.skill ? 'skill' : '');
    case 'askuserquestion':
      return named(Array.isArray(input.questions) && input.questions.length === 1 ? 'Question' : 'Questions', '');
    case 'taskoutput':
      return named(label, input.task_id ? 'task: "' + input.task_id + '"' : '');
    default:
      return named(mcp && mcpServerName(rawName) ? mcpServerName(rawName) + ' › ' + label : label, '');
  }
}

/** The agent's type: what the CLI said at its start, else what its Agent call asked for. */
function agentTypeOf(agent) {
  return agent.log.subagentType || agent.type || (agent.log.input && agent.log.input.subagent_type) || '';
}
/** Whether it runs in the background: the CLI's own word at its start, else what its call
 *  asked for. undefined when neither says, and then the line says neither. */
function agentIsBackgrounded(agent) {
  if (typeof agent.log.isBackgrounded === 'boolean') return agent.log.isBackgrounded;
  const input = agent.log.input;
  if (input && typeof input.run_in_background === 'boolean') return input.run_in_background;
  return undefined;
}
/** The model it ran on, under the name the model chooser gives it, not the raw id. */
function agentModelLabel(agent) {
  const id = agent.log.model || (agent.log.input && agent.log.input.model) || '';
  return id ? modelLabelFor(id) : '';
}
/** The line under the head that says what a working or waiting agent is up to, as the
 *  extension's does: "Waiting for your permission to run Bash. Answer it in the session's
 *  permission card.", or "Now" and the call it is in the middle of. null for any other state. */
function agentActivityLine(agent, status) {
  if (status !== 'waiting' && status !== 'working') return null;
  const el = document.createElement('div'); el.className = 'ap-meta ap-activity';
  if (status === 'waiting') {
    const ask = agentWaitingAsk(agent);
    el.textContent = 'Waiting for your permission' + (ask && ask.toolName ? ' to run ' + ask.toolName : '')
        + ". Answer it in the session's permission card.";
    return el;
  }
  const open = agent.log.items.filter(it => it.kind === 'tool' && it.status === undefined).pop();
  if (!open) return null;
  const h = agentToolHeader(open.name, open.input);
  el.textContent = 'Now ' + h.name + (h.detail ? ' ' + h.detail : '');
  return el;
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

  const status = agentStatus(agent);
  const parts = [AGENT_STATUS_LABEL[status]];
  const type = agentTypeOf(agent);
  if (type) parts.push(type);
  const model = agentModelLabel(agent);
  if (model) parts.push(model);
  const bg = agentIsBackgrounded(agent);
  if (bg !== undefined) parts.push(bg ? 'background' : 'foreground');
  const meta = document.createElement('div'); meta.className = 'ap-meta'; meta.textContent = parts.join(' · ');
  body.appendChild(meta);

  // Who started it: the agent whose own tool call it was, or the main agent when it is a
  // call of the conversation itself.
  const parent = agent.parentId ? collectAgents().find(a => a.id === agent.parentId) : null;
  const spawned = document.createElement('div'); spawned.className = 'ap-meta';
  spawned.textContent = 'Spawned by ' + (parent ? parent.description : 'the main agent');
  body.appendChild(spawned);

  // What it is doing now: held up on a permission, or the call it is in the middle of.
  const doing = agentActivityLine(agent, status);
  if (doing) body.appendChild(doing);

  const time = document.createElement('div'); time.className = 'ap-meta';
  time.textContent = agentMeta(agent);
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
    // First, not last: it is sticky (panels.css's #agents-win .ap-response .copy-btn), and a
    // sticky box only pins while scrolling PAST where it sits — at the end it would never pin.
    respBody.prepend(makeCopyBtn(() => response));
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
    const live = agentIsActive(agent);
    toolCalls.forEach(it => {
      const row = document.createElement('div'); row.className = 'ap-row ap-toolrow';
      // Running only while the agent is and the call has no result yet, as in the extension:
      // a finished call has no marker, and one a finished agent never heard back on is not
      // "running". A failed call keeps its red dot, which says more than the extension does.
      const dot = document.createElement('span');
      const ask = agentWaitingAsk(agent);
      if (it.status === 'interrupted') dot.className = 'dot red';
      else if (it.status === undefined && ask && ask.toolUseId === it.id) dot.className = 'dot wait';
      else if (it.status === undefined && live) dot.className = 'dot spin';
      else dot.className = 'ap-dot-space';
      const h = agentToolHeader(it.name, it.input);
      const name = document.createElement('span'); name.className = 'ap-tname'; name.textContent = h.name;
      row.appendChild(dot); row.appendChild(name);
      if (h.detail) {
        const detail = document.createElement('span'); detail.className = 'ap-tdetail'; detail.textContent = h.detail;
        row.appendChild(detail);
      }
      row.title = h.detail ? h.name + ' ' + h.detail : h.name;
      foldBody.appendChild(row);
    });
  }));

  const btnRow = document.createElement('div'); btnRow.className = 'ap-btn-row';
  const openBtn = document.createElement('button'); openBtn.type = 'button'; openBtn.className = 'ap-open-btn';
  openBtn.textContent = 'Open transcript';
  openBtn.onclick = () => { agentsView = 'transcript'; renderAgentsWin(); };
  btnRow.appendChild(openBtn);

  // Stop while it runs (agentStoppable: only once the CLI has given it the task id stop_task
  // names it by). The view is drawn again each second while it runs, so whether a stop was
  // asked for is read from its log (agentStopping), not kept on the button.
  const all = collectAgents();
  if (agentStoppable(agent)) {
    const stopping = agentStopping(agent);
    const stopBtn = document.createElement('button'); stopBtn.type = 'button'; stopBtn.className = 'ap-stop-btn';
    stopBtn.textContent = stopping ? 'Stopping…' : 'Stop agent';
    stopBtn.disabled = stopping;
    stopBtn.onclick = () => stopAgent(agent);
    btnRow.appendChild(stopBtn);
  } else if (agentDismissable(agent, all)) {
    // Over, and looked at: off the map, back to it.
    const rmBtn = document.createElement('button'); rmBtn.type = 'button'; rmBtn.className = 'ap-open-btn';
    rmBtn.textContent = 'Remove from map';
    rmBtn.title = 'Take this agent' + (agentSubtree(agent, all).length > 1 ? ' and the agents it started' : '')
        + ' off the map. The conversation keeps it.';
    rmBtn.onclick = () => { agentsView = 'list'; agentsSelectedId = null; dismissAgents(agentSubtree(agent, all)); };
    btnRow.appendChild(rmBtn);
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
  // As many as the map shows: the ones taken off it no longer count, and with all of them
  // gone the pill goes too (the map stays open to /agents, which can put them back).
  const agents = shownAgents(collectAgents());
  const count = document.getElementById('agents-count');
  const text = agents.length ? agents.length + ' agent' + (agents.length === 1 ? '' : 's') : '';
  // The pill coming, going or changing width is a control more or fewer in the composer
  // bar, so the bar's narrow-width collapse (controls.js) has to be worked out again.
  const changed = (btn.style.display !== 'none') !== !!agents.length || (count && count.textContent !== text);
  btn.style.display = agents.length ? '' : 'none';
  if (count) count.textContent = text;
  if (agents.length) {
    const dot = document.getElementById('agents-dot');
    const state = agentsOverallState(agents);
    if (dot) dot.dataset.state = state;
    btn.title = AGENTS_PILL_TITLE[state];
  }
  if (changed && typeof fitComposerBar === 'function') fitComposerBar();
}
window.updateAgentsBtn = updateAgentsBtn;

// Duration only actually CHANGES when chat.js pokes renderAgentsPanel on a real event
// (a new step starting/finishing) — between those, a still-running agent's shown time
// was frozen at whatever it was on the last one, which reads as "stuck"/broken for
// anything slower than rapid-fire tool calls. This ticks it forward once a second like
// an actual stopwatch, same as any other live timer, for as long as something is running.
let agentsTicker = null;
function agentsHasRunning() {
  return collectAgents().some(agentIsActive) || bgTasksOf(activeTab()).length > 0;
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
  stopTaskOutputPoll();
  bgView = null;
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
  else if (agentsView === 'detail' || agentsView === 'task') { agentsView = 'list'; renderAgentsWin(); }
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

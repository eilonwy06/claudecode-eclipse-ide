/* cards.js — CLI-enforced permission decision card + AskUserQuestion card. */

/* The CLI's own wording for "rejected, and here is what the user wants instead",
   copied verbatim from the claude binary (verified byte-for-byte against
   bin/claude.exe — note it ends with a NEWLINE, not a space; the user's text goes
   on the following line). Using the CLI's phrasing is what makes
   the model treat the tail as the user speaking instead of as anomalous tool
   output, and the explicit rejection clause stops it narrating an edit that never
   happened (#98). The CLI's other variant ends "STOP what you are doing and wait
   for the user to tell you how to proceed." — that one is for a deny with no
   instruction, and it is what the CLI already sends when this text is empty. */
const DENY_WITH_INSTRUCTION =
  "The user doesn't want to proceed with this tool use. The tool use was rejected " +
  "(eg. if it was a file edit, the new_string was NOT written to the file). " +
  "To tell you how to proceed, the user said:\n";

/* Identifies the ONE middle-option label that means "switch this session to
   acceptEdits". The label is built by primary_suggestion() in chat.rs, which is
   the only thing that crosses the bridge — the suggestion's type does not — so
   the wording is the signal. Its other branches are deliberately excluded:
     "Yes, allow '<rule>' …"      addRules      — scoped to one tool/path
     "Yes, allow all <Tool> …"    addRules      — scoped to one tool
     "Yes, allow edits in <dir> …" addDirectories — scoped to one directory
     "Yes, switch to <m> mode …"  setMode, but not acceptEdits
   Flipping the indicator (and therefore the next spawn's --permission-mode) to
   acceptEdits for any of those would hand the session BROADER permission than
   the user agreed to on the card. Keep this in step with primary_suggestion. */
const ACCEPTS_ALL_EDITS = /^Yes, allow all edits\b/;

/* ---- plan approval (ExitPlanMode) ----
   The CLI asks for ExitPlanMode like any other tool — `checkPermissions` returns
   a bare {behavior:"ask", message:"Exit plan mode?"} with NO permission_suggestions,
   so this card's three options cannot be derived from the wire the way an edit's
   middle option is; they are synthesised here to match the CLI's own dialog.

   Plan mode is ONE SHOT: approving ends it. The CLI then exits to the mode it
   remembered as `prePlanMode`, which is NOT necessarily the one picked here — so
   the choice is pushed explicitly with set_permission_mode after the allow. */
const PLAN_TOOL = 'ExitPlanMode';
/** decision value → permission mode the user asked for by choosing it. */
const PLAN_MODE_FOR = { allowAuto: 'acceptEdits', allowManual: 'default' };
/* The CLI's own wording for a rejected plan, copied byte-exact from claude.exe
   (its `dmn`, up to the "\n\nRejected plan:\n" slot, which we can't fill — the
   plan text isn't on this wire). NOT DENY_WITH_INSTRUCTION: that one asserts a
   file edit was skipped, which is wrong here and would drop edit-rejection
   language into a planning conversation. */
const PLAN_REJECTED =
  "The agent proposed a plan that was rejected by the user. The user chose to stay " +
  "in plan mode rather than proceed with implementation.\n";
/* The tail fragment of the CLI's deny-with-instruction variant, byte-exact and on
   its own — generic enough to carry "here is what to change" feedback without the
   file-edit clause that precedes it there. The blank-line separator is added at
   the join site (below), NOT baked in here, so this stays greppable in claude.exe. */
const PLAN_FEEDBACK = "To tell you how to proceed, the user said:\n";

/* ---- permission decision card (claude --permission-prompt-tool) ---- */
/**
 * Permission decision card (CLI can_use_tool). Blocks the CLI until _decide(reqId,…).
 * @type {(tabId: string, reqId: string, toolName: string, detail: string,
 *         rememberLabel: string) => void} rememberLabel "" = no middle remember option
 */
window.onApprovalRequest = function(tabId, reqId, toolName, detail, rememberLabel) {
  const t = tabById(tabId);
  if (t && t.cancelled) { if (window._decide) window._decide(reqId, 'deny', ''); return; }  // stopped: auto-deny, no card
  if (t) loadRender(t);   // card belongs to this conversation
  const pane = streamPane() || (activeTab() ? activeTab().pane : null);
  if (!pane) { if (window._decide) window._decide(reqId, 'deny', ''); return; }
  hideWorking();
  // The tool line awaiting this decision is the last one in the turn. Keep its dot
  // GRAY while the card is open (mark .pending so markToolsDone skips it); decide()
  // flips it green on allow / red on deny.
  const pendingTool = lastToolLine(curTurn);
  if (pendingTool) pendingTool.classList.add('pending');
  // Keyed on the tool NAME alone — never on "has no suggestions", which plenty of
  // ordinary asks also satisfy and which would give them plan options.
  const isPlan = (toolName === PLAN_TOOL);
  const isEdit = !isPlan && /edit|write|multiedit|str_replace|notebook|create/i.test(toolName || '');
  const card = document.createElement('div'); card.className = 'decision';

  const q = document.createElement('div'); q.className = 'dec-q';
  if (isPlan) {
    q.textContent = 'Accept this plan?';
  } else if (isEdit && detail) {
    q.appendChild(document.createTextNode('Make this edit to '));
    const c = document.createElement('code'); c.textContent = detail; q.appendChild(c);
    q.appendChild(document.createTextNode('?'));
  } else if (isEdit) {
    q.textContent = 'Make this edit?';
  } else {
    q.appendChild(document.createTextNode('Allow ' + (toolName || 'this action') + (detail ? ' ' : '')));
    if (detail) { const c = document.createElement('code'); c.textContent = detail; q.appendChild(c); }
    q.appendChild(document.createTextNode('?'));
  }
  card.appendChild(q);

  let resolved = false;
  function decide(d, msg) {
    if (resolved) return; resolved = true;
    document.removeEventListener('keydown', onKey, true);
    // Resolve the pending tool's dot: allow → green (finished), deny → red (rejected).
    if (pendingTool) {
      pendingTool.classList.remove('pending');
      const dot = pendingTool.querySelector('.dot');
      if (dot) dot.className = (d === 'deny') ? 'dot red' : 'dot done';
      // The plan line states the OUTCOME, not a diff summary — a green
      // "Claude's Plan … User approved the plan" reads very differently from a
      // red "… Stayed in plan mode", and the dot alone doesn't say which.
      if (isPlan) {
        const sub = document.createElement('div'); sub.className = 'tool-sub';
        sub.textContent = planOutcomeText(d === 'deny');   // shared with the reload path
        pendingTool.appendChild(sub);
      }
    }
    // Bare user prose must never be the deny message on its own: the CLI hands a
    // deny message to the model as an is_error tool_result, so unframed prose there
    // reads as anomalous/injected output, and the model — told only that the tool
    // failed — would narrate success for an edit that never ran (#98).
    //
    // The CLI itself has a canonical wrapper for exactly this case. Prefixing the
    // user's words with DENY_WITH_INSTRUCTION reuses the CLI's own wording, so the
    // model reads the tail as the user speaking rather than as stray tool output.
    // The alternative (deny + a separately queued user message) costs an extra turn
    // and produces a dead-end "the edit was declined" reply before the real one,
    // because the model answers the deny before the queued text arrives.
    //
    // Wire path: Java completes "deny" + text, and chat.rs's `decision.len() > 4`
    // slices the text back out as the deny message — the same channel as before,
    // now carrying CLI-authored framing. JS-only; no DLL rebuild.
    let out = '';
    if (d === 'deny' && isPlan) out = msg ? PLAN_REJECTED + '\n' + PLAN_FEEDBACK + msg : PLAN_REJECTED;
    else if (d === 'deny' && msg) out = DENY_WITH_INSTRUCTION + msg;
    if (window._decide) window._decide(reqId, d, out);
    // Approving the plan ENDS plan mode, so the indicator has to move off "Plan"
    // — and to the mode the user actually chose, which the CLI won't do for us
    // (it restores its own prePlanMode). Push it only AFTER the allow is written,
    // or the control request races the CLI's own transition.
    if (isPlan && PLAN_MODE_FOR[d]) {
      const mode = PLAN_MODE_FOR[d];
      if (t && window._setPermissionMode) {
        try { window._setPermissionMode(t.id, mode); } catch (e) {}
      }
      if (typeof adoptModeForTab === 'function') adoptModeForTab(t, mode);
    }
    // "Yes, allow all edits …" IS a mode switch: chat.rs echoes the CLI's setMode
    // suggestion back as updatedPermissions, so from here on the session behaves
    // exactly like "Edit automatically". Move the indicator to match, or it keeps
    // claiming "Manual" while nothing prompts again.
    // NOT while the tab is in Plan. There, "allow all edits this session" answers
    // the pending edit — it is not a request to leave planning. Adopting it anyway
    // set t.permMode='acceptEdits', so the next message respawned out of plan mode,
    // and the CLI then remembered prePlanMode==='acceptEdits', which SUPPRESSES the
    // setMode suggestion on every later card (the missing middle option). Leaving
    // t.permMode alone keeps Plan sticky exactly as it was before this flip existed.
    if (d === 'allowRemember' && ACCEPTS_ALL_EDITS.test(rememberLabel || '')
        && !(t && t.permMode === 'plan')
        && typeof adoptModeForTab === 'function') {
      adoptModeForTab(t, 'acceptEdits');
    }
    clearBottomCard();                          // card disappears — composer returns
    if (d === 'deny' && msg) {
      // Keep the "User answered:" card: this is a decision the user made on a card,
      // and it reads as such. (A reload replays it from the transcript as a plain
      // user line, since that is what it is on the wire.)
      addAnswered(msg, pane);
      startFreshTurn();
    } else {
      // Process continues after the decision — start a fresh body below the edits
      // and re-show the working indicator so the user sees activity again.
      finalizeThink(); curBody = null; curText = '';
      showWorking();
    }
    scrollBottom();
  }

  // Middle "remember" option is contextual: its label comes from the CLI's own
  // permission_suggestion (edits→"all edits", Bash→the command, etc.), and is
  // omitted entirely when the CLI offers no suggestion for this tool.
  const opts = isPlan
    ? [['allowAuto',   'Yes, and auto-accept'],
       ['allowManual', 'Yes, and manually approve edits'],
       ['deny',        'No, keep planning']]
    : [['allow', 'Yes']];
  if (!isPlan && rememberLabel) opts.push(['allowRemember', rememberLabel]);
  if (!isPlan) opts.push(['deny', 'No']);
  opts.forEach(([d, label], i) => {
    const opt = document.createElement('div'); opt.className = 'dec-opt' + (i === 0 ? ' sel' : '');
    opt.setAttribute('data-d', d);
    opt.innerHTML = '<span class="num"></span><span class="dec-lbl"></span>';
    opt.querySelector('.num').textContent = (i + 1);
    opt.querySelector('.dec-lbl').textContent = label;
    opt.onclick = () => decide(d);
    opt.onmouseenter = () => card.querySelectorAll('.dec-opt.sel').forEach(e => e.classList.remove('sel'));
    card.appendChild(opt);
  });

  const insteadNum = opts.length + 1;
  const instead = document.createElement('div'); instead.className = 'dec-instead';
  instead.innerHTML = '<span class="num">' + insteadNum + '</span>';
  const inp = document.createElement('input'); inp.type = 'text';
  inp.placeholder = isPlan ? 'Tell Claude what to change' : 'Tell Claude what to do instead';
  inp.onkeydown = (e) => {
    e.stopPropagation();
    if (e.key === 'Enter') { e.preventDefault(); const v = inp.value.trim(); if (v) decide('deny', v); }
    else if (e.key === 'Escape') { e.preventDefault(); decide('deny', ''); }
  };
  instead.appendChild(inp); card.appendChild(instead);

  const esc = document.createElement('div'); esc.className = 'dec-esc'; esc.textContent = 'Esc to cancel';
  card.appendChild(esc);

  // Number-key shortcuts map to the visible options in order; Esc cancels.
  function onKey(e) {
    if (resolved || document.activeElement === inp) return;
    if (e.key === 'Escape') { e.preventDefault(); decide('deny', ''); return; }
    const n = parseInt(e.key, 10);
    if (n >= 1 && n <= opts.length) { e.preventDefault(); decide(opts[n - 1][0], ''); }
  }
  document.addEventListener('keydown', onKey, true);

  showBottomCard(card);
};

/* ---- AskUserQuestion card (single/multi question, options + Other) ---- */
/**
 * AskUserQuestion card. questionsJson is a serialized {@link AskQuestion} array;
 * answers go back via _answerQuestion(reqId, json) ("[]" = dismissed).
 * @typedef {{question: string, header?: string, multiSelect?: boolean,
 *            options: {label: string, description?: string}[]}} AskQuestion
 * @type {(tabId: string, reqId: string, questionsJson: string) => void}
 */
window.onAskQuestion = function(tabId, reqId, questionsJson) {
  const t = tabById(tabId);
  if (t && t.cancelled) { if (window._answerQuestion) window._answerQuestion(reqId, '[]'); return; }  // stopped: no card
  if (t) loadRender(t);   // card belongs to this conversation
  const pane = streamPane() || (activeTab() ? activeTab().pane : null);
  let questions = [];
  try { questions = JSON.parse(questionsJson) || []; } catch (e) {}
  if (!pane || !questions.length) { if (window._answerQuestion) window._answerQuestion(reqId, '[]'); return; }
  hideWorking();
  // Keep the Asking tool's dot GRAY while the card is open; answered → green, dismissed → red.
  const pendingTool = lastToolLine(curTurn);
  if (pendingTool) pendingTool.classList.add('pending');
  const resolveDot = (rejected) => {
    if (!pendingTool) return;
    pendingTool.classList.remove('pending');
    const dot = pendingTool.querySelector('.dot');
    if (dot) dot.className = rejected ? 'dot red' : 'dot done';
  };

  const state = questions.map(() => ({ choice: null, other: '' })); // choice = option index or 'other'
  let activeQ = 0, resolved = false;
  const card = document.createElement('div'); card.className = 'question-card';

  function answeredText(i) {
    const st = state[i], q = questions[i];
    // No "[User typed]: " prefix — this is a legitimate answer on the allow path
    // (updatedInput.answers). The prefix leaked into the visible bubble and made
    // the model read the answer as anomalous tool output (issue #98).
    if (st.choice === 'other') return st.other.trim();
    if (st.choice != null && q.options[st.choice]) return q.options[st.choice].label;
    return '';
  }
  function allAnswered() {
    return state.every(st => st.choice === 'other' ? !!st.other.trim() : st.choice != null);
  }
  function finish() {
    if (resolved || !allAnswered()) return; resolved = true;
    document.removeEventListener('keydown', onKey, true);
    resolveDot(false);
    const answers = questions.map((q, i) => ({
      header: q.header || q.question || ('Question ' + (i + 1)),
      question: q.question || '', answer: answeredText(i)
    }));
    if (window._answerQuestion) window._answerQuestion(reqId, JSON.stringify(answers));
    clearBottomCard();
    const summary = answers.map(a => questions.length > 1 ? (a.header + ': ' + a.answer) : a.answer).join('\n');
    addAnswered(summary, pane); startFreshTurn(); scrollBottom();
  }
  function cancel() {
    if (resolved) return; resolved = true;
    document.removeEventListener('keydown', onKey, true);
    resolveDot(true);
    if (window._answerQuestion) window._answerQuestion(reqId, '[]');
    clearBottomCard(); scrollBottom();
  }

  function render() {
    card.innerHTML = '';
    const tabs = document.createElement('div'); tabs.className = 'q-tabs';
    questions.forEach((q, i) => {
      if (questions.length === 1 && i > 0) return;
      const t = document.createElement('div'); t.className = 'q-tab' + (i === activeQ ? ' active' : '');
      t.textContent = q.header || ('Question ' + (i + 1));
      t.onclick = () => { activeQ = i; render(); };
      tabs.appendChild(t);
    });
    const x = document.createElement('div'); x.className = 'q-x'; x.innerHTML = ICONS.X; x.onclick = cancel;
    tabs.appendChild(x);
    card.appendChild(tabs);
    card.appendChild(Object.assign(document.createElement('div'), { className: 'q-sep' }));

    const q = questions[activeQ];
    const qt = document.createElement('div'); qt.className = 'q-text'; qt.textContent = q.question || '';
    card.appendChild(qt);

    const submit = document.createElement('div');
    (q.options || []).forEach((opt, oi) => {
      const row = document.createElement('div'); row.className = 'q-opt' + (state[activeQ].choice === oi ? ' sel' : '');
      const radio = document.createElement('div'); radio.className = 'q-radio';
      const txt = document.createElement('div'); txt.className = 'q-otext';
      const title = document.createElement('div'); title.className = 'q-otitle'; title.textContent = opt.label || '';
      txt.appendChild(title);
      if (opt.description) { const d = document.createElement('div'); d.className = 'q-odesc'; d.textContent = opt.description; txt.appendChild(d); }
      row.appendChild(radio); row.appendChild(txt);
      row.onclick = () => {
        state[activeQ].choice = oi;
        // auto-advance to the next question until the last one (then stay so they can submit)
        if (activeQ < questions.length - 1) activeQ++;
        render();
      };
      card.appendChild(row);
    });
    // Other
    const orow = document.createElement('div'); orow.className = 'q-opt' + (state[activeQ].choice === 'other' ? ' sel' : '');
    orow.appendChild(Object.assign(document.createElement('div'), { className: 'q-radio' }));
    const otxt = document.createElement('div'); otxt.className = 'q-otext';
    otxt.appendChild(Object.assign(document.createElement('div'), { className: 'q-otitle', textContent: 'Other' }));
    orow.appendChild(otxt);
    orow.onclick = () => { state[activeQ].choice = 'other'; render();
      setTimeout(() => { const i = card.querySelector('.q-other-in input'); if (i) i.focus(); }, 0); };
    card.appendChild(orow);
    if (state[activeQ].choice === 'other') {
      const oin = document.createElement('div'); oin.className = 'q-other-in';
      const inp = document.createElement('input'); inp.type = 'text'; inp.placeholder = 'Type your answer'; inp.value = state[activeQ].other;
      inp.oninput = () => { state[activeQ].other = inp.value; submit.classList.toggle('ready', allAnswered()); };
      inp.onkeydown = (e) => { e.stopPropagation(); if (e.key === 'Enter') { e.preventDefault(); finish(); } };
      oin.appendChild(inp); card.appendChild(oin);
    }
    submit.className = 'q-submit' + (allAnswered() ? ' ready' : '');
    submit.innerHTML = '<span class="num">1</span><span>Submit answers</span>';
    submit.onclick = finish;
    card.appendChild(submit);
    card.appendChild(Object.assign(document.createElement('div'), { className: 'q-esc', textContent: 'Esc to cancel' }));
  }
  function onKey(e) {
    if (resolved) return;
    if (e.key === 'Escape' && (!document.activeElement || document.activeElement.tagName !== 'INPUT')) { e.preventDefault(); cancel(); }
  }
  document.addEventListener('keydown', onKey, true);
  render(); showBottomCard(card);
};


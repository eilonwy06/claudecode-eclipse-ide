/* cards.js — CLI-enforced permission decision card + AskUserQuestion card. */

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
  const isEdit = /edit|write|multiedit|str_replace|notebook|create/i.test(toolName || '');
  const card = document.createElement('div'); card.className = 'decision';

  const q = document.createElement('div'); q.className = 'dec-q';
  if (isEdit && detail) {
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
    }
    if (window._decide) window._decide(reqId, d, msg || '');
    clearBottomCard();                          // card disappears — composer returns
    if (d === 'deny' && msg) {
      addAnswered(msg, pane); startFreshTurn();  // instruction stays; reply goes below
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
  const opts = [['allow', 'Yes']];
  if (rememberLabel) opts.push(['allowRemember', rememberLabel]);
  opts.push(['deny', 'No']);
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
  inp.placeholder = 'Tell Claude what to do instead';
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


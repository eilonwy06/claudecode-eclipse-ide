/* claudetool.js — Page side of the claudeCodeEclipse MCP tool's claudeCodeView module:
   lists the conversation tabs, opens them (under a chosen folder) and closes them, sends a prompt into one,
   runs a slash command in one, opens a saved conversation in one,
   switches Remote Control on or off for one, and sets a tab's model/effort/thinking/
   permission mode; reads a tab's conversation, stops its turn, forks, rewinds or deletes
   at one of its messages, bookmarks a reply, archives or renames a saved conversation,
   and asks the CLI for the usage figures or the conversation as text; brings a tab to the
   front, answers a tab's question card (never an approval), opens, answers or closes the
   advisor's card, and closes a folder tab. Java calls window.__ccTool(requestJson) and hands the returned JSON
   back to Claude (ClaudeGuiView#pageTool). */

(function () {
  /* A request that cannot be carried out. Thrown before anything is changed, so a
     refused call leaves every tab as it was. */
  function refuse(msg) { throw { refused: msg }; }

  function bypassAllowed() {
    try { return window._bypassModeAllowed ? !!_bypassModeAllowed() : true; } catch (e) { return true; }
  }
  /* The permission modes the Modes menu offers, read from the menu itself so there is
     no second list to keep in step with it. */
  function modeIds() {
    const ids = [];
    document.querySelectorAll('#modes-menu .item[data-mode]').forEach(el => {
      const id = el.getAttribute('data-mode');
      if ((id !== 'bypassPermissions' || bypassAllowed()) && !modeBlockedBy(id)) ids.push(id);
    });
    return ids;
  }
  function selectableModels() { return MODELS.filter(m => !m.disabled); }
  function underRemoteControl(t) { return !!(t && (t.remoteControlUrl || t.rcConnecting)); }

  function remoteControlState(t) {
    if (t.rcDisconnecting) return 'disconnecting';
    if (t.rcConnecting) return 'connecting';
    return t.remoteControlUrl ? 'on' : 'off';
  }
  function describe(t) {
    const out = describeSettings(t);
    out.remoteControl = remoteControlState(t);
    if (out.remoteControl === 'on') out.remoteControlUrl = t.remoteControlUrl;
    const card = cardKind(t);
    if (card) out.waitingOnCard = card;
    // Claude's requests that came while a card was up, waiting behind it in order (carddock.js).
    if (t.cardQueue && t.cardQueue.length) out.cardsWaiting = t.cardQueue.map(q => q.kind || 'card');
    return out;
  }
  /* The advisor's card a tab waits on: its choices by label and the one in effect. */
  function advisorCardOf(t) {
    const card = t.pendingCard;
    return { choices: (card._choices || []).slice(), current: card._current || '' };
  }
  /* The advisor's card of `t`, for an action on it. The card writes what was picked into the
     tab in front, and only shows there, so that tab has to be the one in front. */
  function advisorCardFor(t) {
    const kind = cardKind(t);
    if (kind !== 'advisor') {
      refuse(kind ? 'That tab waits on ' + (kind === 'question' ? 'a question card' : 'an approval card') + ", not the advisor's."
        : "That tab is not waiting on the advisor's card. Open it with action 'runCommand' and '/advisor'.");
    }
    if (t !== activeTab()) refuse("The advisor's card is answered in the tab in front. Bring that tab forward with action 'selectTab' first.");
    return t.pendingCard;
  }
  /* What an answer to a card says about the requests that waited behind it: the next one's
     card comes up right after this call. */
  function queueNotes(t) {
    return (t.cardQueue && t.cardQueue.length)
      ? ["A request of Claude's waited behind the card; its own card comes up now ('readTab' shows it)."] : [];
  }
  /* Which card a tab waits on: a question (AskUserQuestion), an approval (a tool asking to
     run or to edit) or the advisor's choice. Null when none. */
  function cardKind(t) {
    const card = t && t.pendingCard;
    if (!card) return null;
    if (card.classList.contains('question-card')) return 'question';
    return card.classList.contains('advisor') ? 'advisor' : 'approval';
  }
  /* A question card's questions as 'answerQuestion' takes answers to them. */
  function cardQuestions(t) {
    const qs = (t.pendingCard && t.pendingCard._questions) || [];
    return qs.map(q => {
      const out = { question: q.question || '', options: (q.options || []).map(o => (o && o.label) || '') };
      if (q.header) out.header = q.header;
      if (q.multiSelect) out.multiSelect = true;
      return out;
    });
  }
  function describeSettings(t) {
    return { id: t.id, title: t.title, active: t === activeTab(), root: rootPathOf(t),
      sessionId: t.sessionId || '', model: t.model || '', modelLabel: modelLabelFor(t.model || ''),
      effort: EFFORTS[t.effortIdx], thinking: !!t.thinking, mode: t.permMode || DEFAULT_PERM_MODE,
      streaming: !!t.streaming };
  }

  /* The settings a tab ends up with: `base` with the request laid over it, then made
     legal the way the composer's own controls keep it legal (see the thinking/effort
     gate in models.js). `t` is the tab being changed, null for one not created yet. */
  function resolve(req, base, t) {
    const out = { model: base.model, effortIdx: base.effortIdx, thinking: base.thinking,
                  permMode: base.permMode, notes: [] };
    if (req.model !== undefined) {
      let id = String(req.model);
      if (id.toLowerCase() === 'default') id = '';
      const entry = MODELS.find(m => m.id === id);
      if (entry ? entry.disabled : !/^claude-[\w.\-\[\]]+$/.test(id)) {
        refuse("Unknown model '" + req.model + "'. Use 'default', a full claude-… id, or one of: "
          + selectableModels().map(m => m.id).filter(Boolean).join(', ') + '.');
      }
      // The one model choice that can restart a conversation's process, which the view
      // confirms with the user first when other devices are attached to it.
      if (!id && base.model && underRemoteControl(t)) {
        refuse('Switching to the default model can restart this conversation, and it is under '
          + 'Remote Control. That switch has to be made in the view.');
      }
      out.model = id;
    }
    if (req.effort !== undefined) {
      const idx = EFFORTS.indexOf(String(req.effort).toLowerCase());
      if (idx < 0) refuse("Unknown effort '" + req.effort + "'. Use one of: " + EFFORTS.join(', ') + '.');
      out.effortIdx = idx;
    }
    if (req.thinking !== undefined) {
      if (typeof req.thinking !== 'boolean') refuse("'thinking' must be true or false.");
      out.thinking = req.thinking;
    }
    if (req.mode !== undefined) {
      const mode = String(req.mode);
      if (mode === 'bypassPermissions' && !bypassAllowed()) {
        refuse("Bypass permissions is turned off in this Eclipse's preferences.");
      }
      if (modeBlockedBy(mode, t)) {
        refuse("The '" + mode + "' mode is " + modeBlockedText(modeBlockedBy(mode, t)).toLowerCase() + '.');
      }
      if (modeIds().indexOf(mode) < 0) {
        refuse("Unknown mode '" + req.mode + "'. Use one of: " + modeIds().join(', ') + '.');
      }
      out.permMode = mode;
    }

    if (underRemoteControl(t) && !out.thinking) {
      out.thinking = true;
      out.notes.push('Thinking stays on while Remote Control is active.');
    }
    if (isThinkingGatedModel(out.model) && !out.thinking
        && EFFORT_REQUIRES_THINKING.indexOf(EFFORTS[out.effortIdx]) >= 0) {
      const at = EFFORT_LABELS[out.effortIdx] + ' effort on ' + gateModelName(out.model);
      if (req.thinking === false && req.effort === undefined) {
        // Only thinking was asked for, so it is the effort that gives way.
        while (out.effortIdx > 0 && EFFORT_REQUIRES_THINKING.indexOf(EFFORTS[out.effortIdx]) >= 0) out.effortIdx--;
        out.notes.push('Effort lowered to ' + EFFORT_LABELS[out.effortIdx] + ': ' + at + ' needs thinking on.');
      } else {
        out.thinking = true;
        out.notes.push('Thinking turned on: required at ' + at + '.');
      }
    }
    return out;
  }

  function switchDividerName(id) {
    const entry = MODELS.find(m => m.id === id);
    return (entry && entry.fullId) ? entry.fullId : (/^claude-/.test(id) ? id : modelLabelFor(id));
  }

  /* Writes resolved settings to a tab the way a pick in the composer does: stored on
     the tab, saved for a resume, and handed to its running process. The composer is
     repainted only when the tab is the one in front; another tab picks them up from
     its own fields when it is switched to. */
  function apply(t, r) {
    const modelChanged = r.model !== t.model;
    t.model = r.model; t.effortIdx = r.effortIdx; t.thinking = r.thinking; t.permMode = r.permMode;
    if (modelChanged && !t.pane.querySelector('.welcome')) {
      t.pane.appendChild(makeSwitchDivider(switchDividerName(r.model)));
      if (t === activeTab()) scrollBottom();
    }
    if (t === activeTab()) applyTabSettings(t);
    persistTabPrefs(t);
    try {
      // The tab's Ultracode goes with them: left out, the view takes it for off and the
      // process is told to switch it off, while the tab's switch still says on.
      if (window._applySettings) _applySettings(t.id, t.permMode, EFFORTS[t.effortIdx], t.model, t.thinking ? '1' : '0', !!t.ultracode);
    } catch (e) {}
  }

  /* The prompt of a request, trimmed. A slash command is not a message: the view runs
     some of them itself, and they have an action of their own ('runCommand'). */
  function promptOf(req) {
    const text = (typeof req.prompt === 'string') ? req.prompt.trim() : '';
    if (!text) refuse("'prompt' is required: the message to send.");
    if (text.charAt(0) === '/') refuse("A slash command is not sent as a message; use action 'runCommand'.");
    return text;
  }

  /* A tab restored from the last Eclipse session holds only its session id until it is
     first shown (see switchTab). Whatever reads or adds to its conversation has it
     rebuilt first, read on the spot and not in the background. */
  function drawn(t) {
    if (!t._restore) return;
    const rs = t._restore;
    t._restore = null;
    loadHistory(rs.sessionId, rs.title, t, { sync: true });
  }

  /* The files a request attaches, each the way the composer attaches a file by its path:
     the message names the path and Claude reads the file itself. Java has checked that
     each one is there. */
  function filesOf(req) {
    if (req.files === undefined) return [];
    if (!Array.isArray(req.files)) refuse("'files' must be a list of file paths.");
    return req.files.map(p => pathAttachment(basename(String(p)), String(p)));
  }

  /* Sends `text` as the user's next message in tab `t`, front or not — what doSend()
     does for the tab in front, without the composer: no draft or editor context goes
     with it, only the `files` the call names, and the composer of the tab in front is
     left as it is.
     @returns {boolean} whether it was queued behind a turn already running */
  function send(t, text, files) {
    // Rebuilt first, or the later rebuild would empty the pane this turn is rendering
    // into; and on the spot, since the message is written to the same transcript.
    drawn(t);
    files = files || [];
    const target = rtab;           // whichever tab the page was rendering into
    t.cancelled = false;
    loadRender(t);
    const queueing = !!t.streaming;
    addUserMessage(text, null, files, null, nowIso(), t.pane, null);
    // Sent while the tab is working: remembered as doSend does (stream.js), so that what
    // Claude writes after it is handed over goes below its bubble.
    if (queueing && typeof noteQueuedSend === 'function') noteQueuedSend(t);
    if (!queueing) setStreaming(true);
    // The live gerund is for the tab in front only: showWorking restarts the page's one set
    // of gerund timers, and the front tab's own turn may be running on them. A tab behind
    // it gets a still one, as it does after a Remote Control wait (resumeWorkingFor).
    if (t === activeTab()) { if (!queueing || !workingEl) showWorking(); }
    else resumeWorkingFor(t);
    if (window._sendToJava) {
      window._sendToJava(text, false, t.sessionId || '', t.permMode || DEFAULT_PERM_MODE, EFFORTS[t.effortIdx],
        t.model || '', t.thinking ? '1' : '0', t.id, files.length ? JSON.stringify(files.map(f => f.block)) : '',
        rootPathOf(t), !!t.ultracode);
    }
    persistTabPrefs(t);
    // A tab behind hands the render target back: the gerund timers animate the line of
    // whichever tab holds it, and until this call that was the one the user is watching.
    if (t !== activeTab() && target && target !== t) loadRender(target);
    return queueing;
  }

  /* The tab an action that sends to or closes one is aimed at. No default: the tab in
     front is usually the one the call came from. */
  function tabForChange(req) {
    if (!req.tabId) refuse("'tabId' is required. Use action 'listTabs'.");
    const t = tabById(String(req.tabId));
    if (!t) refuse("No tab with id '" + req.tabId + "'. Use action 'listTabs'.");
    if (t.setupGuide) refuse('That tab is the setup guide, not a conversation.');
    return t;
  }

  /* Switches Remote Control on for `t` unless it already is, or is on its way — the
     same request /remote-control makes, for a tab that need not be in front. */
  function remoteControlOn(t) {
    if (t.remoteControlUrl || t.rcConnecting) return;
    if (remoteControlLock(t)) refuse(remoteControlLock(t));
    beginConnecting(t);
    rcSend(t, true);
  }
  function requireRemoteControl() {
    if (!window._remoteControl) refuse('Remote Control is not available in this build.');
  }

  /* A message sent before the bridge is up never reaches the other devices, which is
     why the composer stays shut while a tab connects. A prompt for such a tab waits
     the same way, and goes out once the tab has stopped connecting — connected or
     not, since the conversation works here either way.
     @returns {boolean} whether the prompt is waiting rather than sent */
  function sendOnceConnected(t, text, files) {
    if (!t.rcConnecting) { send(t, text, files); return false; }
    const timer = setInterval(() => {
      if (!tabById(t.id)) { clearInterval(timer); return; }   // closed while it waited
      if (t.rcConnecting) return;
      clearInterval(timer);
      if (!t.rcDisconnecting) send(t, text, files);
    }, 250);
    return true;
  }

  /* Where a new conversation goes: `rootId` of a folder tab that is already open, or
     `path` of a folder that may be opened as one. Nothing is changed here, so a call
     refused further on leaves no folder tab behind. Without 'folder' it is the one in
     front.

     A folder Claude has not been run in before is not opened from here: the view asks
     "Trust this folder?" first, and that question is for whoever sits at the view, not
     for a tool call to answer or to leave waiting there. */
  function folderFor(req) {
    if (req.folder === undefined) return { rootId: activeRootId };
    const asked = (typeof req.folder === 'string') ? req.folder.trim() : '';
    // Java would read anything else against Eclipse's own working directory.
    if (!/^([A-Za-z]:[\\/]|[\\/])/.test(asked)) {
      refuse("'folder' must be the full path of a folder, such as a 'root' that 'listTabs' shows.");
    }
    // Same two looks as openRootDirectory: the path as given, then as Java spells it.
    let root = rootByPath(asked), path = asked;
    if (!root) {
      let info = null;
      try { info = window._folderInfo ? JSON.parse(_folderInfo(asked) || 'null') : null; } catch (e) {}
      if (!info) refuse('This view cannot look folders up, so a tab can only be opened in a folder that is already open.');
      path = info.path || asked;
      root = rootByPath(path);
      if (!root && !info.exists) refuse("There is no folder at '" + path + "'.");
      if (!root && !info.trusted) {
        refuse("Claude has not been run in '" + path + "' before, and starting it there needs the user's "
          + 'consent. They open that folder once in the Claude Code view ("New Claude root directory", or '
          + 'Open Claude Here) and answer "Trust this folder?"; a folder trusted in the Claude Terminal counts too.');
      }
    }
    // With the row hidden nothing shows which folder is in front, or leads back to another.
    if (rootDirectoriesRowHidden && !(root && root.id === activeRootId)) {
      refuse('The folder row is hidden in this Eclipse (preference "Hide the root directories row"), '
        + 'so a tab can only be opened in the folder in front.');
    }
    return root ? { rootId: root.id } : { path: path };
  }

  /* Whether `t` can be sent something for Claude now. A message sent while Remote Control
     is being switched reaches nothing else, and a tab waiting on a card has a turn held
     open behind it. */
  function refuseWhileSwitching(t) {
    if (t.rcConnecting || t.rcDisconnecting) refuse('That tab is switching Remote Control on or off; try again once it has finished.');
  }
  function requireSendable(t) {
    refuseWhileSwitching(t);
    if (t.pendingCard) refuse('That tab is waiting for an answer to an approval or question card.');
  }

  /* The commands the view answers by opening something that waits for the user there. */
  const DESK_ONLY = {
    '/rewind': 'opens the rewind dialog',
    '/resume': 'opens the session history',
    '/mcp': 'opens the MCP servers window'
  };
  /* The view's own command menu (slash.js), so there is no second list to keep in step. */
  function commandList() {
    return SLASH_COMMANDS.map(c => ({ command: c.cmd, description: c.desc, available: !DESK_ONLY.hasOwnProperty(c.cmd) }));
  }
  /* What /context prints in the view, as numbers, for a tab that need not be in front. */
  function contextOf(t) {
    let s = {};
    try { s = (window._getContextStatus ? JSON.parse(_getContextStatus(t.id)) : null) || {}; } catch (e) {}
    const size = s.contextWindow || 0;
    if (typeof s.contextPct !== 'number' || !size) return null;
    const out = { percent: Math.floor(Math.min(100, Math.max(0, s.contextPct))), window: size,
      input: s.inputTokens || 0, cacheRead: s.cacheReadTokens || 0, cacheCreation: s.cacheCreationTokens || 0,
      outputLastTurn: s.outputTokens || 0 };
    if (typeof s.costUsd === 'number') out.costUsd = s.costUsd;
    return out;
  }
  /* The commands the view carries out itself (slash.js handleSlashCommand), each for the
     tab it is given: the composer's own handlers all act on the tab in front, on its
     settings and its draft. What a command prints into the conversation there comes back
     in the result here. (t, argument, the command as given) -> what to add to the result. */
  const VIEW_COMMANDS = {
    '/compact': function (t) {
      requireSendable(t);
      t.compacting = true;             // pins the tab's gerund to "Compacting", as sendCompact does
      return { queued: send(t, '/compact') };
    },
    '/clear': function (t, arg, text) {
      refuseWhileSwitching(t);
      clearTab(t);
      addUserMessage(text, null, [], null, nowIso(), t.pane, null);   // the one message left, as in the view
      return {};
    },
    '/context': function (t) {
      const context = contextOf(t);
      return context ? { context: context }
        : { context: null, notes: ['No context usage recorded yet for this conversation.'] };
    },
    '/model': function (t, arg) {
      if (!arg) return { models: selectableModels().map(m => ({ id: m.id, label: m.label })) };
      // The name as the composer's /model reads it: any case, 'default', or a full id.
      const id = resolveModelArg(arg);
      if (id === null) {
        refuse("Model '" + arg + "' not found. Use 'default', a full claude-… id, or one of: "
          + selectableModels().map(m => m.id).filter(Boolean).join(', ') + '.');
      }
      const r = resolve({ model: id || 'default' }, { model: t.model || '', effortIdx: t.effortIdx, thinking: !!t.thinking,
        permMode: t.permMode || DEFAULT_PERM_MODE }, t);
      apply(t, r);
      return r.notes.length ? { notes: r.notes } : {};
    },
    '/remote-control': function (t) {
      requireRemoteControl();
      if (t.rcDisconnecting) refuse('That tab is still switching Remote Control off; try again in a moment.');
      if (t.remoteControlUrl || t.rcConnecting) { beginDisconnecting(t); rcSend(t, false); }
      else remoteControlOn(t);
      return {};
    },
    '/help': function () {
      return { commands: commandList(), notes: ['Any other command is passed to Claude in the tab.'] };
    },
    /* The advisor's card, opened as the composer's /advisor opens it: in the tab in front,
       which it is drawn in and writes its outcome to. Answered with 'answerAdvisor' or closed
       with 'dismissAdvisor'. */
    '/advisor': function (t, arg, text) {
      refuseWhileSwitching(t);
      if (t !== activeTab()) refuse("The advisor's card opens in the tab in front. Bring that tab forward with action 'selectTab' first.");
      if (t.pendingCard) refuse('That tab is waiting on a card already; it has to be answered before the advisor\'s card can open.');
      openAdvisorCard(text);
      return { advisor: advisorCardOf(t) };
    }
  };

  /* ---- reading a conversation, and acting at one of its messages ---- */

  /* The tab an action that only reads is for: the one named, or the one in front. */
  function tabForRead(req) {
    if (req.tabId) return tabForChange(req);
    const t = activeTab();
    if (!t) refuse('No tab is open.');
    if (t.setupGuide) refuse('The tab in front is the setup guide, not a conversation.');
    return t;
  }
  /* Forking, rewinding and deleting are carried out by the view for the folder in front
     (ClaudeGuiView hands RewindService activeRoot()), so a conversation under another
     folder tab is refused here, not looked for in the wrong folder. */
  function requireFrontFolder(t) {
    if (t.rootId !== activeRootId) {
      refuse("That tab's folder is not the one in front in the view, and this is carried out for the "
        + 'folder in front. The user brings that folder tab to the front first.');
    }
  }
  /* The bubble of the user's message a call names, by the id 'readTab' gave it. */
  function bubbleOf(t, req) {
    const mid = (typeof req.messageId === 'string') ? req.messageId.trim() : '';
    if (!mid) refuse("'messageId' is required: a message of the user's, as action 'readTab' lists them.");
    if (!t.sessionId) refuse('That tab has no saved conversation yet.');
    drawn(t);
    backfillMessageIds(t);
    const all = t.pane.querySelectorAll('.user-msg[data-mid]');
    for (let i = 0; i < all.length; i++) if (all[i].dataset.mid === mid) return all[i];
    refuse("That tab shows no message of the user's with id '" + mid + "'. Use action 'readTab'.");
  }
  const MAX_TEXT = 20000;
  function clip(text) {
    text = String(text || '');
    return text.length > MAX_TEXT ? text.slice(0, MAX_TEXT) + '… (' + (text.length - MAX_TEXT) + ' more characters)' : text;
  }
  function requireBookmarks(t) {
    if (!bookmarksAvailable()) refuse('Bookmarks are not available in this build.');
    if (!t.sessionId) refuse('That tab has no saved conversation yet.');
  }
  /* A saved conversation the history panel has in its list, when it has read one. */
  function listedSession(id) {
    try { return histSessions.find(s => s.sessionId === id) || null; } catch (e) { return null; }
  }
  function redrawHistory() {
    try { if (document.getElementById('history-list')) renderHistoryList(); } catch (e) {}
  }

  /* ---- answers that take a moment ---- */

  /* The CLI answers a control request later, and this page answers Java at once. So an
     action that asks the CLI answers with a token ('wait'), and Java asks for the result
     under it (action 'collect') until it is there. */
  const waiting = {};
  /* One control request to `t`'s process, on that tab's own settings — cliAsk
     (clidialogs.js) sends the ones of the tab in front, which are what a process
     started for the request would come up on. `shape` turns the CLI's answer into what
     the action answers with. */
  function ask(t, subtype, shape) {
    if (!window._cli || typeof cliWaiting !== 'function') refuse('That is not available in this build.');
    const w = cliWaiting('c');
    waiting[w.token] = { done: false };
    w.done.then(
      r => { waiting[w.token] = { done: true, out: Object.assign({ ok: true }, shape(r || {})) }; },
      e => { waiting[w.token] = { done: true, out: { ok: false, error: (e && e.message) || 'Claude Code did not answer.' } }; });
    _cli(t.id, w.token, JSON.stringify({ subtype: subtype }), t.sessionId || '', t.permMode || DEFAULT_PERM_MODE,
      EFFORTS[t.effortIdx], t.model || '', t.thinking ? '1' : '0', rootPathOf(t), !!t.ultracode);
    return { ok: true, wait: w.token };
  }

  function result(t, notes) {
    const out = { ok: true, tab: describe(t) };
    if (notes.length) out.notes = notes;
    return out;
  }

  const ACTIONS = {
    listTabs: function () {
      return { ok: true, tabs: tabs.map(describe),
        models: selectableModels().map(m => ({ id: m.id, label: m.label })),
        efforts: EFFORTS.slice(), modes: modeIds(), commands: commandList() };
    },
    newTab: function (req) {
      const text = req.prompt !== undefined ? promptOf(req) : null;
      if (text === null && req.files !== undefined) refuse("'files' go with a 'prompt'.");
      const files = filesOf(req);
      if (req.remoteControl !== undefined && typeof req.remoteControl !== 'boolean') {
        refuse("'remoteControl' must be true or false.");
      }
      if (req.remoteControl) requireRemoteControl();
      const r = resolve(req, { model: defaultModel(), effortIdx: DEFAULT_EFFORT_IDX,
        thinking: defaultThinking(), permMode: defaultPermMode() }, null);
      const where = folderFor(req);
      closeMenus();
      // A folder not open yet gets its tab from here, not from addRoot: that one would
      // be on the defaults and, with Remote Control on startup, already connecting.
      const rootId = where.rootId || addRoot(where.path, { select: false }).id;
      const t = createTab({ rootId: rootId, model: r.model, effortIdx: r.effortIdx,
        thinking: r.thinking, permMode: r.permMode });
      // After createTab: with Remote Control on startup set, it is already connecting.
      if (req.remoteControl) remoteControlOn(t);
      const waiting = text !== null && sendOnceConnected(t, text, files);
      const out = result(t, r.notes);
      if (waiting) out.promptDeferred = true;
      return out;
    },
    configureTab: function (req) {
      const t = req.tabId ? tabById(String(req.tabId)) : activeTab();
      if (!t) refuse(req.tabId ? "No tab with id '" + req.tabId + "'. Use action 'listTabs'." : 'No tab is open.');
      if (req.model === undefined && req.effort === undefined && req.thinking === undefined && req.mode === undefined) {
        refuse("Nothing to change: give at least one of 'model', 'effort', 'thinking', 'mode'.");
      }
      const r = resolve(req, { model: t.model || '', effortIdx: t.effortIdx, thinking: !!t.thinking,
        permMode: t.permMode || DEFAULT_PERM_MODE }, t);
      apply(t, r);
      return result(t, r.notes);
    },
    sendPrompt: function (req) {
      const t = tabForChange(req);
      const text = promptOf(req);
      const files = filesOf(req);
      requireSendable(t);
      const queued = send(t, text, files);
      return { ok: true, queued: queued, tab: describe(t) };
    },
    runCommand: function (req) {
      const t = tabForChange(req);
      const text = (typeof req.command === 'string') ? req.command.trim() : '';
      if (!/^\/\S/.test(text)) {
        refuse("'command' must be a slash command such as '/compact'. A message goes through action 'sendPrompt'.");
      }
      // Read as the composer reads one (handleSlashCommand): the first word names it.
      const name = text.split(/\s+/)[0].toLowerCase();
      if (DESK_ONLY.hasOwnProperty(name)) {
        refuse("'" + name + "' " + DESK_ONLY[name] + ' in the Claude Code view and waits for an answer there, so it is not run from here.');
      }
      let out;
      if (VIEW_COMMANDS.hasOwnProperty(name)) {
        out = VIEW_COMMANDS[name](t, text.slice(name.length).trim(), text);
      } else {
        // Not one of the view's: Claude's own, a custom command or a skill. Passed on as
        // the composer passes on any command it does not know.
        requireSendable(t);
        out = { queued: send(t, text), passedToClaude: true };
      }
      out.ok = true; out.command = name; out.tab = describe(t);
      return out;
    },
    remoteControl: function (req) {
      const t = tabForChange(req);
      if (typeof req.enabled !== 'boolean') refuse("'enabled' must be true or false.");
      requireRemoteControl();
      // One request at a time: the CLI's answer to a second one cannot be told from a
      // refusal of the first (see toggleRemoteControl).
      if (t.rcDisconnecting) refuse('That tab is still switching Remote Control off; try again in a moment.');
      if (req.enabled) remoteControlOn(t);
      else if (t.remoteControlUrl || t.rcConnecting) { beginDisconnecting(t); rcSend(t, false); }
      return result(t, []);
    },
    /* A saved conversation in a tab of its own. Java has checked that it exists in
       'folder' and found its title; which conversations there are is its to list too
       ('listHistory'), since that scan is not for the UI thread this runs on.

       Not through the history panel's pick (loadHistory without a tab): that one reuses
       an empty tab in front, which with Remote Control on startup is already connected as
       a new conversation, and reads the conversation before its tab — and so its folder —
       is in front. The tab is made first, as viewstate's restore makes one, and the
       transcript rebuilt into it. */
    openSession: function (req) {
      const id = (typeof req.sessionId === 'string') ? req.sessionId.trim() : '';
      if (!id) refuse("'sessionId' is required. Use action 'listHistory'.");
      const where = folderFor(req);
      // Never a second tab on one conversation: its tab comes to the front instead.
      const open = tabs.find(tb => tb.sessionId === id);
      if (open) {
        if (open.id !== activeId) switchTab(open.id);
        return { ok: true, alreadyOpen: true, tab: describe(open) };
      }
      closeMenus();
      const title = (typeof req.title === 'string' && req.title.trim()) ? req.title.trim() : 'Claude Code';
      const rootId = where.rootId || addRoot(where.path, { select: false }).id;
      const t = createTab({ rootId: rootId, sessionId: id, title: title, titled: true });
      // Read on the spot: the answer describes the tab as its conversation leaves it.
      loadHistory(id, title, t, { sync: true });
      return result(t, []);
    },
    closeTab: function (req) {
      const t = tabForChange(req);
      // Its last conversation closing closes the folder too, and the last folder asks
      // the user before taking the whole view down; neither is this tool's to start.
      if (tabs.filter(x => x.rootId === t.rootId).length === 1) {
        refuse('That is the only conversation in its folder, so closing it would close the folder. Open another tab first.');
      }
      closeTab(t.id);
      return { ok: true, closed: t.id, tabs: tabs.map(describe) };
    },
    /* What the tab shows of its conversation: the user's messages and Claude's replies, in
       order, each with the id the actions below take. Tool lines and thinking are left out. */
    readTab: function (req) {
      const t = tabForRead(req);
      drawn(t);
      backfillMessageIds(t);
      if (typeof backfillReplyIds === 'function') backfillReplyIds(t);
      let limit = 20;
      if (req.limit !== undefined) {
        limit = Number(req.limit);
        if (!(limit >= 1 && limit <= 200) || Math.floor(limit) !== limit) refuse("'limit' must be a number from 1 to 200.");
      }
      const marked = (bookmarksAvailable() && t.sessionId) ? tabBookmarks(t).map(b => b.uuid) : [];
      const all = [];
      t.pane.querySelectorAll('.user-msg, .a-item').forEach(el => {
        if (el.classList.contains('user-msg')) {
          const body = el.querySelector('.body');
          const m = { role: 'user', text: clip(body ? body.textContent : '') };
          if (el.dataset.mid) m.messageId = el.dataset.mid;
          all.push(m);
        } else if (typeof el._replyText === 'string' && el._replyText.trim()) {
          const m = { role: 'assistant', text: clip(el._replyText) };
          if (el.dataset.rid) {
            m.replyId = el.dataset.rid;
            if (marked.indexOf(el.dataset.rid) >= 0) m.bookmarked = true;
          }
          all.push(m);
        }
      });
      const out = { ok: true, tab: describe(t), total: all.length, messages: all.slice(-limit),
        waitingOnCard: cardKind(t) || false };
      if (out.waitingOnCard === 'question') out.questions = cardQuestions(t);
      if (out.waitingOnCard === 'advisor') out.advisor = advisorCardOf(t);
      const notes = [];
      if (t.streaming) notes.push('Claude is still working in this tab; its reply so far may be incomplete.');
      if (t.opening) notes.push('The conversation is still being drawn; its older messages are not all here yet.');
      if (notes.length) out.notes = notes;
      return out;
    },
    /* Stop, for a tab that need not be in front. In front it is the Stop button itself. */
    stopTab: function (req) {
      const t = tabForChange(req);
      if (!t.streaming) return { ok: true, stopped: false, tab: describe(t) };
      if (t === activeTab()) {
        doCancel();
      } else {
        // What doCancel does, for a tab behind. Its working line is a still one, so the
        // page's one set of gerund timers, which the tab in front may be running on, is
        // left alone: no hideWorking and no setStreaming here.
        const target = rtab;
        loadRender(t);
        t.queuedAfterStop = (t.queuedSends || 0) > 0;
        t.cancelled = true;
        if (window._cancelRequest) window._cancelRequest(t.id);
        const stoppedTool = markInterrupted();
        discardAssistant();
        if (workingEl) { workingEl.remove(); workingEl = null; }
        sweepWorkingNodes(t.pane);
        t.streaming = false; t.queuedSends = 0;
        addSystemTo(t, 'Request cancelled.');
        addInterrupted(stoppedTool ? 'Tool interrupted' : 'Interrupted', t.pane);
        backfillMessageIds(t);
        if (typeof refreshReplies === 'function') refreshReplies(t);
        if (target && target !== t) loadRender(target);
      }
      return { ok: true, stopped: true, tab: describe(t) };
    },
    /* "Fork conversation from here", and with 'restoreCode' "Fork conversation and rewind
       code": the conversation up to that message in a new tab, which comes to the front.
       forkFrom (rewind.js) with two differences: a failure is the call's answer and not a
       line in the conversation, and the message forked at comes back in the result where
       the view puts it in the composer for the user to send again. */
    forkMessage: function (req) {
      const t = tabForChange(req);
      requireFrontFolder(t);
      const mid = bubbleOf(t, req).dataset.mid;
      if (req.restoreCode !== undefined && typeof req.restoreCode !== 'boolean') refuse("'restoreCode' must be true or false.");
      const fn = req.restoreCode ? window._rewindApply : window._rewindForkOnly;
      if (!fn) refuse('Forking is not available in this build.');
      let res = {};
      try { res = JSON.parse(fn(t.sessionId, mid) || '{}') || {}; } catch (e) {}
      if (res.error) refuse((req.restoreCode ? 'Rewind failed: ' : 'Fork failed: ') + res.error);
      if (!res.sessionId) refuse('The fork was not created.');
      closeMenus();
      const titled = t.titled, title = forkTitle(t.title, titled);
      const forked = createTab({ rootId: t.rootId, title: title, titled: titled, sessionId: res.sessionId });
      loadHistory(res.sessionId, title, forked, { sync: true });
      if (titled && window._renameSession) window._renameSession(res.sessionId, title);
      const out = { ok: true, forkedFrom: t.id, codeRestored: !!req.restoreCode, tab: describe(forked),
        prompt: stripMeta(res.prompt || '') };
      out.notes = ["The fork ends before that message; 'prompt' is its text, to send again or change with action 'sendPrompt'."];
      return out;
    },
    /* "Rewind code to here": the files as they were when that message was sent, the
       conversation left alone. With 'preview' it only says what would change. */
    rewindCode: function (req) {
      const t = tabForChange(req);
      requireFrontFolder(t);
      const mid = bubbleOf(t, req).dataset.mid;
      if (req.preview !== undefined && typeof req.preview !== 'boolean') refuse("'preview' must be true or false.");
      if (!window._rewindPreview || !window._rewindCodeOnly) refuse('Rewinding is not available in this build.');
      let prev = {};
      try { prev = JSON.parse(window._rewindPreview(t.sessionId, mid) || '{}') || {}; } catch (e) {}
      if (prev.error) refuse('Could not read the checkpoint (' + prev.error + ').');
      if (prev.queued) refuse('That message was sent while Claude was working, so no file checkpoint was taken for it and code cannot be restored.');
      if (prev.noCheckpoint) refuse('That message has no file checkpoint, so code cannot be restored.');
      const files = prev.files || [];
      const out = { ok: true, tab: describe(t), messageId: mid, files: files, restored: false };
      if (req.preview) { out.preview = true; return out; }
      if (!files.length) { out.notes = ['The code has not changed since that message, so nothing was restored.']; return out; }
      let res = {};
      try { res = JSON.parse(window._rewindCodeOnly(t.sessionId, mid) || '{}') || {}; } catch (e) {}
      if (res.error) refuse('Rewind failed: ' + res.error);
      addInterrupted('Code rewind successful', t.pane);
      out.restored = true;
      out.notes = ['Rewinding does not affect files edited manually or via bash.'];
      return out;
    },
    /* The message's Delete, already confirmed: it is gone from the transcript for good. */
    deleteMessage: function (req) {
      const t = tabForChange(req);
      requireFrontFolder(t);
      const box = bubbleOf(t, req);
      if (t.streaming) refuse("Claude is working in that tab; let it finish or stop it (action 'stopTab') before deleting a message.");
      if (!window._deleteMessage) refuse('Deleting a message is not supported by this build.');
      const mid = box.dataset.mid;
      let res = {};
      try { res = JSON.parse(window._deleteMessage(t.id, t.sessionId, mid) || '{}') || {}; } catch (e) {}
      if (!res.ok) refuse('Could not delete the message: ' + (res.error || 'unknown error'));
      markMessageDeleted(box);
      return { ok: true, deleted: mid, tab: describe(t) };
    },
    listBookmarks: function (req) {
      const t = tabForRead(req);
      requireBookmarks(t);
      drawn(t);
      return { ok: true, tab: describe(t),
        bookmarks: tabBookmarks(t).map(b => ({ replyId: b.uuid, text: clip(bookmarkText(t, b.uuid) || '') })) };
    },
    setBookmark: function (req) {
      const t = tabForChange(req);
      requireBookmarks(t);
      const id = (typeof req.replyId === 'string') ? req.replyId.trim() : '';
      if (!id) refuse("'replyId' is required: a reply of Claude's, as action 'readTab' lists them.");
      if (typeof req.enabled !== 'boolean') refuse("'enabled' must be true or false.");
      drawn(t);
      if (typeof backfillReplyIds === 'function') backfillReplyIds(t);
      const el = replyEl(t, id);
      const has = tabBookmarks(t).some(b => b.uuid === id);
      // Only a reply the tab shows is bookmarked; one that is bookmarked already can
      // always have its bookmark taken away.
      if (!el && !(has && !req.enabled)) refuse("That tab shows no reply with id '" + id + "'. Use action 'readTab'.");
      if (has !== req.enabled) {
        setBookmark(t, id, req.enabled, (el && el._replyAt) || 0);
        if (tabBookmarks(t).some(b => b.uuid === id) !== req.enabled) refuse('The bookmark could not be written.');
      }
      return { ok: true, replyId: id, bookmarked: req.enabled, tab: describe(t) };
    },
    /* A saved conversation into the archive or back out of it, as its row in the history
       panel does it. Java has checked that it exists. Open in a tab, archiving it closes
       the tab, as the view does. */
    archiveSession: function (req) {
      const id = (typeof req.sessionId === 'string') ? req.sessionId.trim() : '';
      if (!id) refuse("'sessionId' is required. Use action 'listHistory'.");
      if (typeof req.archived !== 'boolean') refuse("'archived' must be true or false.");
      const open = tabs.find(tb => tb.sessionId === id);
      if (req.archived && open && tabs.filter(x => x.rootId === open.rootId).length === 1) {
        refuse('That conversation is open in the only tab of its folder, and archiving it would close the folder. Open another tab first.');
      }
      let recorded = false;
      try { recorded = !!(window._setSessionsArchived && window._setSessionsArchived(JSON.stringify([id]), req.archived)); } catch (e) {}
      if (!recorded) refuse('The conversation could not be ' + (req.archived ? 'archived.' : 'taken out of the archive.'));
      const listed = listedSession(id);
      if (listed) { listed.archived = req.archived; redrawHistory(); }
      const out = { ok: true, sessionId: id, archived: req.archived };
      if (req.archived && open) { closeTab(open.id, { forget: true }); out.closedTab = open.id; }
      if (req.archived) forgetClosedSession(id);
      return out;
    },
    /* A saved conversation's name, as renaming its row in the history panel sets it. */
    renameSession: function (req) {
      const id = (typeof req.sessionId === 'string') ? req.sessionId.trim() : '';
      if (!id) refuse("'sessionId' is required. Use action 'listHistory'.");
      const title = (typeof req.newTitle === 'string') ? req.newTitle.trim() : '';
      if (!title || /[\r\n]/.test(title) || title.length > 200) refuse("'title' must be one line of at most 200 characters.");
      if (!window._renameSession) refuse('Renaming is not available in this build.');
      window._renameSession(id, title);
      const listed = listedSession(id);
      if (listed) { listed.display = title; redrawHistory(); }
      const t = tabs.find(tb => tb.sessionId === id);
      if (t) { t.title = title; renderTabs(); }
      return { ok: true, sessionId: id, title: title };
    },
    /* The figures of Account & Usage, as the CLI gives them. */
    usage: function (req) {
      return ask(tabForRead(req), 'get_usage', r => ({ usage: r }));
    },
    /* The whole conversation as plain text, as Export conversation gets it. */
    exportConversation: function (req) {
      const t = tabForRead(req);
      if (!t.sessionId) refuse('That tab has no saved conversation yet.');
      return ask(t, 'export_conversation', d => ({ sessionId: t.sessionId, text: String(d.text || '') }));
    },
    /* A tab brought to the front, as a click on it does: its folder comes with it. */
    selectTab: function (req) {
      const t = tabForChange(req);
      // With the row hidden nothing shows which folder is in front, or leads back to another.
      if (rootDirectoriesRowHidden && t.rootId !== activeRootId) {
        refuse('The folder row is hidden in this Eclipse (preference "Hide the root directories row"), '
          + "so only a tab of the folder in front is brought forward; that one is in another folder.");
      }
      closeMenus();
      if (t.id !== activeId) switchTab(t.id);
      return result(t, []);
    },
    /* The question card a tab waits on, answered as its own Submit answers it. An approval is
       the user's: they decide what Claude may do. */
    answerQuestion: function (req) {
      const t = tabForChange(req);
      const kind = cardKind(t);
      if (!kind) refuse('That tab is not waiting on a card.');
      if (kind === 'advisor') refuse("That tab waits on the advisor's card; answer it with action 'answerAdvisor' or close it with 'dismissAdvisor'.");
      if (kind !== 'question') {
        refuse('That tab waits on an approval card, which only the user answers, in the view or on their other devices.');
      }
      if (!Array.isArray(req.answers)) refuse("'answers' must be a list, one answer per question.");
      const card = t.pendingCard;
      const questions = cardQuestions(t);
      // The card's submit starts the next turn in whichever tab the page renders into.
      const target = rtab;
      loadRender(t);
      let refusal;
      try { refusal = card._answer(req.answers.map(String)); }
      finally { if (t !== activeTab() && target && target !== t) loadRender(target); }
      if (refusal) refuse(refusal);
      return { ok: true, answered: true, questions: questions.map(q => q.question), answers: req.answers, tab: describe(t) };
    },
    /* The advisor's card, answered as a click on one of its choices: the choice is written to
       Claude Code's own settings, for every conversation on this machine. */
    answerAdvisor: function (req) {
      const t = tabForChange(req);
      const card = advisorCardFor(t);
      const asked = (typeof req.advisor === 'string') ? req.advisor.trim() : '';
      const i = card._choices.findIndex(label => label.toLowerCase() === asked.toLowerCase());
      if (i < 0) refuse("The advisor's card has no choice '" + asked + "'. Use one of: " + card._choices.join(', ') + '.');
      const notes = queueNotes(t);
      card._choose(i);
      const out = { ok: true, advisor: card._choices[i], tab: describe(t) };
      if (notes.length) out.notes = notes;
      return out;
    },
    /* The advisor's card closed as Esc closes it: nothing is changed or written. */
    dismissAdvisor: function (req) {
      const t = tabForChange(req);
      const card = advisorCardFor(t);
      const notes = queueNotes(t);
      card._dismiss();
      const out = { ok: true, dismissed: true, tab: describe(t) };
      if (notes.length) out.notes = notes;
      return out;
    },
    /* A folder tab closed with its conversations, as its × does, short of the one question
       the view asks: the last folder is not closed from here. */
    closeFolder: function (req) {
      const asked = (typeof req.folder === 'string') ? req.folder.trim() : '';
      if (!asked) refuse("'folder' is required: the full path of an open folder, a 'root' that 'listTabs' shows.");
      let root = rootByPath(asked);
      if (!root) {
        let info = null;
        try { info = window._folderInfo ? JSON.parse(_folderInfo(asked) || 'null') : null; } catch (e) {}
        if (info && info.path) root = rootByPath(info.path);
      }
      if (!root) refuse("No folder tab is open for '" + asked + "'. 'listTabs' shows each tab's 'root'.");
      if (roots.length === 1) {
        refuse('That is the only folder open, and closing it would close the Claude Code view, which '
          + 'asks the user first. Open another folder first.');
      }
      const own = tabsOfRoot(root.id);
      const busy = own.find(x => x.streaming || x.pendingCard);
      if (busy) {
        refuse("Tab '" + busy.id + "' in that folder is " + (busy.pendingCard ? 'waiting on a card' : 'working')
          + "; let it finish or stop it (action 'stopTab') first.");
      }
      const closedTabs = own.map(x => x.id);
      closeMenus();
      closeRoot(root.id);
      return { ok: true, closed: root.path, closedTabs: closedTabs, tabs: tabs.map(describe) };
    },
    collect: function (req) {
      const entry = waiting.hasOwnProperty(req.token) ? waiting[req.token] : null;
      if (!entry) refuse('Nothing is waiting under that token.');
      if (!entry.done) return { ok: true, done: false };
      delete waiting[req.token];
      return Object.assign({ done: true }, entry.out);
    }
  };

  window.__ccTool = function (json) {
    let out;
    try {
      const req = JSON.parse(json);
      const action = ACTIONS.hasOwnProperty(req.action) ? ACTIONS[req.action] : null;
      if (!action) refuse("Unknown action '" + req.action + "'. Use one of: " + Object.keys(ACTIONS).join(', ') + '.');
      out = action(req);
    } catch (e) {
      out = { ok: false, error: (e && e.refused) || ('claudeCodeView failed: ' + (e && e.message || e)) };
    }
    try {
      // Not each "not yet" of a wait, and never a whole exported conversation.
      if (window.__ccDebug && window._debugLog && !(out.ok && out.done === false)) {
        const said = JSON.stringify(out);
        _debugLog('[CCTOOL] ' + json + ' -> ' + (said.length > 2000 ? said.slice(0, 2000) + '… (' + said.length + ' characters)' : said));
      }
    } catch (e) {}
    return JSON.stringify(out);
  };
})();

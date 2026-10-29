# Changelog

All notable changes to Claude Code for Eclipse are documented here.

---

## [3.2.27] — 2026-10-29 *(current)*

### Changed
- A slash command typed with something after it, such as `/context all` or `/mcp reconnect all`, goes to Claude Code instead of opening the view's own window.
- Slash commands show in the command font with what was typed after them.

### Fixed
- `/compact` keeps the instructions typed after it.

---

## [3.2.26] — 2026-10-27

### Added
- **Reopen Closed Session** (Ctrl+Alt+Shift+R): brings back the conversation you closed most recently, in its folder.
- **Create Worktree** in the command menu: makes a git worktree for the folder and opens it as a folder of its own.
- **Continue after reload**: a step that closing Eclipse cut off finishes by itself when Eclipse starts again (Preferences › Claude Code).

### Changed
- Closing Eclipse while Claude is working no longer records that reply as stopped by you.
- The native core carries the plugin's version number ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #228).

### Fixed
- Minor UI fixes.

### Security
- The plugin is signed: installing it asks you once to trust its key, instead of warning about unsigned content ([@LalaLolLucky](https://github.com/LalaLolLucky), issue #229).

---

## [3.2.25] — 2026-10-25

### Changed
- Build and release maintenance.

---

## [3.2.24] — 2026-10-24

### Added
- **Changelog** in the command menu: every version's notes with a search; the arrow keys browse versions.
- **Check for updates** in the command menu says whether a newer Claude Code or plugin is out, and updates either ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #222).
- **Drop files on the view** to attach them; a file from the project becomes an `@` mention ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #220).
- Muted lines for what Claude Code reports itself: a background agent finishing, failing or being stopped, messages from subagents, hook and safeguard notices ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #221).

### Changed
- A reopened conversation shows how long Claude thought, its narration between steps, and slash commands with their arguments ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #220).
- Session history names a conversation by its rename, its AI title or its last prompt, and leaves out scripted and background runs ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #220).
- The agent map lists agents started by other agents, and a reopened conversation keeps whether an agent was stopped or failed ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #220).
- A fork is named after its conversation, with "(fork)" ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #220).

### Fixed
- The agent map's token counts match what Claude Code reports, instead of counting the context again on every call ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #220).
- Session history, rewind and forks find conversations when `CLAUDE_CONFIG_DIR` is set ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #220).
- Claude Code's note after a stopped reply no longer shows as a message you sent ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #221).
- Rewinding to the first message opens a new conversation, instead of a tab that could not start.
- Windows: updating Claude Code installed with npm no longer fails with "Failed to check for updates" on the first click.

---

## [3.2.23] — 2026-10-21

### Changed
- The Claude Code view follows your settings and your organization's managed settings: permission modes, models, effort levels, fast mode, Remote Control, hooks, permission rules, plugins, output styles, the advisor and feedback that they rule out are greyed, with the reason ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #217).
- Signed in through Amazon Bedrock, Google Vertex AI or Microsoft Foundry, Account & usage names the provider, and Switch account and Sign out are hidden ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #217).

### Fixed
- Claude can open ABAP sources from SAP's ABAP Development Tools in the editor, and says plainly when it cannot show a diff for one ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #218).
- FreeBSD: a Claude Code view conversation no longer spins forever when Claude Code freezes inside FreeBSD's Linux compatibility layer.
- FreeBSD: a Claude Terminal tab whose Claude Code freezes, in the middle of a task or while idle, restarts on the same conversation by itself within seconds.
- FreeBSD: switching on Remote Control no longer fails with "the bridge did not connect" when Claude Code has frozen between replies; it is restarted and Remote Control comes on. Remote Control that is already on stays connected through a restart after a freeze.
- Windows: updating Claude Code from the update banner no longer fails on the first try when Claude Code was installed with npm.
- A failed update now says why it failed, instead of showing the version it started from.

---

## [3.2.22] — 2026-10-20

### Added
- **Manage Plugins:** install, update, turn on or off and remove plugins, and add, update or remove marketplaces, from "Manage plugins" in the command menu or `/plugins` ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #207).
- "Delete session" beside "Archive session" on History rows, archived ones too; it asks first ([@jmoraleda](https://github.com/jmoraleda), PR #209).
- A clock in the composer bar shows how long the prompt cache stays warm, and turns the accent colour once it has run out ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #206).
- Replies render much more Markdown: tables with alignment, task lists, footnotes, alerts, definition lists, math, Mermaid flowcharts and more ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #208).

### Changed
- The token count beside "Thinking…" counts only the thinking under way, and starts over with each thought ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #206).

### Added (Development tools)
- **Copy Logs and Clear Logs** in the Claude IDE Server view.
- `claudeCodeEclipse`: read the Claude IDE Server view's log.

### Fixed
- Linux: Eclipse no longer freezes when Claude asks to edit a file and the change's diff preview opens (issue #215).
- Replies stream as they are written, instead of appearing a block at a time ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #207).
- A message queued behind a turn you stop is sent once the turn ends ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #207).
- The context figure in the status bar matches `/context`; a long turn no longer reads 100% ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #206).
- The "prompt cache has likely expired" note on a reopened conversation goes away once Claude replies ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #206).

---

## [3.2.21] — 2026-10-17

### Added
- **Edit before you approve:** change a Bash command on its permission card, or a proposed Write or Edit in its diff preview, and the edited version is what runs. Off by default: tick "Enable editable commands on permission card" in the Preferences ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #203).
- "Yes, allow … for …" lets you choose where the rule is saved, and remembers your choice ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #203).
- `claudeCodeEclipse`: open, answer or close the advisor's card, and see the requests waiting behind a card.

### Changed
- The Agent map shows each agent's status (Working, Waiting for your permission, Finished, Failed or Stopped), who started it, its type and model, and a readable list of its tool calls ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #199).
- Timestamps show above Claude's replies too, with the full date and time on hover and one divider per day ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #200).
- A refused call shows why under its tool: your reason, or Claude Code's own text in its OUT box ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #203).
- A plain "No" on a permission card now stops and waits for you; give a reason and Claude carries on with it ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #203).
- A failed tool shows its whole error in its OUT box, not one cut-off line ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #201).
- MCP and plugin tools name their server in front of the tool, as in `Playwright › Browser click` ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #201).
- Answered questions show as the question's own output, with a start mode of their own in the Preferences ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #201).
- The buttons on tool input and output boxes show on hover only ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #201).

### Fixed
- A permission request that comes while another card is up waits its turn, instead of replacing it and leaving the first one unanswered ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #199).
- A background agent's permission card no longer disappears when the turn ends ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #199).
- One Esc closes one card: the request waiting behind it is no longer refused with it, and closing a card no longer also stops Claude's turn.
- The working indicator no longer stays up while a request waits behind another card.
- An edit's diff preview opens when its card comes up, not while the request still waits behind another.
- Choosing an advisor no longer restarts a conversation that is waiting on you.

---

## [3.2.20] — 2026-10-16

### Added
- The Agent map (`/tasks`) lists background tasks such as shells, with their output and a Stop button.
- `claudeCodeEclipse`: bring a tab to the front, answer a tab's question card, close a folder tab; in the Claude Terminal, list the tabs and switch plan mode on.
- **Send to Claude Terminal** and **Send to Claude Code** in the Problems and Console views' context menus.
- `coverage` tool: run a launch configuration under EclEmma and read its coverage, per package or line by line.
- `cIndex` tool: C and C++ references, callers, includes and macro expansion from CDT's index.
- `pydevCode` tool: Python definitions and references through PyDev.

### Changed
- The model chooser lists every model your installed Claude Code offers, as VS Code does, older ones such as Opus 4.8 included, with Claude Code's own descriptions.

### Fixed
- The model chooser no longer shows models without their version (such as "Opus" for Opus 5.5), which happened on the first start of the day and on macOS.

---

## [3.2.19] — 2026-10-14

### Fixed
- With Debug Mode on, an Eclipse started by a launcher that never reads its output (such as PDE Tools' "Launch New Workspace") no longer breaks Remote Control, model switching or the slash-command list, and prompts in a tab keep going through.

---

## [3.2.18] — 2026-10-14

### Added
- `/copy`, `/plan` and `/tasks` in the Claude Code view: copy one of Claude's replies, switch to plan mode or show the plan, and open the Agent map ([#90](https://github.com/eilonwy06/claudecode-eclipse-ide/issues/90)).
- `eclipseCustomizePerspective` tool: Claude shows or hides menu items, toolbar items, action sets and the New, Open Perspective and Show View shortcuts, as Customize Perspective does, and hides the whole main toolbar or any part of it, such as the launch bar.

### Changed
- `eclipseShowView` lists each open tab of a view by its title and can close one tab of a view that is open more than once.

---

## [3.2.17] — 2026-10-13

### Added
- `forge` tool: Claude runs the whole build procedure in one call (refresh, clean, build, refresh, build) and warns when Build Automatically is on.
- `autoBuild` tool: Claude reports Build Automatically, or switches it on or off.
- `quickFix` tool: Claude lists the quick fixes Eclipse offers for a file's problems and applies one.
- `projects`, `localHistory`, `tasks` and `workspaceSearch` tools: Claude opens, closes and imports projects, restores a file from local history, lists task markers, and searches with Eclipse's File Search.
- `callHierarchy`, `classpath` and `generateJava` tools for Java: a method's callers and callees, a project's build path, and generated getters and setters, constructors and override stubs.
- `targetPlatform` and `mavenUpdate` tools, where PDE or m2e is installed: reload the target platform, and update Maven projects from their `pom.xml`.
- `claudeCodeEclipse` in the Claude Code view: Claude reads a tab's conversation, stops its turn, attaches files to a prompt, forks, rewinds or deletes at a message, bookmarks replies, archives or renames a saved conversation, and gets the usage figures or the conversation as text.
- `claudeCodeEclipse` in the Claude Terminal: Claude reads what the terminal shows and presses keys in it.

### Fixed
- With Remote Control on, forking a conversation no longer switches Remote Control off in the conversation it was forked from.

---

## [3.2.16] — 2026-10-12

### Added
- Ultracode switch in the command menu's Model section and in the Modes and model menus; it is greyed for a model that cannot run it, and the status bar shows `ultracode` while it is on ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #184).
- The question card handles multi-select questions, with checkboxes, and shows an option's preview beside the options ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #187).
- Answered questions stay in the conversation as a folding overview with the chosen options marked; a dismissed one reads "Declined" ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #187).
- The Sandbox window can change the mode, the "Allow unsandboxed fallback" override and the excluded commands ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #183).
- A message sent while Claude is working can be forked and deleted like any other ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #182).
- The command menu has a footer with the plug-in and Claude Code versions and "Report a problem" ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #184).
- `/usage` opens Account & Usage ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #184).

### Changed
- `/context` opens a window built from Claude Code's own numbers: the model, a token bar, a breakdown by category, the memory files and the custom agents ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #184).
- Account & Usage shows the session's cost, time, code changes and what each model used, and the plan's limits with when each resets ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #184).
- Long conversations open several times faster ([@jmoraleda](https://github.com/jmoraleda), PR #185).
- Clicking the Effort or Zoom row in the command menu steps to the next value ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #184).
- In the command menu, Support comes last, after Appearance, and `/workflows` is no longer listed ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #184).
- The Sandbox window says in words why sandboxing is unavailable, and lists the sandbox's restrictions ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #183).
- A forked conversation is named after the one it came from, with "(fork)" after it ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #183).

### Fixed
- A message sent while Claude is working no longer disappears when the conversation is reopened ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #182).
- What Claude writes after a message sent mid-turn appears below that message, not above it ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #182).
- Find in Conversation opens whatever hides a match (the rest of a long message, a collapsed tool card, a thinking block) and no longer matches the labels of buttons ([@jmoraleda](https://github.com/jmoraleda), PR #186).
- "Thought for Ns" shows each thinking step's own time instead of growing through the turn ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #183).
- The token count beside "Thinking…" no longer falls back at each step of a turn ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #183).
- "Remote Control could not start." now says why ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #183).
- Account & Usage no longer says "not signed in" for an API key or auth token login ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #184).
- With "Enable Remote Control for all sessions" on, forking a message no longer opens two tabs.
- With "Enable Remote Control for all sessions" on, opening a conversation from Session History no longer leaves a blank connecting tab behind.

---

## [3.2.15] — 2026-10-09

### Added
- The command menu is rebuilt in sections (Context, Model, Customize, Settings, Support, Appearance), with switches for Thinking and Focus view (#90).
- Typing `/` in the message box opens the same command menu, filtered as you type (#90).
- Slash commands window: every command and skill the folder offers, with each skill's source and an On/Off switch (#90).
- Memory and Instructions windows: read, edit and delete Claude's memory files and the CLAUDE.md files.
- Permissions window: the folder's permission rules, with Add rule and Remove.
- Hooks window: the hooks in force, with add, edit and remove.
- Output styles window: pick a style, or build a custom one in four steps.
- Status, Sandbox and Export conversation windows.
- Claude in Chrome and Claude Design windows.
- Switch account, Sign out (asks first) and Report a problem, from the command menu; `/login`, `/logout`, `/design-login` and `/feedback` open the same windows (#90).
- Focus view: each run of tool calls folds into one "N tool calls" line that opens on a click.
- Toggle fast mode, with a bolt on the message box while it is on.
- "Switch models when a message is flagged" switch.
- "Open Claude in Terminal" moves the tab's own session into the Claude Terminal.
- A tool's input, output, diff and search results can each be hidden, collapsed to one line, shown as a few lines or shown in full, with buttons on each card ([@jmoraleda](https://github.com/jmoraleda), PR #179).
- An arrow after each tool line hides or brings back that tool's cards ([@jmoraleda](https://github.com/jmoraleda), PR #179).
- New preference "Start a tool's input, output and diff cards", with its own choice for Bash commands, file edits, search results, agents and web tools ([@jmoraleda](https://github.com/jmoraleda), PR #179).
- "Show all (N)" on a search result shows every row, each one clickable.

### Changed
- The preference "Enable remote control on startup" is now "Enable Remote Control for all sessions".
- `/model` typed on its own opens the model chooser.
- FreeBSD: updating Claude Code goes through `claude-freebsd` and checks that the version changed.

### Fixed
- A conversation left in front no longer comes back as "This conversation is empty or could not be loaded." when Eclipse is restarted.
- In a reopened conversation that was compacted, Delete, Fork and Rewind on a new message no longer act on an identical message from before the compaction.
- In a reopened conversation that was compacted, a bookmark no longer lands on an identical reply from before the compaction.
- A message sent just before an automatic compaction no longer disappears: it stays in view under the "Compacted chat" line.
- A restored tab still named "Claude Code" gets its title at startup.
- Archiving a conversation open in the view's last tab, then cancelling the "close the view" question, no longer leaves it open but archived.
- "Show less" on an expanded long prompt no longer covers its last words: it shows only while the pointer is over the message.
- The working indicator no longer stays on "Compacting" after an automatic compaction.
- The Claude Code view no longer loads blank on Linux and FreeBSD.
- The conversation keeps following the bottom when a card or an image grows after it is drawn ([@jmoraleda](https://github.com/jmoraleda), PR #176).
- Typing stays responsive in long conversations ([@jmoraleda](https://github.com/jmoraleda), PR #177).

---

## [3.2.14] — 2026-10-07

### Added
- Bookmarks: a bookmark button under each of Claude's replies, and a Bookmarks panel beside the conversation that lists that conversation's bookmarks.
- A copy button under each of Claude's replies.
- Session history can archive and unarchive sessions; archived ones sit under "Archived sessions".
- New preference "Archive Inactive Sessions": sessions idle for the chosen time (14 days by default) are archived on their own.
- New preference "Initial Permission Mode": the mode a new conversation starts in, in the Claude Code view and the Claude Terminal.
- New preference "Hide messages from before a compaction" (on by default): after a compaction the earlier messages are hidden, in the conversation, the Bookmarks panel and the Rewind list. Turned off, they stay under an expandable "Messages before compaction" line.
- A reopened conversation says when the prompt cache has likely expired and about how many tokens the next message will re-cache.

### Changed
- Session history rows archive a session instead of deleting it.
- Bypass permissions mode has its own color in the Claude Code view.
- Minor UI improvements.

### Fixed
- Session history no longer shows an out-of-date list when opened, and loads much faster.
- Reopening a long conversation no longer holds up Eclipse while it loads: its tab opens at once, its newest messages are shown first and the older ones fill in above.
- With Scroll Lock on, a tab restored at startup returns to where it was left instead of jumping to the end.
- Tool output and long prompts drawn while their tab was not in front are shortened again once the tab is shown.
- After an automatic compaction, what Claude writes next appears under the "Compacted chat" line instead of above it.

### Security
- Updated rustls to 0.23.45, which no longer accepts TLS 1.3 handshake messages sent at the wrong encryption level.

---

## [3.2.13] — 2026-10-06

### Added
- New MCP tools: `eclipseShowView`, `eclipseOpenPerspective` and `eclipseDialog`. Claude can open and close views and perspectives, and answer dialogs in this Eclipse or another one.
- New MCP tool: `claudeCodeEclipse`. Claude can open, set up, prompt and close Claude Code tabs, open them under another folder, run slash commands in them, reopen past conversations, and turn Remote Control on or off for a tab.
- `claudeCodeEclipse` works in the Claude Terminal too: open and close tabs, send a prompt or a slash command, set the model and effort, and switch Remote Control.
- The Claude Terminal can use the plugin's MCP tools, under the new preference "Enable MCP tools integration".
- New preference "Exclusively use terminal": Claude opens in the Claude Terminal and the Claude Code view is turned off.
- Up and Down in the composer go back through sent prompts ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #169).

### Changed
- The preferences are grouped under General, Claude Terminal view and Claude Code view.
- "Enable Thinking by default", "Enable remote control on startup" and "Allow bypass permissions mode" apply to the Claude Terminal too.
- "Allow bypass permissions mode" is off by default ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #169).
- Tabs are named like the history list, and a renamed tab keeps its name ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #169).
- Read shows the lines it read ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #169).
- A sent message's file and image chips share one scrolling row ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #169).
- The stop button keeps the color of the permission mode ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #169).

### Fixed
- A tool call that raises a dialog no longer hangs on it.
- A resumed conversation opens on its newest message ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #169).
- Search results open the file for context lines, drive-letter paths and single-file Grep rows ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #169).
- 1M-context models no longer show the older-model warning ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #169).
- "Bypass permissions" is listed only when the preference allows it ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #169).
- A closed editor's file is no longer sent to Claude ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #169).
- No doubled divider next to Cost in the status bar ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #169).
- The working text stays when Remote Control is switched during a turn.
- Remote control on startup keeps a restored tab's model, effort and thinking.
- Conversations in a second folder stay in their folder after `/compact`, a slash command or turning Remote Control on.
- `runTests` no longer waits out its timeout when its launch was cancelled.
- `runAs` with "JUnit Test" picks the launcher that applies when two share that name.
- Minor UI fixes ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #169).

---

## [3.2.12] — 2026-10-03

### Fixed
- Selections in tabbed editors such as `MANIFEST.MF`, `plugin.xml` and `pom.xml`, and in files not stored on disk, now reach Claude ([#153](https://github.com/eilonwy06/claudecode-eclipse-ide/issues/153)).
- Selection line numbers in the Claude Terminal are no longer off by one with Claude Code 2.1.181 and later.
- `runTests` works in JUnit 6 projects and takes a package name.
- `getCurrentSelection` reports the selection's columns, and a bare cursor as empty.

---

## [3.2.11] — 2026-10-02

### Added
- Copy buttons on search result lists, agent log text and agent responses ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #163).
- Clicking the file pill on a sent message opens the file and selects its lines.
- Clicking the model in the status bar opens the model menu.
- The update banner offers the package manager's own command for Homebrew, WinGet and apk installs ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #165).

### Changed
- The file chip in the composer has an X that stops sending the open file or selection; it comes back on a new selection or another file ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #161).
- Copy buttons are icons, with a checkmark once copied ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #163).
- The actions, model and slash menus are as wide as the composer, and the Agents panel has more room ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #164).
- The update banner follows the release channel set for Claude Code ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #165).

### Fixed
- In a narrow view, a tool's path no longer shares a line with the tool name, and its copy button no longer covers short text ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #162).
- In a narrow view, the composer's controls stay inside the box: the agents pill shrinks to a dot and the send button moves under the mic.
- Menus and cards fit a narrow or short view; a long plan or permission card scrolls inside itself.
- Messages sent from Eclipse no longer show the raw editor context tag on web and mobile.
- The working text no longer shakes as its glyph changes.
- Minor UI fixes and improvements.

---

## [3.2.10] — 2026-09-30

### Added
- New MCP tools: `debug`, `launches`, `errorLog`, `format` and `refactorResource`.
- New MCP tools with JDT: `getSource`, `refactorJava` and `organizeImports`.
- A context ring in the composer appears once 65% of the context is used; click it to compact ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #147).
- The welcome screen links to the MCP servers window and to the Claude Terminal ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #147).

### Changed
- The composer border, send button and loader take the color of the permission mode ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #147).
- A new loader animation while Claude is working, and a new icon for Bypass permissions ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #147).
- A new welcome screen ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #147).
- Read no longer shows the file's contents in the conversation ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #147).
- Grep and Glob show the folder they searched ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #147).
- The actions menu's command filter is at the top and ready to type in; the model menu has an effort slider ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #147).
- Minor UI fixes ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #147).

### Fixed
- Placing the cursor without selecting text no longer shows "1 line selected" ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #147).
- The status bar no longer shows 0% context after a conversation is resumed ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #147).
- A failed Eclipse tool call is no longer reported to Claude as a success.
- Eclipse's MCP traffic no longer goes through a proxy when NO_PROXY lists a similar-looking host.
- On Windows, console arguments that are empty, contain tabs, or end in a backslash are passed correctly.
- An internal error in the native library now shows an error instead of closing Eclipse.

---

## [3.2.9] — 2026-09-27

### Added
- /mcp opens an MCP servers window: see each server's status and tools, reconnect, enable or disable, sign in, and add or remove servers.

### Fixed
- The required Eclipse Terminal version is lowered to 1.0.0 (#148).
- A .bat or .cmd wrapper set as the Claude command now works, even in a folder with spaces.

---

## [3.2.8] — 2026-09-25

### Fixed
- Minor UI fixes.

---

## [3.2.7] — 2026-09-24

### Added
- On FreeBSD, a setup guide appears when Claude Code isn't installed.
- On FreeBSD, when a tool fails because fdescfs isn't mounted, Claude explains the fix.

### Fixed
- Thinking text is no longer empty on FreeBSD.
- A newly installed Claude Code is picked up on the next check, not an hour later.
- On macOS, copy on a code block now works.
- The conversation scrolls to a tool's output as soon as it arrives.
- A Copy button clicked twice no longer stays stuck on "Copied".

---

## [3.2.6] — 2026-09-23

### Changed
- Updated dependencies (axum 0.8, tokio-tungstenite 0.30, rubato 5).
- Minor UI fixes.

### Fixed
- Ctrl+F in an editor no longer opens the Claude Code find bar.

---

## [3.2.5] — 2026-09-22

### Fixed
- One unreadable subagent record no longer hides every agent log in the conversation.
- Tool output opened in an editor no longer leaves temp files behind after a crash.

---

## [3.2.4] — 2026-09-21

### Added
- **An Agents panel** lists each subagent with its duration and tokens, and opens its prompt, tool calls, response and full transcript; a running background agent can be stopped from there ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #122).
- **`/context`** shows how the context window is being used ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #122).
- **Security issues can be reported privately** through GitHub's vulnerability reporting ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #124).

### Changed
- **A tool's input and output share one box**, and long output or diffs are capped with a link that opens the full text in an editor ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #122).
- **Bash, Agent and Workflow show their description beside the tool name**, with the command or prompt always boxed ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #122).
- **Your latest prompt stays pinned at the top while you scroll** ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #122).
- **The composer no longer stretches edge to edge on a wide view** ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #122).

### Fixed
- **A subagent's own messages and tool calls no longer appear inside Claude's reply** ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #122).
- **A reopened conversation shows its tools' output**, not just their input ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #122).
- **Background agents report their real duration and token count** ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #122).
- **Grep and Glob show the search pattern** rather than the folder searched ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #122).
- **The context meter no longer reads past 100%** ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #122).
- **The actions menu has its filter box again** and no longer runs off-screen ([@LalaLolLucky](https://github.com/LalaLolLucky), PR #122).

---

## [3.2.3] — 2026-09-17

### Added
- **Connect Claude to Chrome.** `@browser` in the composer hands Claude the browser; a banner shows while it is connected, and its × ends it and tells Claude you meant to.
- **Auto permission mode**, which approves what passes a safety check and pauses for anything risky. The mode previously called Auto is now named Bypass permissions, and is listed only when the new preference allows it.
- **Preference: "Enable Thinking by default"** — new conversations start with thinking on.

### Changed
- **Switching model, effort, thinking or permission mode no longer restarts the conversation.** It applies to the one already running, and reaches claude.ai and your phone straight away.
- **A model, effort or mode picked on another device now shows up here**, within a second.
- **Thinking stays on while Remote Control is active.**
- **The menus are more compact.**

### Fixed
- **Remote Control no longer goes one-way**, dropping silently on the first message after you turn it on.
- **Restarting a conversation no longer leaves its Claude process running** on Windows.
- **Squeezed narrow, the view stays readable** — banners wrap onto their own rows, "Claude Code" no longer splits mid-word, and a horizontal scrollbar appears below the minimum width.

---

## [3.2.2] — 2026-09-14

### Added
- **Dictate into the composer (experimental)** with the microphone button or Ctrl+D — hold to talk, or tap to keep recording. Only available on macOS with debug mode; on Linux and FreeBSD it needs ALSA (plus `alsa-plugins` on FreeBSD).
- **Find in Conversation**: Ctrl+F searches the open conversation ([@jmoraleda](https://github.com/jmoraleda), PR #119).
- **A tool line's file path opens that file in an editor** when clicked ([@jmoraleda](https://github.com/jmoraleda), PR #120).

### Fixed
- **The Claude Terminal no longer goes blank after Clear & Refresh** ([@xgsa](https://github.com/xgsa), PR #121).
- **Escape no longer sometimes takes two presses to close something** ([@jmoraleda](https://github.com/jmoraleda), PR #119).

---

## [3.2.1] — 2026-09-09

### Fixed
- **A decision card answered on your phone or on claude.ai now closes here too**, instead of staying open and holding up every later prompt in the run.
- **Messages sent from another device now appear in the conversation**, the first one of a session included.
- **On macOS, thinking blocks expand again** — along with the model list, the version check and Update, which all failed together when Eclipse was started from Finder.

### Changed
- **The Claude Terminal's persistent scrollbar is painted in the terminal's own colors on Linux**, rather than standing on a grey panel beside it ([@xgsa](https://github.com/xgsa), PR #117).

---

## [3.2.0] — 2026-09-08

### Added
- **Continue a conversation on the web or your phone.** `/remote-control` in the Claude Code view hands the conversation to claude.ai; a status-bar indicator shows when it is live, and the chevron beside it opens a QR code to scan. Optionally on for every new conversation, in Preferences ▸ Claude Code.
- **A Web tab in Session History**, listing the conversations on your Claude account alongside the ones on this machine.
- **Teleport**: pick a web conversation and it comes down to this machine as a local session, with its files and branch checked out to match — it tells you when the session belongs to a different repository, or when the branch it was on is not the one you are on.

### Fixed
- **A conversation opened from Session History comes back with the model, effort, thinking and permission mode you left it on**, instead of resetting to the defaults every time (issue #114).
- **The Terminal's rightmost columns no longer go blank on themes whose scrollbar fades out**, the KDE default among them — with a "Persistent vertical scrollbar" preference, on by default and shown only where it applies ([@xgsa](https://github.com/xgsa), PR #116).

---

## [3.1.22] — 2026-09-07

### Added
- **Search in Session History**, the button beside the search box cycles titles, your own messages, or the whole conversation, and matches show the line they were found in ([@jmoraleda](https://github.com/jmoraleda), PR #113).
- **Persistent view state**, Claude Code view only: your conversations, which one you were in, your working-directory folders, the row's collapsed state, and your scroll position are restored on restart.

### Fixed
- Opening the conversation you are already in no longer duplicates its tab, Claude Code view only.
- Line breaks are now visible in Claude Code view.

---

## [3.1.21] — 2026-09-06

### Added
- **Scroll Lock can start switched on**, as a preference shared by the Claude Code and Claude Terminal views ([@jmoraleda](https://github.com/jmoraleda), PR #111).
- **"Smart" Scroll Lock**, Claude Code view only: the lock still holds your place while you read, but sending a message, answering a card, and a new card arriving each jump you to the bottom ([@jmoraleda](https://github.com/jmoraleda), PR #111).

### Changed
- **New Session and Session History moved to the Eclipse view toolbar**. Renaming moved onto the tabs themselves: hover a tab for its pencil, or double-click its name, in both views ([@jmoraleda](https://github.com/jmoraleda), PR #112).
- **Session History opens your pick in a new tab** instead of replacing the conversation you were in; `/resume` loads it into the current one instead ([@jmoraleda](https://github.com/jmoraleda), PR #112).
- **Escape closes the Session History panel** ([@jmoraleda](https://github.com/jmoraleda), PR #112).

### Fixed
- **On Linux, macOS and FreeBSD the plugin loads its native library straight from the bundle**, instead of quietly falling back to a temporary copy because every platform was being handed the Windows one.
- Minor UI/UX improvements.

---

## [3.1.20] — 2026-09-05

### Added
- **A timestamp above each message you send**, in local time, in live conversations and in history alike — off by default, in Preferences ▸ Claude Code ▸ Miscellaneous Configuration ([@jmoraleda](https://github.com/jmoraleda), PR #107).
- **Each usage meter can show when it resets**, as its own preference for the 5-hour and the weekly limit ([@jmoraleda](https://github.com/jmoraleda), PR #108).
- **The working-directory row can be hidden entirely** — picker, collapsed stand-in and toolbar toggle included ([@jmoraleda](https://github.com/jmoraleda), PR #109).
- **FreeBSD (x86_64 and ARM64), Linux on RISC-V (riscv64) and Windows on ARM support.**

### Changed
- **The conversation tab strip takes its colors from your own editor tabs**, so it matches whatever theme, OS or desktop environment you run ([@jmoraleda](https://github.com/jmoraleda), PR #106).

### Fixed
- **A tool that failed now says why**, instead of showing the same green dot as one that succeeded — while it runs and when you reopen the conversation.
- **A card waiting in one tab is no longer replaced by one opening in another**, which used to leave the first tab waiting forever ([@jmoraleda](https://github.com/jmoraleda), PR #104).
- **The tab strip's active and inactive colors were inverted under light themes** ([@jmoraleda](https://github.com/jmoraleda), PR #106).
- **An unsupported CPU architecture now reports itself as unsupported**, instead of failing deep inside the native library load.

---

## [3.1.19] — 2026-09-04

### Added
- **"Use custom spinner verbs"** in Preferences ▸ Claude Code ▸ Miscellaneous Configuration ▸ "Use custom spinner verbs" — add your own words to `~/.claude/settings.json` (e.g. `"spinnerVerbs": {"mode": "append", "verbs": ["Example-ing", "Sampling"]}`; or `"mode": "replace"` to use only your own) and tick this box to mix them into the Claude Code view's rotation.
  - *Known limitation:* Custom words added to `spinnerVerbs` in `~/.claude/settings.json` always show up in the Terminal's spinner, whether or not "Use custom spinner verbs" is ticked — that box only controls the Claude Code view.
- **The Terminal's spinner follows the same verb choices as the Claude Code view.** The categories you tick apply to both; a change reaches the next tab you open.

### Fixed
- The `/compact` bubble and its "Compacted chat" summary no longer swap places when you close a conversation and open it again.
- Minor UI fixes.

---

## [3.1.18] — 2026-09-03

### Changed
- Persistent bridge relay ports.

### Added (Development tools)
- **Stop/Start Server** in the Claude IDE Server view, taking the server and its relay down and back up together.

### Fixed
- **A dropped relay re-establishes itself**, and a disconnected peer no longer takes the relay down with it — either way, the MCP server's port is not affected and should work the same as before.

---

## [3.1.17] — 2026-09-02

### Fixed
- **Fixed port usage.** No more conflicts or pile-up between MCP servers and bridge relays — a restart reuses its port instead of stranding it and climbing to the next one.
- **Ports no longer die on a bad range.** An unusable port range used to take Eclipse with it; now the server just stays stopped, and the range is checked before it can be saved.

---

## [3.1.16] — 2026-09-01

### Added
- **Conversations can run in any folder, not just the workspace root.** A row of directory tabs sits above the conversation tabs — one per folder, each with its own conversations and its own session history. Add a folder from that row or from a project's context menu; Claude asks before it runs in one for the first time ([#93](https://github.com/eilonwy06/claudecode-eclipse-ide/issues/93)).
- **Show In ▸ Claude Code**, next to the Terminal's, opening the selected folder as a directory tab.

### Changed
- **The navigator's two Claude entries are grouped under one "Open Claude Here" menu**, holding Claude Code and Claude Terminal.

### Fixed
- Minor UI improvements for Claude Code view.

---

## [3.1.15] — 2026-08-30

### Added
- **Scroll Lock in the Claude Code view.** A toolbar toggle, the same one the Terminal has. With it on, reading back through a conversation no longer drags you to the bottom every time Claude writes a line, a tool finishes, or a card appears — and sending a message leaves you where you are. A **Jump to latest** button shows while you're held back; turning the lock off, or switching tabs, follows along again. It applies to every tab, is off by default, and changes nothing while off ([@jmoraleda](https://github.com/jmoraleda), PR #101).
- **New MCP tools: `refresh`, `clean`, `build` and `runAs`.** Claude can refresh, clean and rebuild your projects or the whole workspace, and run a project the way Run As does.
- **The permission, question and diff-review cards have configurable timeouts.** Default (30 minutes), Never, or a custom number of seconds, set independently for each ([@jmoraleda](https://github.com/jmoraleda), PR #102).

### Fixed
- **Session and weekly usage now show in the Claude Code view.** They stayed blank unless you also used the Terminal view, which was the only thing filling them in ([#99](https://github.com/eilonwy06/claudecode-eclipse-ide/issues/99)).
- **The "Other" box on a question card takes more than one line.** Shift+Enter inserts a newline, and Escape while typing no longer throws the answer away ([@jmoraleda](https://github.com/jmoraleda), PR #103).
- **A card that times out now clears itself.** It used to sit on screen after Claude had already moved on ([@jmoraleda](https://github.com/jmoraleda), PR #102).

---

## [3.1.14] — 2026-08-27

### Fixed
- **The prompt input's key bindings no longer misfire on Linux.** One press of Emacs's Ctrl+Y pasted two or three times, Ctrl+X H typed an "h" on top of selecting all, and Ctrl+V pasted the clipboard twice — GTK acting on a keystroke Eclipse had already taken. Any custom scheme shaped the same way is covered, not just Emacs. Windows and macOS were never affected ([@jmoraleda](https://github.com/jmoraleda), PR #100, [#97](https://github.com/eilonwy06/claudecode-eclipse-ide/issues/97))

---

## [3.1.13] — 2026-08-08

### Changed
- **Pasting with Ctrl+V in the prompt input should no longer insert the text twice on Linux.** GTK gives no way to call off the webview's own paste, so the second one is dropped — unconfirmed on GTK ([#97](https://github.com/eilonwy06/claudecode-eclipse-ide/issues/97)).
- **Debug mode logs the keys the prompt input sees**, and stamps each editing command with the time of the press behind it, so one press reported twice reads differently from a key repeating.

### Fixed
- **Long paths and URLs no longer push the conversation sideways.** They wrap inside the message now instead of raising a horizontal scrollbar with a white square in its corner. Code blocks, tables and diffs still scroll within themselves.

---

## [3.1.12] — 2026-08-05

### Added
- **Approving a plan asks how you want to continue.** Leaving plan mode now has its own card: apply edits automatically, keep being asked before each one, or stay in plan mode. The conversation ends up in the mode you picked instead of the one it started in.

### Changed
- **The permission modes are named Manual, Edit automatically, Plan and Auto.**
- **The editing commands no longer wait for the page to report in while it loads.** Possibly why the key bindings have never worked on Linux ([#97](https://github.com/eilonwy06/claudecode-eclipse-ide/issues/97)) — unconfirmed; Debug mode logs the handshake.

### Fixed
- **The permission mode button shows the mode you are actually in.** "Yes, allow all edits this session" switched the conversation without moving the button.
- **Rejecting a plan no longer tells Claude a file edit was skipped.**
- **The right-click menu picks up a key binding change straight away.**

---

## [3.1.11] — 2026-08-03

### Added
- **The Claude Code view honours your Eclipse key bindings.** Cut, Copy, Paste, Select All and Delete in the prompt input follow whatever is set under **Window → Preferences → General → Keys** — the Emacs scheme's Alt+W / Ctrl+W / Ctrl+Y, your own customizations, multi-keystroke sequences and all. The right-click menu shows those keys too. Previously only Ctrl+X / Ctrl+C / Ctrl+V / Ctrl+A worked, and they still do on the default scheme.

### Changed
- **General UI improvements to image attachments.**
- **Pasting copied web content keeps its images.** The text goes in the prompt and the images come with it as attachments, downloaded in the background. Icons, spacers and tracking pixels are left out.

### Fixed
- **Typing your own answer to a question no longer confuses Claude.** Text entered under **Other**, or under **Tell Claude what to do instead** on a permission prompt, arrived unlabelled on the channel tool results use — Claude took it for tool output impersonating you and refused it as a possible prompt injection. It is now marked as coming from you.
- **The Delete key works in the prompt input.**

---

## [3.1.10] — 2026-08-01

### Fixed
- **Arrow keys no longer type box characters on macOS.** Pressing ← or → at the ends of the message you're writing — or any arrow key in an empty composer — inserted an invisible control character that showed up as a box, and got sent to Claude along with your message. Windows and Linux were never affected.
- **The rewind confirmation fits on screen again.** Rewinding across dozens of files pushed the note and the **Continue** / **Never mind** buttons off the bottom of the dialog, so there was no way to reach them without scrolling a long way down. The file list now scrolls inside the dialog, with the line counts pinned above it and the buttons pinned below.
- **The working indicator no longer keeps spinning after a turn ends.** Leaving a permission prompt or a question unanswered for long enough that the turn behind it ended left the animated indicator running at the bottom of the conversation with nothing left to stop it. It's now removed whenever the turn it belongs to is over.

---

## [3.1.9] — 2026-07-31

### Fixed
- **Links in Claude's responses open in your browser.** Clicking a link in a response used to load the page inside the Claude Code view itself, replacing the conversation with no way back — the only way to recover it was to close the view and reopen the conversation from session history. Links now open in your system browser and the conversation stays put.
- **The links on cards and in the account panel open in your browser too.** "Learn more" on the advisor card, "View usage" on the usage warning and "Manage usage on claude.ai" in the account panel used to open a bare popup window with no address bar, toolbar or tabs. They go to your normal browser now, like every other link.

---

## [3.1.8] — 2026-07-31

### Added
- **Per-message actions in the Claude Code view.** Hover a message you sent and a badge appears on the bubble. The undo badge opens **Fork conversation from here**, **Rewind code to here** and **Fork conversation and rewind code** — so you can now restore the files without forking, or fork without touching the files, instead of always doing both. Rewinding is still available from the actions menu and `/rewind`.
- **Delete a message you sent.** The trash badge beside it asks for confirmation, then permanently removes that message from the conversation's history — it's gone from the Claude Code view, from `claude --resume`, and from any other Claude Code client reading the same project.

### Changed
- **Forking lands on the newest message.** A forked conversation opens scrolled to the bottom, right above the message waiting in the composer, instead of at the top of the conversation.

### Fixed
- **Rewind could not restore code at all.** Since Claude Code 2.1.220, every rewind reported "The code has not changed, so no code will be restored" and left the files alone — the CLI had moved the pre-edit backup it records for each file into a new kind of transcript entry that the plugin didn't read, so it always compared against the *post*-edit version. Rewinding now restores files again, in existing conversations as well as new ones.
- **Messages sent with images were invisible to rewind.** They never appeared in the "Rewind to…" list, and forking at one opened the new tab with an empty composer instead of the message you'd sent. Every message you sent is now listed and carries its text into the fork.
- **Reopened conversations are formatted correctly again.** Internal markers that Claude Code attaches to messages — the file you have open, interruption notices, image scaling notes — were showing up verbatim as text you'd sent, in message bubbles, the rewind list and a forked composer. They're stripped or rendered as the notes they are, so a reopened conversation looks the way it did live.
- **Pasted images are restored when you reopen a conversation.** Messages you sent with images attached now show those image chips again, and clicking one still opens it full size.

---

## [3.1.7] — 2026-07-29

### Fixed
- **Thinking is readable again in the Claude Code view.** The "Thought for Ns" step expands to Claude's reasoning again on recent models.
- **Conversations from the Claude Code view appear in session history again.** Applies to new conversations only — ones started before this release stay hidden.

### Changed
- **Effort and Thinking can no longer be set to a combination Claude rejects.** Claude 5 models refuse X-High and Max effort with Thinking off. Thinking now stays on at those levels, the slider stops at High while Thinking is off, and switching to a Claude 5 model turns Thinking back on. Earlier models are unaffected.

---

## [3.1.6] — 2026-07-28

### Added
- **Image pasting in the Claude Code view** — paste a screenshot or image straight into the message box and it's sent along with your message. Click an attached image to view it full size.
- **`/advisor` and `/compact` slash commands in the Claude Code view.**
- **Update Claude Code from inside the Claude Code view.** When a newer Claude Code release is published, a banner appears at the top of the view telling you which version you have and which is available. **Update** runs Claude Code's own updater, so it works however you installed it, and reports when it's done — restart Eclipse (or open a new tab) to pick up the new version.
- **Permission mode is now per conversation.** "Ask before edits", "Edit automatically", "Plan mode" and "Auto mode" are remembered per tab, so one conversation can apply edits automatically while another keeps asking. New tabs start at "Ask before edits" rather than inheriting, the choice is restored when you reopen a past conversation, and changing it mid-conversation takes effect immediately instead of on the next message.

### Changed
- **Claude Terminal copy and paste now behave the same from every trigger** — Ctrl+V, Ctrl+Shift+V, Shift+Insert and right-click → Paste all take one path, and images paste from all of them, including the context menu. Copy is unified across Ctrl+C (with a selection), Ctrl+Shift+C and Ctrl+Insert; Ctrl+C with nothing selected still interrupts Claude. On Linux, image paste needs `xclip` (X11) or `wl-clipboard` (Wayland) ([@xgsa](https://github.com/xgsa), PR #87)
- **"New Session" now comes before "Resume Session" in the Claude Terminal** — matching the usual order of New and Open actions ([@xgsa](https://github.com/xgsa), PR #88)

### Removed
- **The broken "Resume Session" menu item** — it pointed at the wrong view and failed with "Could not create the view". Resuming is already available from the Claude Terminal's "Session history" toolbar button and the Claude Code view's history panel ([@xgsa](https://github.com/xgsa), PR #88)

### Fixed
- **Ctrl+V pasted text twice in the Claude Terminal on Linux** ([@xgsa](https://github.com/xgsa), PR #87)
- **Opening a conversation that's already open now switches to its tab** instead of loading a second copy of the same conversation in a new one.
- **`/model` did nothing when picked from the command menu.** Choosing it opened the model chooser and immediately closed it again, so the command appeared to be dead.
- **Text you were part-way through typing is no longer discarded** when you pick a command from the menu — the message you were composing is kept.
- **A command's reply could appear in the wrong tab.** Running a command while another conversation was still generating could print the response into that other conversation instead of the one you were in.

---

## [3.1.5] — 2026-07-22

### Added
- **Light theme support for the Claude Code view.** The chat view now follows Eclipse's light/dark theme instead of always rendering dark: switch Eclipse to a light theme (General &gt; Appearance) and the whole view — backgrounds, text, menus, cards, code blocks, and diffs — recolors to a matching light palette, keeping the Claude coral accent. The dark appearance is unchanged. The change applies the instant you switch themes, no reopen needed.

---

## [3.1.4] — 2026-07-18

### Fixed
- **Step-dot colors are kept when you reopen a past conversation.** Reloading a conversation from the session-history list used to reset every step dot to gray. The Claude Code view now reconstructs each step's outcome from the conversation transcript, so finished tools stay green and interrupted or rejected ones stay red — matching how the conversation looked live.

---

## [3.1.3] — 2026-07-16

### Added
- **Linux aarch64 (ARM64) support** — the native core library now ships for ARM64 Linux (Raspberry Pi 4/5, AWS Graviton, Ampere, and other ARM64 machines), alongside the existing x86_64 build. All plugin features work identically.

---

## [3.1.2] — 2026-07-14

### Changed
- **Clearer step dots in the Claude Code view.** A step's dot is gray while it's pending or in progress (and for plain conversational replies), turns green only once a tool has finished, and turns red when a step is interrupted or a permission request is rejected. While a permission or question card is open, that step's dot stays gray, then resolves to green (accepted) or red (rejected).

### Fixed
- **Pressing Stop now really stops.** Previously a reply already in flight could still slip in just after you cancelled; the view now drops anything the stopped turn sends after you press Stop, so nothing new appears.
- **Interrupting a turn is clearer.** The step you stopped at is marked in red, followed by "Request cancelled." and an italic "Tool interrupted" (or "Interrupted" when no tool was running) note.

---

## [3.1.1] — 2026-07-14

### Changed
- **The MCP server now always starts on launch.** The plugin can't function without it, so it starts unconditionally rather than depending on a preference. As a result, the old "Start server automatically on Eclipse launch" checkbox has been repurposed into **"Open new Claude Terminal automatically on Eclipse launch"** — tick it to have a Claude Terminal tab open by itself when Eclipse starts (off by default).
- **Status bar preference changes now apply live.** Toggling which segments the status bar shows (model, cost, context, usage limits, and so on) — and, in the Claude Code view, the refresh interval — takes effect immediately in already-open Claude Terminal and Claude Code views, without relaunching a session. (The Terminal's refresh interval still binds on the next launch.)
- **Preferences: the status bar section is retitled** "Claude status bar configuration" (it governs both the Claude Terminal and Claude Code status bars, not just the terminal).

### Fixed
- **Diff highlights now span the full width of a scrolled line.** In the Claude Code view, the red/green background on an added or removed line used to stop at the visible edge; scrolling a long line sideways revealed unhighlighted text. The highlight now extends across the entire line.

---

## [3.1.0] — 2026-07-11

### Changed
- **The bundled PHP runtime is removed.** Everything it did (the session-history reader and the bridge relay) now runs inside the plugin's native core — same behavior, same ports, same session-history output, but the plugin is ~150 MB smaller, starts these features faster, and no longer extracts a scripting runtime to a temp folder.
- **Deleting a conversation also closes its tab.** Removing a conversation from the session-history list now closes the tab it was open in (deleting the only open conversation just clears the view), matching the VS Code extension.
- **More variety in the working indicator** — the status word draws from a bigger pool while Claude works.

### Fixed
- **Renaming in the session-history list no longer fights your clicks.** Clicking inside the rename field to position the cursor or select text now behaves like a normal text field — previously it could open the conversation or abort the edit.

---

## [3.0.3] — 2026-07-10

### Added
- **Session history in the Claude Terminal** — a new "Session history" toolbar button opens a fresh tab running `claude --resume`, the interactive picker for jumping back into a past conversation.

---

## [3.0.2] — 2026-07-09

### Changed
- **Renaming a conversation is now shared with the CLI and other IDEs.** Renaming in the Claude Code view is written into the conversation itself (via the CLI's native rename), so the new title also shows up in `claude`'s `/resume` picker, the VS Code extension, and any other Claude Code client reading the same project — and a rename made in those places shows up here too. Previously a rename was only visible inside Eclipse. (Thanks to [@xgsa](https://github.com/xgsa) for pointing out the cross-client approach.)
- **Session-history list now matches the CLI's `/resume` exactly.** Titles use your rename first, then the CLI's AI-generated title, then the first message; conversations that only have a title (no messages yet) are listed too; and everything is ordered by last activity — so the history panel and `/resume` show the same conversations in the same order.

### Fixed
- **Each conversation tab keeps its own unsent draft.** Text typed but not sent no longer follows you when you switch tabs — every tab restores what you had in its composer.
- **Windows: the Claude Terminal reliably finds `claude` installed via npm.** A bare `claude` command is now resolved against `PATH`/`PATHEXT` (so `claude.cmd` is found) and launched without mangling its arguments (#83).

---

## [3.0.1] — 2026-07-08

### Added
- **Rewind — restore code and fork the conversation.** From the Claude Code view's actions menu (or `/rewind`), pick any earlier message to jump back to: a preview shows exactly which files will change and how many lines are added/removed, then Claude's edits are rolled back to how the files looked at that message and the conversation is forked into a new tab with your message ready to edit and resend. The original conversation is left untouched. Files changed manually or via shell commands aren't affected, and messages from conversations predating this release can only fork (their file states were never checkpointed — the dialog says so).
- **Rename conversations** — from the conversation header (hover, then click the pencil) or the session-history list; custom titles persist and survive resuming.
- **Terminated indicator in the Claude Terminal** — the view now shows clearly when the CLI process has exited ([@xgsa](https://github.com/xgsa), PR #79)

### Changed
- **Richer Markdown rendering in the Claude Code view** — covers the full GitHub-flavored Markdown Claude emits (tables, blockquotes, strikethrough, and more) ([@xgsa](https://github.com/xgsa), PR #81)
- **Smarter session-history titles and ordering** — past conversations now use the CLI's AI-generated titles when available (falling back to the first message) and are ordered by last activity, matching the CLI's `/resume` list.
- **Working indicator polish** — the status word now morphs typewriter-style between words instead of switching abruptly.
- **The composer adapts to narrow panels** — as the view shrinks, button labels collapse to icons (mode first, then the file context) and the send button minimizes, so the input controls never overflow the panel.

### Fixed
- **Cancelling a turn no longer leaves a half-rendered answer** — the in-progress text is discarded and only "Request cancelled." remains.
- **Long permission prompts wrap correctly** — an approval option with a long command or path no longer runs past the card's edge.

---

## [3.0.0] — 2026-07-06

Major release introducing the **Claude Code** view — a full graphical chat panel — alongside status-bar, theming, and terminal improvements.

### Added
- **Claude Code view — a VS Code-style graphical chat panel.** A rich in-IDE chat experience alongside the terminal:
  - **Multiple concurrent conversation tabs**, each backed by its own persistent Claude process — a streaming conversation in one tab never blocks typing or sending in another. Tabs can be reordered by dragging.
  - **Per-conversation model, reasoning effort, and extended thinking**, remembered and restored when you reopen a past conversation.
  - **Dynamic model chooser** — the list is fetched from the models your account can actually use (curated to the latest of each family), so it keeps working as new models ship instead of relying on a hardcoded list.
  - **In-panel status bar** — the same widget as the terminal, showing the live model, context-window usage, and session cost.
  - **Inline permission and question cards** — approve or deny tool use and answer Claude's multiple-choice questions right in the panel, enforced by the CLI; a "remember this" choice is scoped correctly to the specific action.
  - **Live extended-thinking reveal**, **model-switch dividers** within a conversation, **session history** (list / resume / delete past conversations), and **inline file diffs**.
- **Status bar for the Claude Terminal** — shows the live model, context-window usage, and session cost ([@xgsa](https://github.com/xgsa), PR #63)
- **Shift+Enter inserts a newline in the Claude Terminal** — for composing multi-line prompts without sending ([@xgsa](https://github.com/xgsa), PR #76)

### Changed
- **Views renamed for clarity** — "Claude CLI" → **Claude Terminal**, and the former "Claude Code" server view → **Claude IDE Server**; the new graphical chat panel takes the **Claude Code** name. The main menu was reorganized to group related actions, and the advanced "Claude IDE Server" view/menu entry is now hidden unless debug mode is enabled ([@xgsa](https://github.com/xgsa), PR #73)
- **Claude Terminal colors and font moved to the standard Colors and Fonts preferences** — theme the terminal (and the renamed **Claude Terminal Font**) from **Preferences → General → Appearance → Colors and Fonts**. Note: any terminal colors you previously customized reset once to the new defaults and must be re-picked there ([@xgsa](https://github.com/xgsa), PR #77)
- **Terminal light/dark hint auto-derived from the terminal background** — the separate "Claude CLI theme" preference is gone; Claude's light/dark hint now follows your terminal's background color automatically ([@xgsa](https://github.com/xgsa), PR #69)

### Fixed
- **Claude Code view honors your configured `--model`** — when the model picker is on "Default", the panel now launches with the `--model` from your Claude *Arguments* preference (matching the Claude Terminal), instead of silently falling back to the account's default model.

---

## [2.4.9] — 2026-06-22

### Changed
- **Unified file-open dialog and faster Ctrl+Click resolution** — the multiple-files open dialog now shares the common entity-selection dialog used by the other resolvers, and identifier/file resolution is faster (resolvers run in parallel, with optimized workspace traversal) ([@xgsa](https://github.com/xgsa), PR #61)

---

## [2.4.8] — 2026-06-22

### Added
- **Ctrl+Click to open C/C++ identifiers** — clicking a C or C++ type, function, or symbol name in the Claude CLI view now resolves and opens its definition (when CDT is installed) ([@xgsa](https://github.com/xgsa), PR #58)

### Changed
- **Clearer Ctrl+Click hint bar message** — the one-time hint now reads more naturally ([@xgsa](https://github.com/xgsa), PR #59)

### Security
- **Hardened local server access** — tightened validation so the plugin's local MCP server only accepts connections originating from this machine

---

## [2.4.7] — 2026-06-15

### Fixed
- **Optional language tooling dependencies** — PyDev and JDT are now truly optional; the plugin no longer fails to load when either is absent ([@xgsa](https://github.com/xgsa), PR #57)

---

## [2.4.6] — 2026-06-14

### Added
- **Ctrl+Click to open Python identifiers** — clicking a Python class, function, or variable name in the Claude CLI view now resolves and opens its definition, alongside a matcher for accurate identifier recognition ([@xgsa](https://github.com/xgsa), PR #52)
- **One-time Ctrl+Click hint bar** — a dismissable hint shown once in the Claude CLI view lets you know identifiers and paths can be opened with Ctrl+Click ([@xgsa](https://github.com/xgsa), PR #54)

### Changed
- **Improved Java identifier resolver dialog** — clearer disambiguation when a clicked Java identifier matches multiple candidates ([@xgsa](https://github.com/xgsa), PR #53)

---

## [2.4.5] — 2026-06-13

### Changed
- **Reverted the spaced-path Ctrl+click heuristics introduced in 2.4.4** — entity recognition is back to the previous simple behavior. Thanks to [@xgsa](https://github.com/xgsa) for the analysis and feedback.

---

## [2.4.4] — 2026-06-12

### Fixed
- **Claude now auto-connects to the IDE** — sessions export the auto-connect variable current CLIs actually read (`CLAUDE_CODE_SSE_PORT`); previous releases set only legacy variables, so the IDE integration could silently never connect (the legacy `CLAUDE_IDE_*` variables are still exported for older CLIs)
- **Claude sees your active file and selection in real time** — selection changes are pushed in the exact notification format the CLI consumes, and the latest selection is replayed when a session connects, so Claude knows the current file immediately without you clicking in the editor first
- **Selection line numbers are exact** — reported ranges were off by 1–2 lines; selections now carry true line and column positions (a selection ending at a line start correctly excludes that line)
- **Ctrl-click works on paths containing spaces** — absolute paths (`C:\Users\My Name\…`), workspace-relative paths (`Project\src\my dir\File.java`), and paths wrapped across terminal rows all open from any clicked segment; every candidate is validated against the filesystem before opening
- **Ctrl-click works on file names with spaces mentioned in prose** — clicking `Sample File.java` in a sentence finds and opens the file via the workspace search
- **Ctrl-clicking ordinary text is quiet now** — no more "Unable to recognize entity" status-bar error on every non-link word; the right-click **Open** action still reports misses
- **Multiple Eclipse instances now coexist** — launching the CLI no longer deletes other instances' lock files (reverses the 2.3.13 workaround; the CLI pins to the correct instance by port), and internal relay ports are assigned per instance from the MCP port range instead of fixed ports

### Changed
- **Send Selection (`Ctrl+Alt+S`) and Add File (`Ctrl+Alt+A`) now insert an `@file` mention** (with a `#Lstart-end` line range for selections) at the Claude prompt — previously these actions had no visible effect

---

## [2.4.3] — 2026-06-06

### Added
- **Stop button in Claude Chat** — Send button transforms to a red Stop (■) button while processing; click it or press Escape to cancel the current request; Enter key disabled during streaming to prevent accidental cancellation

---

## [2.4.2] — 2026-06-05

### Added
- **Scroll lock button** in Claude CLI toolbar — toggles auto-scroll behavior ([@xgsa](https://github.com/xgsa), PR #33)
- **Ctrl+\` shortcut** to quickly activate/focus the Claude CLI view ([@xgsa](https://github.com/xgsa), PR #35)

### Changed
- **Send Selection shortcut changed from Ctrl+Shift+S to Ctrl+Alt+S** — avoids conflict with "Save All" on Linux ([@xgsa](https://github.com/xgsa), PR #30)
- **Polished toolbar and popup menu** — updated icons for "New Session" and "Clear & Refresh" actions, improved menu item states ([@xgsa](https://github.com/xgsa), PRs #32, #33)

### Fixed
- **NPE on Eclipse termination** — terminals now properly disconnect when Eclipse shuts down, preventing NullPointerException in workbench listener ([@xgsa](https://github.com/xgsa), PRs #31, #34)

---

## [2.4.1] — 2026-06-03

### Fixed
- **Ctrl+C with selection now copies instead of sending SIGINT** — when text is selected in the terminal, `Ctrl`/`⌘`+`C` copies to clipboard; only sends interrupt when nothing is selected (thanks [@xgsa](https://github.com/xgsa), PR #28)

---

## [2.4.0] — 2026-06-03

### Changed
- **Claude CLI now uses the embedded Eclipse Terminal** on all platforms, replacing the previous native console approach (Windows conhost / Linux + macOS Rust PTY + SWT StyledText renderer). This brings a mature terminal with proper scrollback, 24-bit truecolor, resize, and text selection — and fixes the Linux/macOS scrollback quirks
- **Tab titles** now reflect Claude Code's current task (when provided by the CLI), falling back to "Claude N"

### Added
- **Customizable Claude CLI colors** — set the terminal background and foreground in Window → Preferences → Claude Code ("Claude CLI background" / "Claude CLI foreground"); independent of Eclipse's built-in Terminal colors and applied immediately without restart
- **Copy/paste and right-click menu** — Copy, Paste, Select All, and Clear & Refresh, plus cross-platform keyboard shortcuts (`Ctrl`/`⌘`+`V` or `Shift`+`Insert` to paste; `Ctrl`/`⌘`+`Shift`+`C` or `Ctrl`/`⌘`+`Insert` to copy; `Ctrl`/`⌘`+`C` copies a selection, otherwise interrupts Claude)
- **Shift+Tab** support (Claude's auto-mode cycle)
- **24-bit truecolor** — sets `COLORTERM=truecolor` so Claude emits RGB colors (thanks [@xgsa](https://github.com/xgsa), PR #26)
- **Login-shell environment capture (macOS/Linux)** — GUI-launched Eclipse now inherits the login-shell `PATH` and proxy variables, so Claude installed via nvm/asdf/Homebrew/`npm -g` and shell-rc proxy settings are found

### Dependencies
- Now requires the Eclipse Terminal bundles — `org.eclipse.terminal.control`, `org.eclipse.terminal.connector.process`, `org.eclipse.terminal.connector.local` (1.1.0+) — and their CDT native PTY dependency. These ship with most Eclipse packages; on a minimal "Eclipse IDE for Java Developers" they may need installing first (see the README)

---

## [2.3.15] — 2026-05-02

### Added
- **Console theme preference** — new "Console theme" dropdown in Window → Preferences → Claude Code to switch between Dark and Light themes; changes apply immediately without restart; works on all platforms (Windows uses native console color API, Linux/macOS use SWT StyledText colors)

---

## [2.3.14] — 2026-05-02

### Added
- **Console font preference** — the Claude CLI terminal font can now be customized in Window → Preferences → General → Appearance → Colors and Fonts → Basic → Claude CLI Console Font; defaults to Eclipse's Text Font setting; works on all platforms (Windows, Linux, macOS)

### Changed
- **Debug logging gated by preference** — all debug output (Java and Rust) is now controlled by the Debug mode checkbox in Claude Code preferences; no more console spam when debug mode is off

---

## [2.3.13] — 2026-04-28

### Fixed
- **MCP multi-workspace conflicts** — launching Claude CLI now clears other Eclipse instances' lock files and rewrites its own, ensuring the CLI connects to the correct MCP server when multiple Eclipse workspaces are open

### Changed
- **Restart Server** button now also restarts all CLI sessions so MCP tools reconnect automatically
- **MCP status indicator** in Claude Code view now shows client connection count (green = connected, yellow = waiting for clients)

---

## [2.3.12] — 2026-04-25

### Changed
- **Linux binary compatibility** — rebuilt `libclaude_eclipse_core.so` with older glibc symbols to support a wider range of Linux distributions (Ubuntu 20.04+, Debian 10+, RHEL 8+, etc.)

---

## [2.3.11] — 2026-04-21

### Changed
- **"Debug mode" preference** — the Claude Code preference that controls verbose logging is now simply labelled **Debug mode**, and all internal diagnostic console output consistently respects it (no log spam when it's off)
- **Connection status in the Claude Code view** — the view now surfaces the IDE-integration connection state more clearly, including a distinct indicator and message on macOS when the direct connection path is active

---

## [2.3.10] — 2026-04-21

### Changed
- **Version alignment** — brought the manifest and packaging files up to the correct version so every bundle reports a consistent release number

---

## [2.3.9] — 2026-04-21

### Fixed
- **macOS IDE integration falls back gracefully** — when the standard connection path can't be established on macOS, the plugin now switches to a direct connection instead of showing an "off" state, so the IDE integration keeps working; the Claude Code view reflects this with a distinct status indicator

---

## [2.3.8] — 2026-04-21

### Fixed
- **macOS connectivity — Homebrew tooling and Gatekeeper quarantine** — on macOS the plugin now prefers a Homebrew-installed helper when present (Apple Silicon `/opt/homebrew`, Intel `/usr/local`) and otherwise clears quarantine attributes from the bundled binary first, so the IDE integration starts on freshly downloaded installs

---

## [2.3.7] — 2026-04-21

### Fixed
- **Unix startup reliability** — the helper process is now launched through a login shell on all non-Windows platforms (previously macOS only), giving it a proper environment so the IDE integration starts consistently on Linux and macOS

---

## [2.3.6] — 2026-04-21

### Fixed
- **macOS process startup** — the IDE-integration helper is now spawned through a shell on macOS so it inherits a complete environment, fixing cases where launching from an `.app` bundle left it partially initialized

---

## [2.3.5] — 2026-04-21

### Fixed
- **macOS startup diagnostics** — added earlier, more precise readiness reporting so genuine startup failures surface immediately instead of appearing to hang (part of the ongoing macOS connectivity fixes)

---

## [2.3.4] — 2026-04-21

### Fixed
- **macOS startup handshake** — replaced the pipe-based readiness signal with a more robust file-based one to work around macOS pipe-buffering that could prevent Eclipse from detecting that the IDE-integration helper had started

---

## [2.3.3] — 2026-04-21

### Fixed
- **macOS startup detection** — hardened readiness detection with early-exit reporting and a fallback signal path, so a helper process that dies during startup is reported instead of silently timing out

---

## [2.3.2] — 2026-04-21

### Fixed
- **macOS startup diagnostics** — added detailed startup logging to pin down why the IDE integration wasn't coming up on some macOS setups (first of the 2.3.2–2.3.9 macOS connectivity fixes)

---

## [2.3.1] — 2026-04-21

### Changed
- **Packaging/update-site refresh** — rebuilt the p2 update site and bumped the bundle version; no functional changes

---

## [2.3.0] — 2026-04-21

### Added
- **Open Claude CLI Here** — right-click context menu in any navigator (Package Explorer, Project Explorer, etc.) to launch a Claude CLI session scoped to the selected folder or project
- **Show In → Claude CLI** — Package Explorer now supports "Show In → Claude CLI" across 30+ perspectives (Java, Java EE, Node.js, Python, C/C++, etc.)
- **Proxy preferences** — new HTTP Proxy, HTTPS Proxy, and NO_PROXY fields in preferences; auto-localhost safeguard prepends `localhost,127.0.0.1,::1` when a proxy is active
- **Apple Silicon support** — native `aarch64` dylib now bundled for M1/M2/M3 Macs (fixes #5)

### Changed
- Claude CLI tab labels now show the full project-relative path (e.g., `Claude (MyProject/src/main)` instead of just `Claude (main)`)

---

## [2.2.3] — 2026-04-19

### Added
- **Custom CLI arguments** — new "Arguments" field in preferences lets you pass additional flags to the Claude CLI (e.g., `--model claude-opus-4-7-20260418`); arguments are appended to every terminal launch

---

## [2.2.2] — 2026-04-17

### Fixed
- **macOS bare `claude` command** — Eclipse.app launched from Finder only inherits a minimal PATH, so a bare `claude` in preferences previously failed with "spawn failed … not found on PATH"; the Rust core now captures the user's login-shell PATH on first use (via `$SHELL -l -i -c`, 5-second timeout, cached for the session) and injects it into both the chat and PTY child processes, so `claude` installed under Homebrew/nvm/asdf resolves without pasting an absolute path
- Users who already configured an absolute path (e.g. from `which claude`) continue to work unchanged — the captured PATH is only used to resolve the command

---

## [2.2.1] — 2026-04-17

### Added
- **macOS support** — prebuilt native libraries (`libclaude_eclipse_core.dylib`) ship for both Apple Silicon (`aarch64`) and Intel (`x86_64`); Mac users on the PTY + StyledText terminal path now work identically to Linux

### Changed
- PTY terminal font selection is now platform-aware: `Menlo` on macOS, `Monospace` on Linux — prevents SWT falling back to a proportional font on Mac

---

## [2.2.0] — 2026-04-17

### Added
- **Inline diff accept/reject** — `openDiff` MCP tool now opens a native Eclipse compare editor where proposed changes can be merged into the current file; CLI "Yes" auto-applies and closes the diff, closing the tab rejects it, and Ctrl+S on an unmerged diff also rejects (detected via document-level interaction tracking)
- New MCP tools: `acceptDiff`, `rejectDiff`, `getDiffStatus`
- Pending diffs automatically close when the MCP client connects or disconnects, preventing stale compare tabs across sessions

### Changed
- Refactored Rust core with a dedicated `session` module to coordinate MCP client lifecycle with the diff registry

---

## [2.1.0] — 2026-04-04

### Added
- **Linux support** — Claude CLI view now works on Linux using the native Rust PTY system with an SWT StyledText terminal renderer; full ANSI color, keyboard input, scrollback, and resize support
- Linux native library (`libclaude_eclipse_core.so`) bundled for x86_64

### Changed
- Claude CLI view now detects the platform at startup: Windows uses the embedded conhost approach, Linux/macOS uses PTY + StyledText rendering

---

## [2.0.1] — 2026-04-04

### Fixed
- **Chat special characters** — messages containing `"`, `\`, `/`, `'` no longer trigger "not recognized as an internal or external command" errors; removed manual `cmd.exe /c` wrapping in favor of Rust 1.77+ native `.cmd` handling
- **Terminal AutoRun interference** — added `/D` flag to suppress Windows Registry AutoRun commands that could fail on paths with spaces
- **Focus stealing across views** — clicking on Terminal, Console, or other views in the same view group as Claude CLI no longer snaps back to Claude CLI; overlay now only appears when the view is visible but not active
- **Tab switching focus** — switching between Claude CLI session tabs (e.g. Claude 1 → Claude 2) now properly transfers keyboard focus to the new console; ghost overlays from other tabs are cleaned up on switch

---

## [2.0.0] — 2026-04-03

### Changed
- **Native embedded console** — Claude CLI now runs in a real embedded Windows console (conhost) reparented directly into the Eclipse view, replacing the previous PTY + xterm.js + WebView2 approach
- Eliminates all WebView2-related focus, rendering, and scrollback issues
- Native ANSI color rendering, mouse support, and scrollback handled by Windows itself

### Fixed
- **Single-click focus restore** — clicking on the Claude CLI console area now immediately activates the Eclipse view and restores keyboard focus (previously required double-click)
- Tab highlight persists when switching between Claude CLI session tabs

### Known Issues
- Ctrl+V paste does not work — use right-click paste as a workaround

---

## [1.2.1] — 2026-03-29

### Fixed
- New and reopened terminal tabs now reliably receive keyboard input — focus is requested after a short delay to let SWT finish settling the tab's focus chain, eliminating the intermittent "can't type" issue on Windows WebView2
- Terminal output now batches writes per animation frame (`requestAnimationFrame`), so Claude's streaming ANSI sequences are processed together before each repaint — eliminates the flickering/regenerating-lines effect

---

## [1.2.0] — 2026-03-29

### Added
- Dedicated **Claude CLI** view with full PTY support (ANSI colors, cursor movement, readline)
- **Multi-tab terminal** — open any number of independent Claude CLI sessions side by side under a single view, each in its own tab
- "+" button in the Claude CLI view toolbar to spawn additional sessions
- Tabs automatically marked `[done]` when a session exits

### Changed
- Claude CLI is no longer launched inside TM Terminal — it now runs in its own standalone view
- Terminal rendering powered by [xterm.js](https://xtermjs.org) + [PTY4J](https://github.com/JetBrains/pty4j), fully self-contained within the plugin (no external terminal emulator required)

---

## [1.1.1] — 2026-03-28

### Fixed
- Claude Chat responses were being duplicated — assistant messages now appear exactly once

---

## [1.1.0] — 2026-03-26

### Added
- **Claude Chat** panel — a persistent web-based chat interface with markdown rendering, streamed responses, and multi-turn conversation support
- Chat supports tool-use indicators (shows which MCP tools Claude is invoking)
- New Session / Clear controls in the chat panel

---

## [1.0.0] — 2026-03-24

### Added
- **Claude CLI terminal** — launch an interactive Claude CLI session directly from Eclipse using TM Terminal
- MCP server auto-starts when Eclipse launches (configurable)
- Lock file written to `~/.claude/ide/` so Claude CLI auto-discovers the running IDE instance
- Launch / Resume Session / Restart Server controls in the Claude Code view

---

## [0.1.1] — 2026-03-23

### Added
- **MCP Tools** — Claude can now interact with the Eclipse IDE programmatically:
  - `openFile`, `getOpenEditors`, `getCurrentSelection`, `getLatestSelection`
  - `getWorkspaceFolders`, `getDiagnostics`, `saveDocument`, `checkDocumentDirty`
  - `openDiff`, `closeAllDiffTabs`
- Editor selection tracking — cursor and selection are continuously reported to Claude

---

## [0.1.0] — 2026-03-22

### Added
- First functional release — call the Claude API from inside Eclipse IDE
- Basic Claude Code view with server status and launch controls
- HTTP+SSE server for IDE-Claude communication

---

## [0.1.1-beta] — 2025-05-23

### Added
- Proof-of-concept agentic plugin — initial exploration of embedding Claude Code into an Eclipse IDE plugin

---

## [0.0.1-alpha] — 2020-06-23

### Added
- Initial components as proof-of-concept for an agentic, AI-powered plugin

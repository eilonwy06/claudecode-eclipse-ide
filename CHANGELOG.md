# Changelog

All notable changes to Claude Code for Eclipse are documented here.

---

## [Unreleased]

### Changed
- **The bundled PHP runtime is gone.** Everything it did (the session-history reader and the bridge relay) now runs inside the plugin's native core — same behavior, same ports, same session-history output, but the plugin is ~150 MB smaller, starts these features faster, and no longer extracts a scripting runtime to a temp folder.

---

## [3.0.3] — 2026-07-10 *(current)*

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

# Changelog

All notable changes to Claude Code for Eclipse are documented here.

---

## [2.3.11] — 2026-04-21 *(current)*

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

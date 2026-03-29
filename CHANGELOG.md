# Changelog

All notable changes to Claude Code for Eclipse are documented here.

---

## [1.2.1] — 2026-03-29 *(current)*

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
- HTTP+SSE server for IDE↔Claude communication

---

## [0.1.1-beta] — 2025-05-23

### Added
- Proof-of-concept agentic plugin — initial exploration of embedding Claude Code into an Eclipse IDE plugin

---

## [0.0.1-alpha] — 2020-06-23

### Added
- Initial components as proof-of-concept for an agentic, AI-powered plugin

# Claude Code for Eclipse IDE

> **Unofficial community port** — This is an independent Eclipse IDE adaptation of the [Claude Code VS Code extension](https://marketplace.visualstudio.com/items?itemName=Anthropic.claude-code), built by [eilonwy06](https://github.com/eilonwy06). It is not affiliated with, endorsed by, or maintained by Anthropic.

An Eclipse IDE plugin that integrates [Claude Code](https://claude.ai/code) — Anthropic's AI-powered CLI — directly into your Eclipse development environment.

## Installation

1. Open Eclipse and go to **Help → Install New Software**
2. Click **Add** and enter:
   - Name: `Claude Code`
   - URL: `https://eilonwy06.github.io/claudecode-eclipse-ide/com.anthropic.claudecode.eclipse.site/`
3. Select the **Claude Code for Eclipse IDE** feature and follow the install prompts
4. Restart Eclipse when prompted

## Prerequisites

- Eclipse IDE (tested with Eclipse 2023-12+)
- Java 21 or later
- [Claude Code CLI](https://claude.ai/code) installed and available on your PATH
- A valid Anthropic API key
- **Windows:** x86_64
- **Linux:** x86_64
- **macOS:** aarch64 (Apple Silicon) and x86_64 (Intel)

### Required Eclipse Terminal bundles

The **Claude CLI** view embeds the Eclipse Terminal, so these bundles must be present (version **1.1.0 or newer**, within the `1.x` range):

- `org.eclipse.terminal.control`
- `org.eclipse.terminal.connector.process`
- `org.eclipse.terminal.connector.local`

…plus their transitive dependencies (notably CDT's native PTY support, `org.eclipse.cdt.core.native`), which p2 resolves automatically.

These bundles ship with most Eclipse packages (Eclipse IDE for C/C++ Developers, for Committers, for Enterprise Java Developers, and the full SDK), and the plugin's feature declares them, so p2 normally pulls them in during installation. If you're on the minimal **Eclipse IDE for Java Developers** and installation reports them as missing, install them first via **Help → Install New Software** from the main Eclipse release update site (search for *Terminal*). A reasonably recent Eclipse release is required, since Terminal `1.1+` ships only in newer versions.

### Setting Up Claude Code CLI

1. **Install Node.js** (v18 or later) from [nodejs.org](https://nodejs.org) if you don't have it
2. **Install Claude Code CLI** globally via npm:
   ```bash
   npm install -g @anthropic-ai/claude-code
   ```
3. **Verify the install** — open a terminal and run:
   ```bash
   claude --version
   ```
   You should see a version number. If the command is not found, ensure your npm global bin directory is on your PATH.

### Setting Up Your Anthropic API Key

Claude Code CLI requires an Anthropic API key to function. You have two options:

**Option A — Interactive login (recommended):**
```bash
claude auth
```
Follow the prompts to log in. Your credentials are stored securely and reused automatically.

**Option B — Environment variable:**

Set `ANTHROPIC_API_KEY` in your environment before launching Eclipse:

- **Windows:** In System Properties → Environment Variables, add `ANTHROPIC_API_KEY` = `sk-ant-...`
- **macOS/Linux:** Add to your shell profile (`~/.bashrc`, `~/.zshrc`, etc.):
  ```bash
  export ANTHROPIC_API_KEY="sk-ant-..."
  ```

> You can get an API key from [console.anthropic.com](https://console.anthropic.com).

## Usage

### Opening the Views

Go to **Window → Show View → Other → Claude Code** and open the views you want:
- **Claude Code** — server status, launch/resume/restart controls
- **Claude CLI** — dedicated interactive terminal (multiple "Claude N" tabs), built on the Eclipse Terminal with full ANSI/24-bit color, scrollback, copy/paste, and customizable colors
- **Claude Chat** — web-based chat interface with markdown rendering

### Getting Started

1. In the **Claude Code** view, click **Launch Claude Terminal** — this opens the **Claude CLI** view and starts Claude automatically
2. Type directly in the **Claude CLI** terminal, or switch to **Claude Chat** for a richer markdown interface
3. Claude can read your open files, selection, and workspace context automatically via MCP tools

> **Note (all platforms):** The Claude CLI view embeds the Eclipse Terminal control and launches `claude` over a local PTY (ConPTY on Windows, native PTY on Linux/macOS), with full ANSI/24-bit color, scrollback, and resize. Copy/paste is available via the right-click menu or the keyboard: `Ctrl/⌘+V` (or `Shift+Insert`) to paste, `Ctrl/⌘+Shift+C` to copy, and `Ctrl/⌘+C` copies when text is selected (otherwise it passes through to interrupt Claude).

> **Font customization (all platforms):** The console font can be changed in **Window → Preferences → General → Appearance → Colors and Fonts → Basic → Claude CLI Console Font**. By default it inherits from Eclipse's "Text Font" setting.

> **Color customization (all platforms):** The Claude CLI's background/foreground colors — and the Dark/Light theme hint — can be set in **Window → Preferences → Claude Code** ("Claude CLI background", "Claude CLI foreground", and "Claude CLI theme"). These are independent of Eclipse's built-in Terminal colors and apply immediately without restart.

### Keyboard Shortcuts

| Shortcut | Action |
|---|---|
| `Ctrl+Shift+C` | Toggle Claude Code view |
| `Ctrl+Shift+S` | Send current editor selection to Claude |
| `Ctrl+Alt+A` | Add current file to Claude's context |

These are also available from the **Claude Code** menu in the menu bar and from the right-click context menu in any text editor.

### Chat Controls

- **Clear** — clears the chat display
- **New Session** — starts a fresh conversation
- **Resume Session** (Claude Code view) — resumes the previous CLI session with `--resume`
- **Restart Server** (Claude Code view) — restarts the internal MCP server

### What Claude Can Do in Eclipse

Claude has access to the following MCP tools, which it invokes automatically:

| Tool | Description |
|---|---|
| `openFile` | Open a file at a specific line/column with optional text selection |
| `getOpenEditors` | List all open editor tabs |
| `getCurrentSelection` | Get the currently selected text and its location |
| `getLatestSelection` | Get the most recent selection (even after focus change) |
| `getWorkspaceFolders` | List all open Eclipse projects |
| `getDiagnostics` | Get errors and warnings from Eclipse's problem markers |
| `saveDocument` | Save a file |
| `checkDocumentDirty` | Check if a file has unsaved changes |
| `openDiff` | Show a diff view comparing proposed vs. current file content |
| `closeAllDiffTabs` | Close all open diff tabs |

### Configuration

Go to **Window → Preferences → Claude Code** to configure:

| Setting | Default | Description |
|---|---|---|
| Start server automatically | On | Auto-start the MCP server when Eclipse launches |
| Track editor selection | On | Continuously track cursor/selection for Claude context |
| Claude command | `claude` | Path to the Claude CLI executable |
| Arguments | *(empty)* | Additional CLI arguments (e.g., `--model claude-opus-4-7-20260418`) |
| Port range (min/max) | 10000–65535 | Port range for the internal HTTP+SSE server |
| Claude CLI theme | Dark | Theme hint for Claude's `/theme auto` (Dark or Light); applies immediately |
| Claude CLI background / foreground | `#121314` / `#E5E5E5` | Terminal colors, independent of Eclipse's Terminal; apply immediately |

## Architecture

The plugin follows a **Rust-first** approach: the heavy logic — HTTP/SSE server, MCP/JSON-RPC protocol, chat process management, and lock-file handling — lives in a native Rust library (`claude-eclipse-core`) loaded via JNI. Java is a thin glue layer for Eclipse/SWT API calls.

```
Claude CLI  <--NDJSON-->  Rust (chat.rs)  --JNI callbacks-->  Java (ClaudeChatView)
                          Rust (mcp.rs)   --JNI tool call-->  Java (McpToolRegistry)
                          Rust (server.rs) --SSE-->           Claude CLI
```

## Project Structure

| Project | Description |
|---|---|
| `claude-eclipse-core` | Rust native library — HTTP+SSE server, MCP/JSON-RPC protocol, chat process manager, lock-file management. Built as a cdylib (`claude_eclipse_core.dll` / `libclaude_eclipse_core.so` / `libclaude_eclipse_core.dylib`) |
| `com.anthropic.claudecode.eclipse` | Eclipse plugin — UI views, MCP tool implementations, JNI bridge, chat HTML/JS |
| `com.anthropic.claudecode.eclipse.feature` | Eclipse feature definition — declares the plugin and its metadata |
| `com.anthropic.claudecode.eclipse.site` | p2 update site — the installable artifacts hosted via GitHub Pages |

### Building the Native Library

The Rust library must be compiled for each target platform:

**Windows (native build):**
```bash
cd claude-eclipse-core
cargo build --release
cp target/release/claude_eclipse_core.dll ../com.anthropic.claudecode.eclipse/native/windows/x86_64/
```

**Linux (via Docker):**

*From Linux/macOS:*
```bash
cd claude-eclipse-core
docker run --rm -v "$(pwd):/src" -w /src rust:slim-bullseye cargo build --release
cp target/release/libclaude_eclipse_core.so \
   ../com.anthropic.claudecode.eclipse/native/linux/x86_64/
```

*From Windows (CMD):*
```cmd
docker run --rm -v "%cd%:/src" -w /src rust:slim-bullseye cargo build --release
copy claude-eclipse-core\target\release\libclaude_eclipse_core.so ^
     com.anthropic.claudecode.eclipse\native\linux\x86_64\
```

*From Windows (PowerShell):*
```powershell
docker run --rm -v "${PWD}:/src" -w /src rust:slim-bullseye cargo build --release
copy claude-eclipse-core\target\release\libclaude_eclipse_core.so `
     com.anthropic.claudecode.eclipse\native\linux\x86_64\
```

**macOS (native build — must be built on a Mac):**
```bash
rustup target add aarch64-apple-darwin x86_64-apple-darwin
cd claude-eclipse-core
cargo build --release --target aarch64-apple-darwin
cargo build --release --target x86_64-apple-darwin
cp target/aarch64-apple-darwin/release/libclaude_eclipse_core.dylib \
   ../com.anthropic.claudecode.eclipse/native/macos/aarch64/
cp target/x86_64-apple-darwin/release/libclaude_eclipse_core.dylib \
   ../com.anthropic.claudecode.eclipse/native/macos/x86_64/
```
> Cross-compiling to macOS from Windows/Linux requires Apple's SDK and is not supported — build on a Mac.

## Updating the Plugin

After making changes and rebuilding the update site in Eclipse:

```bash
git add .
git commit -m "vX.X.X - description of changes"
git push
```

GitHub Pages will redeploy within ~1 minute and the new version will be available to install.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the full release history.

## Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

## Credits

Special thanks to [xgsa](https://github.com/xgsa) for helping fix some issues in Linux and for improving the plugin.

## License

[MIT](LICENSE)

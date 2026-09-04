# Claude Code for Eclipse IDE (Unofficial)

> ## ****Unofficial community port** — This is an independent Eclipse IDE adaptation of the [Claude Code VS Code extension](https://marketplace.visualstudio.com/items?itemName=Anthropic.claude-code). It is not affiliated with, endorsed by, or maintained by Anthropic.**

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
- **Linux:** x86_64 and aarch64 (ARM64)
- **macOS:** aarch64 (Apple Silicon) and x86_64 (Intel)

### Required Eclipse Terminal bundles

The **Claude Terminal** view embeds the Eclipse Terminal, so these bundles must be present (version **1.1.0 or newer**, within the `1.x` range):

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
- **Claude Code** — a VS Code-style graphical chat panel. A row of **directory tabs** sits on top, one per working folder, each with its own conversations and its own session history; beneath it, multiple conversation tabs run concurrently, each backed by its own Claude process. Model, reasoning effort, extended thinking and permission mode are set **per conversation**. Also: an in-panel status bar (live model, context usage, cost, session and weekly usage), inline permission / question / diff-review cards, live extended-thinking reveal, image paste, per-message fork / rewind / delete, session history, inline file diffs, Scroll Lock, and a light or dark palette that follows Eclipse's theme
- **Claude Terminal** — dedicated interactive terminal, built on the Eclipse Terminal with full ANSI/24-bit color, scrollback, copy/paste, customizable colors, an optional Claude status line, and Ctrl/⌘-click navigation to file paths and links mentioned in Claude's answers


### Getting Started

1. Open the **Claude Code** view for a graphical chat experience, or the **Claude Terminal** view for the interactive CLI
2. Claude can read your open files, selection, and workspace context automatically via MCP tools

> **Note (all platforms):** The Claude Terminal view embeds the Eclipse Terminal control and launches `claude` over a local PTY (ConPTY on Windows, native PTY on Linux/macOS), with full ANSI/24-bit color, scrollback, and resize. Copy/paste is available via the right-click menu or the keyboard, and every paste trigger handles both text **and** images: `Ctrl/⌘+V`, `Ctrl/⌘+Shift+V`, or `Shift+Insert` to paste; `Ctrl/⌘+Shift+C` or `Ctrl/⌘+Insert` to copy; and `Ctrl/⌘+C` copies when text is selected (otherwise it passes through to interrupt Claude).
>
> **Image paste on Linux** relies on a clipboard helper that the Claude CLI shells out to — install `xclip` (X11) or `wl-clipboard` (Wayland), e.g. `sudo apt install xclip`. Without it the CLI reports "No image found in clipboard"; text paste is unaffected.

> **Open files, links, and Java references:** Claude often references file paths, URLs and Java type/member names in its answers. **Ctrl-click** (⌘-click on macOS) any such token in the **Claude Terminal** view to jump straight to it — a file opens in an editor, an `http`/`https` URL opens in your browser, and a Java reference (e.g. `java.util.List`, `com.foo.Bar:21`, `Bar#baz(int)`) opens in the Java editor (only if JDT is installed). Paths and file names containing spaces are fully supported — absolute or workspace-relative, even when the path wraps across terminal lines or a file name appears mid-sentence — clicking any segment opens the right file. You can also select text and choose **Open** from the right-click menu.

> **Font customization (all platforms):** The console font can be changed in **Window → Preferences → General → Appearance → Colors and Fonts → Basic → Claude Terminal Console Font**. By default it inherits from Eclipse's "Text Font" setting. **Linux users:** If you see horizontal lines or other rendering artifacts, try setting the font to one commonly used by terminal emulators (e.g., MesloLGS NF, JetBrains Mono, or your terminal's default font).

> **Color customization (all platforms):** The Claude Terminal's background/foreground colors — can be set in **Window → Preferences → Claude Code** ("Claude Terminal background" and "Claude Terminal foreground"). These are independent of Eclipse's built-in Terminal colors and apply immediately without restart.

### Keyboard Shortcuts

| Shortcut | Action |
|---|---|
| ``Ctrl+` ``    | Activate Claude Terminal view |
| `Ctrl+Alt+S` | Send current editor selection to Claude |
| `Ctrl+Alt+A` | Add current file to Claude's context |
| `Esc` | Dismiss the card currently awaiting an answer (`Ctrl+G` on the Emacs scheme) |

These are also available from the **Claude Code** menu in the menu bar and from the right-click context menu in any text editor. A project's context menu carries **Open Claude Here**, holding **Claude Code** and **Claude Terminal**, and **Show In ▸ Claude Code** opens the selected folder as a directory tab.

### Chat Controls

In the **Claude Code** view:
- **New Session** — opens a fresh conversation tab (multiple run concurrently)
- **New Claude root directory** — adds a directory tab, so a conversation can run in any folder rather than only the workspace root
- **Model picker** — sets the model, reasoning effort, and extended thinking for the current conversation
- **Permission mode** — Manual, Edit automatically, Plan or Auto, remembered per conversation and applied immediately
- **History** — browse, resume, or delete past conversations
- **Scroll Lock** — holds your place while Claude writes; a **Jump to latest** button appears while you're held back
- **Clear** — clears the current conversation's display

Hovering a message you sent reveals per-message actions: **Fork conversation from here**, **Rewind code to here**, **Fork conversation and rewind code**, and **Delete**, which removes that message from the conversation's history for every Claude Code client reading the project. Slash commands including `/compact`, `/rewind` and `/advisor` work from the composer, and a banner offers to run Claude Code's own updater when a newer CLI release is published.

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
| `acceptDiff` / `rejectDiff` | Apply or discard a diff being reviewed |
| `closeAllDiffTabs` | Close all open diff tabs |
| `refresh` | Refresh projects from the filesystem, so files changed outside Eclipse are picked up |
| `clean` | Discard build output and problem markers |
| `build` | Clean and/or rebuild projects and report the resulting compile errors |
| `runAs` | Run a project the way **Run As** does, or a saved launch configuration by name |
| `findReferences` † | Find every reference to a Java type or member |
| `getSymbolInfo` † | Resolve the Java symbol at a position — kind, declaring type, signature |
| `getTypeHierarchy` † | Show a Java type's supertypes and subtypes |
| `runTests` † | Run JUnit tests and report the results |

† Requires the Java Development Tools (JDT). The plugin never hard-depends on JDT — these tools are simply absent from the tool list when it isn't installed, and everything else works without it.

### Configuration

Go to **Window → Preferences → Claude Code** to configure:

| Setting | Default | Description |
|---|---|---|
| Open new Claude Terminal automatically on Eclipse launch | Off | Opens a Claude Terminal tab when Eclipse starts |
| Track editor selection in real-time | On | Continuously track cursor/selection for Claude context |
| Claude command | `claude` | Path to the Claude CLI executable |
| Arguments | *(empty)* | Additional CLI arguments (e.g., `--model claude-opus-4-7-20260418`) |
| Port range (min/max) | 10000–65535 | Port range for the internal HTTP+SSE server |
| Claude Terminal background / foreground | `#121314` / `#E5E5E5` | Terminal colors, independent of Eclipse's Terminal; apply immediately |

**Claude status bar** — a status line for the Claude Terminal, assembled from the parts you choose:

| Setting | Default |
|---|---|
| Show status bar (applies to newly launched sessions) | On |
| Show model | On |
| Show effort level | On |
| Show thinking indicator | Off |
| Show context-window usage | On |
| Show session cost (USD) | Off |
| Show 5-hour (session) usage limit | On |
| Show weekly (7-day) usage limit | On |
| Status refresh interval (seconds) | 60 |

**Network / Proxy** — `HTTP_PROXY`, `HTTPS_PROXY` and `NO_PROXY`. Empty by default, in which case they are auto-detected from your shell.

**Decision card timeouts** — how long an unanswered card waits before Claude Code assumes an answer and continues. Set independently for **Permission approval**, **Ask-user question** and **Diff review**; each is Default (30 minutes), Never, or a custom number of seconds.

**Miscellaneous Configuration** — additional options, including which sets of working-indicator verbs the Claude Code view and the Terminal cycle through.

## Architecture

The plugin follows a **Rust-first** approach: the heavy logic — HTTP/SSE server, MCP/JSON-RPC protocol, chat process management, session-history reconstruction, shell and proxy environment detection, and lock-file handling — lives in a native Rust library (`claude-eclipse-core`) loaded via JNI. Java is a thin glue layer for Eclipse/SWT API calls.

```
Claude CLI  <--NDJSON-->  Rust (chat.rs)  --JNI callbacks-->  Java (NativeCore → ChatProcessManager)
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

**Linux aarch64 (via Docker, emulated ARM64 container):**

Add `--platform linux/arm64` to the same command — Docker Desktop (or QEMU binfmt on Linux) runs the build inside an ARM64 container, so the output is a native aarch64 `.so`. Note it lands in the same `target/release/` folder as the x86_64 build, so copy it out before rebuilding for the other architecture:

```powershell
docker run --rm -v "${PWD}:/src" -w /src --platform linux/arm64 rust:slim-bullseye cargo build --release
copy claude-eclipse-core\target\release\libclaude_eclipse_core.so `
     com.anthropic.claudecode.eclipse\native\linux\aarch64\
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

## Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what
you would like to change.

This repository also hosts the update site, so nobody pushes to it directly — a change
reaches users through a pull request, and then through a release the maintainer publishes.

1. **Fork** the repository on GitHub and clone your fork.
2. **Create a branch** for your change.
3. **Import the projects** into Eclipse: *File ▸ Import ▸ Existing Projects into Workspace*,
   pointing at the clone.
4. **Make your change.** If it touches Rust, run `cargo test` in `claude-eclipse-core` and
   rebuild the native library for your own platform (see
   [Building the Native Library](#building-the-native-library)) so you can run what you wrote.
5. **Try it** — *Run As ▸ Eclipse Application* launches a second Eclipse with the plugin
   installed. Confirm the behaviour there before sending anything.
6. **Push to your fork** and open a pull request against `master`, describing what changed
   and how you tested it.

Please leave these to the maintainer, and keep them out of your pull request:

- the version numbers in `MANIFEST.MF`, `feature.xml` and `site.xml`
- the `CHANGELOG.md` entry
- the generated p2 artifacts under `com.anthropic.claudecode.eclipse.site/`
- native libraries for platforms you cannot build and test on yourself

Once a release is published, GitHub Pages redeploys the update site within about a minute
and the new version becomes available to install.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the full release history.

## Credits

Special thanks to [xgsa](https://github.com/xgsa) and [jmoraleda](https://github.com/jmoraleda) for the fixes and improvements they have contributed to the plugin.

## License

[MIT](LICENSE)

## Copyright

Copyright (c) 2026 Carlo Louis Felipe (eilonwy06). Not affiliated with Anthropic.

NOTICE: This project is an unofficial, community-made Eclipse IDE plugin. It is not affiliated with, endorsed by, or maintained by Anthropic, PBC.
"Claude" and "Claude Code" are trademarks of Anthropic, PBC.

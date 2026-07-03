# Claude Status Bar — implementation plan

## Context
`ClaudeCliView` embeds the Claude CLI in an Eclipse terminal but surfaces none of
Claude's live status. The goal is an at-a-glance indicator, **one per terminal tab**, of:
**current model, reasoning-effort level, context-window usage %, and subscription
usage-limit (rate-limit) % with reset times** — updated live, fed by Claude itself
rather than scraped from its TUI.

The whole design hinges on "how do we talk to the `claude` we launch?" Answer: Claude
already emits exactly this data through its **statusLine** mechanism. We inject a
statusLine command scoped to IDE-launched sessions only, and that command is a tiny
**Java forwarder** (no curl, no shipped binary) that POSTs the JSON back to the plugin's
existing axum server, which routes it to the right tab via a **dedicated** JNI callback.

**Scope decisions (confirmed):**
- Status bar is **per-tab** (each `TerminalSession` owns its own bar).
- v1 elements: **Model**, **Effort level**, **Context %**, **5-hour usage + reset**,
  **Weekly (7-day) usage + reset**. "Mode" (`output_style.name`) is **out of scope** for v1.
- Settings are injected via a **single shared settings file** in the bundle's state location,
  passed as `--settings <path>` (see §5). The per-tab routing token rides in a **per-tab
  environment variable** (`CLAUDE_TAB_TOKEN`, §6), not in the settings JSON, so the file's
  content is identical for every tab and every run-time-equal set of prefs — written **once per
  run**, not per tab. Dangling files on abnormal exit are handled by a **startup sweep** (see
  §9). A dedicated status channel is used, kept **separate from the MCP tool infrastructure**
  (see §3).

---

## 1. Data source — Claude Code statusLine JSON (authoritative)
Docs: https://code.claude.com/docs/en/statusline — Claude runs a configured
`statusLine.command`, piping a JSON document to its **stdin** after each assistant
message / `/compact` / permission-mode change / vim-mode toggle (debounced 300 ms;
in-flight runs are cancelled; `refreshInterval` re-runs it on an idle timer).

**Fields we consume (verified against the published schema):**

| Field | Notes |
|---|---|
| `model.id`, `model.display_name` | e.g. `"Opus 4.8"`. Always present. |
| `effort.level` | `low` \| `medium` \| `high` \| `xhigh` \| `max`. **Absent when the current model doesn't support the effort parameter** — hide the segment then. Ultracode reports as `xhigh`. |
| `thinking.enabled` | Boolean. Whether extended thinking is on. Optional — hide the segment when absent or `false`. Shown only when the (off-by-default) preference is enabled. |
| `context_window.used_percentage` | 0–100, **may be a float** (e.g. `23.5`) and **may be `null`** before the first API response and again after `/compact` until the next API call. Round down for display. |
| `context_window.context_window_size` | Max tokens (200000, or 1000000 for extended-context models). Optional, for a richer tooltip. |
| `cost.total_cost_usd` | Float — accumulated session cost in USD. Optional — hide the segment when absent. Shown only when the (off-by-default) preference is enabled. |
| `rate_limits.five_hour.used_percentage`, `rate_limits.five_hour.resets_at` | `resets_at` is **Unix epoch seconds**. **Pro/Max accounts only**, after the first API response; each window may be **independently absent** — hide that segment. |
| `rate_limits.seven_day.used_percentage`, `rate_limits.seven_day.resets_at` | Same caveats as `five_hour`. |

All optional fields **must degrade gracefully** (hide the segment, never show `null`/`NaN`).

**Inject without clobbering user config.** Settings merge by top-level key across sources
(managed > command line > local > project > user). For an object-valued key like `statusLine`,
the **highest-precedence source supplies the whole value** — not a deep merge. So our
command-line `--settings` override **replaces** the user's `statusLine` for that session only;
it never touches files on disk. If *managed* settings already pin a `statusLine`, Claude
enforces the managed value and our override is ignored (no special handling needed on our
side).

**The settings JSON we generate at each launch** (shared by every tab — the content is identical
across tabs; `refreshInterval` is in **seconds**, min 1; `<StandaloneStatusForwarder-FQN>` is
`StandaloneStatusForwarder.class.getName()`, never a hardcoded literal — §5):

```json
{
  "statusLine": {
    "type": "command",
    "command": "\"<java.home>/bin/java\" -Xmx24m -Xms8m -Xss512k -cp \"<bundle-cp>\" <StandaloneStatusForwarder-FQN>",
    "padding": 0,
    "refreshInterval": 60
  }
}
```

**The per-tab token is deliberately *not* in this command.** It would be the only per-tab value,
and embedding it would force a distinct settings file per tab. Instead the token is passed to the
forwarder through the per-tab `CLAUDE_TAB_TOKEN` environment variable (§6), leaving the command —
and thus the whole file — identical across tabs. The remaining values (`<java.home>`,
`<bundle-cp>`, `refreshInterval`) are install-/preference-scoped, equal for every tab in a run, so
every launch rewrites the **same** shared file with byte-identical content (§5).

`refreshInterval` keeps the reset-time countdowns fresh while the session is idle; it is
user-configurable (preference, §7).

---

## 2. Runner — `StandaloneStatusForwarder`, a pure `java.base` forwarder (no curl, no shipped binary)
The statusLine `command` is the only hook Claude gives us, and it spawns once per assistant
message (plus idle-timer ticks). Make that command the **JVM already present** — the one
running Eclipse — located via `System.getProperty("java.home")`:

```
"<java.home>/bin/java" -Xmx24m -Xms8m -Xss512k -cp "<bundle-cp>" <StandaloneStatusForwarder-FQN>
```

**`StandaloneStatusForwarder` responsibilities (deliberately minimal):**
1. Takes **no arguments**. Read the per-tab token from `CLAUDE_TAB_TOKEN`, plus
   `CLAUDE_CODE_SSE_PORT` and `CLAUDE_IDE_AUTH_TOKEN`, all from its **inherited environment**
   (Claude is its parent; all three are injected per-tab by `ClaudeCliView.launch()` — the token
   stays out of the shared settings file and **no secrets are on the command line**, see §6).
   The token is sent back to the server as the `tab` query param (§3a).
2. Read **all of stdin verbatim** (the JSON Claude pipes) into a byte array. **Do not parse
   it** — it's an opaque passthrough; parsing happens in Java on the plugin side.
3. POST those raw bytes to the server (URL/auth per §3a), using **`HttpURLConnection`**
   (deliberately not `java.net.http.HttpClient` — fewer classes to load → faster cold
   start, lower footprint) with short connect/read timeouts (≈2 s). Print **nothing** to
   stdout (Claude would render it); exit 0 regardless of POST outcome so a transient server
   hiccup never spams the TUI.

**Hard constraints on `StandaloneStatusForwarder`:**
- **`java.base` only.** It runs in a bare JVM with just `-cp <bundle>` and **no OSGi
  runtime** — no `Activator`, no `gson`, no `org.eclipse.*`. Keep it a single self-contained
  class. (It still lives in the plugin source tree and compiles with the normal PDE build;
  its `.class` ships inside the plugin jar.)
- Suggested package: `com.anthropic.claudecode.eclipse.status`. The launcher derives the FQN
  via `StandaloneStatusForwarder.class.getName()` so a rename/move needs no other edit (§5).

**JVM sizing — minimal, with reservation, for fast cold start.** The runner only reads a few
KB from stdin, loads the `HttpURLConnection` stack, and POSTs once. Heap demand is well under
8 MB; the rest is JVM baseline (metaspace, GC/JIT threads, class loading). The cold-start and
memory win comes almost entirely from capping committed heap and stack:
- `-Xmx24m` — comfortably covers stdin buffer + HTTP client classes with headroom; can be
  trimmed toward `-Xmx16m` if measured stable, but 24 MB keeps a safety margin.
- `-Xss512k` — one short-lived thread, shallow call stack; 512 KB is generous. `-Xss256k`
  also works if trimming.
- `-Xms8m` — set initial heap *just above* the workload's low-single-digit-MB peak so the
  heap **never grows** during the run, while committing far less than the implicit default.
  Note: **don't set `-Xms = -Xmx`** for "faster cold start" — committing heap up front is
  *work*, not a saving, and it only helps if the workload would otherwise trigger heap
  *grows*, which it won't at 8m. With `-Xmx24m` clamping HotSpot's RAM-fraction ergonomics,
  the default initial heap is already ~24m on a multi-GB box, so `-Xms24m` is a no-op while
  `-Xms8m` actively *reduces* startup commit + RSS. Cross-VM-safe (also helps OpenJ9, which
  starts smaller and grows). The real cold-start bottleneck is class loading, not heap.
- **`-X` flags only** — see the portability note below. Tune empirically on the target VMs,
  not by guessing `-XX:` knobs.

**Cost (benchmarked, Java 21, warm cache):** a capped short-lived JVM is ~11 ms start /
~12 MB RSS — on par with `bash+curl` (~16 ms / ~11 MB). The only heavy case is the *default*
(uncapped) JVM (~86 ms / ~40 MB), avoided by the `-Xmx`/`-Xss` caps. Eclipse already runs this
JVM, so its image/CDS are warm in the page cache. statusLine fires per *message* (and on the
idle timer), not per token, so a short-lived JVM a few times per turn is negligible.

**JVM flags — must be cross-VM.** Unknown `-XX:` options are **fatal** on HotSpot (the JVM
won't start → status silently breaks), and HotSpot-only flags (`TieredStopAtLevel`,
`CICompilerCount`, `UsePerfData`, `UseSerialGC`) are invalid on Eclipse OpenJ9 / Semeru.
Attribution testing showed the entire start-time + memory win comes from the **universal `-X`
flags** — the `-XX:` flags add ~nothing. So use **`-X` only**: standard on HotSpot, OpenJ9,
GraalVM, all OSes; no vendor detection, no fatal-flag risk.

**Locating the class at runtime (`<bundle-cp>`):** resolve the plugin's on-disk location via
`FileLocator.getBundleFile(Activator.getDefault().getBundle())`. In a PDE/dev launch this is
the `bin/` output directory; in an installed product it's the plugin jar in `plugins/`. Use
that absolute path as the `-cp` entry. **Do not assume a jar** — `getBundleFile` returns
whichever applies (directory or jar); both are valid `-cp` entries.

> Build note: because the class ships inside the existing plugin jar/`bin`, **no new
> top-level directory** is added to the plugin project, so `build.properties` `bin.includes`
> needs no change. (If the class is ever moved into a dedicated dir, add it there — see the
> `build.properties` gotcha in CLAUDE.md.)

---

## 3. Channel — dedicated `/statusline` route + dedicated status callback (NOT the MCP path)
This is **not** an MCP tool, so it must **not** reuse `McpToolRegistry`, `NativeToolBridge`,
`ToolCallback.executeEclipseTool`, or `mcp::call_java_tool`. Instead we add a parallel,
purpose-built status channel that mirrors the *structure* of the existing tool-callback
plumbing without sharing its code. **This requires a native-lib rebuild for all 3 platforms**
(the documented manual process in CLAUDE.md).

### 3a. Rust (`server.rs`) — new route + auth mirroring existing infra
Add one route alongside `/sse` and `/messages`:

```rust
.route("/statusline", post(statusline_handler))
```

**Authorization — mirror the existing pattern, don't invent a new scheme.** The server today
authenticates by (a) binding loopback `127.0.0.1` and (b) requiring a per-request secret in
the query string that it validates against server state — `/messages?sessionId=<secret>` is
checked against `state.clients`. We do the analogous thing with the real `state.auth_token`
(already generated and advertised via the lock file): the forwarder appends the `authToken` it
holds in `CLAUDE_IDE_AUTH_TOKEN`, and the handler validates it.

> **Why `authToken` and not `sessionId`?** They are different things. `sessionId` is a
> per-SSE-connection id minted by `sse_handler` and only knowable after completing the `/sse`
> handshake — it identifies one MCP message stream. The statusline forwarder is a fresh,
> short-lived process with **no SSE session**, so it has no `sessionId` to present; it
> authenticates with the workspace server's shared secret instead. The query param is named
> **`authToken`** to match this codebase's canonical name for that secret (the lock-file
> `authToken` field at `lock_file.rs:91`, `state.auth_token` in Rust, `CLAUDE_IDE_AUTH_TOKEN`
> in the env) — not the generic `token`, and not `sessionId` (a distinct concept).

`statusline_handler` outline:
1. Parse query params `tab` and `authToken` (`Query<StatusQuery>`). `400` if `tab` missing.
2. Compare `authToken` to `state.auth_token` (use a constant-time comparison). Reject with
   `401` on mismatch/absence. This matches the loopback-plus-request-secret model already in
   use.
3. Read the raw body → UTF-8 string (`400` on invalid UTF-8).
4. Hand `(tab, body)` to the dedicated status callback (§3b) on a plain OS thread (as
   `ClientGuard::drop` already does for JNI), so `attach_current_thread` is safe even off a
   tokio worker. Return `200 OK` immediately (the forwarder ignores the body).

### 3b. Dedicated status callback (parallel to, but separate from, the tool callback)
Clone the *shape* of the tool-callback plumbing under new names:

- **`AppState`**: add `pub status_callback: Mutex<Option<StatusCallbackRef>>` next to the
  existing `tool_callback` (initialise to `None` in `Server::new`).
- **`StatusCallbackRef`**: a new struct `{ java_vm: Arc<jni::JavaVM>, callback: Arc<GlobalRef> }`
  (a sibling of `ToolCallbackRef`, not a reuse of it).
- **`Server::register_status_callback(&self, vm, callback)`**: mirrors
  `register_tool_callback`, storing into `status_callback`.
- **New JNI export in `lib.rs`**:
  `Java_com_anthropic_claudecode_eclipse_NativeCore_registerStatusCallback(env, _class, server_handle, callback)`
  — mirrors `registerToolCallback` (lines ~208–219): take a `GlobalRef`, call
  `server.register_status_callback(java_vm(), global_ref)`.
- **New invoker fn** (e.g. `fn call_java_status(vm, callback, tab, status_json)`), living with
  the server/status code — **a separate function from `mcp::call_java_tool`**. It does its own
  `attach_current_thread` and calls the Java method
  `onStatusUpdate(String tabToken, String statusJson)` with two `String` args. (JNI
  attach/detach is unavoidable, but the function and the Java method are dedicated to status.)

### 3c. Java native surface
- **`NativeCore`**: add
  `public static native void registerStatusCallback(long serverHandle, StatusCallback callback);`
  and a new interface:
  ```java
  public interface StatusCallback {
      /** Called from a Rust worker/OS thread (NOT the UI thread). */
      void onStatusUpdate(String tabToken, String statusJson);
  }
  ```
- **`StatusBridge implements NativeCore.StatusCallback`** — a sibling of `NativeToolBridge`,
  but it routes to the status subsystem (§4), never to `McpToolRegistry`. `Activator`
  registers it right after the server starts (where `registerToolCallback` is wired today).

> Rationale for the separation (per design guidance): status is not a tool, so it must not be
> discoverable via `tools/list` nor flow through tool dispatch. A dedicated callback keeps the
> two concerns independent and avoids overloading the MCP path with a non-tool message.

---

## 4. Delivery into the view — per-tab routing in Java

### 4a. Token → session routing (no registry — the token lives on the session)
The status JSON must land on the correct tab. Claude's JSON has a `session_id`, but the
plugin doesn't know which `session_id` maps to which `CTabItem`, and it can change. Instead we
**mint our own per-tab token** (`tabToken`) at launch and pass it to the forwarder through the
per-tab `CLAUDE_TAB_TOKEN` environment variable (§6) — **not** through the statusLine command,
so the shared settings file stays identical across tabs (§5). The forwarder echoes it back as
the `tab` query param, so the full request it makes is
`POST /statusline?tab=<tabToken>&authToken=<secret>` — `tab` is *our* routing id (distinct from
Claude's `session_id`, which we deliberately avoid depending on), and `authToken` is the auth
secret from §3a.

**No registry map.** Rather than maintaining a separate `Map<token → TerminalSession>` (a second
index that has to be kept in sync on every teardown path, and a process-global leak surface),
the token is simply a **field on `TerminalSession`**. `TerminalSession` is already an inner class
of `ClaudeCliView`, and the codebase already stores each session on its tab via
`CTabItem.setData(session)` and looks them up by iterating `tabFolder.getItems()` everywhere
(see `ClaudeCliView.java` lines 177, 207, 422, 498, 516). Routing reuses that existing idiom:

- The token is `UUID.randomUUID().toString()`, minted per `launch()` — see §5. A UUID is globally
  unique across views **and across JVM runs**, so a stale forwarder left over from a previous launch
  or a previous Eclipse run can never present a token that collides with a current tab's (§9).
- `TerminalSession` stores its token in a `final` field (`tabToken()` accessor). There is nothing
  to register and nothing to remove — the token dies with the session automatically.

### 4b. Dispatch — iterate live views and sessions
`StatusBridge.onStatusUpdate(tabToken, statusJson)`:
1. Parse `statusJson` with the plugin's bundled `gson` **off the UI thread** (see threading
   note).
2. `Display.getDefault().asyncExec(...)` to do the lookup-and-update on the UI thread: enumerate
   live `ClaudeCliView` instances via
   `PlatformUI.getWorkbench().getWorkbenchWindows()` → each page's `getViewReferences()`,
   filtered by `ClaudeCliView.VIEW_ID`, `getView(false)`, cast. For each, iterate
   `tabFolder.getItems()` → `(TerminalSession) item.getData()` and compare its `tabToken()`
   field. On the (usually only) match, push the parsed `ClaudeStatus` into that session's
   `ClaudeStatusBar`.
3. If no live session matches (tab already closed, race), drop it silently.

Lookup is O(views × tabs) — a handful of tabs, fired per assistant message + idle timer, not per
token — so the cost is negligible, and it deletes the registry, its concurrency control, and
every teardown-time `remove` call.

> **Threading:** the JNI call arrives on a Rust worker / spawned OS thread — **not** the SWT
> display thread. Parse the JSON off the UI thread (step 1); accessing the workbench windows,
> tab folders, `CTabItem` data, and the `ClaudeStatusBar` widget all require the UI thread, so the
> whole lookup-and-update runs inside the single `asyncExec` (step 2), per CLAUDE.md.

### 4c. Status model + parsing
Add a small immutable `ClaudeStatus` value type (model, effort, contextPct, fiveHour{pct,
resetsAt}, sevenDay{pct, resetsAt}) parsed with gson, **tolerant of missing / null fields**
(each becomes `Optional`/null → segment hidden). `TerminalSession` keeps its latest
`ClaudeStatus` and pushes it into its `ClaudeStatusBar` (the standalone widget class, §7) via
`setStatus(...)`. Both `ClaudeStatus` and `ClaudeStatusBar` are their own top-level classes, not inner
classes of `ClaudeCliView`.

---

## 5. Generating the command & the shared settings file — `ClaudeCliView.launch()`
This wires it together. In `TerminalSession.launch()` (currently `ClaudeCliView.java` around
the `extraArgs` loop, ~line 681–686), **when the status-line preference is enabled**:

1. `String token = UUID.randomUUID().toString();` Store it in the session's `final tabToken`
   field (§4a) — no registry to populate — and inject it into the child's environment as
   `CLAUDE_TAB_TOKEN`, alongside the `CLAUDE_CODE_SSE_PORT` / `CLAUDE_IDE_AUTH_TOKEN` already set
   per-tab (§6). **The token travels in the env, not in the settings file**, which is what keeps
   the file identical across tabs.
2. Build the statusLine `command` string (**no token in it**):
   - `javaBin = System.getProperty("java.home") + "/bin/java"` (`/bin/java.exe` on Windows).
   - `cp = FileLocator.getBundleFile(bundle).getAbsolutePath()`. **Prefer forward slashes**
     even on Windows (`cp.replace('\\','/')`) — Java accepts `/` in classpaths and it keeps
     backslashes out of the JSON.
   - **Derive the class name, don't hardcode it:**
     `String fqn = StandaloneStatusForwarder.class.getName();` (a compile-time reference — renaming or
     moving the class updates this automatically and fails the build if it's gone).
   - `command = "\"" + javaBin + "\" -Xmx24m -Xms8m -Xss512k -cp \"" + cp + "\" " + fqn;`
     Quote `javaBin` and `cp` (either may contain spaces, e.g. `C:/Program Files/...`).
3. **(Over)write the single shared settings file** — `statusline/settings.json` in the bundle
   state location (not the system temp dir, so it's sweepable and bundle-private — §9):
   - Dir: `Activator.getDefault().getStateLocation().append("statusline").toFile()`
     (`mkdirs()` once). This is the OSGi per-bundle data area — ours to clean.
   - Build the JSON (§1) with **gson** (compact + `disableHtmlEscaping()` so `<`, `>`, `&`, `=`,
     `'` aren't turned into `\uXXXX`) and write it to `settings.json` (UTF-8) **unconditionally on
     every launch** — no signature, no first-write flag, no diffing. The content depends only on
     `javaBin`, `cp`, and `refreshInterval`; the first two are fixed for the run and the third is
     a pref, so re-writing each launch is what makes a mid-session `refreshInterval` change take
     effect on the **next launch** (§8) for free, while staying byte-identical the rest of the
     time. The write is a sub-KB file touched only on a user-initiated tab launch (never a hot
     path), so the cost is negligible. Call `file.deleteOnExit()` once as a normal-shutdown
     backstop.
   - **No synchronization (mutex) is needed.** `launch()` is confined to the SWT UI thread — a
     `TerminalSession` defers it via `Display.getCurrent().asyncExec(...)` in its constructor
     (`ClaudeCliView.java:617`), the only caller — so every tab's write runs serially on the one
     UI thread and two launches can never overlap. A plain truncating write is therefore safe;
     atomic temp+rename would only guard against *concurrent* writers, which don't exist here. (A
     leftover from a *previous* run is a different concern, handled by the §9 startup sweep, not
     by locking.)
4. Append two tokens to `argTokens`: `"--settings"` and the **shared file path**, the latter via
   the existing `quoteArg`. Passing a *path* (not raw JSON) is exactly why the file route is
   robust: `quoteArg` already handles paths safely for the connector's `StreamTokenizer`
   tokenizer (it's the same mechanism proven for `claudeCmd`/`workingDir`), so there is **no
   inline-JSON escaping to get right** and **no Windows/no-PTY quoting hazard**.

When the preference is **disabled**, skip all of the above — inject nothing, so the user's own
`statusLine` (if any) is untouched and no status bar is shown.

> Edge cases in `launch()`:
> - `FileLocator.getBundleFile`/`getStateLocation` can throw, or `java.home` may be unset, or
>   the write can fail (read-only FS) → log (debug-gated) and launch **without** the status
>   line rather than failing the terminal.
> - `claudeArgs` is whitespace-split (line ~683) but `extraArgs` are added as discrete tokens,
>   so keep `--settings`/`<path>` in the `extraArgs` token list — never fold them into the
>   free-form `claudeArgs` string.
> - `--settings` is read by Claude at launch, so the file only needs to exist at spawn time. The
>   shared file is never deleted on tab close (other tabs and relaunches reuse it); cleanup is
>   the startup sweep plus `deleteOnExit` (§9).

---

## 6. Environment — the token rides here, no secrets on the command line
`StandaloneStatusForwarder` reads three env vars from its inherited environment:
`CLAUDE_CODE_SSE_PORT`, `CLAUDE_IDE_AUTH_TOKEN`, and `CLAUDE_TAB_TOKEN` (the per-tab routing
token, §4a). The first two are **already injected** by `ClaudeCliView.launch()`
(`ClaudeCliView.java` ~line 665–667) into the Claude process environment; Claude spawns the
statusLine command as a child, so they propagate automatically. **`CLAUDE_TAB_TOKEN` is the one
new env entry** — add it to that same per-tab environment setup (§5 step 1). It's a UUID, not a
secret, but routing it through the env rather than the statusLine command is what keeps the
shared settings file identical across tabs (§5) and keeps the auth token off every command line.
(Verify the port/auth pair is still set unconditionally there before relying on it.)

---

## 7. UI — the per-tab status bar widget (`ClaudeStatusBar`, its own class/file)

**Put the widget in its own file.** `ClaudeStatusBar` is a **top-level class**
`com.anthropic.claudecode.eclipse.ui.ClaudeStatusBar extends Composite`, living in its own
`ClaudeStatusBar.java` alongside the other `ui/` classes — **not** an inner class of `ClaudeCliView`
(which is already ~930 lines). All the bulk — building the segment labels/bars, the color
thresholds, the long/short responsive relayout, and the reset-countdown formatting — lives inside
`ClaudeStatusBar`. `ClaudeCliView`/`TerminalSession` only (a) create one per tab, (b) lay it out, and
(c) forward each `ClaudeStatus`, so essentially no new rendering code lands in the view.

Status bar is visually separated from the view main part with a delimiter (line).

**Public surface (everything else is `private` to the class):**
- `ClaudeStatusBar(Composite parent)` — builds the child labels/segments once.
- `void setStatus(ClaudeStatus status)` — called on the **UI thread** (from §4b, via
  `TerminalSession`); updates the segments, hiding any whose data is absent or whose preference is
  off. It re-reads the per-element visibility prefs (§8) on each call so toggles apply live.
- The class owns its own `SWT.Resize` listener (long vs short form) and its color/threshold and
  countdown-format helpers — none of that leaks into `ClaudeCliView`.

**Layout & wiring (the *only* `ClaudeCliView`-side change):** today the tab's `content` composite
uses `FillLayout` with the terminal as its sole child (`openNewSession`, ~line 392; the terminal
control is created with `content` as parent, ~line 734). Change `content` to a zero-margin
`GridLayout(1, false)`; give the terminal control `GridData(SWT.FILL, SWT.FILL, true, true)` so it
grabs the remaining space, and add the `ClaudeStatusBar` below it with
`GridData(SWT.FILL, SWT.CENTER, true, false)` — a fixed-height strip. `TerminalSession` holds a
`ClaudeStatusBar statusBar` field, creates it in `launch()` after the terminal control, and pushes data
via `statusBar.setStatus(...)`. One `ClaudeStatusBar` per `TerminalSession`; it shows only that tab's
data and, being a child of `content`, is disposed automatically with the tab (§9).

**Segments (each independently shown/hidden by preference AND by data availability):**
1. **Model** — `model.display_name`, black.
2. **Effort** — `effort.level` (e.g. `high`), gray. Hidden when `effort` absent.
3. **Thinking** — `thinking.enabled`, shown as a muted `thinking` indicator right after Effort
   in the model segment, joined by ` · `. Hidden when the field is absent or `false`, and
   **off by default** via its preference (`PREF_STATUSLINE_SHOW_THINKING`).
4. **Context** — label `Context` (long) / `C` (short), a small progress bar + rounded-down
   `%`. Color thresholds: green &lt; 70%, amber 70–85%, red ≥ 85%. Shown even if context is clear (0%).
5. **Cost** — `cost.total_cost_usd`, shown as `Cost $1.23` (long) / `$1.23` (short) right after
   Context in the left group, delimited by a vertical separator line. Hidden when the field is
   absent, and **off by default** via its preference (`PREF_STATUSLINE_SHOW_COST`).
6. **Session 5h** — label `Session`/`S`, bar + `%` + reset countdown from `resets_at`
   (epoch s → `Xh Ym` / `Xd Yh`). Color thresholds: blue &lt; 80%, amber 80–90%, red ≥ 90%.
   Hidden when `rate_limits.five_hour` absent (API-key users).
7. **Weekly** — label `Weekly`/`W`, bar + `%` + reset countdown from `seven_day.resets_at`.
   Color thresholds: same as Session (blue &lt; 80%, amber 80–90%, red ≥ 90%).
   Hidden when absent.

**Responsiveness:** on `SWT.Resize`, choose long vs short form by available width (recompute
label/segment visibility). Reset countdowns update on each `refreshInterval` push.

**Empty / pre-first-response state:** before the first status arrives (or when
`used_percentage` is `null`), the context segment shows `0%` with an empty bar. Rate-limit
segments (Session, Weekly) remain hidden until data is present.

**Colors/fonts:** small UI text — the default dialog font is likely more appropriate than the
console `fontDefinition`; pick deliberately and reuse the view's theme conventions.

---

## 8. Preferences
Add keys to `Constants.java` (`PREF_*`) with defaults in `ClaudePreferenceInitializer`, and a
section on the existing Claude preference page.

- `PREF_STATUSLINE_ENABLED` (boolean, default **true**) — master switch. When off, no
  `--settings` injection and no bar.
- Per-element visibility toggles (all default true, **except thinking and cost**):
  - `PREF_STATUSLINE_SHOW_MODEL`
  - `PREF_STATUSLINE_SHOW_EFFORT`
  - `PREF_STATUSLINE_SHOW_THINKING` (default **false** — opt-in)
  - `PREF_STATUSLINE_SHOW_CONTEXT`
  - `PREF_STATUSLINE_SHOW_COST` (default **false** — opt-in)
  - `PREF_STATUSLINE_SHOW_SESSION_5H`
  - `PREF_STATUSLINE_SHOW_WEEKLY`
- `PREF_STATUSLINE_REFRESH_SECONDS` (int, default **60**, min 1) — fed into the settings JSON
  `refreshInterval`. Document that this is Claude's idle re-run timer (seconds).

Label wording: **"Session"** (5-hour) and **"Weekly"** (7-day), matching the mockups. "Mode"
(`output_style.name`) is intentionally **not** a v1 element.

Element-visibility toggles can apply live (re-read prefs in the `ClaudeStatusBar` render path).
`PREF_STATUSLINE_ENABLED` and `PREF_STATUSLINE_REFRESH_SECONDS` take effect on the **next
launch** (the settings JSON is generated at launch) — note this in the pref tooltip.

---

## 9. Lifecycle & teardown checklist — incl. settings-file cleanup
Moving the token into the env (§6) collapses the per-tab file into **one shared
`statusline/settings.json`** per run, so there is no per-tab file to delete and the cleanup
surface shrinks to a single artifact:
- **Startup sweep (handles abnormal termination):** in `Activator.start()`, delete the
  contents of the `statusline/` state-location dir before any tab launches. Even if a prior
  Eclipse crash / JVM kill left the file behind, it's cleared at next startup (and regenerated
  on the next launch), and it only ever lives in a bundle-private directory (never random names
  in the system temp). **This is the primary mitigation for the dangling-file concern.**
- **No per-tab delete:** `TerminalSession.dispose()` only disposes the `ClaudeStatusBar` widget — it
  **does not** touch the settings file, because other live tabs and future relaunches reuse the
  same shared file. **No registry entry to remove** either — the token is a field on the session,
  reclaimed automatically when the session/`CTabItem` is disposed (§4a). The whole class of
  per-tab teardown bugs is gone with the per-tab file.
- **`deleteOnExit()` backstop (normal JVM shutdown):** registered once when the shared file is
  first written.
- **View close / Eclipse shutdown:** no process-global routing state survives a view, so there
  is no cross-view cleanup obligation and no leak path to get wrong. Status lookups simply find
  no live session and drop (§4b).
- **Server already gone:** the forwarder POST fails fast and exits 0 — harmless.
- **Token:** a fresh `UUID.randomUUID()` per `launch()`, passed via `CLAUDE_TAB_TOKEN` (§6);
  never reused across relaunch of the same tab and globally unique across JVM runs, so an
  in-flight forwarder from a prior launch or a previous Eclipse run can never echo a `tab` that
  collides with a current session. Defense-in-depth: the server also mints a fresh `auth_token`
  per run (`server.rs:110`), so any orphaned forwarder from a previous run presents a stale
  `CLAUDE_IDE_AUTH_TOKEN` and is rejected with `401` (§3a).

---

## 10. Build / rebuild checklist
- **Rust:** new `/statusline` route + `statusline_handler` (auth via `state.auth_token`),
  `status_callback`/`StatusCallbackRef`/`register_status_callback` in `server.rs`, the
  `registerStatusCallback` JNI export + dedicated `call_java_status` invoker in `lib.rs`.
  Rebuild `libclaude_eclipse_core` for **all 3 platforms** and copy into
  `com.anthropic.claudecode.eclipse/native/<os>/<arch>/` (manual process per CLAUDE.md).
- **Java:** `StandaloneStatusForwarder` (pure `java.base`), `NativeCore.StatusCallback` +
  `registerStatusCallback`, `StatusBridge`, `Activator` registration, `ClaudeStatus` model **(own
  file)**, `ClaudeStatusBar` widget **(own top-level class `ui/ClaudeStatusBar.java`, not inner to
  `ClaudeCliView`)**, and in `ClaudeCliView` only the thin glue: the `tabToken` (`UUID`) field on
  `TerminalSession` + view/tab iteration for routing + launch/teardown + per-tab
  `CLAUDE_TAB_TOKEN` env injection + the unconditional **shared** `statusline/settings.json` write
  (UI-thread-confined, no locking) + the `content` `FillLayout`→`GridLayout` change that seats the
  bar under the terminal (§7). Plus the `statusline/` startup sweep in `Activator.start()`, new
  `Constants` keys + `ClaudePreferenceInitializer` defaults + pref-page UI. Compiled by the normal
  PDE build.
- **`build.properties`:** no change expected (class ships inside the existing plugin jar). If
  `StandaloneStatusForwarder` is ever placed under a new top-level dir, add it to `bin.includes`.
- **ABI:** one new native method (`registerStatusCallback`) + one new HTTP route; the MCP
  tool path is untouched.

---

## 11. Open items to verify during implementation
- Confirm the CLI build treats command-line `--settings <path>` at the documented precedence
  (above local/project/user, below managed). Passing a **file path** (not inline JSON) is the
  documented form, so this is low-risk.
- Empirically tune the runner's `-Xmx`/`-Xss` on HotSpot **and** OpenJ9/Semeru; settle on the
  smallest stable values with a small reservation (start at `-Xmx24m -Xss512k`).
- Confirm `used_percentage` typing in practice (int vs float) and round-down display.
- Decide exact width breakpoints for long↔short forms against the two mockups.
- Confirm `effort` is *hidden* (vs shown as "—") when the model lacks the parameter (current
  plan: hide).

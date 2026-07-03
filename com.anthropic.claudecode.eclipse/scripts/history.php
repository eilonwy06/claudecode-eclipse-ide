<?php
declare(strict_types=1);

/*
 * Local session-history reader for the Claude GUI.
 *
 * Reads the Claude CLI's per-project conversation logs
 * (~/.claude/projects/<hash>/*.jsonl) — pure local file + JSON work, so it lives
 * here in PHP instead of the Rust core (history iterates a lot; keeping it as a
 * script means no DLL rebuild to tweak it).
 *
 * Usage:  php history.php <list|load|delete> <workspaceRoot> [sessionId]
 * Output (stdout, JSON):
 *   list   -> [{sessionId, display, timestamp}, ...]   (newest first, max 100)
 *   load   -> [{role, content, timestamp}, ...]        (final text only)
 *   delete -> {"ok": true|false}
 */

const MAX_SESSIONS = 100;
const DISPLAY_CHARS = 120;

$action = $argv[1] ?? '';
$root   = $argv[2] ?? '';
$sid    = $argv[3] ?? '';

function workspace_hash(string $p): string {
    // Mirrors Claude CLI: every non-alphanumeric char becomes '-'.
    return preg_replace('/[^A-Za-z0-9]/', '-', $p) ?? '';
}

function home_dir(): ?string {
    if (PHP_OS_FAMILY === 'Windows') {
        $h = getenv('USERPROFILE');
    } else {
        $h = getenv('HOME');
    }
    return ($h !== false && $h !== '') ? $h : null;
}

function projects_dir(string $root): ?string {
    $home = home_dir();
    if ($home === null) return null;
    $dir = $home . DIRECTORY_SEPARATOR . '.claude' . DIRECTORY_SEPARATOR . 'projects'
         . DIRECTORY_SEPARATOR . workspace_hash($root);
    return is_dir($dir) ? $dir : null;
}

/** Strip the editor-context preamble AND Claude Code command/meta wrappers so list
 *  titles aren't raw <ide_selection> / <command-name> / <local-command-*> tags. */
function strip_ide(string $s): string {
    $s = preg_replace('/<ide_selection\b[^>]*>.*?<\/ide_selection>/is', '', $s) ?? $s;
    $s = preg_replace('/<ide_context\b[^>]*\/>/is', '', $s) ?? $s;
    $s = preg_replace('/<local-command-caveat>.*?<\/local-command-caveat>/is', '', $s) ?? $s;
    $s = preg_replace('/<command-message>.*?<\/command-message>/is', '', $s) ?? $s;
    $s = preg_replace('/<command-args>.*?<\/command-args>/is', '', $s) ?? $s;
    $s = preg_replace('/<local-command-stdout>.*?<\/local-command-stdout>/is', '', $s) ?? $s;
    $s = preg_replace('/<command-name>(.*?)<\/command-name>/is', '$1', $s) ?? $s;
    return ltrim($s);
}

function take(string $s, int $n): string {
    return function_exists('mb_substr') ? mb_substr($s, 0, $n) : substr($s, 0, $n);
}

function list_sessions(string $root): array {
    $dir = projects_dir($root);
    if ($dir === null) return [];
    $out = [];
    foreach (glob($dir . DIRECTORY_SEPARATOR . '*.jsonl') ?: [] as $path) {
        $id = pathinfo($path, PATHINFO_FILENAME);
        $fh = @fopen($path, 'r');
        if (!$fh) continue;
        $display = ''; $ts = ''; $found = false;
        while (($line = fgets($fh)) !== false) {
            $line = trim($line);
            if ($line === '') continue;
            $e = json_decode($line, true);
            if (!is_array($e)) continue;
            if (($e['type'] ?? '') === 'user') {
                $c = $e['message']['content'] ?? null;
                if (is_string($c)) $display = take(strip_ide($c), DISPLAY_CHARS);
                $ts = is_string($e['timestamp'] ?? null) ? $e['timestamp'] : '';
                $found = true;
                break;
            }
        }
        fclose($fh);
        if (!$found) continue;
        $out[] = ['sessionId' => $id, 'display' => $display, 'timestamp' => $ts];
    }
    usort($out, fn($a, $b) => strcmp((string) $b['timestamp'], (string) $a['timestamp']));
    return array_slice($out, 0, MAX_SESSIONS);
}

/*
 * Returns the conversation as an ordered list of render items so the GUI can
 * reconstruct EXACTLY how the live session looked:
 *   {t:user, content}      - user message (raw; GUI parses the ide_selection chip)
 *   {t:thinking}           - a thinking block (shown as "Thinking", no duration)
 *   {t:tool, name, input}  - a tool call (Read/Edit/Search/Asking... + inline diff)
 *   {t:answered, text}     - the user's answer to an askUserQuestion card
 *   {t:text, text}         - assistant prose
 */
function load_session(string $root, string $sid): array {
    $dir = projects_dir($root);
    if ($dir === null || $sid === '') return [];
    $path = $dir . DIRECTORY_SEPARATOR . $sid . '.jsonl';
    $fh = @fopen($path, 'r');
    if (!$fh) return [];
    $items = [];
    $askIds = [];   // tool_use_id => true, for askUserQuestion calls (to surface answers)
    while (($line = fgets($fh)) !== false) {
        $line = trim($line);
        if ($line === '') continue;
        $e = json_decode($line, true);
        if (!is_array($e)) continue;
        $type = $e['type'] ?? '';
        if ($type === 'user') {
            $c = $e['message']['content'] ?? '';
            if (is_string($c)) {
                $items[] = ['t' => 'user', 'content' => $c];
            } elseif (is_array($c)) {
                foreach ($c as $b) {
                    if (($b['type'] ?? '') === 'tool_result' && isset($askIds[$b['tool_use_id'] ?? ''])) {
                        $rc = $b['content'] ?? '';
                        if (is_array($rc)) {
                            $txt = '';
                            foreach ($rc as $rb) { if (($rb['type'] ?? '') === 'text') $txt .= ($rb['text'] ?? ''); }
                            $rc = $txt;
                        }
                        if (is_string($rc) && $rc !== '') {
                            $rc = preg_replace('/^\s*The user answered:\s*/i', '', $rc);
                            $items[] = ['t' => 'answered', 'text' => $rc];
                        }
                    }
                }
            }
        } elseif ($type === 'assistant') {
            if (!empty($e['partial'])) continue;
            $content = $e['message']['content'] ?? null;
            if (!is_array($content)) continue;
            // The model this turn ran on — attached to each item so the GUI can
            // resume the conversation with its last-used model + show it in the bar.
            $model = is_string($e['message']['model'] ?? null) ? $e['message']['model'] : '';
            foreach ($content as $b) {
                $bt = $b['type'] ?? '';
                if ($bt === 'thinking') {
                    $tt = is_string($b['thinking'] ?? null) ? $b['thinking'] : '';
                    $items[] = ['t' => 'thinking', 'model' => $model, 'text' => $tt];
                } elseif ($bt === 'text') {
                    if (isset($b['text']) && is_string($b['text']) && $b['text'] !== '') {
                        $items[] = ['t' => 'text', 'text' => $b['text'], 'model' => $model];
                    }
                } elseif ($bt === 'tool_use') {
                    $name = is_string($b['name'] ?? null) ? $b['name'] : 'tool';
                    $input = $b['input'] ?? new stdClass();
                    $items[] = ['t' => 'tool', 'name' => $name, 'input' => $input, 'model' => $model];
                    if (stripos($name, 'askUserQuestion') !== false && isset($b['id'])) {
                        $askIds[$b['id']] = true;
                    }
                }
            }
        }
    }
    fclose($fh);
    return $items;
}

function delete_session(string $root, string $sid): bool {
    if ($sid === '' || strpbrk($sid, "/\\") !== false || str_contains($sid, '..')) return false;
    $dir = projects_dir($root);
    if ($dir === null) return false;
    $path = $dir . DIRECTORY_SEPARATOR . $sid . '.jsonl';
    return is_file($path) ? @unlink($path) : false;
}

$flags = JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE;
switch ($action) {
    case 'list':   echo json_encode(list_sessions($root), $flags); break;
    case 'load':   echo json_encode(load_session($root, $sid), $flags); break;
    case 'delete': echo json_encode(['ok' => delete_session($root, $sid)]); break;
    default:       echo '[]';
}

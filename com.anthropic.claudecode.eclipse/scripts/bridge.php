#!/usr/bin/env php
<?php
declare(strict_types=1);
error_reporting(E_ALL);
set_time_limit(0);
ob_implicit_flush(true);
if (function_exists('ob_end_flush')) {
    @ob_end_flush();
}

// Early diagnostic: write to ready file immediately to confirm script is running
if ($argc === 4 && !empty($argv[3])) {
    file_put_contents($argv[3], "STARTED\n");
}

if ($argc < 3 || $argc > 4) {
    fwrite(STDERR, "Usage: php bridge.php <port_min> <port_max> [ready_file]\n");
    exit(1);
}

$portMin = (int) $argv[1];
$portMax = (int) $argv[2];
$readyFile = ($argc === 4) ? $argv[3] : null;

// Shared-secret handshake token, passed out-of-band via the environment (never
// argv, which is world-readable via the process list). Every accepted peer must
// present it on its first line before being wired through. Fail closed: with no
// token, refuse to run rather than expose an open relay.
$expectedToken = getenv('CB_TOKEN');
if ($expectedToken === false || $expectedToken === '') {
    fwrite(STDERR, "Missing handshake token; refusing to start.\n");
    exit(1);
}

// PHP's stream_socket_server() sets SO_REUSEADDR, which on Windows lets a
// bind succeed even when another process already listens on the port. A
// failed bind therefore isn't a reliable "port taken" signal: probe with a
// connect first and skip any port that answers.
function port_in_use(int $port): bool
{
    $probe = @stream_socket_client("tcp://127.0.0.1:$port", $errno, $errstr, 0.2);
    if ($probe) {
        fclose($probe);
        return true;
    }
    return false;
}

// Accept a connection on $server only if it presents the expected token on its
// first line within a short window; otherwise drop it and keep listening. This
// is what prevents any other local process from attaching to the relay.
function accept_authed($server, string $expectedToken)
{
    $conn = @stream_socket_accept($server, 0);
    if (!$conn) {
        return null;
    }
    stream_set_blocking($conn, true);
    stream_set_timeout($conn, 2);
    $line = fgets($conn, 4096);
    $got = ($line === false) ? '' : trim($line);
    if (!hash_equals($expectedToken, $got)) {
        // Surfaced as [PhpBridge STDERR] in the plugin log only when Debug mode
        // is on (the Java drain gates it); always written here, cheap and rare.
        fwrite(STDERR, "rejected unauthenticated peer\n");
        @fclose($conn);
        return null;
    }
    stream_set_blocking($conn, false);
    fwrite(STDERR, "authenticated peer\n");
    return $conn;
}

// Scan the range and bind the first two free ports, so concurrent IDE
// instances each get their own pair instead of colliding on fixed ports.
$serverA = null;
$serverB = null;
$portA = 0;
$portB = 0;
for ($p = $portMin; $p <= $portMax; $p++) {
    if (port_in_use($p)) {
        continue;
    }
    $sock = @stream_socket_server("tcp://127.0.0.1:$p", $errno, $errstr);
    if (!$sock) {
        continue;
    }
    if ($serverA === null) {
        $serverA = $sock;
        $portA = $p;
    } else {
        $serverB = $sock;
        $portB = $p;
        break;
    }
}
if ($serverA === null || $serverB === null) {
    fwrite(STDERR, "No two free ports in range $portMin-$portMax\n");
    if ($serverA !== null) {
        fclose($serverA);
    }
    exit(1);
}

stream_set_blocking($serverA, false);
stream_set_blocking($serverB, false);

fwrite(STDOUT, "READY $portA $portB\n");
fflush(STDOUT);
fwrite(STDERR, "READY_STDERR $portA $portB\n");

// File-based ready signal (workaround for macOS pipe buffering)
if ($readyFile !== null) {
    file_put_contents($readyFile, "READY $portA $portB\nBridge connected.\n");
}

$clientA = null;
$clientB = null;
$running = true;

if (function_exists('pcntl_signal')) {
    pcntl_signal(SIGTERM, function() use (&$running) { $running = false; });
    pcntl_signal(SIGINT,  function() use (&$running) { $running = false; });
}

while ($running) {
    if (function_exists('pcntl_signal_dispatch')) {
        pcntl_signal_dispatch();
    }

    if (!$clientA) {
        $conn = accept_authed($serverA, $expectedToken);
        if ($conn) {
            $clientA = $conn;
        }
    }
    if (!$clientB) {
        $conn = accept_authed($serverB, $expectedToken);
        if ($conn) {
            $clientB = $conn;
        }
    }

    if ($clientA && $clientB) {
        $read = [$clientA, $clientB];
        $write = null;
        $except = null;

        if (@stream_select($read, $write, $except, 0, 50000) > 0) {
            foreach ($read as $sock) {
                $data = @fread($sock, 65536);
                if ($data === false || $data === '') {
                    $running = false;
                    break 2;
                }
                $target = ($sock === $clientA) ? $clientB : $clientA;
                @fwrite($target, $data);
            }
        }
    } else {
        usleep(10000);
    }
}

if ($clientA) fclose($clientA);
if ($clientB) fclose($clientB);
fclose($serverA);
fclose($serverB);
exit(0);

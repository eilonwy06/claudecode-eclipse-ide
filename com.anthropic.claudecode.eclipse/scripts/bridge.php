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

// How long the relay may sit without a complete peer pair before it gives up and
// exits. This is the orphan guard: the relay is a child process holding two listening
// sockets, and now that it outlives a disconnect, an Eclipse that dies without stopping
// it would strand it -- and its two ports -- until the machine is restarted. A dead IDE
// closes both of its peer sockets, so "nobody wired through for a while" is exactly the
// shape of that crash. The plug-in's watchdog re-establishes a live relay within seconds,
// so this can only fire when there is nothing left to reconnect.
const IDLE_EXIT_SECONDS = 30;

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

// Wall clock since the relay last had both peers wired through, or since it came up if
// it never has. Only the idle guard reads it; a live pairing keeps resetting it.
$unpairedSince = microtime(true);

if (function_exists('pcntl_signal')) {
    pcntl_signal(SIGTERM, function() use (&$running) { $running = false; });
    pcntl_signal(SIGINT,  function() use (&$running) { $running = false; });
}

// One pass per pairing. The two listening sockets are bound once, above, and stay bound
// for the life of this process: a peer hanging up ends that PAIRING, not the relay, so
// the loop drops both halves and goes back to accepting on the SAME two ports. Only a
// signal from the plug-in, or the idle guard, ends the relay itself.
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
        // Wired through, so the idle guard's clock stays parked at now.
        $unpairedSince = microtime(true);

        $read = [$clientA, $clientB];
        $write = null;
        $except = null;
        $hungUp = false;

        if (@stream_select($read, $write, $except, 0, 50000) > 0) {
            foreach ($read as $sock) {
                $data = @fread($sock, 65536);
                // On a non-blocking socket an empty read is EOF only when feof() agrees;
                // a readable socket can still come back empty. Tearing a live pairing
                // down on that would drop the relay under a peer that never left.
                if ($data === false || ($data === '' && feof($sock))) {
                    $hungUp = true;
                    break;
                }
                if ($data === '') {
                    continue;
                }
                $target = ($sock === $clientA) ? $clientB : $clientA;
                @fwrite($target, $data);
            }
        }

        if ($hungUp) {
            // Both halves go, not just the one that left: a relay wired to a single peer
            // is not a relay, and the plug-in rebuilds its side from scratch anyway. The
            // listeners are untouched, so the next pair lands on the same ports.
            @fclose($clientA);
            @fclose($clientB);
            $clientA = null;
            $clientB = null;
            $unpairedSince = microtime(true);
            fwrite(STDERR, "peer disconnected; relay waiting for a new pair\n");
        }
    } else {
        // Orphan guard -- see IDLE_EXIT_SECONDS.
        if (microtime(true) - $unpairedSince >= IDLE_EXIT_SECONDS) {
            fwrite(STDERR, "no peers for " . IDLE_EXIT_SECONDS . "s; relay exiting\n");
            break;
        }
        usleep(10000);
    }
}

if ($clientA) fclose($clientA);
if ($clientB) fclose($clientB);
fclose($serverA);
fclose($serverB);
exit(0);

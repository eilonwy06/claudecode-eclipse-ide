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
    fwrite(STDERR, "Usage: php bridge.php <port_a> <port_b> [ready_file]\n");
    exit(1);
}

$portA = (int) $argv[1];
$portB = (int) $argv[2];
$readyFile = ($argc === 4) ? $argv[3] : null;

$serverA = @stream_socket_server("tcp://127.0.0.1:$portA", $errno, $errstr);
if (!$serverA) {
    fwrite(STDERR, "Failed to bind port $portA: $errstr\n");
    exit(1);
}

$serverB = @stream_socket_server("tcp://127.0.0.1:$portB", $errno, $errstr);
if (!$serverB) {
    fwrite(STDERR, "Failed to bind port $portB: $errstr\n");
    fclose($serverA);
    exit(1);
}

stream_set_blocking($serverA, false);
stream_set_blocking($serverB, false);

fwrite(STDOUT, "READY $portA $portB\n");
fflush(STDOUT);
fwrite(STDERR, "READY_STDERR $portA $portB\n");

// File-based ready signal (workaround for macOS pipe buffering)
if ($readyFile !== null) {
    file_put_contents($readyFile, "READY $portA $portB\n");
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
        $conn = @stream_socket_accept($serverA, 0);
        if ($conn) {
            $clientA = $conn;
            stream_set_blocking($clientA, false);
        }
    }
    if (!$clientB) {
        $conn = @stream_socket_accept($serverB, 0);
        if ($conn) {
            $clientB = $conn;
            stream_set_blocking($clientB, false);
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

#!/usr/bin/env bash
# Builds the Rust native library and copies it into the Eclipse plugin.
# Run this from the claude-eclipse-core/ directory.

set -euo pipefail

PLUGIN_DIR="../com.anthropic.claudecode.eclipse"

cargo build --release

# Windows
if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "cygwin" || "$OS" == "Windows_NT" ]]; then
    DEST="$PLUGIN_DIR/native/windows/x86_64"
    mkdir -p "$DEST"
    cp target/release/claude_eclipse_core.dll "$DEST/"
    echo "Copied claude_eclipse_core.dll -> $DEST/"

# Linux
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    DEST="$PLUGIN_DIR/native/linux/x86_64"
    mkdir -p "$DEST"
    cp target/release/libclaude_eclipse_core.so "$DEST/"
    echo "Copied libclaude_eclipse_core.so -> $DEST/"

# macOS
elif [[ "$OSTYPE" == "darwin"* ]]; then
    DEST="$PLUGIN_DIR/native/macos/x86_64"
    mkdir -p "$DEST"
    cp target/release/libclaude_eclipse_core.dylib "$DEST/"
    echo "Copied libclaude_eclipse_core.dylib -> $DEST/"
fi

echo "Done. Now rebuild the Eclipse plugin."

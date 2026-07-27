#!/usr/bin/env bash
set -e

JAR="target/v2ray-ubuntu-1.0.0.jar"
INSTALL_DIR="$HOME/.local/bin"
XRAY_BIN="$INSTALL_DIR/xray"

# ── Check Java ────────────────────────────────────────────────────────────────
if ! command -v java &>/dev/null; then
    echo "[ERROR] Java is not installed."
    echo "        Install it with: sudo apt install openjdk-21-jre"
    exit 1
fi

JAVA_VER=$(java -version 2>&1 | awk -F'"' '/version/ {print $2}' | cut -d'.' -f1)
if [ "$JAVA_VER" -lt 21 ] 2>/dev/null; then
    echo "[ERROR] Java 21+ is required (found version $JAVA_VER)."
    echo "        Install it with: sudo apt install openjdk-21-jre"
    exit 1
fi

# ── Install Xray if missing ───────────────────────────────────────────────────
if ! command -v xray &>/dev/null && [ ! -x "$XRAY_BIN" ]; then
    echo "[INFO] Xray not found. Installing to $INSTALL_DIR ..."
    mkdir -p "$INSTALL_DIR"
    curl -fsSL \
        "https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-64.zip" \
        -o /tmp/xray.zip
    unzip -o /tmp/xray.zip xray -d "$INSTALL_DIR"
    chmod +x "$XRAY_BIN"
    rm -f /tmp/xray.zip
    echo "[INFO] Xray installed at $XRAY_BIN"

    # Add to PATH for this session
    export PATH="$INSTALL_DIR:$PATH"

    # Persist PATH in shell config if not already there
    SHELL_RC="$HOME/.bashrc"
    if [ -n "$ZSH_VERSION" ] || echo "$SHELL" | grep -q zsh; then
        SHELL_RC="$HOME/.zshrc"
    fi
    if ! grep -q "$INSTALL_DIR" "$SHELL_RC" 2>/dev/null; then
        echo "export PATH=\"$INSTALL_DIR:\$PATH\"" >> "$SHELL_RC"
        echo "[INFO] Added $INSTALL_DIR to PATH in $SHELL_RC"
    fi
else
    echo "[INFO] Xray found: $(command -v xray || echo $XRAY_BIN)"
fi

# ── Build if JAR missing ──────────────────────────────────────────────────────
if [ ! -f "$JAR" ]; then
    echo "[INFO] JAR not found. Building from source ..."
    if command -v mvn &>/dev/null; then
        mvn clean package -DskipTests -q
    else
        echo "[ERROR] Maven (mvn) not found and JAR is missing."
        echo "        Install Maven: sudo apt install maven"
        echo "        Or download a pre-built JAR."
        exit 1
    fi
fi

# ── Launch ────────────────────────────────────────────────────────────────────
echo ""
echo "  v2ray-ubuntu is starting ..."
echo "  Open http://localhost:8080 in your browser"
echo "  Press Ctrl+C to stop"
echo ""

export PATH="$INSTALL_DIR:$PATH"
exec java -jar "$JAR"

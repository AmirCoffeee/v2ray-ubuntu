#!/usr/bin/env bash
set -e

# ─────────────────────────────────────────────────────────────────────────────
#  v2ray-ubuntu installer  (port 12345)
#  Usage:  bash install.sh
#  One-line: bash <(curl -fsSL https://raw.githubusercontent.com/AmirCoffeee/v2ray-ubuntu/main/install.sh)
# ─────────────────────────────────────────────────────────────────────────────

REPO="AmirCoffeee/v2ray-ubuntu"
VERSION="1.0.1"
JAR_URL="https://github.com/${REPO}/releases/download/v${VERSION}/v2ray-ubuntu-${VERSION}.jar"
INSTALL_DIR="/opt/v2ray-ubuntu"
JAR_PATH="${INSTALL_DIR}/v2ray-ubuntu.jar"
VERSION_FILE="${INSTALL_DIR}/VERSION"
SERVICE_NAME="v2ray-ubuntu"
PORT=12345

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BOLD='\033[1m'
NC='\033[0m'

info()    { echo -e "${GREEN}[✔]${NC} $*"; }
warn()    { echo -e "${YELLOW}[!]${NC} $*"; }
error()   { echo -e "${RED}[✘]${NC} $*"; exit 1; }
section() { echo -e "\n${BOLD}── $* ──${NC}"; }

need_sudo() {
    if [ "$EUID" -ne 0 ]; then
        warn "This step needs sudo."
        SUDO="sudo"
    else
        SUDO=""
    fi
}

# ── Detect desktop environment ────────────────────────────────────────────────
detect_desktop() {
    HAS_DISPLAY=false
    DESKTOP_ENV="none"

    # Check if any display is available
    if [ -n "$DISPLAY" ] || [ -n "$WAYLAND_DISPLAY" ]; then
        HAS_DISPLAY=true
    fi

    # Detect desktop environment
    if [ -n "$XDG_CURRENT_DESKTOP" ]; then
        case "${XDG_CURRENT_DESKTOP,,}" in
            *gnome*)  DESKTOP_ENV="gnome" ;;
            *kde*)    DESKTOP_ENV="kde"   ;;
            *xfce*)   DESKTOP_ENV="xfce"  ;;
            *lxde*)   DESKTOP_ENV="lxde"  ;;
            *mate*)   DESKTOP_ENV="mate"  ;;
            *)        DESKTOP_ENV="other" ;;
        esac
    elif [ -n "$GNOME_DESKTOP_SESSION_ID" ]; then
        DESKTOP_ENV="gnome"
    elif [ -n "$KDE_FULL_SESSION" ]; then
        DESKTOP_ENV="kde"
    elif command -v gnome-shell &>/dev/null; then
        DESKTOP_ENV="gnome"
    elif command -v plasmashell &>/dev/null; then
        DESKTOP_ENV="kde"
    fi

    # Server: no display
    if [ "$HAS_DISPLAY" = false ]; then
        DESKTOP_ENV="none"
    fi

    info "Desktop environment: ${DESKTOP_ENV} (display=${HAS_DISPLAY})"
}

# ── 1. Dependencies ───────────────────────────────────────────────────────────
section "Checking dependencies"
need_sudo
detect_desktop

# Java 21+
JAVA_OK=false
if command -v java &>/dev/null; then
    JV=$(java -version 2>&1 | awk -F'"' '/version/{print $2}' | cut -d'.' -f1)
    if [ "${JV:-0}" -ge 21 ] 2>/dev/null; then
        JAVA_OK=true
    fi
fi

if [ "$JAVA_OK" = false ]; then
    warn "Java 21 not found — installing..."
    $SUDO apt-get update -qq
    if [ "$DESKTOP_ENV" = "none" ]; then
        $SUDO apt-get install -y openjdk-21-jre-headless
    else
        # Desktop: install full JRE with JavaFX support
        $SUDO apt-get install -y openjdk-21-jre openjdk-21-jdk
    fi
    info "Java installed: $(java -version 2>&1 | head -1)"
else
    info "Java OK: $(java -version 2>&1 | head -1)"
fi

# curl, unzip
for pkg in curl unzip; do
    if ! command -v $pkg &>/dev/null; then
        warn "$pkg not found — installing..."
        $SUDO apt-get install -y $pkg
    fi
done

# Xray-core
if ! command -v xray &>/dev/null && [ ! -x "/usr/local/bin/xray" ]; then
    warn "Xray-core not found — installing..."
    bash -c "$(curl -fsSL https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install
    info "Xray installed: $(xray version 2>&1 | head -1)"
else
    info "Xray OK: $(xray version 2>&1 | head -1)"
fi

# ── 2. Version check & upgrade ────────────────────────────────────────────────
section "Installing v2ray-ubuntu v${VERSION}"

$SUDO mkdir -p "${INSTALL_DIR}"

NEEDS_DOWNLOAD=true

if [ -f "${JAR_PATH}" ]; then
    if [ -f "${VERSION_FILE}" ]; then
        INSTALLED_VER=$(cat "${VERSION_FILE}" 2>/dev/null || echo "0.0.0")
        if [ "$INSTALLED_VER" = "$VERSION" ]; then
            warn "v${VERSION} already installed — skipping download."
            NEEDS_DOWNLOAD=false
        else
            warn "Old version ${INSTALLED_VER} found — replacing with v${VERSION}..."
            $SUDO rm -f "${JAR_PATH}"
            # Stop running service before replacing
            if $SUDO systemctl is-active --quiet "${SERVICE_NAME}" 2>/dev/null; then
                $SUDO systemctl stop "${SERVICE_NAME}" || true
                info "Stopped old service"
            fi
        fi
    else
        warn "No version file found — replacing existing JAR with v${VERSION}..."
        $SUDO rm -f "${JAR_PATH}"
    fi
fi

if [ "$NEEDS_DOWNLOAD" = true ]; then
    info "Downloading JAR v${VERSION} from GitHub..."
    $SUDO curl -fsSL "${JAR_URL}" -o "${JAR_PATH}"
    echo "${VERSION}" | $SUDO tee "${VERSION_FILE}" > /dev/null
    info "Downloaded to ${JAR_PATH}"
fi

$SUDO chmod +x "${JAR_PATH}"

# ── 3. Systemd service ────────────────────────────────────────────────────────
section "Creating systemd service"

# Determine exec: headless server → just java; desktop → javafx gui mode flag
if [ "$DESKTOP_ENV" = "none" ]; then
    EXEC_START="/usr/bin/java -jar ${JAR_PATH} --headless"
else
    EXEC_START="/usr/bin/java -jar ${JAR_PATH}"
fi

$SUDO tee /etc/systemd/system/${SERVICE_NAME}.service > /dev/null <<EOF
[Unit]
Description=v2ray-ubuntu — Xray-core manager (port ${PORT})
After=network.target

[Service]
Type=simple
User=${USER}
ExecStart=${EXEC_START}
Restart=on-failure
RestartSec=5
WorkingDirectory=${INSTALL_DIR}
StandardOutput=journal
StandardError=journal
Environment="DISPLAY=:0"
Environment="XAUTHORITY=${HOME}/.Xauthority"

[Install]
WantedBy=multi-user.target
EOF

$SUDO systemctl daemon-reload
$SUDO systemctl enable --now ${SERVICE_NAME}

if $SUDO systemctl is-active --quiet ${SERVICE_NAME}; then
    info "Service running (systemctl status ${SERVICE_NAME})"
else
    warn "Service may have failed. Check: journalctl -u ${SERVICE_NAME} -n 30"
fi

# ── 4. CLI launcher ───────────────────────────────────────────────────────────
section "Creating CLI launcher"

$SUDO tee /usr/local/bin/v2ray-ubuntu > /dev/null <<'SCRIPT'
#!/usr/bin/env bash
case "$1" in
    start)   sudo systemctl start   v2ray-ubuntu ;;
    stop)    sudo systemctl stop    v2ray-ubuntu ;;
    restart) sudo systemctl restart v2ray-ubuntu ;;
    status)  sudo systemctl status  v2ray-ubuntu ;;
    log)     sudo journalctl -u     v2ray-ubuntu -f ;;
    open)    xdg-open http://localhost:12345 ;;
    *)
        echo "Usage: v2ray-ubuntu {start|stop|restart|status|log|open}"
        ;;
esac
SCRIPT

$SUDO chmod +x /usr/local/bin/v2ray-ubuntu
info "CLI launcher: v2ray-ubuntu {start|stop|restart|status|log|open}"

# ── 5. Desktop shortcut (only if GUI available) ───────────────────────────────
if [ "$HAS_DISPLAY" = true ] && [ "$DESKTOP_ENV" != "none" ]; then
    section "Creating desktop shortcut & application entry"

    DESKTOP_FILE="${HOME}/.local/share/applications/v2ray-ubuntu.desktop"
    mkdir -p "${HOME}/.local/share/applications"

    # The Exec opens the web panel; if JavaFX tray is running it will bring up window
    cat > "${DESKTOP_FILE}" <<DEOF
[Desktop Entry]
Version=1.0
Type=Application
Name=v2ray-ubuntu
GenericName=VPN & Proxy Manager
Comment=Xray-core based VPN and Proxy manager
Exec=bash -c 'systemctl --user is-active v2ray-ubuntu 2>/dev/null || sudo systemctl start v2ray-ubuntu; sleep 1; xdg-open http://localhost:${PORT}'
Icon=network-vpn
Terminal=false
StartupNotify=true
Categories=Network;Security;
Keywords=vpn;proxy;xray;v2ray;vless;vmess;
DEOF

    chmod +x "${DESKTOP_FILE}"

    # Also update desktop db if available
    if command -v update-desktop-database &>/dev/null; then
        update-desktop-database "${HOME}/.local/share/applications" 2>/dev/null || true
    fi

    info "Desktop shortcut created at ${DESKTOP_FILE}"
    info "Find it in your app launcher as 'v2ray-ubuntu'"
else
    section "Server mode — skipping desktop shortcut"
    info "Access the web panel at: http://<your-ip>:${PORT}"
fi

# ── Done ──────────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}${GREEN}Installation complete! (v${VERSION})${NC}"
echo ""
if [ "$HAS_DISPLAY" = true ]; then
    echo -e "  Open panel   : ${BOLD}http://localhost:${PORT}${NC}"
    echo -e "  Desktop env  : ${BOLD}${DESKTOP_ENV}${NC}"
fi
echo -e "  CLI commands : ${BOLD}v2ray-ubuntu {start|stop|restart|status|log|open}${NC}"
echo -e "  View logs    : ${BOLD}v2ray-ubuntu log${NC}"
echo ""

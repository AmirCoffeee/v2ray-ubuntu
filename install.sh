#!/usr/bin/env bash
set -e

# ─────────────────────────────────────────────────────────────────────────────
#  v2ray-ubuntu installer
#  Usage:  bash install.sh
#  One-line: bash <(curl -fsSL https://raw.githubusercontent.com/AmirCoffeee/AmirCoffeee-v2ray-ubuntu/main/install.sh)
# ─────────────────────────────────────────────────────────────────────────────

REPO="AmirCoffeee/v2ray-ubuntu"
VERSION="1.0.0"
JAR_URL="https://github.com/${REPO}/releases/download/v${VERSION}/v2ray-ubuntu-${VERSION}.jar"
INSTALL_DIR="/opt/v2ray-ubuntu"
JAR_PATH="${INSTALL_DIR}/v2ray-ubuntu.jar"
SERVICE_NAME="v2ray-ubuntu"
PORT=8080

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

# ── 1. Dependencies ───────────────────────────────────────────────────────────
section "Checking dependencies"

need_sudo

# Java 21+
if ! command -v java &>/dev/null || [ "$(java -version 2>&1 | awk -F'"' '/version/{print $2}' | cut -d'.' -f1)" -lt 21 ] 2>/dev/null; then
    warn "Java 21 not found — installing..."
    $SUDO apt-get update -qq
    $SUDO apt-get install -y openjdk-21-jre-headless
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

# ── 2. Install app ────────────────────────────────────────────────────────────
section "Installing v2ray-ubuntu v${VERSION}"

$SUDO mkdir -p "${INSTALL_DIR}"

if [ ! -f "${JAR_PATH}" ]; then
    info "Downloading JAR from GitHub..."
    $SUDO curl -fsSL "${JAR_URL}" -o "${JAR_PATH}"
    info "Downloaded to ${JAR_PATH}"
else
    warn "JAR already exists at ${JAR_PATH} — skipping download."
    warn "To re-download, run: sudo rm ${JAR_PATH} && bash install.sh"
fi

$SUDO chmod +x "${JAR_PATH}"

# ── 3. Systemd service ────────────────────────────────────────────────────────
section "Creating systemd service"

$SUDO tee /etc/systemd/system/${SERVICE_NAME}.service > /dev/null <<EOF
[Unit]
Description=v2ray-ubuntu — Xray-core web manager
After=network.target

[Service]
Type=simple
User=${USER}
ExecStart=/usr/bin/java -jar ${JAR_PATH}
Restart=on-failure
RestartSec=5
WorkingDirectory=${INSTALL_DIR}
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

$SUDO systemctl daemon-reload
$SUDO systemctl enable --now ${SERVICE_NAME}

if $SUDO systemctl is-active --quiet ${SERVICE_NAME}; then
    info "Service running (systemctl status ${SERVICE_NAME})"
else
    warn "Service may have failed to start. Check: journalctl -u ${SERVICE_NAME} -n 30"
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
    open)    xdg-open http://localhost:8080 ;;
    *)
        echo "Usage: v2ray-ubuntu {start|stop|restart|status|log|open}"
        ;;
esac
SCRIPT

$SUDO chmod +x /usr/local/bin/v2ray-ubuntu
info "CLI launcher created: v2ray-ubuntu {start|stop|restart|status|log|open}"

# ── 5. Desktop shortcut ───────────────────────────────────────────────────────
section "Creating desktop shortcut"

DESKTOP_FILE="${HOME}/.local/share/applications/v2ray-ubuntu.desktop"
mkdir -p "${HOME}/.local/share/applications"

cat > "${DESKTOP_FILE}" <<EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=v2ray-ubuntu
Comment=Xray-core web manager
Exec=bash -c 'xdg-open http://localhost:${PORT}'
Icon=network-vpn
Terminal=false
Categories=Network;
Keywords=vpn;proxy;xray;v2ray;vless;vmess;
EOF

chmod +x "${DESKTOP_FILE}"
info "Desktop shortcut created"

# ── Done ──────────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}${GREEN}Installation complete!${NC}"
echo ""
echo -e "  Open browser : ${BOLD}http://localhost:${PORT}${NC}"
echo -e "  CLI commands : ${BOLD}v2ray-ubuntu {start|stop|restart|status|log|open}${NC}"
echo -e "  Stop service : ${BOLD}sudo systemctl stop v2ray-ubuntu${NC}"
echo -e "  View logs    : ${BOLD}v2ray-ubuntu log${NC}"
echo ""

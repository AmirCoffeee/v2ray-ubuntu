# v2ray-ubuntu

![Version](https://img.shields.io/badge/version-1.0.2-blue)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-green)
![Xray-core](https://img.shields.io/badge/Xray--core-latest-blue)
![Platform](https://img.shields.io/badge/platform-Linux-lightgrey)
![License](https://img.shields.io/badge/license-MIT-brightgreen)

A lightweight, web-based GUI manager for [Xray-core](https://github.com/XTLS/Xray-core) on Linux.  
Paste a `vless://`, `vmess://`, or `ss://` link, hit Connect — done.

---

## Install (one line)

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/AmirCoffeee/v2ray-ubuntu/master/install.sh)
```

The script will:
1. Install **Java 21** if missing
2. Install **Xray-core** if missing
3. Download the latest JAR to `/opt/v2ray-ubuntu/`
4. Register a **systemd service** that starts on boot
5. Create a **`v2ray-ubuntu`** CLI command

Then open **http://localhost:12345** in your browser.

---

## CLI commands

```bash
v2ray-ubuntu start    # start the service
v2ray-ubuntu stop     # stop the service
v2ray-ubuntu restart  # restart the service
v2ray-ubuntu status   # show service status
v2ray-ubuntu log      # follow live logs
v2ray-ubuntu open     # open http://localhost:12345 in browser
```

---

## Features

- Paste any config link (`vless://`, `vmess://`, `ss://`, `socks://`) and connect in one click
- **Ctrl+V anywhere** on the page auto-detects and adds config links from clipboard
- **Proxy mode** — SOCKS5 on `127.0.0.1:10808`, system proxy set automatically on connect and restored on disconnect
- **VPN mode** — real `tun0` interface via `sudo`, all traffic routed through Xray
- HTTP ping per config (measures round-trip through proxy to google.com)
- Connect button doubles as Disconnect when active — no separate button needed
- Mode switch locked while connected — must disconnect first
- Install Xray-core from the UI without touching the terminal
- Config list persists for the entire session — never clears on reconnect or status poll
- Runs entirely as a local web app on port **12345** — no cloud, no accounts

---

## Requirements

- Ubuntu / Debian Linux (20.04+)
- `curl` and `unzip`
- GNOME desktop recommended (for automatic system proxy switching)

---

## Build from source

```bash
git clone https://github.com/AmirCoffeee/v2ray-ubuntu.git
cd v2ray-ubuntu
mvn clean package -DskipTests
java -jar target/v2ray-ubuntu-1.0.2.jar
```

---

## API Endpoints

| Method   | Path                            | Description                              |
|----------|---------------------------------|------------------------------------------|
| `GET`    | `/api/configs/status`           | Connection status + xray install state   |
| `POST`   | `/api/configs/add`              | Add a config from a link                 |
| `GET`    | `/api/configs/list`             | List all saved configs                   |
| `POST`   | `/api/configs/select/{id}`      | Connect in Proxy (SOCKS5) mode           |
| `POST`   | `/api/configs/select-vpn/{id}`  | Connect in VPN (TUN) mode with sudo      |
| `POST`   | `/api/configs/disconnect`       | Disconnect and restore system proxy      |
| `POST`   | `/api/configs/ping/{id}`        | HTTP ping through proxy to google.com    |
| `DELETE` | `/api/configs/remove/{id}`      | Remove a config                          |
| `POST`   | `/api/configs/install-xray`     | Download and install Xray-core           |
| `POST`   | `/api/proxy/stop`               | Stop connection (alias for disconnect)   |

---

## How it works

- Xray config files are written to `~/.xray-manager/`
- Previous system proxy settings are backed up to `~/.xray-manager/proxy-backup.properties` before connect, and restored exactly on disconnect — including custom proxies like `http://1.1.1.1:80`
- The systemd service runs as your user with the correct `DBUS_SESSION_BUS_ADDRESS` so `gsettings` works even when started at boot
- No desktop shortcut is created — access the panel at `http://localhost:12345`

---

## Changelog

### v1.0.2
- Fixed system proxy being set to remote server IP instead of `127.0.0.1`
- Fixed `gsettings` failing silently when service starts from systemd (no DBUS session)
- Fixed proxy backup lost on service restart — now saved to disk
- Removed `--headless` flag that caused crash on startup
- Removed desktop shortcut creation from installer
- Connect button now acts as Disconnect when active

### v1.0.1
- Config list no longer resets on status poll or reconnect
- Port changed from 8080 → 12345
- Version detection in installer — replaces old JAR automatically
- VPN mode uses real `tun0` interface via `sudo`

### v1.0.0
- Initial release

---

## Uninstall

```bash
# Stop and disable the service
sudo systemctl stop v2ray-ubuntu
sudo systemctl disable v2ray-ubuntu
sudo rm /etc/systemd/system/v2ray-ubuntu.service
sudo systemctl daemon-reload

# Remove app files
sudo rm -rf /opt/v2ray-ubuntu

# Remove CLI command
sudo rm -f /usr/local/bin/v2ray-ubuntu

# Remove config/cache files
rm -rf ~/.xray-manager
```

---

## License

MIT

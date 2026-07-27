# v2ray-ubuntu

A lightweight, web-based GUI manager for [Xray-core](https://github.com/XTLS/Xray-core) on Linux.  
Paste a `vless://`, `vmess://`, or `ss://` link, hit Connect — done.

## Features

- Paste any config link (`vless://`, `vmess://`, `ss://`, `socks://`) and connect in one click
- **Ctrl+V anywhere** on the page auto-detects and adds config links from clipboard
- Switch between **Proxy mode** (SOCKS5 on `127.0.0.1:10808`) and **System Proxy mode** (sets GNOME-wide proxy via `gsettings`)
- TCP ping per config to find the fastest server
- Install Xray-core from the UI without touching the terminal
- Runs entirely as a local web app — no cloud, no accounts

## Requirements

- Linux (Ubuntu / Debian recommended)
- Java 21+
- `curl` and `unzip` (for Xray auto-install)
- GNOME desktop (for System Proxy mode only)

## Quick Start

### 1 — Install Xray-core

```bash
bash -c "$(curl -fsSL https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install
```

This installs xray to `/usr/local/bin/xray`. Alternatively, use the **⬇ Install** button inside the UI after launching the app.

### 2 — Run the app

```bash
./start.sh
```

Or manually:

```bash
java -jar target/v2ray-ubuntu-1.0.0.jar
```

Then open **http://localhost:8080** in your browser.

### Build from source

```bash
# requires Maven 3.8+ and Java 21+
mvn clean package -DskipTests
java -jar target/v2ray-ubuntu-1.0.0.jar
```

## Usage

1. **Add a config** — paste a link into the input and press Enter (or Add), or just press **Ctrl+V** anywhere on the page
2. **Select a config** from the list and click **Connect**
3. Choose **Proxy** or **System Proxy** mode before connecting:
   - **Proxy** — starts a SOCKS5 proxy on `127.0.0.1:10808`. Configure your browser or app to use it
   - **System Proxy** — sets the GNOME system-wide proxy automatically (no per-app config needed)
4. Use **Disconnect** to stop the active connection, or **Stop All** to kill everything

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/api/proxy/status` | Connection status + xray install state |
| `POST` | `/api/proxy/stop` | Stop all connections |
| `POST` | `/api/proxy/install` | Download and install Xray-core |
| `POST` | `/api/proxy/vpn` | Enable / disable system proxy |
| `POST` | `/api/configs/add` | Add a config from a link |
| `GET`  | `/api/configs/list` | List all saved configs |
| `POST` | `/api/configs/select/{id}` | Activate a config and connect |
| `POST` | `/api/configs/disconnect` | Disconnect and clear active state |
| `POST` | `/api/configs/ping/{id}` | TCP ping a config |
| `DELETE` | `/api/configs/remove/{id}` | Remove a config |

## Config storage

Xray config files are written to `~/.xray-manager/` — no root access needed.

## License

MIT

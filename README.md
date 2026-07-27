# v2ray-ubuntu

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
1. Install **Java 21** (if missing)
2. Install **Xray-core** (if missing)
3. Download the latest JAR to `/opt/v2ray-ubuntu/`
4. Register a **systemd service** that starts on boot
5. Create a **`v2ray-ubuntu`** CLI command
6. Add a **desktop shortcut** in your app menu

Then open **http://localhost:8080** in your browser.

---

## CLI commands

```bash
v2ray-ubuntu start    # start the service
v2ray-ubuntu stop     # stop the service
v2ray-ubuntu restart  # restart the service
v2ray-ubuntu status   # show service status
v2ray-ubuntu log      # follow live logs
v2ray-ubuntu open     # open http://localhost:8080 in browser
```

---

## Features

- Paste any config link (`vless://`, `vmess://`, `ss://`, `socks://`) and connect in one click
- **Ctrl+V anywhere** on the page auto-detects and adds config links from clipboard
- Switch between **Proxy mode** (SOCKS5 on `127.0.0.1:10808`) and **System Proxy mode** (GNOME-wide via `gsettings`)
- TCP ping per config to find the fastest server
- Install Xray-core from the UI without touching the terminal
- Runs entirely as a local web app — no cloud, no accounts

---

## Requirements

- Ubuntu / Debian Linux
- `curl` and `unzip`
- GNOME desktop (for System Proxy mode only)

---

## Build from source

```bash
git clone https://github.com/AmirCoffeee/v2ray-ubuntu.git
cd v2ray-ubuntu
mvn clean package -DskipTests
java -jar target/v2ray-ubuntu-1.0.0.jar
```

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET`    | `/api/proxy/status`           | Connection status + xray install state |
| `POST`   | `/api/proxy/stop`             | Stop all connections |
| `POST`   | `/api/proxy/install`          | Download and install Xray-core |
| `POST`   | `/api/proxy/vpn`              | Enable / disable system proxy |
| `POST`   | `/api/configs/add`            | Add a config from a link |
| `GET`    | `/api/configs/list`           | List all saved configs |
| `POST`   | `/api/configs/select/{id}`    | Activate a config and connect |
| `POST`   | `/api/configs/disconnect`     | Disconnect and clear active state |
| `POST`   | `/api/configs/ping/{id}`      | TCP ping a config |
| `DELETE` | `/api/configs/remove/{id}`    | Remove a config |

---

## How it works

- Config files are written to `~/.xray-manager/` — no root access needed for the proxy
- The systemd service runs as your user
- System Proxy mode calls `gsettings` to set GNOME proxy (requires GNOME desktop)

---

## License

MIT

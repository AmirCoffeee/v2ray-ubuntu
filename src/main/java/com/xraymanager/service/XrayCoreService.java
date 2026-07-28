package com.xraymanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xraymanager.model.ConnectionStatus;
import com.xraymanager.model.ProxyConfig;
import com.xraymanager.model.VpnConfig;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Manages the Xray process.
 *
 * Two modes:
 *  PROXY — SOCKS5 inbound 127.0.0.1:10808 → outbound to remote server.
 *          System proxy (gsettings) is set to socks5://127.0.0.1:10808 on connect
 *          and restored to previous values on disconnect / shutdown.
 *
 *  VPN   — tun0 created via sudo, all traffic routed through it.
 *
 * Fixes vs previous version:
 *  - gsettings always runs with the user's DBUS session (detected at startup
 *    and again at runtime so it works from systemd).
 *  - Previous proxy settings are saved to disk so they survive service restarts.
 *  - setSystemProxySocks always uses 127.0.0.1, never the remote server address.
 */
@Service
public class XrayCoreService {

    private static final Logger LOG = Logger.getLogger(XrayCoreService.class.getName());

    private static final String SOCKS_LISTEN_HOST = "127.0.0.1";
    private static final int    SOCKS_LISTEN_PORT = 10808;

    private final ObjectMapper   objectMapper = new ObjectMapper();
    private final ConnectionStatus status     = new ConnectionStatus();
    private final AtomicBoolean proxySystemActive = new AtomicBoolean(false);

    private Process xrayProcess;
    private String  currentMode = "proxy";

    private final String CONFIG_DIR  = System.getProperty("user.home") + "/.xray-manager";
    private final String BACKUP_FILE = CONFIG_DIR + "/proxy-backup.properties";

    private static final List<String> XRAY_CANDIDATES = List.of(
        "/usr/local/bin/xray",
        "/usr/bin/xray",
        "/opt/xray/xray",
        System.getProperty("user.home") + "/.local/bin/xray",
        System.getProperty("user.home") + "/xray"
    );

    public XrayCoreService() {
        new File(CONFIG_DIR).mkdirs();
    }

    // ── Xray binary ───────────────────────────────────────────────────────────

    public String resolveXrayPath() {
        for (String p : XRAY_CANDIDATES) {
            if (new File(p).canExecute()) return p;
        }
        try {
            Process w = new ProcessBuilder("which", "xray").redirectErrorStream(true).start();
            String out = new String(w.getInputStream().readAllBytes()).trim();
            if (!out.isEmpty() && new File(out).canExecute()) return out;
        } catch (IOException ignored) {}
        return null;
    }

    public boolean isXrayInstalled() { return resolveXrayPath() != null; }

    public String getXrayStatus() {
        if (resolveXrayPath() == null) return "not_installed";
        if (xrayProcess != null && xrayProcess.isAlive()) return "running";
        return "installed";
    }

    // ── Start proxy (PROXY mode) ──────────────────────────────────────────────

    public boolean startProxy(ProxyConfig config) throws Exception {
        String xrayPath = resolveXrayPath();
        if (xrayPath == null) throw new Exception("Xray is not installed. Use the Install button.");

        // Always listen locally — never expose proxy on remote interface
        config.setLocalPort(SOCKS_LISTEN_PORT);
        config.setLocalAddress(SOCKS_LISTEN_HOST);

        currentMode = "proxy";

        String configId   = config.getProtocol() + "_" + config.getAddress() + "_" + config.getPort();
        String configPath = CONFIG_DIR + "/" + configId + ".json";

        FileUtils.writeStringToFile(
            new File(configPath), generateXrayConfig(config), StandardCharsets.UTF_8);

        stopXrayProcess();

        ProcessBuilder pb = new ProcessBuilder(xrayPath, "run", "-config", configPath);
        pb.redirectErrorStream(true);
        xrayProcess = pb.start();

        Thread.sleep(600);
        if (!xrayProcess.isAlive()) {
            String out = new String(xrayProcess.getInputStream().readAllBytes());
            throw new Exception("Xray failed to start:\n" + out);
        }

        status.setConnected(true);
        status.setProtocol(config.getProtocol());
        status.setServerAddress(config.getAddress() + ":" + config.getPort());

        // Save previous proxy THEN set ours — always 127.0.0.1:10808
        saveCurrentSystemProxy();
        setSystemProxySocks(SOCKS_LISTEN_HOST, SOCKS_LISTEN_PORT);

        return true;
    }

    // ── Stop proxy ────────────────────────────────────────────────────────────

    public boolean stopProxy() throws Exception {
        stopXrayProcess();
        if ("vpn".equals(currentMode)) teardownTun();
        if (proxySystemActive.get())   restoreSystemProxy();
        status.setConnected(false);
        status.setProtocol(null);
        status.setServerAddress(null);
        status.setActiveConnections(0);
        currentMode = "proxy";
        return true;
    }

    private void stopXrayProcess() throws Exception {
        if (xrayProcess != null) {
            if (xrayProcess.isAlive()) {
                xrayProcess.destroy();
                if (!xrayProcess.waitFor(3, TimeUnit.SECONDS)) {
                    xrayProcess.destroyForcibly();
                    xrayProcess.waitFor(2, TimeUnit.SECONDS);
                }
            }
            xrayProcess = null;
        }
    }

    // ── VPN / TUN mode ────────────────────────────────────────────────────────

    public boolean enableVpnTun(ProxyConfig config, String sudoPassword) throws Exception {
        String xrayPath = resolveXrayPath();
        if (xrayPath == null) throw new Exception("Xray is not installed.");

        currentMode = "vpn";

        String configPath = CONFIG_DIR + "/vpn_tun.json";
        FileUtils.writeStringToFile(
            new File(configPath), generateTunXrayConfig(config), StandardCharsets.UTF_8);

        runWithSudo(sudoPassword, "ip", "tuntap", "add",  "tun0",        "mode", "tun");
        runWithSudo(sudoPassword, "ip", "addr",   "add",  "10.0.0.1/24", "dev",  "tun0");
        runWithSudo(sudoPassword, "ip", "link",   "set",  "tun0",        "up");
        runWithSudo(sudoPassword, "ip", "route",  "add",  "0.0.0.0/1",   "dev",  "tun0");
        runWithSudo(sudoPassword, "ip", "route",  "add",  "128.0.0.0/1", "dev",  "tun0");

        stopXrayProcess();
        ProcessBuilder pb = new ProcessBuilder("sudo", "-S", xrayPath, "run", "-config", configPath);
        pb.redirectErrorStream(true);
        xrayProcess = pb.start();
        xrayProcess.getOutputStream().write((sudoPassword + "\n").getBytes(StandardCharsets.UTF_8));
        xrayProcess.getOutputStream().flush();

        Thread.sleep(800);
        if (!xrayProcess.isAlive()) {
            teardownTun(sudoPassword);
            String out = new String(xrayProcess.getInputStream().readAllBytes());
            throw new Exception("Xray VPN failed to start:\n" + out);
        }

        status.setConnected(true);
        status.setProtocol(config.getProtocol() + "+tun");
        status.setServerAddress(config.getAddress() + ":" + config.getPort());
        return true;
    }

    private void teardownTun() {
        for (String[] cmd : new String[][]{
            {"sudo","ip","route","del","0.0.0.0/1","dev","tun0"},
            {"sudo","ip","route","del","128.0.0.0/1","dev","tun0"},
            {"sudo","ip","link","set","tun0","down"},
            {"sudo","ip","tuntap","delete","tun0","mode","tun"}
        }) { try { runCmd(cmd); } catch (Exception ignored) {} }
    }

    private void teardownTun(String pw) {
        for (String[] cmd : new String[][]{
            {"ip","route","del","0.0.0.0/1","dev","tun0"},
            {"ip","route","del","128.0.0.0/1","dev","tun0"},
            {"ip","link","set","tun0","down"},
            {"ip","tuntap","delete","tun0","mode","tun"}
        }) { try { runWithSudo(pw, cmd); } catch (Exception ignored) {} }
    }

    // ── DBUS helper ───────────────────────────────────────────────────────────

    /**
     * Find the DBUS_SESSION_BUS_ADDRESS for the current user so gsettings works
     * even when called from a systemd service (which has no session bus by default).
     *
     * Strategy:
     *  1. If env var is already set (interactive session) → use it.
     *  2. Look for /run/user/<uid>/bus (systemd user bus, available on Ubuntu 20+).
     *  3. Scan /proc for a running gnome-shell / dbus-daemon process owned by this
     *     user and read its DBUS_SESSION_BUS_ADDRESS from /proc/<pid>/environ.
     */
    private String resolveDbus() {
        // 1. Already set in environment
        String env = System.getenv("DBUS_SESSION_BUS_ADDRESS");
        if (env != null && !env.isBlank()) return env;

        // 2. systemd user bus socket
        String uid = String.valueOf(ProcessHandle.current().pid()); // not uid, but try path
        try {
            // get real UID
            Process p = new ProcessBuilder("id", "-u").redirectErrorStream(true).start();
            uid = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        File userBus = new File("/run/user/" + uid + "/bus");
        if (userBus.exists()) return "unix:path=/run/user/" + uid + "/bus";

        // 3. Scan /proc for gnome-session / gnome-shell process owned by current user
        File proc = new File("/proc");
        File[] pids = proc.listFiles(f -> f.isDirectory() && f.getName().matches("\\d+"));
        if (pids != null) {
            String currentUser = System.getProperty("user.name");
            for (File pid : pids) {
                File envFile = new File(pid, "environ");
                if (!envFile.canRead()) continue;
                try {
                    // Only check processes owned by this user
                    if (!Files.getOwner(pid.toPath()).getName().equals(currentUser)) continue;
                    byte[] bytes = Files.readAllBytes(envFile.toPath());
                    String[] vars = new String(bytes, StandardCharsets.UTF_8).split("\0");
                    for (String var : vars) {
                        if (var.startsWith("DBUS_SESSION_BUS_ADDRESS=")) {
                            return var.substring("DBUS_SESSION_BUS_ADDRESS=".length());
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    // ── System proxy persistence ──────────────────────────────────────────────

    /**
     * Read current gsettings proxy and save to disk.
     * Disk-based backup survives service restarts.
     * Guard: if backup file already exists and proxy is already ours → skip.
     */
    private void saveCurrentSystemProxy() {
        // If backup file already exists, a previous session connected but didn't
        // disconnect cleanly. Don't overwrite the original backup — but DO re-apply
        // our proxy settings in case they were cleared by a reboot or logout.
        if (new File(BACKUP_FILE).exists()) {
            LOG.info("Proxy backup already on disk — re-applying proxy settings.");
            proxySystemActive.set(true);
            try {
                gsettingsSet("org.gnome.system.proxy",       "mode", "manual");
                gsettingsSet("org.gnome.system.proxy.socks", "host", SOCKS_LISTEN_HOST);
                gsettingsSet("org.gnome.system.proxy.socks", "port", String.valueOf(SOCKS_LISTEN_PORT));
                gsettingsSet("org.gnome.system.proxy.http",  "host", "");
                gsettingsSet("org.gnome.system.proxy.http",  "port", "0");
                gsettingsSet("org.gnome.system.proxy.https", "host", "");
                gsettingsSet("org.gnome.system.proxy.https", "port", "0");
            } catch (Exception e) {
                LOG.warning("Could not re-apply proxy: " + e.getMessage());
            }
            return;
        }
        try {
            String mode  = gsettingsGet("org.gnome.system.proxy",       "mode").replace("'","").trim();
            String sHost = gsettingsGet("org.gnome.system.proxy.socks", "host").replace("'","").trim();
            String sPort = gsettingsGet("org.gnome.system.proxy.socks", "port").trim();
            String hHost = gsettingsGet("org.gnome.system.proxy.http",  "host").replace("'","").trim();
            String hPort = gsettingsGet("org.gnome.system.proxy.http",  "port").trim();
            String hHost2= gsettingsGet("org.gnome.system.proxy.https", "host").replace("'","").trim();
            String hPort2= gsettingsGet("org.gnome.system.proxy.https", "port").trim();

            Properties props = new Properties();
            props.setProperty("mode",       mode.isEmpty()  ? "none" : mode);
            props.setProperty("socks.host", sHost);
            props.setProperty("socks.port", sPort.isEmpty() ? "0"    : sPort);
            props.setProperty("http.host",  hHost);
            props.setProperty("http.port",  hPort.isEmpty() ? "0"    : hPort);
            props.setProperty("https.host", hHost2);
            props.setProperty("https.port", hPort2.isEmpty()? "0"    : hPort2);

            try (FileOutputStream fos = new FileOutputStream(BACKUP_FILE)) {
                props.store(fos, "xray-manager proxy backup");
            }
            LOG.info("Proxy backup saved: mode=" + mode
                + " socks=" + sHost + ":" + sPort);
        } catch (Exception e) {
            LOG.warning("Could not save proxy backup: " + e.getMessage());
        }
    }

    /**
     * Set system SOCKS5 proxy to 127.0.0.1:10808.
     * Always uses the constant — never the remote server address.
     */
    private void setSystemProxySocks(String host, int port) {
        try {
            gsettingsSet("org.gnome.system.proxy",       "mode", "manual");
            gsettingsSet("org.gnome.system.proxy.socks", "host", host);
            gsettingsSet("org.gnome.system.proxy.socks", "port", String.valueOf(port));
            // Clear http/https so they don't interfere
            gsettingsSet("org.gnome.system.proxy.http",  "host", "");
            gsettingsSet("org.gnome.system.proxy.http",  "port", "0");
            gsettingsSet("org.gnome.system.proxy.https", "host", "");
            gsettingsSet("org.gnome.system.proxy.https", "port", "0");
            proxySystemActive.set(true);
            LOG.info("System proxy set → socks5://" + host + ":" + port);
        } catch (Exception e) {
            LOG.warning("Could not set system proxy: " + e.getMessage());
        }
    }

    /**
     * Restore the proxy settings from the on-disk backup.
     */
    public void restoreSystemProxy() {
        proxySystemActive.set(false);
        File backup = new File(BACKUP_FILE);
        if (!backup.exists()) {
            try { gsettingsSet("org.gnome.system.proxy", "mode", "none"); }
            catch (Exception ignored) {}
            return;
        }
        try {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(backup)) {
                props.load(fis);
            }
            String mode  = props.getProperty("mode",       "none");
            String sHost = props.getProperty("socks.host", "");
            String sPort = props.getProperty("socks.port", "0");
            String hHost = props.getProperty("http.host",  "");
            String hPort = props.getProperty("http.port",  "0");
            String h2Host= props.getProperty("https.host", "");
            String h2Port= props.getProperty("https.port", "0");

            gsettingsSet("org.gnome.system.proxy", "mode", mode);
            if ("manual".equals(mode)) {
                if (!sHost.isEmpty()) {
                    gsettingsSet("org.gnome.system.proxy.socks", "host", sHost);
                    gsettingsSet("org.gnome.system.proxy.socks", "port", sPort);
                }
                if (!hHost.isEmpty()) {
                    gsettingsSet("org.gnome.system.proxy.http", "host", hHost);
                    gsettingsSet("org.gnome.system.proxy.http", "port", hPort);
                }
                if (!h2Host.isEmpty()) {
                    gsettingsSet("org.gnome.system.proxy.https", "host", h2Host);
                    gsettingsSet("org.gnome.system.proxy.https", "port", h2Port);
                }
            }
            // Delete backup so next connect can save fresh values
            backup.delete();
            LOG.info("System proxy restored: mode=" + mode
                + " socks=" + sHost + ":" + sPort);
        } catch (Exception e) {
            LOG.warning("Could not restore proxy: " + e.getMessage());
        }
    }

    // ── gsettings wrappers ────────────────────────────────────────────────────

    private String gsettingsGet(String schema, String key) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("gsettings", "get", schema, key);
        pb.redirectErrorStream(true);
        String dbus = resolveDbus();
        if (dbus != null) pb.environment().put("DBUS_SESSION_BUS_ADDRESS", dbus);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor(3, TimeUnit.SECONDS);
        return out;
    }

    private void gsettingsSet(String schema, String key, String value) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("gsettings", "set", schema, key, value);
        pb.redirectErrorStream(true);
        String dbus = resolveDbus();
        if (dbus != null) pb.environment().put("DBUS_SESSION_BUS_ADDRESS", dbus);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() != 0)
            throw new Exception("gsettings set failed: " + out);
    }

    // ── Legacy API (VpnController) ────────────────────────────────────────────

    public boolean setupVpn(VpnConfig vpnConfig) throws Exception {
        if (Boolean.TRUE.equals(vpnConfig.getEnabled())) {
            saveCurrentSystemProxy();
            setSystemProxySocks(SOCKS_LISTEN_HOST, SOCKS_LISTEN_PORT);
        } else {
            restoreSystemProxy();
        }
        return true;
    }

    // ── Status ────────────────────────────────────────────────────────────────

    public ConnectionStatus getStatus() {
        boolean alive = xrayProcess != null && xrayProcess.isAlive();
        if (!alive && status.getConnected()) {
            status.setConnected(false);
            status.setProtocol(null);
            status.setServerAddress(null);
            status.setActiveConnections(0);
        }
        if (alive) status.setActiveConnections(1);
        return status;
    }

    public String getCurrentMode() { return currentMode; }

    // ── Install Xray ──────────────────────────────────────────────────────────

    public void installXray() throws Exception {
        if (isXrayInstalled()) return;
        String dir = System.getProperty("user.home") + "/.local/bin";
        new File(dir).mkdirs();
        runCmd("bash", "-c",
            "curl -fsSL https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-64.zip"
            + " -o /tmp/xray.zip && unzip -o /tmp/xray.zip xray -d " + dir
            + " && chmod +x " + dir + "/xray");
        if (resolveXrayPath() == null)
            throw new Exception("Install succeeded but xray not found. Add " + dir + " to PATH.");
    }

    // ── Ping ──────────────────────────────────────────────────────────────────

    public Long httpPing(String targetUrl, int timeoutMs, boolean useProxy) throws Exception {
        java.net.Proxy proxy = useProxy
            ? new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                new java.net.InetSocketAddress(SOCKS_LISTEN_HOST, SOCKS_LISTEN_PORT))
            : java.net.Proxy.NO_PROXY;
        @SuppressWarnings("deprecation")
        java.net.URL url = new java.net.URL(targetUrl);
        java.net.HttpURLConnection conn =
            (java.net.HttpURLConnection) url.openConnection(proxy);
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestMethod("HEAD");
        conn.setRequestProperty("User-Agent", "v2ray-ubuntu/1.0");
        long start = System.currentTimeMillis();
        try { conn.connect(); conn.getResponseCode(); }
        finally { conn.disconnect(); }
        return System.currentTimeMillis() - start;
    }

    public Map<String, Object> pingWithComparison(String target, int timeoutMs) {
        Map<String, Object> result = new HashMap<>();
        long direct = -1, proxied = -1;
        StringBuilder errors = new StringBuilder();
        try { direct = httpPing(target, timeoutMs, false); }
        catch (Exception e) { errors.append("Direct: ").append(e.getMessage()); }
        if (xrayProcess != null && xrayProcess.isAlive()) {
            try { proxied = httpPing(target, timeoutMs, true); }
            catch (Exception e) {
                if (errors.length() > 0) errors.append(" | ");
                errors.append("Proxy: ").append(e.getMessage());
            }
        } else {
            if (errors.length() > 0) errors.append(" | ");
            errors.append("Proxy not active");
        }
        result.put("withoutProxy", direct);
        result.put("withProxy",    proxied);
        result.put("error",        errors.length() > 0 ? errors.toString() : null);
        return result;
    }

    // ── Xray config generation ────────────────────────────────────────────────

    private String generateXrayConfig(ProxyConfig config) throws Exception {
        Map<String, Object> root = new HashMap<>();
        root.put("log", Map.of("loglevel", "warning"));
        root.put("inbounds", List.of(Map.of(
            "port", SOCKS_LISTEN_PORT,
            "listen", SOCKS_LISTEN_HOST,
            "protocol", "socks",
            "settings", Map.of("auth", "noauth", "udp", true)
        )));
        root.put("outbounds", List.of(buildOutbound(config),
            Map.of("protocol", "freedom", "tag", "direct")));
        root.put("routing", Map.of(
            "domainStrategy", "AsIs",
            "rules", List.of(Map.of("type","field","outboundTag","direct",
                "domain", List.of("geosite:cn")))
        ));
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private String generateTunXrayConfig(ProxyConfig config) throws Exception {
        Map<String, Object> root = new HashMap<>();
        root.put("log", Map.of("loglevel", "warning"));
        root.put("inbounds", List.of(Map.of(
            "protocol", "dokodemo-door",
            "port", SOCKS_LISTEN_PORT,
            "listen", "0.0.0.0",
            "settings", Map.of("network", "tcp,udp", "followRedirect", true),
            "streamSettings", Map.of("sockopt", Map.of("tproxy", "tproxy"))
        )));
        root.put("outbounds", List.of(buildOutbound(config),
            Map.of("protocol", "freedom", "tag", "direct")));
        root.put("routing", Map.of("domainStrategy", "IPIfNonMatch", "rules", List.of()));
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private Map<String, Object> buildOutbound(ProxyConfig config) {
        Map<String, Object> outbound = new HashMap<>();
        outbound.put("protocol", config.getProtocol());
        outbound.put("tag", "proxy");
        String proto = config.getProtocol().toLowerCase();
        if (proto.equals("vless") || proto.equals("vmess")) {
            Map<String, Object> user = new HashMap<>();
            user.put("id", config.getUserId() != null ? config.getUserId() : "");
            if (proto.equals("vless")) {
                user.put("encryption", config.getEncryption() != null ? config.getEncryption() : "none");
                if (config.getFlow() != null && !config.getFlow().isEmpty())
                    user.put("flow", config.getFlow());
            } else {
                user.put("security", config.getSecurity() != null ? config.getSecurity() : "auto");
                user.put("alterId", 0);
            }
            outbound.put("settings", Map.of("vnext", List.of(Map.of(
                "address", config.getAddress(),
                "port",    config.getPort(),
                "users",   List.of(user)
            ))));
        } else if (proto.equals("shadowsocks") || proto.equals("ss")) {
            outbound.put("settings", Map.of("servers", List.of(Map.of(
                "address",  config.getAddress(),
                "port",     config.getPort(),
                "method",   config.getEncryption() != null ? config.getEncryption() : "chacha20-ietf-poly1305",
                "password", config.getUserId() != null ? config.getUserId() : ""
            ))));
        } else {
            outbound.put("settings", Map.of("servers", List.of(Map.of(
                "address", config.getAddress(),
                "port",    config.getPort()
            ))));
        }
        if (config.getNetwork() != null && !config.getNetwork().isEmpty()) {
            Map<String, Object> stream = new HashMap<>();
            stream.put("network", config.getNetwork());
            String sec = config.getSecurity();
            if ("tls".equals(sec) || "reality".equals(sec)) stream.put("security", sec);
            if ("ws".equals(config.getNetwork())) {
                Map<String, Object> ws = new HashMap<>();
                if (config.getPath() != null) ws.put("path", config.getPath());
                if (config.getHost() != null) ws.put("headers", Map.of("Host", config.getHost()));
                stream.put("wsSettings", ws);
            } else if ("grpc".equals(config.getNetwork())) {
                Map<String, Object> grpc = new HashMap<>();
                if (config.getServiceName() != null) grpc.put("serviceName", config.getServiceName());
                stream.put("grpcSettings", grpc);
            }
            outbound.put("streamSettings", stream);
        }
        return outbound;
    }

    // ── Shell helpers ─────────────────────────────────────────────────────────

    private void runCmd(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() != 0)
            throw new Exception("Command failed: " + out.trim());
    }

    private void runWithSudo(String password, String... cmd) throws Exception {
        String[] full = new String[cmd.length + 2];
        full[0] = "sudo"; full[1] = "-S";
        System.arraycopy(cmd, 0, full, 2, cmd.length);
        ProcessBuilder pb = new ProcessBuilder(full);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().write((password + "\n").getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().flush();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0)
            throw new Exception("sudo failed: " + out.trim());
    }
}

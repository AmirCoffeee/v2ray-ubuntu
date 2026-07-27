package com.xraymanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xraymanager.model.ConnectionStatus;
import com.xraymanager.model.ProxyConfig;
import com.xraymanager.model.VpnConfig;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Manages the Xray process.
 *
 * Two modes:
 *  - PROXY  : Xray runs as SOCKS5 inbound (port 10808) → outbound to the remote server.
 *             System proxy (gsettings / KDE) is set to socks5://127.0.0.1:10808 on connect
 *             and restored to whatever it was before on disconnect / shutdown.
 *
 *  - VPN    : A real TUN interface (tun0) is created with sudo. All traffic is routed
 *             through it. Requires the user to supply their sudo password via the UI.
 */
@Service
public class XrayCoreService {

    private static final Logger LOG = Logger.getLogger(XrayCoreService.class.getName());

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConnectionStatus status   = new ConnectionStatus();
    private final AtomicBoolean proxySystemActive = new AtomicBoolean(false);

    private Process xrayProcess;
    private String  currentMode = "proxy"; // "proxy" | "vpn"

    private final String CONFIG_DIR = System.getProperty("user.home") + "/.xray-manager";

    // ── Saved previous system-proxy so we can restore it ─────────────────────
    private String savedProxyMode    = null;  // "none" / "manual" / "auto"
    private String savedSocksHost    = null;
    private int    savedSocksPort    = 0;
    private String savedHttpHost     = null;
    private int    savedHttpPort     = 0;
    private boolean savedProxyCaptured = false;

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

    // ── Xray binary resolution ────────────────────────────────────────────────

    public String resolveXrayPath() {
        for (String p : XRAY_CANDIDATES) {
            if (new File(p).canExecute()) return p;
        }
        try {
            Process which = new ProcessBuilder("which", "xray")
                .redirectErrorStream(true).start();
            String out = new String(which.getInputStream().readAllBytes()).trim();
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

    /**
     * Start Xray: SOCKS5 inbound on 127.0.0.1:10808, outbound to remote server.
     * Also sets the system proxy automatically.
     */
    public boolean startProxy(ProxyConfig config) throws Exception {
        String xrayPath = resolveXrayPath();
        if (xrayPath == null) throw new Exception("Xray is not installed. Use the Install button.");

        if (config.getLocalPort()    == null) config.setLocalPort(10808);
        if (config.getLocalAddress() == null) config.setLocalAddress("127.0.0.1");

        currentMode = "proxy";

        String configId   = config.getProtocol() + "_" + config.getAddress() + "_" + config.getPort();
        String configPath = CONFIG_DIR + "/" + configId + ".json";

        FileUtils.writeStringToFile(
            new File(configPath),
            generateXrayConfig(config),
            StandardCharsets.UTF_8
        );

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

        // Set system proxy → socks5://127.0.0.1:10808
        saveCurrentSystemProxy();
        setSystemProxySocks(config.getLocalAddress(), config.getLocalPort());

        return true;
    }

    // ── Stop proxy ────────────────────────────────────────────────────────────

    public boolean stopProxy() throws Exception {
        stopXrayProcess();

        if ("vpn".equals(currentMode)) {
            teardownTun();
        }

        if (proxySystemActive.get()) {
            restoreSystemProxy();
        }

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

    // ── VPN mode (TUN) ────────────────────────────────────────────────────────

    /**
     * Enable VPN mode: create tun0, route all traffic through it via xray tun.
     * sudoPassword is passed to sudo via stdin (askpass-style: we write it to
     * a temp script to avoid it appearing in process args).
     */
    public boolean enableVpnTun(ProxyConfig config, String sudoPassword) throws Exception {
        String xrayPath = resolveXrayPath();
        if (xrayPath == null) throw new Exception("Xray is not installed.");

        currentMode = "vpn";

        // Build xray config with tun inbound
        String configPath = CONFIG_DIR + "/vpn_tun.json";
        FileUtils.writeStringToFile(
            new File(configPath),
            generateTunXrayConfig(config),
            StandardCharsets.UTF_8
        );

        // Create tun0 interface with sudo
        runWithSudo(sudoPassword, "ip", "tuntap", "add", "tun0", "mode", "tun");
        runWithSudo(sudoPassword, "ip", "addr",   "add", "10.0.0.1/24", "dev", "tun0");
        runWithSudo(sudoPassword, "ip", "link",   "set", "tun0", "up");

        // Default route through tun0 (split into two /1 routes to not break the
        // route to the VPN server itself)
        runWithSudo(sudoPassword, "ip", "route",  "add", "0.0.0.0/1",   "dev", "tun0");
        runWithSudo(sudoPassword, "ip", "route",  "add", "128.0.0.0/1", "dev", "tun0");

        // Start xray with tun config
        stopXrayProcess();
        ProcessBuilder pb = new ProcessBuilder(xrayPath, "run", "-config", configPath);
        pb.redirectErrorStream(true);
        // xray needs CAP_NET_ADMIN for TUN; run via sudo
        pb = new ProcessBuilder("sudo", "-S", xrayPath, "run", "-config", configPath);
        pb.redirectErrorStream(true);
        xrayProcess = pb.start();
        // feed password to sudo stdin
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
        try {
            runCmd("sudo", "ip", "route", "del", "0.0.0.0/1",   "dev", "tun0");
        } catch (Exception ignored) {}
        try {
            runCmd("sudo", "ip", "route", "del", "128.0.0.0/1", "dev", "tun0");
        } catch (Exception ignored) {}
        try {
            runCmd("sudo", "ip", "link", "set", "tun0", "down");
        } catch (Exception ignored) {}
        try {
            runCmd("sudo", "ip", "tuntap", "delete", "tun0", "mode", "tun");
        } catch (Exception ignored) {}
    }

    private void teardownTun(String sudoPassword) {
        try { runWithSudo(sudoPassword, "ip", "route", "del", "0.0.0.0/1",   "dev", "tun0"); } catch (Exception ignored) {}
        try { runWithSudo(sudoPassword, "ip", "route", "del", "128.0.0.0/1", "dev", "tun0"); } catch (Exception ignored) {}
        try { runWithSudo(sudoPassword, "ip", "link",  "set", "tun0", "down"); }             catch (Exception ignored) {}
        try { runWithSudo(sudoPassword, "ip", "tuntap","delete", "tun0", "mode", "tun"); }   catch (Exception ignored) {}
    }

    // ── System proxy persistence ──────────────────────────────────────────────

    /**
     * Read and remember current system proxy settings (GNOME gsettings or KDE).
     * Called once before we overwrite them.
     */
    private void saveCurrentSystemProxy() {
        if (savedProxyCaptured) return; // already saved — don't overwrite with our own values
        try {
            savedProxyMode = gsettingsGet("org.gnome.system.proxy", "mode").trim().replace("'", "");
            savedSocksHost = gsettingsGet("org.gnome.system.proxy.socks", "host").trim().replace("'", "");
            String sp      = gsettingsGet("org.gnome.system.proxy.socks", "port").trim();
            savedSocksPort = sp.isEmpty() ? 0 : Integer.parseInt(sp);
            savedHttpHost  = gsettingsGet("org.gnome.system.proxy.http", "host").trim().replace("'", "");
            String hp      = gsettingsGet("org.gnome.system.proxy.http", "port").trim();
            savedHttpPort  = hp.isEmpty() ? 0 : Integer.parseInt(hp);
            savedProxyCaptured = true;
            LOG.info("Saved system proxy: mode=" + savedProxyMode
                + " socks=" + savedSocksHost + ":" + savedSocksPort
                + " http=" + savedHttpHost + ":" + savedHttpPort);
        } catch (Exception e) {
            LOG.warning("Could not read current system proxy: " + e.getMessage());
        }
    }

    private void setSystemProxySocks(String host, int port) {
        try {
            gsettingsSet("org.gnome.system.proxy",       "mode",  "manual");
            gsettingsSet("org.gnome.system.proxy.socks", "host",  host);
            gsettingsSet("org.gnome.system.proxy.socks", "port",  String.valueOf(port));
            proxySystemActive.set(true);
            LOG.info("System proxy set to socks5://" + host + ":" + port);
        } catch (Exception e) {
            LOG.warning("Could not set system proxy: " + e.getMessage());
        }
    }

    /**
     * Restore system proxy to whatever it was before we changed it.
     * Handles the case where it was http://1.1.1.1:80 or anything else.
     */
    public void restoreSystemProxy() {
        if (!savedProxyCaptured) {
            // Nothing was saved — just disable proxy
            try { gsettingsSet("org.gnome.system.proxy", "mode", "none"); } catch (Exception ignored) {}
            proxySystemActive.set(false);
            return;
        }
        try {
            String mode = (savedProxyMode == null || savedProxyMode.isEmpty()) ? "none" : savedProxyMode;
            gsettingsSet("org.gnome.system.proxy", "mode", mode);

            if ("manual".equals(mode)) {
                if (savedSocksHost != null && !savedSocksHost.isEmpty()) {
                    gsettingsSet("org.gnome.system.proxy.socks", "host", savedSocksHost);
                    gsettingsSet("org.gnome.system.proxy.socks", "port", String.valueOf(savedSocksPort));
                }
                if (savedHttpHost != null && !savedHttpHost.isEmpty()) {
                    gsettingsSet("org.gnome.system.proxy.http", "host", savedHttpHost);
                    gsettingsSet("org.gnome.system.proxy.http", "port", String.valueOf(savedHttpPort));
                }
            }

            proxySystemActive.set(false);
            savedProxyCaptured = false; // allow re-capture next time
            LOG.info("System proxy restored to: mode=" + mode);
        } catch (Exception e) {
            LOG.warning("Could not restore system proxy: " + e.getMessage());
        }
    }

    private String gsettingsGet(String schema, String key) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("gsettings", "get", schema, key);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor(3, TimeUnit.SECONDS);
        return out;
    }

    private void gsettingsSet(String schema, String key, String value) throws Exception {
        runCmd("gsettings", "set", schema, key, value);
    }

    // ── setupVpn (legacy API kept for VpnController) ─────────────────────────

    public boolean setupVpn(VpnConfig vpnConfig) throws Exception {
        if (Boolean.TRUE.equals(vpnConfig.getEnabled())) {
            setSystemProxySocks("127.0.0.1", 10808);
            return true;
        } else {
            restoreSystemProxy();
            return true;
        }
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

    // ── Ping helpers ──────────────────────────────────────────────────────────

    public Long httpPing(String targetUrl, int timeoutMs, boolean useProxy) throws Exception {
        java.net.Proxy proxy = useProxy
            ? new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                new java.net.InetSocketAddress("127.0.0.1", 10808))
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
        try {
            conn.connect();
            conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
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

    /** PROXY mode: SOCKS5 inbound → remote outbound */
    private String generateXrayConfig(ProxyConfig config) throws Exception {
        Map<String, Object> root = new HashMap<>();
        root.put("log", Map.of("loglevel", "warning"));
        root.put("inbounds", List.of(Map.of(
            "port",     config.getLocalPort(),
            "listen",   config.getLocalAddress(),
            "protocol", "socks",
            "settings", Map.of("auth", "noauth", "udp", true)
        )));
        root.put("outbounds", List.of(buildOutbound(config),
            Map.of("protocol", "freedom", "tag", "direct")));
        root.put("routing", Map.of(
            "domainStrategy", "AsIs",
            "rules", List.of(Map.of("type","field","outboundTag","direct","domain",
                List.of("geosite:cn")))
        ));
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    /** VPN/TUN mode: tun inbound → remote outbound */
    private String generateTunXrayConfig(ProxyConfig config) throws Exception {
        Map<String, Object> root = new HashMap<>();
        root.put("log", Map.of("loglevel", "warning"));
        root.put("inbounds", List.of(Map.of(
            "protocol", "dokodemo-door",
            "port",     10808,
            "listen",   "0.0.0.0",
            "settings", Map.of("network", "tcp,udp", "followRedirect", true),
            "streamSettings", Map.of("sockopt", Map.of("tproxy", "tproxy"))
        )));
        root.put("outbounds", List.of(buildOutbound(config),
            Map.of("protocol", "freedom", "tag", "direct")));
        root.put("routing", Map.of("domainStrategy", "IPIfNonMatch",
            "rules", List.of()));
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
        if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() != 0)
            throw new Exception("Command failed: " + out.trim());
    }

    /**
     * Run a command with sudo, feeding password via stdin.
     * Uses "sudo -S" so the password goes on stdin, never in the process args.
     */
    private void runWithSudo(String password, String... cmd) throws Exception {
        String[] full = new String[cmd.length + 2];
        full[0] = "sudo";
        full[1] = "-S";
        System.arraycopy(cmd, 0, full, 2, cmd.length);
        ProcessBuilder pb = new ProcessBuilder(full);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().write((password + "\n").getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().flush();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0)
            throw new Exception("sudo command failed: " + out.trim());
    }

    public String getCurrentMode() { return currentMode; }
}

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class XrayCoreService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, ProxyConfig> activeProxies = new ConcurrentHashMap<>();
    private final AtomicBoolean systemProxyActive = new AtomicBoolean(false);
    private final ConnectionStatus status = new ConnectionStatus();
    private Process xrayProcess;

    private final String CONFIG_DIR = System.getProperty("user.home") + "/.xray-manager";

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

    public String resolveXrayPath() {
        for (String p : XRAY_CANDIDATES) {
            if (new File(p).canExecute()) return p;
        }
        try {
            Process which = new ProcessBuilder("which", "xray").redirectErrorStream(true).start();
            String out = new String(which.getInputStream().readAllBytes()).trim();
            if (!out.isEmpty() && new File(out).canExecute()) return out;
        } catch (IOException ignored) {}
        return null;
    }

    public boolean isXrayInstalled() {
        return resolveXrayPath() != null;
    }

    public boolean startProxy(ProxyConfig config) throws Exception {
        String xrayPath = resolveXrayPath();
        if (xrayPath == null) {
            throw new Exception(
                "Xray is not installed.\n" +
                "Run: bash -c \"$(curl -fsSL https://github.com/XTLS/Xray-install/raw/main/install-release.sh)\" @ install\n" +
                "Or use the Install button in the UI."
            );
        }

        if (config.getLocalPort() == null)    config.setLocalPort(10808);
        if (config.getLocalAddress() == null) config.setLocalAddress("127.0.0.1");

        String configId   = config.getProtocol() + "_" + config.getAddress() + "_" + config.getPort();
        String configPath = CONFIG_DIR + "/" + configId + ".json";

        FileUtils.writeStringToFile(new File(configPath), generateXrayConfig(config), StandardCharsets.UTF_8);

        if (xrayProcess != null && xrayProcess.isAlive()) {
            xrayProcess.destroy();
            xrayProcess.waitFor(3, TimeUnit.SECONDS);
        }

        ProcessBuilder pb = new ProcessBuilder(xrayPath, "run", "-config", configPath);
        pb.redirectErrorStream(true);
        xrayProcess = pb.start();

        Thread.sleep(500);
        if (!xrayProcess.isAlive()) {
            String output = new String(xrayProcess.getInputStream().readAllBytes());
            throw new Exception("Xray failed to start:\n" + output);
        }

        activeProxies.put(configId, config);
        status.setConnected(true);
        status.setProtocol(config.getProtocol());
        status.setServerAddress(config.getAddress() + ":" + config.getPort());
        return true;
    }

    public boolean stopProxy() throws Exception {
        if (xrayProcess != null) {
            if (xrayProcess.isAlive()) {
                xrayProcess.destroy();
                if (!xrayProcess.waitFor(2, TimeUnit.SECONDS)) {
                    xrayProcess.destroyForcibly();
                    xrayProcess.waitFor(2, TimeUnit.SECONDS);
                }
            }
            xrayProcess = null;
        }
        activeProxies.clear();
        status.setConnected(false);
        status.setProtocol(null);
        status.setServerAddress(null);
        status.setActiveConnections(0);
        if (systemProxyActive.get()) {
            try { disableSystemProxy(); } catch (Exception ignored) {}
        }
        return true;
    }

    public boolean setupVpn(VpnConfig vpnConfig) throws Exception {
        if (Boolean.TRUE.equals(vpnConfig.getEnabled())) {
            return enableSystemProxy();
        } else {
            return disableSystemProxy();
        }
    }

    private boolean enableSystemProxy() throws Exception {
        try {
            runCmd("gsettings", "set", "org.gnome.system.proxy", "mode", "manual");
            runCmd("gsettings", "set", "org.gnome.system.proxy.socks", "host", "127.0.0.1");
            runCmd("gsettings", "set", "org.gnome.system.proxy.socks", "port", "10808");
            systemProxyActive.set(true);
            return true;
        } catch (Exception e) {
            throw new Exception(
                "Could not set system proxy via gsettings.\n" +
                "For full TUN-based VPN, run with root and use:\n" +
                "  sudo ip tuntap add tun0 mode tun\n" +
                "  sudo ip addr add 10.0.0.2/24 dev tun0\n" +
                "  sudo ip link set tun0 up\n" +
                "  sudo ip route add 0.0.0.0/1 dev tun0\n" +
                "Error: " + e.getMessage()
            );
        }
    }

    private boolean disableSystemProxy() throws Exception {
        try { runCmd("gsettings", "set", "org.gnome.system.proxy", "mode", "none"); }
        catch (Exception ignored) {}
        systemProxyActive.set(false);
        return true;
    }

    public ConnectionStatus getStatus() {
        boolean alive = xrayProcess != null && xrayProcess.isAlive();
        if (!alive) {
            if (status.getConnected()) {
                status.setConnected(false);
                status.setProtocol(null);
                status.setServerAddress(null);
            }
            status.setActiveConnections(0);
        } else {
            status.setActiveConnections(activeProxies.size());
        }
        return status;
    }

    public String getXrayStatus() {
        if (resolveXrayPath() == null) return "not_installed";
        if (xrayProcess != null && xrayProcess.isAlive()) return "running";
        return "installed";
    }

    public void installXray() throws Exception {
        if (isXrayInstalled()) return;
        String installDir = System.getProperty("user.home") + "/.local/bin";
        new File(installDir).mkdirs();
        runCmdArray(new String[]{
            "bash", "-c",
            "curl -fsSL https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-64.zip" +
            " -o /tmp/xray.zip && unzip -o /tmp/xray.zip xray -d " + installDir +
            " && chmod +x " + installDir + "/xray"
        });
        if (resolveXrayPath() == null) {
            throw new Exception("Installation succeeded but xray not found. Add " + installDir + " to your PATH.");
        }
    }

    public Long httpPing(String targetUrl, int timeoutMs, boolean useProxy) throws Exception {
        java.net.Proxy proxy = useProxy
            ? new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                new java.net.InetSocketAddress("127.0.0.1", 10808))
            : java.net.Proxy.NO_PROXY;

        @SuppressWarnings("deprecation")
        java.net.URL url = new java.net.URL(targetUrl);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection(proxy);
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
        try {
            direct = httpPing(target, timeoutMs, false);
        } catch (Exception e) {
            errors.append("Direct: ").append(e.getMessage());
        }
        if (xrayProcess != null && xrayProcess.isAlive()) {
            try {
                proxied = httpPing(target, timeoutMs, true);
            } catch (Exception e) {
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

    private String generateXrayConfig(ProxyConfig config) throws Exception {
        Map<String, Object> root = new HashMap<>();
        root.put("log", Map.of("loglevel", "warning"));
        root.put("inbounds", List.of(Map.of(
            "port",     config.getLocalPort(),
            "listen",   config.getLocalAddress(),
            "protocol", "socks",
            "settings", Map.of("auth", "noauth", "udp", true)
        )));

        Map<String, Object> outbound = new HashMap<>();
        outbound.put("protocol", config.getProtocol());
        outbound.put("tag", "proxy");

        String proto = config.getProtocol().toLowerCase();
        if (proto.equals("vless") || proto.equals("vmess")) {
            Map<String, Object> user = new HashMap<>();
            user.put("id", config.getUserId() != null ? config.getUserId() : "");
            if (proto.equals("vless")) {
                user.put("encryption", config.getEncryption() != null ? config.getEncryption() : "none");
                if (config.getFlow() != null && !config.getFlow().isEmpty()) user.put("flow", config.getFlow());
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
            if ("tls".equals(config.getSecurity()) || "reality".equals(config.getSecurity())) {
                stream.put("security", config.getSecurity());
            }
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

        root.put("outbounds", List.of(outbound, Map.of("protocol", "freedom", "tag", "direct")));
        root.put("routing", Map.of(
            "domainStrategy", "AsIs",
            "rules", List.of(Map.of("type", "field", "outboundTag", "direct", "domain", List.of("geosite:cn")))
        ));
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private void runCmd(String... command) throws Exception {
        runCmdArray(command);
    }

    private void runCmdArray(String[] command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new Exception("Command failed: " + output.trim());
        }
    }
}

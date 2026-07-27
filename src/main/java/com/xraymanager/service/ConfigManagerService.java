package com.xraymanager.service;

import com.xraymanager.model.ConfigEntry;
import com.xraymanager.model.ProxyConfig;
import com.xraymanager.utils.ConfigParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the in-memory config store.
 *
 * Configs are NEVER cleared automatically — they survive reconnects,
 * disconnects, mode switches, and status polls. Only explicit user
 * delete calls remove a config.
 */
@Service
public class ConfigManagerService {

    // Persisted for the lifetime of the JVM — never reset
    private final Map<String, ConfigEntry> configStore = new ConcurrentHashMap<>();
    private volatile String activeConfigId = null;

    @Autowired private ConfigParser      configParser;
    @Autowired private XrayCoreService   xrayCoreService;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public ConfigEntry addConfigFromLink(String link) {
        ConfigEntry entry = configParser.parseLink(link);
        if (entry == null) return null;
        configStore.put(entry.getId(), entry);
        return entry;
    }

    public boolean removeConfig(String id) {
        if (!configStore.containsKey(id)) return false;
        if (id.equals(activeConfigId)) {
            try { xrayCoreService.stopProxy(); } catch (Exception ignored) {}
            activeConfigId = null;
        }
        configStore.remove(id);
        return true;
    }

    /** Returns all configs — list is never null, never stale from a reset. */
    public List<ConfigEntry> getAllConfigs() {
        return new ArrayList<>(configStore.values());
    }

    public ConfigEntry getConfig(String id) {
        return configStore.get(id);
    }

    // ── Connect / Disconnect ──────────────────────────────────────────────────

    /**
     * Activate a config in PROXY mode (SOCKS5).
     * The active flag on all other configs is cleared, but they remain in the store.
     */
    public boolean setActiveConfig(String id) throws Exception {
        ConfigEntry entry = configStore.get(id);
        if (entry == null) return false;
        if (id.equals(activeConfigId)) return true;

        // Stop current connection without clearing the store
        xrayCoreService.stopProxy();

        boolean started = xrayCoreService.startProxy(entryToProxyConfig(entry));
        if (started) {
            // Clear active flag on previous entry
            if (activeConfigId != null) {
                ConfigEntry prev = configStore.get(activeConfigId);
                if (prev != null) prev.setActive(false);
            }
            activeConfigId = id;
            entry.setActive(true);
        }
        return started;
    }

    /**
     * Activate a config in VPN/TUN mode.
     * sudoPassword is collected via the UI and passed straight through to XrayCoreService.
     */
    public boolean setActiveConfigVpn(String id, String sudoPassword) throws Exception {
        ConfigEntry entry = configStore.get(id);
        if (entry == null) return false;

        xrayCoreService.stopProxy();

        boolean started = xrayCoreService.enableVpnTun(entryToProxyConfig(entry), sudoPassword);
        if (started) {
            if (activeConfigId != null) {
                ConfigEntry prev = configStore.get(activeConfigId);
                if (prev != null) prev.setActive(false);
            }
            activeConfigId = id;
            entry.setActive(true);
        }
        return started;
    }

    /**
     * Disconnect: stop xray + restore system proxy.
     * Config store is intentionally left untouched.
     */
    public boolean disconnect() throws Exception {
        if (activeConfigId != null) {
            ConfigEntry prev = configStore.get(activeConfigId);
            if (prev != null) prev.setActive(false);
            activeConfigId = null;
        }
        xrayCoreService.stopProxy();
        return true;
    }

    // ── Ping ──────────────────────────────────────────────────────────────────

    public Long pingConfig(String id) {
        ConfigEntry entry = configStore.get(id);
        if (entry == null) return -1L;
        long start = System.currentTimeMillis();
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(
                new java.net.InetSocketAddress(entry.getAddress(), entry.getPort()), 3000);
            long ping = System.currentTimeMillis() - start;
            entry.setPingMs(ping);
            return ping;
        } catch (Exception e) {
            entry.setPingMs(-1L);
            return -1L;
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void autoPingAll() {
        configStore.values().forEach(e -> pingConfig(e.getId()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ProxyConfig entryToProxyConfig(ConfigEntry entry) {
        ProxyConfig pc = new ProxyConfig(
            entry.getProtocol(),
            entry.getAddress(),
            entry.getPort(),
            entry.getUserId() != null ? entry.getUserId() : ""
        );
        pc.setFlow(entry.getFlow());
        pc.setEncryption(entry.getEncryption());
        pc.setSecurity(entry.getSecurity());
        pc.setNetwork(entry.getNetwork());
        pc.setPath(entry.getPath());
        pc.setHost(entry.getHost());
        pc.setServiceName(entry.getServiceName());
        pc.setLocalPort(10808);
        pc.setLocalAddress("127.0.0.1");
        return pc;
    }

    public String getActiveConfigId() { return activeConfigId; }
}

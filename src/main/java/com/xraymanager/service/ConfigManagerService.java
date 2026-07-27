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

@Service
public class ConfigManagerService {
    private final Map<String, ConfigEntry> configStore = new ConcurrentHashMap<>();
    private String activeConfigId = null;

    @Autowired
    private ConfigParser configParser;
    @Autowired
    private XrayCoreService xrayCoreService;

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

    public List<ConfigEntry> getAllConfigs() {
        return new ArrayList<>(configStore.values());
    }

    public ConfigEntry getConfig(String id) {
        return configStore.get(id);
    }

    public boolean setActiveConfig(String id) throws Exception {
        ConfigEntry entry = configStore.get(id);
        if (entry == null) return false;
        if (id.equals(activeConfigId)) return true;
        xrayCoreService.stopProxy();
        boolean started = xrayCoreService.startProxy(entryToProxyConfig(entry));
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

    public boolean disconnect() throws Exception {
        for (ConfigEntry e : configStore.values()) e.setActive(false);
        activeConfigId = null;
        xrayCoreService.stopProxy();
        return true;
    }

    public Long pingConfig(String id) {
        ConfigEntry entry = configStore.get(id);
        if (entry == null) return -1L;
        long start = System.currentTimeMillis();
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(entry.getAddress(), entry.getPort()), 3000);
            long ping = System.currentTimeMillis() - start;
            entry.setPingMs(ping);
            return ping;
        } catch (Exception e) {
            entry.setPingMs(-1L);
            return -1L;
        }
    }

    @Scheduled(fixedDelay = 60000)
    public void autoPingAll() {
        for (ConfigEntry entry : configStore.values()) pingConfig(entry.getId());
    }

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
}

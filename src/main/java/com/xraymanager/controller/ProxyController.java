package com.xraymanager.controller;

import com.xraymanager.model.ConnectionStatus;
import com.xraymanager.model.ProxyConfig;
import com.xraymanager.model.VpnConfig;
import com.xraymanager.service.ConfigManagerService;
import com.xraymanager.service.ProxyService;
import com.xraymanager.service.XrayCoreService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/proxy")
public class ProxyController {

    @Autowired
    private XrayCoreService xrayCoreService;
    @Autowired
    private ProxyService proxyService;
    @Autowired
    private ConfigManagerService configManager;

    @PostMapping("/start/vless")
    public ResponseEntity<?> startVless(@RequestBody Map<String, Object> params) {
        try {
            String address  = (String) params.get("address");
            Integer port    = (Integer) params.get("port");
            String userId   = (String) params.getOrDefault("userId", "");
            if (userId == null || userId.isEmpty()) userId = proxyService.generateUserId();
            ProxyConfig config = proxyService.createVlessConfig(address, port, userId);
            if (params.containsKey("flow"))      config.setFlow((String) params.get("flow"));
            if (params.containsKey("network"))   config.setNetwork((String) params.get("network"));
            if (params.containsKey("path"))      config.setPath((String) params.get("path"));
            if (params.containsKey("host"))      config.setHost((String) params.get("host"));
            if (params.containsKey("localPort")) config.setLocalPort((Integer) params.get("localPort"));
            boolean started = xrayCoreService.startProxy(config);
            return ResponseEntity.ok(Map.of("success", started, "localPort", config.getLocalPort()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/start/vmess")
    public ResponseEntity<?> startVmess(@RequestBody Map<String, Object> params) {
        try {
            String address = (String) params.get("address");
            Integer port   = (Integer) params.get("port");
            String userId  = (String) params.getOrDefault("userId", "");
            if (userId == null || userId.isEmpty()) userId = proxyService.generateUserId();
            ProxyConfig config = proxyService.createVmessConfig(address, port, userId);
            if (params.containsKey("security")) config.setSecurity((String) params.get("security"));
            if (params.containsKey("network"))  config.setNetwork((String) params.get("network"));
            boolean started = xrayCoreService.startProxy(config);
            return ResponseEntity.ok(Map.of("success", started, "localPort", config.getLocalPort()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/start/shadowsocks")
    public ResponseEntity<?> startShadowsocks(@RequestBody Map<String, Object> params) {
        try {
            String address  = (String) params.get("address");
            Integer port    = (Integer) params.get("port");
            String password = (String) params.get("password");
            ProxyConfig config = proxyService.createShadowsocksConfig(address, port, password);
            if (params.containsKey("encryption")) config.setEncryption((String) params.get("encryption"));
            boolean started = xrayCoreService.startProxy(config);
            return ResponseEntity.ok(Map.of("success", started, "localPort", config.getLocalPort()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/stop")
    public ResponseEntity<?> stopProxy() {
        try {
            configManager.disconnect();
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/vpn")
    public ResponseEntity<?> setupVpn(@RequestBody VpnConfig vpnConfig) {
        try {
            boolean ok = xrayCoreService.setupVpn(vpnConfig);
            return ResponseEntity.ok(Map.of("success", ok));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        ConnectionStatus s = xrayCoreService.getStatus();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("connected",         s.getConnected());
        resp.put("protocol",          s.getProtocol());
        resp.put("serverAddress",     s.getServerAddress());
        resp.put("activeConnections", s.getActiveConnections());
        resp.put("xrayStatus",        xrayCoreService.getXrayStatus());
        resp.put("xrayInstalled",     xrayCoreService.isXrayInstalled());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/install")
    public ResponseEntity<?> installXray() {
        try {
            xrayCoreService.installXray();
            return ResponseEntity.ok(Map.of("success", true, "message", "Xray installed"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/generate-user-id")
    public ResponseEntity<?> generateUserId() {
        return ResponseEntity.ok(Map.of("userId", proxyService.generateUserId()));
    }
}

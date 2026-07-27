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

import java.util.Map;

@RestController
@RequestMapping("/api/proxy")
public class ProxyController {

    @Autowired private XrayCoreService      xrayCoreService;
    @Autowired private ProxyService         proxyService;
    @Autowired private ConfigManagerService configManager;

    @PostMapping("/start/vless")
    public ResponseEntity<?> startVless(@RequestBody Map<String, Object> p) {
        try {
            String  address = (String)  p.get("address");
            Integer port    = (Integer) p.get("port");
            String  userId  = (String)  p.getOrDefault("userId", "");
            if (userId == null || userId.isEmpty()) userId = proxyService.generateUserId();
            ProxyConfig cfg = proxyService.createVlessConfig(address, port, userId);
            if (p.containsKey("flow"))      cfg.setFlow((String) p.get("flow"));
            if (p.containsKey("network"))   cfg.setNetwork((String) p.get("network"));
            if (p.containsKey("path"))      cfg.setPath((String) p.get("path"));
            if (p.containsKey("host"))      cfg.setHost((String) p.get("host"));
            if (p.containsKey("localPort")) cfg.setLocalPort((Integer) p.get("localPort"));
            boolean started = xrayCoreService.startProxy(cfg);
            return ResponseEntity.ok(Map.of("success", started, "localPort", cfg.getLocalPort()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/start/vmess")
    public ResponseEntity<?> startVmess(@RequestBody Map<String, Object> p) {
        try {
            String  address = (String)  p.get("address");
            Integer port    = (Integer) p.get("port");
            String  userId  = (String)  p.getOrDefault("userId", "");
            if (userId == null || userId.isEmpty()) userId = proxyService.generateUserId();
            ProxyConfig cfg = proxyService.createVmessConfig(address, port, userId);
            if (p.containsKey("security")) cfg.setSecurity((String) p.get("security"));
            if (p.containsKey("network"))  cfg.setNetwork((String) p.get("network"));
            boolean started = xrayCoreService.startProxy(cfg);
            return ResponseEntity.ok(Map.of("success", started, "localPort", cfg.getLocalPort()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/start/shadowsocks")
    public ResponseEntity<?> startShadowsocks(@RequestBody Map<String, Object> p) {
        try {
            String  address  = (String)  p.get("address");
            Integer port     = (Integer) p.get("port");
            String  password = (String)  p.get("password");
            ProxyConfig cfg  = proxyService.createShadowsocksConfig(address, port, password);
            if (p.containsKey("encryption")) cfg.setEncryption((String) p.get("encryption"));
            boolean started  = xrayCoreService.startProxy(cfg);
            return ResponseEntity.ok(Map.of("success", started, "localPort", cfg.getLocalPort()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /** Disconnect: stop xray + restore system proxy. Config store untouched. */
    @PostMapping("/stop")
    public ResponseEntity<?> stopProxy() {
        try {
            configManager.disconnect();
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/vpn")
    public ResponseEntity<?> setupVpn(@RequestBody VpnConfig vpnConfig) {
        try {
            boolean ok = xrayCoreService.setupVpn(vpnConfig);
            return ResponseEntity.ok(Map.of("success", ok));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        ConnectionStatus s = xrayCoreService.getStatus();
        return ResponseEntity.ok(Map.of(
            "connected",         s.getConnected(),
            "protocol",          s.getProtocol()      != null ? s.getProtocol()      : "",
            "serverAddress",     s.getServerAddress()  != null ? s.getServerAddress() : "",
            "activeConnections", s.getActiveConnections(),
            "mode",              xrayCoreService.getCurrentMode(),
            "xrayStatus",        xrayCoreService.getXrayStatus(),
            "xrayInstalled",     xrayCoreService.isXrayInstalled()
        ));
    }

    @PostMapping("/install")
    public ResponseEntity<?> installXray() {
        try {
            xrayCoreService.installXray();
            return ResponseEntity.ok(Map.of("success", true, "message", "Xray installed"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/generate-user-id")
    public ResponseEntity<?> generateUserId() {
        return ResponseEntity.ok(Map.of("userId", proxyService.generateUserId()));
    }
}

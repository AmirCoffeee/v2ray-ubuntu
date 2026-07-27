package com.xraymanager.controller;

import com.xraymanager.model.ConfigEntry;
import com.xraymanager.service.ConfigManagerService;
import com.xraymanager.service.XrayCoreService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/configs")
public class ConfigController {

    @Autowired
    private ConfigManagerService configManager;
    @Autowired
    private XrayCoreService xrayCoreService;

    @PostMapping("/add")
    public ResponseEntity<?> addConfig(@RequestBody Map<String, String> body) {
        String link = body.get("link");
        if (link == null || link.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Link is required"));
        }
        ConfigEntry entry = configManager.addConfigFromLink(link);
        if (entry == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Invalid or unsupported config link"));
        }
        return ResponseEntity.ok(Map.of("success", true, "config", entry));
    }

    @GetMapping("/list")
    public ResponseEntity<?> listConfigs() {
        List<ConfigEntry> configs = configManager.getAllConfigs();
        return ResponseEntity.ok(Map.of("success", true, "configs", configs));
    }

    @DeleteMapping("/remove/{id}")
    public ResponseEntity<?> removeConfig(@PathVariable String id) {
        if (!configManager.removeConfig(id)) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Config not found"));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/select/{id}")
    public ResponseEntity<?> selectConfig(@PathVariable String id) {
        try {
            boolean ok = configManager.setActiveConfig(id);
            if (!ok) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Failed to activate config"));
            }
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/disconnect")
    public ResponseEntity<?> disconnect() {
        try {
            configManager.disconnect();
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/ping/{id}")
    public ResponseEntity<?> pingConfig(@PathVariable String id) {
        long ping = configManager.pingConfig(id);
        return ResponseEntity.ok(Map.of("success", true, "pingMs", ping));
    }

    @PostMapping("/http-ping")
    public ResponseEntity<?> httpPing(@RequestBody(required = false) Map<String, String> body) {
        String target = "https://www.google.com";
        int timeout = 5000;
        if (body != null) {
            if (body.containsKey("target") && body.get("target") != null && !body.get("target").isEmpty()) {
                target = body.get("target");
            }
            try { timeout = Integer.parseInt(body.getOrDefault("timeout", "5000")); }
            catch (NumberFormatException ignored) {}
        }
        try {
            Map<String, Object> result = xrayCoreService.pingWithComparison(target, timeout);
            result.put("success", true);
            result.put("target", target);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }
}

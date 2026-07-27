package com.xraymanager.controller;

import com.xraymanager.model.VpnConfig;
import com.xraymanager.service.XrayCoreService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/vpn")
public class VpnController {

    @Autowired
    private XrayCoreService xrayCoreService;

    @PostMapping("/enable")
    public ResponseEntity<?> enableVpn(@RequestBody VpnConfig config) {
        try {
            config.setEnabled(true);
            boolean ok = xrayCoreService.setupVpn(config);
            return ResponseEntity.ok(Map.of("success", ok));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/disable")
    public ResponseEntity<?> disableVpn() {
        try {
            VpnConfig config = new VpnConfig();
            config.setEnabled(false);
            boolean ok = xrayCoreService.setupVpn(config);
            return ResponseEntity.ok(Map.of("success", ok));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> getVpnStatus() {
        return ResponseEntity.ok(Map.of("xrayStatus", xrayCoreService.getXrayStatus()));
    }
}

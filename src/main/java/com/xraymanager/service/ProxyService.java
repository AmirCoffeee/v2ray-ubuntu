package com.xraymanager.service;

import com.xraymanager.model.ProxyConfig;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ProxyService {
    private final XrayCoreService xrayCoreService;

    public ProxyService(XrayCoreService xrayCoreService) {
        this.xrayCoreService = xrayCoreService;
    }

    public ProxyConfig createVlessConfig(String address, Integer port, String userId) {
        ProxyConfig config = new ProxyConfig("vless", address, port, userId);
        config.setLocalPort(10808);
        config.setLocalAddress("127.0.0.1");
        return config;
    }

    public ProxyConfig createVmessConfig(String address, Integer port, String userId) {
        ProxyConfig config = new ProxyConfig("vmess", address, port, userId);
        config.setSecurity("auto");
        config.setNetwork("tcp");
        config.setLocalPort(10808);
        config.setLocalAddress("127.0.0.1");
        return config;
    }

    public ProxyConfig createShadowsocksConfig(String address, Integer port, String password) {
        ProxyConfig config = new ProxyConfig("shadowsocks", address, port, password);
        config.setEncryption("chacha20-ietf-poly1305");
        config.setLocalPort(10808);
        config.setLocalAddress("127.0.0.1");
        return config;
    }

    public String generateUserId() {
        return UUID.randomUUID().toString();
    }
}

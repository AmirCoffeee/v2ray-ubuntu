package com.xraymanager.utils;

import com.xraymanager.model.ConfigEntry;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ConfigParser {

    public ConfigEntry parseLink(String link) {
        if (link == null || link.trim().isEmpty()) return null;
        link = link.trim();
        if (link.startsWith("vless://"))  return parseVless(link);
        if (link.startsWith("vmess://"))  return parseVmess(link);
        if (link.startsWith("ss://"))     return parseShadowsocks(link);
        if (link.startsWith("socks://"))  return parseSocks(link);
        if (link.startsWith("http://") || link.startsWith("https://")) return parseHttp(link);
        if (link.matches("^[a-zA-Z0-9.-]+:\\d+$")) return parsePlain(link);
        return null;
    }

    private ConfigEntry parseVless(String link) {
        try {
            String body = link.substring(8);
            String[] atParts = body.split("@", 2);
            if (atParts.length != 2) return null;
            String userId = atParts[0];
            String[] hostAndParams = atParts[1].split("\\?", 2);
            String[] hostPort = hostAndParams[0].split(":");
            if (hostPort.length < 2) return null;
            String address = hostPort[0];
            Integer port = Integer.parseInt(hostPort[1].split("#")[0]);
            ConfigEntry entry = new ConfigEntry("vless", address, port, userId);
            entry.setRawLink(link);
            entry.setName("VLESS - " + address + ":" + port);
            if (hostAndParams.length > 1) {
                parseQueryParams(hostAndParams[1].split("#")[0], entry);
            }
            return entry;
        } catch (Exception e) {
            return null;
        }
    }

    private void parseQueryParams(String query, ConfigEntry entry) throws Exception {
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length != 2) continue;
            String value = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            switch (kv[0]) {
                case "flow":       entry.setFlow(value);       break;
                case "encryption": entry.setEncryption(value); break;
                case "security":   entry.setSecurity(value);   break;
                case "type":
                case "network":    entry.setNetwork(value);    break;
                case "path":       entry.setPath(value);       break;
                case "host":       entry.setHost(value);       break;
                case "serviceName":entry.setServiceName(value);break;
            }
        }
    }

    private ConfigEntry parseVmess(String link) {
        try {
            String encoded = link.substring(8);
            String json = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            String address  = extractValue(json, "add");
            Integer port    = Integer.parseInt(extractValue(json, "port"));
            String userId   = extractValue(json, "id");
            String security = extractValue(json, "scy");
            String network  = extractValue(json, "net");
            String path     = extractValue(json, "path");
            String host     = extractValue(json, "host");
            ConfigEntry entry = new ConfigEntry("vmess", address, port, userId);
            entry.setRawLink(link);
            entry.setName("VMESS - " + address + ":" + port);
            entry.setSecurity(security);
            entry.setNetwork(network);
            entry.setPath(path);
            entry.setHost(host);
            return entry;
        } catch (Exception e) {
            return null;
        }
    }

    private ConfigEntry parseShadowsocks(String link) {
        try {
            String body = link.substring(5);
            if (body.contains("@")) {
                String[] atParts = body.split("@", 2);
                String[] mp = atParts[0].split(":", 2);
                if (mp.length != 2) return null;
                String[] hp = atParts[1].split(":");
                if (hp.length < 2) return null;
                Integer port = Integer.parseInt(hp[1].split("#")[0]);
                ConfigEntry entry = new ConfigEntry("shadowsocks", hp[0], port, mp[1]);
                entry.setEncryption(mp[0]);
                entry.setRawLink(link);
                entry.setName("SS - " + hp[0] + ":" + port);
                return entry;
            } else {
                String plain = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
                return parseShadowsocks("ss://" + plain);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private ConfigEntry parseSocks(String link) {
        try {
            String body = link.substring(8);
            String hostPart = body.contains("@") ? body.split("@", 2)[1] : body;
            String[] hp = hostPart.split(":");
            if (hp.length < 2) return null;
            Integer port = Integer.parseInt(hp[1].split("#")[0]);
            ConfigEntry entry = new ConfigEntry("socks", hp[0], port, "");
            entry.setRawLink(link);
            entry.setName("SOCKS - " + hp[0] + ":" + port);
            return entry;
        } catch (Exception e) {
            return null;
        }
    }

    private ConfigEntry parseHttp(String link) {
        try {
            String body = link.startsWith("https://") ? link.substring(8) : link.substring(7);
            String[] parts = body.split(":", 2);
            if (parts.length != 2) return null;
            Integer port = Integer.parseInt(parts[1].split("/")[0]);
            ConfigEntry entry = new ConfigEntry("http", parts[0], port, "");
            entry.setRawLink(link);
            entry.setName("HTTP - " + parts[0] + ":" + port);
            return entry;
        } catch (Exception e) {
            return null;
        }
    }

    private ConfigEntry parsePlain(String link) {
        try {
            String[] parts = link.split(":");
            if (parts.length != 2) return null;
            Integer port = Integer.parseInt(parts[1]);
            ConfigEntry entry = new ConfigEntry("tcp", parts[0], port, "");
            entry.setRawLink(link);
            entry.setName("TCP - " + parts[0] + ":" + port);
            return entry;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractValue(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (m.find()) return m.group(1);
        Matcher n = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (n.find()) return n.group(1);
        return "";
    }
}

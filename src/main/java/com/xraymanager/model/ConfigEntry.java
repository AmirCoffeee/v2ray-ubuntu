package com.xraymanager.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.UUID;

/**
 * A single proxy/VPN server config entry.
 * All fields needed by the REST API have public getters so Jackson can serialise them.
 */
public class ConfigEntry {

    private String  id;
    private String  name;
    private String  protocol;
    private String  address;
    private Integer port;
    private String  userId;
    private String  security;
    private String  flow;
    private String  encryption;
    private String  network;
    private String  path;
    private String  host;
    private String  serviceName;
    private String  rawLink;
    private Instant addedAt;
    private Long    pingMs;
    private Boolean active;

    public ConfigEntry() {
        this.id      = UUID.randomUUID().toString();
        this.addedAt = Instant.now();
        this.active  = false;
    }

    public ConfigEntry(String protocol, String address, Integer port, String userId) {
        this();
        this.protocol = protocol;
        this.address  = address;
        this.port     = port;
        this.userId   = userId;
        this.name     = protocol.toUpperCase() + " - " + address + ":" + port;
    }

    // ── Getters (all exposed to JSON) ─────────────────────────────────────────

    public String  getId()          { return id; }
    public String  getName()        { return name; }
    public String  getProtocol()    { return protocol; }
    public String  getAddress()     { return address; }
    public Integer getPort()        { return port; }
    public String  getSecurity()    { return security; }
    public String  getFlow()        { return flow; }
    public String  getEncryption()  { return encryption; }
    public String  getNetwork()     { return network; }
    public String  getPath()        { return path; }
    public String  getHost()        { return host; }
    public String  getServiceName() { return serviceName; }
    public Instant getAddedAt()     { return addedAt; }
    public Long    getPingMs()      { return pingMs; }
    public Boolean getActive()      { return active; }

    /** userId / rawLink intentionally hidden from API responses */
    @JsonIgnore public String getUserId()  { return userId; }
    @JsonIgnore public String getRawLink() { return rawLink; }

    // ── Setters ───────────────────────────────────────────────────────────────

    public void setName(String name)               { this.name        = name; }
    public void setSecurity(String security)       { this.security    = security; }
    public void setFlow(String flow)               { this.flow        = flow; }
    public void setEncryption(String encryption)   { this.encryption  = encryption; }
    public void setNetwork(String network)         { this.network     = network; }
    public void setPath(String path)               { this.path        = path; }
    public void setHost(String host)               { this.host        = host; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public void setRawLink(String rawLink)         { this.rawLink     = rawLink; }
    public void setPingMs(Long pingMs)             { this.pingMs      = pingMs; }
    public void setActive(Boolean active)          { this.active      = active; }
}

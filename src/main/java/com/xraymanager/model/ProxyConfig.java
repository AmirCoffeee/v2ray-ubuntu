package com.xraymanager.model;

public class ProxyConfig {
    private String protocol;
    private String address;
    private Integer port;
    private String userId;
    private String security;
    private String flow;
    private String encryption;
    private String network;
    private String path;
    private String host;
    private String serviceName;
    private Integer localPort;
    private String localAddress;

    public ProxyConfig() {}

    public ProxyConfig(String protocol, String address, Integer port, String userId) {
        this.protocol = protocol;
        this.address = address;
        this.port = port;
        this.userId = userId;
        this.security = "auto";
        this.flow = "";
        this.encryption = "none";
        this.network = "tcp";
    }

    public String getProtocol() { return protocol; }
    public String getAddress() { return address; }
    public Integer getPort() { return port; }
    public String getUserId() { return userId; }
    public String getSecurity() { return security; }
    public void setSecurity(String security) { this.security = security; }
    public String getFlow() { return flow; }
    public void setFlow(String flow) { this.flow = flow; }
    public String getEncryption() { return encryption; }
    public void setEncryption(String encryption) { this.encryption = encryption; }
    public String getNetwork() { return network; }
    public void setNetwork(String network) { this.network = network; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public Integer getLocalPort() { return localPort; }
    public void setLocalPort(Integer localPort) { this.localPort = localPort; }
    public String getLocalAddress() { return localAddress; }
    public void setLocalAddress(String localAddress) { this.localAddress = localAddress; }
}

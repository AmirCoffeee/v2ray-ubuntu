package com.xraymanager.model;

public class ConnectionStatus {
    private Boolean connected;
    private String protocol;
    private String serverAddress;
    private Integer activeConnections;

    public ConnectionStatus() {
        this.connected = false;
        this.activeConnections = 0;
    }

    public Boolean getConnected() { return connected; }
    public void setConnected(Boolean connected) { this.connected = connected; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public String getServerAddress() { return serverAddress; }
    public void setServerAddress(String serverAddress) { this.serverAddress = serverAddress; }
    public Integer getActiveConnections() { return activeConnections; }
    public void setActiveConnections(Integer activeConnections) { this.activeConnections = activeConnections; }
}

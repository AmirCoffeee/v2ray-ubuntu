package com.xraymanager.model;

public class VpnConfig {
    private Boolean enabled;
    private String tunName;
    private String tunAddress;
    private String dns;
    private String route;
    private Boolean routeAllTraffic;

    public VpnConfig() {
        this.enabled = false;
        this.tunName = "tun0";
        this.tunAddress = "10.0.0.2/24";
        this.dns = "8.8.8.8";
        this.route = "0.0.0.0/1";
        this.routeAllTraffic = true;
    }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}

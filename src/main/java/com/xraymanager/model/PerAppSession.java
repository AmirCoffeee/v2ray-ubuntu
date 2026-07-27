package com.xraymanager.model;

import java.time.Instant;

/**
 * Represents a running per-app proxy session.
 */
public class PerAppSession {

    private String  id;
    private String  appCommand;
    private String  configId;
    private String  configName;
    private String  namespace;   // ip netns name
    private Instant startedAt;
    private boolean running;
    private Long    pid;

    public PerAppSession() {}

    public PerAppSession(String id, String appCommand, String configId, String configName) {
        this.id          = id;
        this.appCommand  = appCommand;
        this.configId    = configId;
        this.configName  = configName;
        this.namespace   = "xray-pa-" + id.substring(0, 8);
        this.startedAt   = Instant.now();
        this.running     = false;
    }

    public String  getId()          { return id; }
    public String  getAppCommand()  { return appCommand; }
    public String  getConfigId()    { return configId; }
    public String  getConfigName()  { return configName; }
    public String  getNamespace()   { return namespace; }
    public Instant getStartedAt()   { return startedAt; }
    public boolean isRunning()      { return running; }
    public Long    getPid()         { return pid; }

    public void setRunning(boolean running) { this.running = running; }
    public void setPid(Long pid)            { this.pid = pid; }
}

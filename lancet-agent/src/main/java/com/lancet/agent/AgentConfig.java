package com.lancet.agent;

/**
 * Agent 配置
 */
public class AgentConfig {
    private int port = 9999;
    private String frameworkType;

    public static AgentConfig parse(String agentArgs) {
        AgentConfig config = new AgentConfig();
        if (agentArgs == null || agentArgs.isEmpty()) {
            return config;
        }
        String[] pairs = agentArgs.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String key = kv[0].trim();
            String value = kv[1].trim();
            if ("port".equals(key)) {
                try {
                    config.port = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    System.err.println("[lancet] Invalid port: " + value + ", using default 9999");
                }
            } else if ("framework".equals(key)) {
                config.frameworkType = value;
            }
        }
        return config;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getFrameworkType() {
        return frameworkType;
    }

    public void setFrameworkType(String frameworkType) {
        this.frameworkType = frameworkType;
    }
}

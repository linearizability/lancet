package com.lancet.agent;

import com.lancet.agent.adapter.FrameworkAdapter;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Agent 内置 HTTP 服务
 */
public class AgentHttpServer {

    private final int port;
    private final FrameworkAdapter adapter;

    public AgentHttpServer(int port, FrameworkAdapter adapter) {
        this.port = port;
        this.adapter = adapter;
    }

    public void start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/invoke", new InvokeHandler(adapter));
            server.setExecutor(null); // 使用默认 executor
            server.start();
            System.out.println("[lancet] HTTP server started on port " + port);
        } catch (IOException e) {
            System.err.println("[lancet] Failed to start HTTP server on port " + port + ": " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
}

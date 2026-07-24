package com.linearizability.lancet.agent;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Agent 内置 HTTP 服务
 */
public class AgentHttpServer {

    private final int port;

    public AgentHttpServer(int port) {
        this.port = port;
    }

    public void start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/invoke", new InvokeHandler());
            server.setExecutor(null);
            server.start();
            System.out.println("[lancet] HTTP server started on port " + port);
        } catch (IOException e) {
            System.err.println("[lancet] Failed to start HTTP server on port " + port + ": " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
}

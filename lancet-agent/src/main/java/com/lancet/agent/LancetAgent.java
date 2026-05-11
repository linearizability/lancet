package com.lancet.agent;

import com.lancet.agent.adapter.FrameworkAdapter;
import com.lancet.agent.adapter.GuiceAdapter;
import com.lancet.agent.adapter.PlainAdapter;
import com.lancet.agent.adapter.SpringAdapter;

import java.lang.instrument.Instrumentation;

/**
 * Lancet Agent 入口
 */
public class LancetAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        // 1. 解析参数
        AgentConfig config = AgentConfig.parse(agentArgs);
        System.out.println("[lancet] Agent mounted, waiting for framework ready...");

        // 2. 启动守护线程
        new Thread(() -> {
            FrameworkAdapter adapter = waitForFrameworkReady(config);
            // 3. 启动 HTTP 服务
            new AgentHttpServer(config.getPort(), adapter).start();
        }, "lancet-init").start();
    }

    private static FrameworkAdapter waitForFrameworkReady(AgentConfig config) {
        // 如果用户显式指定了框架类型
        if (config.getFrameworkType() != null) {
            FrameworkAdapter adapter = createAdapter(config.getFrameworkType());
            if (adapter != null) {
                System.out.println("[lancet] Framework '" + config.getFrameworkType() + "' specified by user.");
                return adapter;
            }
            System.err.println("[lancet] Specified framework '" + config.getFrameworkType() + "' not available, falling back to auto-detect.");
        }

        // 自动探测：Spring → Guice → Plain
        FrameworkAdapter[] adapters = new FrameworkAdapter[]{
                new SpringAdapter(),
                new GuiceAdapter(),
                new PlainAdapter()
        };

        while (true) {
            for (FrameworkAdapter adapter : adapters) {
                if (adapter.isAvailable()) {
                    System.out.println("[lancet] Framework detected: " + adapter.getClass().getSimpleName());
                    return adapter;
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for framework", e);
            }
        }
    }

    private static FrameworkAdapter createAdapter(String frameworkType) {
        if ("spring".equalsIgnoreCase(frameworkType)) {
            return new SpringAdapter();
        }
        if ("guice".equalsIgnoreCase(frameworkType) || "topos".equalsIgnoreCase(frameworkType)) {
            return new GuiceAdapter();
        }
        if ("plain".equalsIgnoreCase(frameworkType)) {
            return new PlainAdapter();
        }
        return null;
    }
}

package com.linearizability.lancet.agent;

import com.linearizability.lancet.agent.adapter.FrameworkAdapter;
import com.linearizability.lancet.agent.adapter.GuiceAdapter;
import com.linearizability.lancet.agent.adapter.PlainAdapter;
import com.linearizability.lancet.agent.adapter.SpringAdapter;

import java.lang.instrument.Instrumentation;

/**
 * Lancet Agent 入口
 */
public class LancetAgent {

    private static volatile Instrumentation instrumentation;

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        // 1. 解析参数
        AgentConfig config = AgentConfig.parse(agentArgs);
        System.out.println("[lancet] Agent mounted, waiting for framework ready...");

        // 2. 启动守护线程
        new Thread(() -> {
            // 框架探测（仅输出日志，不再传递给 HTTP 服务）
            detectFramework(config);
            // 3. 启动 HTTP 服务
            new AgentHttpServer(config.getPort()).start();
        }, "lancet-init").start();
    }

    private static void detectFramework(AgentConfig config) {
        // 如果用户显式指定了框架类型
        if (config.getFrameworkType() != null) {
            FrameworkAdapter adapter = createAdapter(config.getFrameworkType());
            if (adapter != null) {
                System.out.println("[lancet] Framework '" + config.getFrameworkType() + "' specified by user.");
                return;
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
                    return;
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

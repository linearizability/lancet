package com.linearizability.lancet.agent.adapter;

import com.linearizability.lancet.agent.util.ClassLoaderUtils;

/**
 * Guice / Topos 框架适配器
 * <p>
 * 获取实例的优先级：
 * 1. Topos.get(Class) — 从 Guice 容器获取
 * 2. 委托给 PlainAdapter — 继续 fallback
 */
public class GuiceAdapter implements FrameworkAdapter {

    private final PlainAdapter plainAdapter = new PlainAdapter();

    @Override
    public Object getInstance(String className) throws Exception {
        Class<?> clazz = ClassLoaderUtils.loadClass(className);

        // 1. 优先尝试 Topos.get(Class)
        try {
            Class<?> topos = ClassLoaderUtils.loadClass("topos.store.framework.Topos");
            Object framework = topos.getMethod("framework").invoke(null);
            if (framework != null) {
                return topos.getMethod("get", Class.class).invoke(null, clazz);
            }
        } catch (Exception e) {
            // Topos 获取失败（类不在 Guice 容器中），继续 fallback
        }

        // 2. 委托 PlainAdapter 继续尝试（INSTANCE 字段 + 遍历构造器）
        return plainAdapter.getInstance(className);
    }

    @Override
    public boolean isAvailable() {
        return exists("topos.store.framework.Topos")
            || exists("com.google.inject.Injector");
    }

    private boolean exists(String className) {
        return ClassLoaderUtils.loadClassOrNull(className) != null;
    }
}

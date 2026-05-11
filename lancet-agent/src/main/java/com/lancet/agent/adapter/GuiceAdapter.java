package com.lancet.agent.adapter;

/**
 * Guice / Topos 框架适配器
 */
public class GuiceAdapter implements FrameworkAdapter {

    @Override
    public Object getInstance(String className) throws Exception {
        Class<?> clazz = Class.forName(className);

        // 优先尝试 Topos.get(Class)
        try {
            Class<?> topos = Class.forName("topos.store.framework.Topos");
            Object framework = topos.getMethod("framework").invoke(null);
            if (framework != null) {
                return topos.getMethod("get", Class.class).invoke(null, clazz);
            }
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // Topos 不存在，尝试 Guice Injector
        }

        // 尝试从 Guice Injector 获取
        try {
            Class<?> guiceClass = Class.forName("com.google.inject.Guice");
            //  Guice 没有全局 injector，通常存储在静态字段或需要通过其他方式获取
            // 这里尝试从常见模式获取：查找类路径下持有 Injector 的类
            throw new IllegalStateException("Guice Injector not found. Please use Topos or ensure a static Injector is accessible.");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Neither Topos nor Guice found in classpath");
        }
    }

    @Override
    public boolean isAvailable() {
        return exists("topos.store.framework.Topos")
            || exists("com.google.inject.Injector");
    }

    private boolean exists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}

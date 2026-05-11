package com.lancet.agent.adapter;

/**
 * 裸 Java 适配器（静态单例或反射 newInstance）
 */
public class PlainAdapter implements FrameworkAdapter {

    @Override
    public Object getInstance(String className) throws Exception {
        Class<?> clazz = Class.forName(className);

        // 尝试获取单例（静态 INSTANCE 字段）
        try {
            return clazz.getField("INSTANCE").get(null);
        } catch (NoSuchFieldException e) {
            // fallback：反射 newInstance
            return clazz.newInstance();
        }
    }

    @Override
    public boolean isAvailable() {
        // 裸 Java 始终作为兜底方案
        return true;
    }
}

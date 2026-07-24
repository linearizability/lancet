package com.linearizability.lancet.agent.adapter;

/**
 * 框架适配器接口
 */
public interface FrameworkAdapter {
    /**
     * 根据类名获取实例
     *
     * @param className 接口/类全限定名
     * @return 实例对象
     */
    Object getInstance(String className) throws Exception;

    /**
     * 检查当前 JVM 是否支持该框架
     */
    boolean isAvailable();
}

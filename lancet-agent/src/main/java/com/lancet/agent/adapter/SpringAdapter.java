package com.lancet.agent.adapter;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Spring Boot 框架适配器
 */
public class SpringAdapter implements FrameworkAdapter {

    private volatile Object applicationContext;

    @Override
    public Object getInstance(String className) throws Exception {
        Class<?> clazz = Class.forName(className);
        Object context = getApplicationContext();
        if (context == null) {
            throw new IllegalStateException("Spring ApplicationContext not found");
        }
        Method getBean = context.getClass().getMethod("getBean", Class.class);
        return getBean.invoke(context, clazz);
    }

    @Override
    public boolean isAvailable() {
        try {
            // 尝试检测 Spring ApplicationContext
            Object context = getApplicationContext();
            return context != null;
        } catch (Exception e) {
            return false;
        }
    }

    private Object getApplicationContext() {
        if (applicationContext != null) {
            return applicationContext;
        }
        // 通过反射查找 Spring 的 ApplicationContext
        // 方式1：尝试 org.springframework.context.ApplicationContext 的实现类
        try {
            // 扫描已加载的类，找持有 ApplicationContext 的类
            // 这里使用一种通用方式：遍历所有已加载类，查找有 getApplicationContext 或 applicationContext 字段的类
            Class<?> springAppContext = Class.forName("org.springframework.context.ApplicationContext");
            // 实际上在 Agent 中无法直接遍历所有已加载类，这里用一种简化方式
            // 尝试从 Spring 的工具类获取
            Class<?> springContextHolder = null;
            try {
                springContextHolder = Class.forName("org.springframework.web.context.ContextLoader");
                Method getCurrentWebApplicationContext = springContextHolder.getMethod("getCurrentWebApplicationContext");
                Object ctx = getCurrentWebApplicationContext.invoke(null);
                if (ctx != null) {
                    applicationContext = ctx;
                    return applicationContext;
                }
            } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                // ignore
            }

            // 尝试 Spring Boot 的 SpringApplication
            try {
                Class<?> appContextClass = Class.forName("org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext");
                // 尝试找到已实例化的 context
                applicationContext = findInstanceByType(appContextClass);
                if (applicationContext != null) {
                    return applicationContext;
                }
            } catch (ClassNotFoundException e) {
                // ignore
            }

            // 尝试通用 ApplicationContext 子类
            applicationContext = findInstanceByType(springAppContext);
            return applicationContext;
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * 通过 Instrumentation 查找已加载的类实例（简化实现）
     * 实际场景中可能需要更复杂的逻辑
     */
    private Object findInstanceByType(Class<?> type) {
        // 简化：尝试从常见 holder 类获取
        String[] holderClassNames = {
            "org.springframework.web.context.ContextLoader",
            "org.springframework.boot.SpringApplication"
        };
        for (String holderClassName : holderClassNames) {
            try {
                Class<?> holderClass = Class.forName(holderClassName);
                for (Field field : holderClass.getDeclaredFields()) {
                    if (type.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        Object instance = field.get(null);
                        if (instance != null) {
                            return instance;
                        }
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return null;
    }
}

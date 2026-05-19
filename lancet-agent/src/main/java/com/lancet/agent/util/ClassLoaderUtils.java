package com.lancet.agent.util;

import com.lancet.agent.LancetAgent;

import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.Set;

/**
 * 跨 ClassLoader 类加载工具。
 * <p>
 * 解决场景：当目标应用使用了 ClassLoader 隔离（插件化框架 / 模块化 / 自定义 AppClassLoader 等）时，
 * Agent 内部默认的 {@code Class.forName(name)} 只能从 Agent 自身的 ClassLoader 查找类，
 * 看不到业务侧 ClassLoader 加载的类，从而抛出 {@link ClassNotFoundException}。
 * <p>
 * 查找顺序：
 * <ol>
 *   <li>Agent 自身 ClassLoader（{@code Class.forName}）</li>
 *   <li>当前线程上下文 ClassLoader</li>
 *   <li>{@link Instrumentation#getAllLoadedClasses()} 中按类名直接命中</li>
 *   <li>遍历去重后的所有 ClassLoader 兜底加载</li>
 * </ol>
 */
public final class ClassLoaderUtils {

    private ClassLoaderUtils() {
    }

    /**
     * 跨 ClassLoader 加载指定类。
     */
    public static Class<?> loadClass(String name) throws ClassNotFoundException {
        // 1. Agent 自身 ClassLoader
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException ignored) {
        }

        // 2. 线程上下文 ClassLoader
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl != null) {
            try {
                return Class.forName(name, false, tccl);
            } catch (ClassNotFoundException ignored) {
            }
        }

        Instrumentation inst = LancetAgent.getInstrumentation();
        if (inst != null) {
            // 3. 已加载类中按类名直接命中（最可靠）
            Class<?>[] all = inst.getAllLoadedClasses();
            for (Class<?> c : all) {
                if (c != null && name.equals(c.getName())) {
                    return c;
                }
            }

            // 4. 遍历去重后的所有 ClassLoader 兜底加载
            Set<ClassLoader> tried = new HashSet<>();
            ClassLoader sys = ClassLoader.getSystemClassLoader();
            if (sys != null) tried.add(sys);
            if (tccl != null) tried.add(tccl);

            for (Class<?> c : all) {
                if (c == null) continue;
                ClassLoader cl = c.getClassLoader();
                if (cl == null || !tried.add(cl)) continue;
                try {
                    return Class.forName(name, false, cl);
                } catch (ClassNotFoundException ignored) {
                }
            }
        }

        throw new ClassNotFoundException(name);
    }

    /**
     * 跨 ClassLoader 加载类，找不到时返回 {@code null}（不抛异常）。
     */
    public static Class<?> loadClassOrNull(String name) {
        try {
            return loadClass(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}

package com.linearizability.lancet.agent.adapter;

import com.linearizability.lancet.agent.LancetAgent;
import com.linearizability.lancet.agent.util.ClassLoaderUtils;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 裸 Java 适配器（静态单例或反射创建实例）
 * <p>
 * 获取实例的优先级：
 * 1. 已注册的实例（{@link #register(String, Object)}）
 * 2. 查找静态 INSTANCE 字段
 * 3. 在 JavaFX 场景图中查找已有实例
 * 4. 通过 Instrumentation 扫描所有已加载类的静态字段
 * 5. 遍历所有构造器，用 null/默认值填充参数
 */
public class PlainAdapter implements FrameworkAdapter {

    // ========== 实例注册表 ==========

    private static final ConcurrentHashMap<String, Object> instanceRegistry = new ConcurrentHashMap<>();

    /**
     * 注册一个实例供后续调用使用。
     * 在应用启动时或创建 UI 组件后调用此方法注册实例。
     */
    public static void register(String className, Object instance) {
        if (className != null && instance != null) {
            instanceRegistry.put(className, instance);
        }
    }

    /**
     * 注销已注册的实例。
     */
    public static void unregister(String className) {
        instanceRegistry.remove(className);
    }

    // ========== 主入口 ==========

    @Override
    public Object getInstance(String className) throws Exception {
        Class<?> clazz = ClassLoaderUtils.loadClass(className);

        // 1. 检查注册表
        Object registered = instanceRegistry.get(className);
        if (registered != null && clazz.isInstance(registered)) {
            return registered;
        }

        // 2. 尝试获取单例（静态 INSTANCE 字段）
        Object instance = findInstanceField(clazz);
        if (instance != null) return instance;

        // 3. 尝试在 JavaFX 场景图中查找已有实例（UI 组件适用）
        instance = findInJavaFXScene(clazz);
        if (instance != null) return instance;

        // 4. 通过 Instrumentation 扫描所有已加载类的静态字段
        instance = findInLoadedClasses(clazz);
        if (instance != null) return instance;

        // 5. 遍历所有构造器，尝试用 null/默认值创建
        instance = createViaConstructors(clazz);
        if (instance != null) return instance;

        throw new IllegalStateException(
            "Cannot get instance of " + className + ": " +
            "\n  1. registry — no registered instance" +
            "\n  2. INSTANCE field — not found" +
            "\n  3. scene graph — not found (Windows count: " + countJavaFXWindows() + ")" +
            "\n  4. loaded classes — no static field holds this type" +
            "\n  5. constructors — none succeeded" +
            "\n\n  Tips:" +
            "\n  - If this is a JavaFX UI component, ensure the window is showing when calling." +
            "\n  - You can register an instance via PlainAdapter.register(className, instance) in your app." +
            "\n  - Or use a Guice-managed Service/interface instead of the UI class directly.");
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    // ========== 单例字段查找 ==========

    private Object findInstanceField(Class<?> clazz) {
        try {
            Field instanceField = clazz.getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            return instanceField.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ========== JavaFX 场景图扫描 ==========

    /** 统计窗口数，用于错误提示 */
    private int countJavaFXWindows() {
        try {
            Class<?> windowClass = Class.forName("javafx.stage.Window");
            Method getWindows = windowClass.getMethod("getWindows");
            Object windows = getWindows.invoke(null);
            return windows instanceof List ? ((List<?>) windows).size() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private Object findInJavaFXScene(Class<?> targetClass) {
        try {
            Class<?> windowClass = Class.forName("javafx.stage.Window");
            Method getWindows = windowClass.getMethod("getWindows");
            Object windows = getWindows.invoke(null);
            if (!(windows instanceof List)) return null;

            for (Object window : (List<?>) windows) {
                if (window == null) continue;

                // 方式 A: Window.getScene() — 标准方式
                try {
                    Method getScene = window.getClass().getMethod("getScene");
                    Object scene = getScene.invoke(window);
                    if (scene != null) {
                        Method getRoot = scene.getClass().getMethod("getRoot");
                        Object root = getRoot.invoke(scene);
                        Object found = findNodeRecursive(root, targetClass);
                        if (found != null) return found;
                    }
                } catch (Exception ignored) {
                }

                // 方式 B: PopupWindow.getScene() 或 getContent()
                try {
                    Method getContent = window.getClass().getMethod("getContent");
                    Object content = getContent.invoke(window);
                    if (content != null) {
                        Object found = findNodeRecursive(content, targetClass);
                        if (found != null) return found;
                    }
                } catch (NoSuchMethodException ignored) {
                }

                // 方式 C: Stage.getScene() — Stage.getScene() != Window.getScene() 在某些版本不同
                try {
                    if (!window.getClass().getName().equals("javafx.stage.Stage")) {
                        Class<?> stageClass = Class.forName("javafx.stage.Stage");
                        if (stageClass.isInstance(window)) {
                            Method getScene = stageClass.getMethod("getScene");
                            Object scene = getScene.invoke(window);
                            if (scene != null) {
                                Method getRoot = scene.getClass().getMethod("getRoot");
                                Object root = getRoot.invoke(scene);
                                Object found = findNodeRecursive(root, targetClass);
                                if (found != null) return found;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
            // JavaFX 不可用或无权访问场景图
        }
        return null;
    }

    private Object findNodeRecursive(Object node, Class<?> targetClass) {
        if (node == null) return null;
        if (targetClass.isInstance(node)) return node;

        // 方式 A: Parent.getChildrenUnmodifiable()
        try {
            Method getChildren = node.getClass().getMethod("getChildrenUnmodifiable");
            Object children = getChildren.invoke(node);
            if (children instanceof List) {
                for (Object child : (List<?>) children) {
                    Object found = findNodeRecursive(child, targetClass);
                    if (found != null) return found;
                }
            }
        } catch (Exception ignored) {
        }

        // 方式 B: ScrollPane.getContent(), TitledPane.getContent()
        try {
            Method getter = node.getClass().getMethod("getContent");
            Object content = getter.invoke(node);
            if (content != null) {
                Object found = findNodeRecursive(content, targetClass);
                if (found != null) return found;
            }
        } catch (Exception ignored) {
        }

        // 方式 C: SplitPane.getItems(), TabPane.getTabs()
        for (String listMethod : new String[]{"getItems", "getTabs"}) {
            try {
                Method getter = node.getClass().getMethod(listMethod);
                Object items = getter.invoke(node);
                if (items instanceof List) {
                    for (Object item : (List<?>) items) {
                        // Tab.getContent() 或直接递归
                        try {
                            Method getContent = item.getClass().getMethod("getContent");
                            Object content = getContent.invoke(item);
                            if (content != null) {
                                Object found = findNodeRecursive(content, targetClass);
                                if (found != null) return found;
                            }
                        } catch (NoSuchMethodException e) {
                            // 不是 Tab 类型，直接递归
                            Object found = findNodeRecursive(item, targetClass);
                            if (found != null) return found;
                        } catch (Exception ignored2) {
                        }
                    }
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Exception ignored2) {
            }
        }

        // 方式 D: Accordion.getPanes()
        try {
            Method getter = node.getClass().getMethod("getPanes");
            Object panes = getter.invoke(node);
            if (panes instanceof List) {
                for (Object pane : (List<?>) panes) {
                    Object found = findNodeRecursive(pane, targetClass);
                    if (found != null) return found;
                }
            }
        } catch (NoSuchMethodException ignored) {
        } catch (Exception ignored2) {
        }

        return null;
    }

    // ========== Instrumentation 全 JVM 静态字段扫描 ==========

    private Object findInLoadedClasses(Class<?> targetClass) {
        Instrumentation inst = LancetAgent.getInstrumentation();
        if (inst == null) return null;

        Class<?>[] loadedClasses = inst.getAllLoadedClasses();
        for (Class<?> clazz : loadedClasses) {
            if (clazz == null || clazz.isInterface() || clazz.isPrimitive()) continue;

            for (Field field : clazz.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                if (!field.getType().isAssignableFrom(targetClass)) continue;

                try {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value != null && targetClass.isInstance(value)) {
                        return value;
                    }
                } catch (Exception ignored) {
                    // 可能无权访问（module system 限制）
                }
            }
        }
        return null;
    }

    // ========== 构造器遍历创建 ==========

    private Object createViaConstructors(Class<?> clazz) {
        Constructor<?>[] ctors = clazz.getDeclaredConstructors();
        for (Constructor<?> ctor : ctors) {
            ctor.setAccessible(true);
            Class<?>[] paramTypes = ctor.getParameterTypes();
            Object[] params = new Object[paramTypes.length];

            for (int i = 0; i < paramTypes.length; i++) {
                Class<?> pt = paramTypes[i];
                if (pt == boolean.class)       params[i] = false;
                else if (pt == byte.class)     params[i] = (byte) 0;
                else if (pt == short.class)    params[i] = (short) 0;
                else if (pt == int.class)      params[i] = 0;
                else if (pt == long.class)     params[i] = 0L;
                else if (pt == float.class)    params[i] = 0f;
                else if (pt == double.class)   params[i] = 0d;
                else if (pt == char.class)     params[i] = '\0';
                // 其他引用类型保持 null
            }

            try {
                return ctor.newInstance(params);
            } catch (Exception ignored) {
                // 这个构造器不行，试下一个
            }
        }
        return null;
    }
}

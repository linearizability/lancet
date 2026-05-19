package com.lancet.agent;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.lancet.agent.adapter.GuiceAdapter;
import com.lancet.agent.adapter.PlainAdapter;
import com.lancet.agent.dto.GsonFactory;
import com.lancet.agent.dto.InvocationRequest;
import com.lancet.agent.dto.InvocationResult;
import com.lancet.agent.util.ClassLoaderUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 调用处理器
 */
public class InvokeHandler implements HttpHandler {

    private final Gson gson = GsonFactory.create();
    private final GuiceAdapter guiceAdapter = new GuiceAdapter();
    private final PlainAdapter plainAdapter = new PlainAdapter();

    public InvokeHandler() {
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, gson.toJson(InvocationResult.fail("Method not allowed: " + exchange.getRequestMethod(), 0)));
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if (!"/invoke".equals(path)) {
            sendResponse(exchange, 404, gson.toJson(InvocationResult.fail("Not found: " + path, 0)));
            return;
        }

        long start = System.currentTimeMillis();
        InvocationResult result;
        try {
            InvocationRequest request = gson.fromJson(
                    new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8),
                    InvocationRequest.class);
            result = invoke(request, start);
        } catch (JsonSyntaxException e) {
            result = InvocationResult.fail("JSON parse error: " + e.getMessage(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            result = InvocationResult.fail("Unexpected error: " + e.getMessage(), System.currentTimeMillis() - start);
        }

        String responseJson = gson.toJson(result);
        sendResponse(exchange, 200, responseJson);
    }

    private InvocationResult invoke(InvocationRequest request, long start) {
        try {
            if (request == null) {
                return InvocationResult.fail("Request body is null", System.currentTimeMillis() - start);
            }

            // 加载目标类（跨 ClassLoader 查找，兼容业务侧自定义/隔离 ClassLoader）
            Class<?> clazz = ClassLoaderUtils.loadClass(request.getClassName());

            // 获取实例 — 根据 instanceSource 选择策略
            Object instance = getInstance(request);
            if (instance == null) {
                return InvocationResult.fail("Instance not found for class: " + request.getClassName(), System.currentTimeMillis() - start);
            }

            // 解析参数类型
            String[] paramTypeNames = request.getParamTypes();
            Class<?>[] paramTypes = new Class<?>[paramTypeNames != null ? paramTypeNames.length : 0];
            for (int i = 0; i < paramTypes.length; i++) {
                paramTypes[i] = classForName(paramTypeNames[i]);
            }

            // 定位方法（支持 private/protected/public，含继承方法）
            Method method = findMethod(clazz, request.getMethodName(), paramTypes);
            method.setAccessible(true);

            // 反序列化参数
            String[] paramsJson = request.getParamsJson();
            Object[] args = new Object[paramsJson != null ? paramsJson.length : 0];
            for (int i = 0; i < args.length; i++) {
                if (paramsJson[i] == null || "null".equals(paramsJson[i])) {
                    args[i] = null;
                } else {
                    args[i] = gson.fromJson(paramsJson[i], paramTypes[i]);
                }
            }

            // 执行方法
            Object returnValue = method.invoke(instance, args);

            long cost = System.currentTimeMillis() - start;
            String dataJson = returnValue == null ? "null" : gson.toJson(returnValue);
            return InvocationResult.ok(dataJson, cost);

        } catch (ClassNotFoundException e) {
            return InvocationResult.fail("Class not found: " + e.getMessage(), System.currentTimeMillis() - start);
        } catch (NoSuchMethodException e) {
            return InvocationResult.fail("Method not found: " + e.getMessage(), System.currentTimeMillis() - start);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return InvocationResult.fail(cause.getClass().getName() + ": " + cause.getMessage() + "\n" + stackTraceToString(cause), System.currentTimeMillis() - start);
        } catch (IllegalAccessException e) {
            return InvocationResult.fail("Illegal access: " + e.getMessage(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            return InvocationResult.fail(e.getClass().getName() + ": " + e.getMessage() + "\n" + stackTraceToString(e), System.currentTimeMillis() - start);
        }
    }

    /**
     * 根据 instanceSource 获取实例。
     * <ul>
     *   <li>{@code null} / {@code auto} — 自动检测（GuiceAdapter → PlainAdapter）</li>
     *   <li>{@code new} — 反射创建新实例</li>
     *   <li>{@code topos} — 仅使用 Topos.get()</li>
     *   <li>{@code scene} — 仅扫描 JavaFX 场景图</li>
     *   <li>{@code classpath} — 仅扫描已加载类的静态字段</li>
     *   <li>{@code registry} — 使用已注册的实例</li>
     * </ul>
     */
    private Object getInstance(InvocationRequest request) throws Exception {
        String source = request.getInstanceSource();
        if (source == null || source.isEmpty() || "auto".equals(source)) {
            // 自动检测：先 Guice，再 Plain
            try {
                return guiceAdapter.getInstance(request.getClassName());
            } catch (Exception e) {
                return plainAdapter.getInstance(request.getClassName());
            }
        }
        switch (source) {
            case "new":
                return newInstance(request.getClassName());
            case "topos":
                return guiceAdapter.getInstance(request.getClassName());
            case "scene":
            case "classpath":
            case "registry":
                return plainAdapter.getInstance(request.getClassName());
            default:
                return plainAdapter.getInstance(request.getClassName());
        }
    }

    /** 直接反射创建新实例 */
    private Object newInstance(String className) throws Exception {
        Class<?> clazz = ClassLoaderUtils.loadClass(className);
        return plainAdapter.getInstance(className);
    }

    private Class<?> classForName(String name) throws ClassNotFoundException {
        if ("int".equals(name)) return int.class;
        if ("long".equals(name)) return long.class;
        if ("short".equals(name)) return short.class;
        if ("byte".equals(name)) return byte.class;
        if ("boolean".equals(name)) return boolean.class;
        if ("char".equals(name)) return char.class;
        if ("float".equals(name)) return float.class;
        if ("double".equals(name)) return double.class;
        if ("void".equals(name)) return void.class;
        return ClassLoaderUtils.loadClass(name);
    }

    private String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }

    /**
     * 查找方法：先从当前类查找声明方法（含 private），再沿继承链向上查找
     */
    private Method findMethod(Class<?> clazz, String methodName, Class<?>[] paramTypes) throws NoSuchMethodException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredMethod(methodName, paramTypes);
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(clazz.getName() + "." + methodName + describeParamTypes(paramTypes));
    }

    private String describeParamTypes(Class<?>[] paramTypes) {
        if (paramTypes == null || paramTypes.length == 0) {
            return "()";
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(paramTypes[i].getName());
        }
        sb.append(")");
        return sb.toString();
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}

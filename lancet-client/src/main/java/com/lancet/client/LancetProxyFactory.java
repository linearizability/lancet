package com.lancet.client;

import com.google.gson.Gson;
import com.lancet.agent.dto.GsonFactory;
import com.lancet.agent.dto.InvocationRequest;
import com.lancet.agent.dto.InvocationResult;

import java.lang.reflect.Proxy;
import java.util.Arrays;

/**
 * Lancet 客户端工厂
 * <p>
 * 支持两种调用方式：
 * 1. create(interfaceClass) — JDK 动态代理，像本地调用一样调用接口方法
 * 2. invoke(className, methodName, ...) — 直接调用任意类的任意方法（含 private）
 */
public class LancetProxyFactory {

    private final String agentUrl;
    private final Gson gson = GsonFactory.create();

    public LancetProxyFactory(String agentUrl) {
        if (agentUrl.endsWith("/")) {
            this.agentUrl = agentUrl.substring(0, agentUrl.length() - 1);
        } else {
            this.agentUrl = agentUrl;
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> interfaceClass) {
        if (!interfaceClass.isInterface()) {
            throw new IllegalArgumentException("Only interface is supported, but got: " + interfaceClass.getName());
        }
        return (T) Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[]{interfaceClass},
                (proxy, method, args) -> {
                    // 1. 构造请求
                    InvocationRequest req = new InvocationRequest();
                    req.setClassName(interfaceClass.getName());
                    req.setMethodName(method.getName());
                    req.setParamTypes(Arrays.stream(method.getParameterTypes())
                            .map(Class::getName)
                            .toArray(String[]::new));
                    if (args != null) {
                        req.setParamsJson(Arrays.stream(args)
                                .map(arg -> arg == null ? "null" : gson.toJson(arg))
                                .toArray(String[]::new));
                    } else {
                        req.setParamsJson(new String[0]);
                    }

                    // 2. 发送 HTTP 请求
                    InvocationResult result = HttpInvokeClient.post(agentUrl + "/invoke", req);

                    // 3. 处理响应
                    if (!result.isSuccess()) {
                        throw new RuntimeException(result.getErrorMsg());
                    }

                    // 4. 反序列化返回值
                    if (method.getReturnType() == void.class) {
                        return null;
                    }
                    if (result.getData() == null || "null".equals(result.getData())) {
                        return null;
                    }
                    return gson.fromJson(result.getData(), method.getGenericReturnType());
                }
        );
    }

    /**
     * 直接调用指定类的指定方法（支持 private/protected/public）。
     * <p>
     * 使用示例：
     * <pre>
     * LancetProxyFactory factory = new LancetProxyFactory("http://localhost:9999");
     *
     * // 无参方法
     * String result = factory.invoke("com.example.MyService", "getStatus", null, null);
     *
     * // 有参方法
     * factory.invoke("com.example.MyService", "process",
     *     new Class[]{String.class, int.class},
     *     new Object[]{"hello", 42});
     *
     * // 带返回类型的方法
     * MyDto dto = factory.invoke("com.example.MyService", "getData",
     *     new Class[]{Long.class},
     *     new Object[]{123L},
     *     MyDto.class);
     * </pre>
     *
     * @param className   目标类全限定名
     * @param methodName  方法名
     * @param paramTypes  参数类型数组，无参传 null
     * @param args        参数值数组，无参传 null
     * @return 方法返回值，void 返回 null
     */
    public Object invoke(String className, String methodName, Class<?>[] paramTypes, Object[] args) {
        return invoke(className, methodName, paramTypes, args, null);
    }

    /**
     * 直接调用指定类的指定方法，带返回类型。
     *
     * @param className   目标类全限定名
     * @param methodName  方法名
     * @param paramTypes  参数类型数组，无参传 null
     * @param args        参数值数组，无参传 null
     * @param returnType  返回值类型，传 null 则按 Object 解析；void 方法传 null 即可
     * @param <T>         返回值泛型
     * @return 方法返回值，void 返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T invoke(String className, String methodName, Class<?>[] paramTypes, Object[] args, Class<T> returnType) {
        return invoke(className, methodName, paramTypes, args, returnType, null);
    }

    /**
     * 直接调用指定类的指定方法，带返回类型和实例来源。
     *
     * @param className      目标类全限定名
     * @param methodName     方法名
     * @param paramTypes     参数类型数组，无参传 null
     * @param args           参数值数组，无参传 null
     * @param returnType     返回值类型，传 null 则按 Object 解析
     * @param instanceSource 实例来源，可选值：{@code null}/auto, new, topos, scene, classpath, registry
     * @param <T>            返回值泛型
     * @return 方法返回值，void 返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T invoke(String className, String methodName, Class<?>[] paramTypes, Object[] args, Class<T> returnType, String instanceSource) {
        // 1. 构造请求
        InvocationRequest req = new InvocationRequest();
        req.setClassName(className);
        req.setMethodName(methodName);
        req.setInstanceSource(instanceSource);
        if (paramTypes != null) {
            req.setParamTypes(Arrays.stream(paramTypes)
                    .map(Class::getName)
                    .toArray(String[]::new));
        } else {
            req.setParamTypes(new String[0]);
        }
        if (args != null) {
            req.setParamsJson(Arrays.stream(args)
                    .map(arg -> arg == null ? "null" : gson.toJson(arg))
                    .toArray(String[]::new));
        } else {
            req.setParamsJson(new String[0]);
        }

        // 2. 发送 HTTP 请求
        InvocationResult result = HttpInvokeClient.post(agentUrl + "/invoke", req);

        // 3. 处理响应
        if (!result.isSuccess()) {
            throw new RuntimeException(result.getErrorMsg());
        }

        // 4. 反序列化返回值
        if (returnType == null || returnType == void.class) {
            return null;
        }
        if (result.getData() == null || "null".equals(result.getData())) {
            return null;
        }
        return gson.fromJson(result.getData(), returnType);
    }
}

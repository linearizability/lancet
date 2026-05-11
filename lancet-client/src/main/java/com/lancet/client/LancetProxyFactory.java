package com.lancet.client;

import com.google.gson.Gson;
import com.lancet.agent.dto.InvocationRequest;
import com.lancet.agent.dto.InvocationResult;

import java.lang.reflect.Proxy;
import java.util.Arrays;

/**
 * 动态代理工厂
 */
public class LancetProxyFactory {

    private final String agentUrl;
    private final Gson gson = new Gson();

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
}

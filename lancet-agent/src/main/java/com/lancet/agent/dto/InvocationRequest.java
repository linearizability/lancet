package com.lancet.agent.dto;

/**
 * 方法调用请求 DTO
 */
public class InvocationRequest {
    private String className;
    private String methodName;
    private String[] paramTypes;
    private String[] paramsJson;

    /**
     * 实例来源，可选值：
     * <ul>
     *   <li>{@code null} 或 {@code "auto"} — 自动检测（默认）</li>
     *   <li>{@code "new"} — 反射创建新实例</li>
     *   <li>{@code "topos"} — 仅使用 Topos.get()</li>
     *   <li>{@code "scene"} — 仅扫描 JavaFX 场景图</li>
     *   <li>{@code "classpath"} — 仅扫描已加载类的静态字段</li>
     *   <li>{@code "registry"} — 使用已注册的实例</li>
     * </ul>
     */
    private String instanceSource;

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String[] getParamTypes() {
        return paramTypes;
    }

    public void setParamTypes(String[] paramTypes) {
        this.paramTypes = paramTypes;
    }

    public String[] getParamsJson() {
        return paramsJson;
    }

    public void setParamsJson(String[] paramsJson) {
        this.paramsJson = paramsJson;
    }

    public String getInstanceSource() {
        return instanceSource;
    }

    public void setInstanceSource(String instanceSource) {
        this.instanceSource = instanceSource;
    }
}

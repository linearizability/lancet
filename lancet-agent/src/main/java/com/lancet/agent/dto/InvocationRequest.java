package com.lancet.agent.dto;

/**
 * 方法调用请求 DTO
 */
public class InvocationRequest {
    private String className;
    private String methodName;
    private String[] paramTypes;
    private String[] paramsJson;

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
}

package com.linearizability.lancet.agent.dto;

/**
 * 方法调用响应 DTO
 */
public class InvocationResult {
    private boolean success;
    private String data;
    private String errorMsg;
    private long costMillis;

    public InvocationResult() {
    }

    public InvocationResult(boolean success, String data, String errorMsg, long costMillis) {
        this.success = success;
        this.data = data;
        this.errorMsg = errorMsg;
        this.costMillis = costMillis;
    }

    public static InvocationResult ok(String data, long costMillis) {
        return new InvocationResult(true, data, null, costMillis);
    }

    public static InvocationResult fail(String errorMsg, long costMillis) {
        return new InvocationResult(false, null, errorMsg, costMillis);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public long getCostMillis() {
        return costMillis;
    }

    public void setCostMillis(long costMillis) {
        this.costMillis = costMillis;
    }
}

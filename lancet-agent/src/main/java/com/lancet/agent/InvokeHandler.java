package com.lancet.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.lancet.agent.adapter.FrameworkAdapter;
import com.lancet.agent.dto.InvocationRequest;
import com.lancet.agent.dto.InvocationResult;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * HTTP 调用处理器
 */
public class InvokeHandler implements HttpHandler {

    private final FrameworkAdapter adapter;
    private final Gson gson = createGson();

    public InvokeHandler(FrameworkAdapter adapter) {
        this.adapter = adapter;
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

            // 加载目标类
            Class<?> clazz = Class.forName(request.getClassName());

            // 获取实例
            Object instance = adapter.getInstance(request.getClassName());
            if (instance == null) {
                return InvocationResult.fail("Instance not found for class: " + request.getClassName(), System.currentTimeMillis() - start);
            }

            // 解析参数类型
            String[] paramTypeNames = request.getParamTypes();
            Class<?>[] paramTypes = new Class<?>[paramTypeNames != null ? paramTypeNames.length : 0];
            for (int i = 0; i < paramTypes.length; i++) {
                paramTypes[i] = classForName(paramTypeNames[i]);
            }

            // 定位方法
            Method method = clazz.getMethod(request.getMethodName(), paramTypes);

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
        return Class.forName(name);
    }

    private String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }

    private static Gson createGson() {
        GsonBuilder builder = new GsonBuilder();

        // LocalDateTime 适配器：支持多种常见字符串格式
        builder.registerTypeAdapter(LocalDateTime.class, new TypeAdapter<LocalDateTime>() {
            private final List<DateTimeFormatter> formatters = Arrays.asList(
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
                    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
            );

            @Override
            public void write(JsonWriter out, LocalDateTime value) throws IOException {
                out.value(value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            }

            @Override
            public LocalDateTime read(JsonReader in) throws IOException {
                String str = in.nextString();
                for (DateTimeFormatter formatter : formatters) {
                    try {
                        return LocalDateTime.parse(str, formatter);
                    } catch (DateTimeParseException e) {
                        // try next formatter
                    }
                }
                throw new IOException("Cannot parse LocalDateTime: " + str);
            }
        });

        // LocalDate 适配器
        builder.registerTypeAdapter(LocalDate.class, new TypeAdapter<LocalDate>() {
            private final List<DateTimeFormatter> formatters = Arrays.asList(
                    DateTimeFormatter.ISO_LOCAL_DATE,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                    DateTimeFormatter.ofPattern("yyyy/MM/dd")
            );

            @Override
            public void write(JsonWriter out, LocalDate value) throws IOException {
                out.value(value.format(DateTimeFormatter.ISO_LOCAL_DATE));
            }

            @Override
            public LocalDate read(JsonReader in) throws IOException {
                String str = in.nextString();
                for (DateTimeFormatter formatter : formatters) {
                    try {
                        return LocalDate.parse(str, formatter);
                    } catch (DateTimeParseException e) {
                        // try next formatter
                    }
                }
                throw new IOException("Cannot parse LocalDate: " + str);
            }
        });

        // java.util.Date 适配器
        builder.registerTypeAdapter(Date.class, new TypeAdapter<Date>() {
            private final List<DateTimeFormatter> formatters = Arrays.asList(
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            );

            @Override
            public void write(JsonWriter out, Date value) throws IOException {
                out.value(value.toInstant().toString());
            }

            @Override
            public Date read(JsonReader in) throws IOException {
                String str = in.nextString();
                for (DateTimeFormatter formatter : formatters) {
                    try {
                        return java.sql.Timestamp.valueOf(LocalDateTime.parse(str, formatter));
                    } catch (DateTimeParseException e) {
                        // try next formatter
                    }
                }
                throw new IOException("Cannot parse Date: " + str);
            }
        });

        return builder.create();
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

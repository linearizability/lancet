package com.linearizability.lancet.agent.dto;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Gson 工厂，创建支持 Java 8 时间类型的 Gson 实例。
 * Agent 端和 Client 端共用，确保序列化格式一致。
 */
public class GsonFactory {

    private static final List<DateTimeFormatter> LOCAL_DATE_TIME_FORMATTERS = Arrays.asList(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
    );

    private static final List<DateTimeFormatter> LOCAL_DATE_FORMATTERS = Arrays.asList(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd")
    );

    private static final List<DateTimeFormatter> DATE_FORMATTERS = Arrays.asList(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    );

    public static Gson create() {
        GsonBuilder builder = new GsonBuilder();

        // LocalDateTime：序列化为 ISO 字符串，反序列化兼容字符串和 Gson 默认对象格式
        builder.registerTypeAdapter(LocalDateTime.class, new TypeAdapter<LocalDateTime>() {
            @Override
            public void write(JsonWriter out, LocalDateTime value) throws IOException {
                if (value == null) {
                    out.nullValue();
                    return;
                }
                out.value(value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            }

            @Override
            public LocalDateTime read(JsonReader in) throws IOException {
                JsonToken peek = in.peek();
                if (peek == JsonToken.STRING) {
                    String str = in.nextString();
                    for (DateTimeFormatter formatter : LOCAL_DATE_TIME_FORMATTERS) {
                        try {
                            return LocalDateTime.parse(str, formatter);
                        } catch (DateTimeParseException e) {
                            // try next
                        }
                    }
                    throw new IOException("Cannot parse LocalDateTime: " + str);
                } else if (peek == JsonToken.BEGIN_OBJECT) {
                    // Gson 默认对象格式：{"year":2025,"monthValue":7,"dayOfMonth":17,...}
                    in.beginObject();
                    int year = 0, monthValue = 1, dayOfMonth = 1, hour = 0, minute = 0, second = 0, nano = 0;
                    while (in.hasNext()) {
                        String name = in.nextName();
                        switch (name) {
                            case "year": year = in.nextInt(); break;
                            case "monthValue": monthValue = in.nextInt(); break;
                            case "month": monthValue = in.nextInt(); break;
                            case "dayOfMonth": dayOfMonth = in.nextInt(); break;
                            case "day": dayOfMonth = in.nextInt(); break;
                            case "hour": hour = in.nextInt(); break;
                            case "minute": minute = in.nextInt(); break;
                            case "second": second = in.nextInt(); break;
                            case "nano": nano = in.nextInt(); break;
                            default: in.skipValue(); break;
                        }
                    }
                    in.endObject();
                    return LocalDateTime.of(year, monthValue, dayOfMonth, hour, minute, second, nano);
                } else {
                    throw new IOException("Expected STRING or BEGIN_OBJECT but was " + peek + " for LocalDateTime");
                }
            }
        });

        // LocalDate：序列化为 ISO 字符串，反序列化兼容字符串和 Gson 默认对象格式
        builder.registerTypeAdapter(LocalDate.class, new TypeAdapter<LocalDate>() {
            @Override
            public void write(JsonWriter out, LocalDate value) throws IOException {
                if (value == null) {
                    out.nullValue();
                    return;
                }
                out.value(value.format(DateTimeFormatter.ISO_LOCAL_DATE));
            }

            @Override
            public LocalDate read(JsonReader in) throws IOException {
                JsonToken peek = in.peek();
                if (peek == JsonToken.STRING) {
                    String str = in.nextString();
                    for (DateTimeFormatter formatter : LOCAL_DATE_FORMATTERS) {
                        try {
                            return LocalDate.parse(str, formatter);
                        } catch (DateTimeParseException e) {
                            // try next
                        }
                    }
                    throw new IOException("Cannot parse LocalDate: " + str);
                } else if (peek == JsonToken.BEGIN_OBJECT) {
                    // Gson 默认对象格式：{"year":2025,"month":7,"day":17}
                    in.beginObject();
                    int year = 0, month = 1, day = 1;
                    while (in.hasNext()) {
                        String name = in.nextName();
                        switch (name) {
                            case "year": year = in.nextInt(); break;
                            case "month": month = in.nextInt(); break;
                            case "day": day = in.nextInt(); break;
                            default: in.skipValue(); break;
                        }
                    }
                    in.endObject();
                    return LocalDate.of(year, month, day);
                } else {
                    throw new IOException("Expected STRING or BEGIN_OBJECT but was " + peek + " for LocalDate");
                }
            }
        });

        // java.util.Date：序列化为 ISO 字符串，反序列化兼容字符串格式
        builder.registerTypeAdapter(Date.class, new TypeAdapter<Date>() {
            @Override
            public void write(JsonWriter out, Date value) throws IOException {
                if (value == null) {
                    out.nullValue();
                    return;
                }
                out.value(value.toInstant().toString());
            }

            @Override
            public Date read(JsonReader in) throws IOException {
                String str = in.nextString();
                for (DateTimeFormatter formatter : DATE_FORMATTERS) {
                    try {
                        return java.sql.Timestamp.valueOf(LocalDateTime.parse(str, formatter));
                    } catch (DateTimeParseException e) {
                        // try next
                    }
                }
                throw new IOException("Cannot parse Date: " + str);
            }
        });

        return builder.create();
    }
}
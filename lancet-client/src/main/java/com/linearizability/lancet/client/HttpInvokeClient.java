package com.linearizability.lancet.client;

import com.google.gson.Gson;
import com.linearizability.lancet.agent.dto.InvocationRequest;
import com.linearizability.lancet.agent.dto.InvocationResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 调用客户端
 */
public class HttpInvokeClient {

    private static final Gson gson = new Gson();

    public static InvocationResult post(String url, InvocationRequest request) {
        HttpURLConnection conn = null;
        try {
            URL requestUrl = new URL(url);
            conn = (HttpURLConnection) requestUrl.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);

            String json = gson.toJson(request);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            BufferedReader reader;
            if (responseCode >= 200 && responseCode < 300) {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            } else {
                reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
            }

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            InvocationResult result = gson.fromJson(sb.toString(), InvocationResult.class);
            if (result == null) {
                return InvocationResult.fail("Empty response from agent", 0);
            }
            return result;

        } catch (IOException e) {
            return InvocationResult.fail("HTTP error: " + e.getMessage(), 0);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}

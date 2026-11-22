package com.cethelper.server.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** SSE 测试工具：按真实 HTTP 端口调用接口，解析 {@code event:} / {@code data:} 行。 */
public final class SseClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SseClient() {
    }

    public record Event(String name, String data) {

        public JsonNode json() {
            try {
                return MAPPER.readTree(data);
            } catch (Exception e) {
                throw new IllegalStateException("SSE data 不是合法 JSON：" + data, e);
            }
        }
    }

    public record Response(int status, String contentType, String body, List<Event> events) {

        public List<String> names() {
            return events.stream().map(Event::name).toList();
        }

        public List<Event> named(String name) {
            return events.stream().filter(event -> name.equals(event.name())).toList();
        }

        public Event first(String name) {
            return events.stream().filter(event -> name.equals(event.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("缺少 SSE 事件：" + name + "，实际为 " + names()));
        }

        /** 拼接全部 delta 的 text。 */
        public String deltaText() {
            StringBuilder text = new StringBuilder();
            for (Event event : named("delta")) {
                text.append(event.json().path("text").asText(""));
            }
            return text.toString();
        }

        public List<String> followUps() {
            List<String> result = new ArrayList<>();
            JsonNode node = first("done").json().path("followUps");
            node.forEach(item -> result.add(item.asText()));
            return result;
        }
    }

    public static Response post(HttpClient client, String url, String token, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<InputStream> response =
                client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (response.statusCode() / 100 != 2) {
            try (InputStream body = response.body()) {
                return new Response(response.statusCode(), contentType,
                        new String(body.readAllBytes(), StandardCharsets.UTF_8), List.of());
            }
        }
        if (!contentType.contains("text/event-stream")) {
            try (InputStream body = response.body()) {
                return new Response(response.statusCode(), contentType,
                        new String(body.readAllBytes(), StandardCharsets.UTF_8), List.of());
            }
        }
        try (InputStream body = response.body()) {
            return new Response(response.statusCode(), contentType, "", parse(body));
        }
    }

    private static List<Event> parse(InputStream input) throws IOException {
        List<Event> events = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String eventName = null;
        StringBuilder data = new StringBuilder();
        boolean pending = false;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (pending) {
                    events.add(new Event(eventName, data.toString()));
                    eventName = null;
                    data.setLength(0);
                    pending = false;
                }
                continue;
            }
            if (line.startsWith("event:")) {
                eventName = line.substring("event:".length()).trim();
                pending = true;
            } else if (line.startsWith("data:")) {
                String value = line.substring("data:".length());
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(value);
                pending = true;
            }
            // 以 ":" 开头的注释与 retry: 行按规范忽略。
        }
        if (pending) {
            events.add(new Event(eventName, data.toString()));
        }
        return events;
    }
}

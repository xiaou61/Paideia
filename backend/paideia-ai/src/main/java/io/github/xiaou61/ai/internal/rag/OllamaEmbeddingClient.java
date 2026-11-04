package io.github.xiaou61.ai.internal.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Ollama 的 embedding 实现（WORK-072）。
 *
 * <p>只调用本地 Ollama 的 {@code /api/embed}。服务地址、模型与超时都来自配置，
 * 不在代码里写死；因此同一份构建可以在开发机、服务器或测试桩之间切换。
 */
@Component
class OllamaEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final EmbeddingProperties properties;
    private final HttpClient http;

    OllamaEmbeddingClient(EmbeddingProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10, properties.getTimeoutSeconds())))
                .build();
    }

    @Override
    public boolean enabled() {
        return properties.isEnabled();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (!enabled() || texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", properties.getModel(),
                    "input", texts);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(properties.getBaseUrl()) + "/api/embed"))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Ollama embedding HTTP " + response.statusCode());
            }
            JsonNode root = JSON.readTree(response.body());
            JsonNode embeddings = root.get("embeddings");
            if (embeddings == null || !embeddings.isArray() || embeddings.size() != texts.size()) {
                throw new IllegalStateException("Ollama embedding 返回数量与输入不一致");
            }
            List<float[]> result = new ArrayList<>(texts.size());
            for (JsonNode embedding : embeddings) {
                if (!embedding.isArray() || embedding.size() == 0) {
                    throw new IllegalStateException("Ollama embedding 返回空向量");
                }
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                result.add(vector);
            }
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ollama embedding 被中断", interrupted);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            log.warn("rag.embedding.failed model={} cause={}", properties.getModel(), failure.toString());
            throw new IllegalStateException("Ollama embedding 失败", failure);
        }
    }

    private static String trimSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://127.0.0.1:11434";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}

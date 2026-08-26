package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.agent.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.*;

@RestController
@RequestMapping("/api/models")
public class ModelController {

    private static final Logger log = LoggerFactory.getLogger(ModelController.class);

    private final Environment environment;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ModelController(Environment environment) {
        this.environment = environment;
        this.restClient = RestClient.builder().build();
        this.objectMapper = new ObjectMapper();
    }

    public record ModelInfo(String id, String name, String group) {}

    @GetMapping
    public ResponseEntity<List<ModelInfo>> list() {
        List<ModelInfo> models = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        List<ModelInfo> remote = fetchModelsFromProvider();
        for (ModelInfo m : remote) {
            if (seen.add(m.id())) models.add(m);
        }

        List<String> configured = resolveConfiguredModels();
        for (String id : configured) {
            if (id == null || id.isBlank() || !seen.add(id)) continue;
            String group = inferGroup(id);
            models.add(new ModelInfo(id, prettyName(id), group));
        }

        List<ModelInfo> defaults = defaultFallback();
        for (ModelInfo m : defaults) {
            if (seen.add(m.id())) models.add(m);
        }

        return ResponseEntity.ok(models);
    }

    private List<ModelInfo> fetchModelsFromProvider() {
        String baseUrl = environment.getProperty("spring.ai.openai.base-url",
                environment.getProperty("OPENAI_BASE_URL", "https://api.deepseek.com"));
        String apiKey = environment.getProperty("spring.ai.openai.api-key",
                environment.getProperty("OPENAI_API_KEY", ""));

        if (baseUrl == null || baseUrl.isBlank()) return List.of();

        String url = baseUrl.replaceAll("/+$", "") + "/models";
        try {
            String body = restClient.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                    .retrieve()
                    .body(String.class);

            if (body == null || body.isBlank()) return List.of();

            JsonNode root = objectMapper.readTree(body);
            JsonNode dataNode = root.has("data") ? root.get("data") : root;

            List<ModelInfo> result = new ArrayList<>();
            if (dataNode.isArray()) {
                for (JsonNode node : dataNode) {
                    String id = node.has("id") ? node.get("id").asText() : null;
                    if (id == null || id.isBlank()) continue;
                    String ownedBy = node.has("owned_by") ? node.get("owned_by").asText("") : "";
                    String group = inferGroup(id, ownedBy);
                    result.add(new ModelInfo(id, prettyName(id), group));
                }
            }
            log.info("[Model] 从 {} 获取到 {} 个模型", url, result.size());
            return result;
        } catch (Exception e) {
            log.warn("[Model] 从 {} 获取模型列表失败: {}", url, e.getMessage());
            return List.of();
        }
    }

    private List<String> resolveConfiguredModels() {
        List<String> result = new ArrayList<>();

        String[] explicit = environment.getProperty("dsh.models", String[].class);
        if (explicit != null) {
            for (String m : explicit) {
                if (m != null && !m.isBlank()) result.add(m.trim());
            }
        }

        String defaultModel = environment.getProperty("spring.ai.openai.chat.options.model",
                environment.getProperty("OPENAI_MODEL", "deepseek-chat"));
        if (defaultModel != null && !defaultModel.isBlank()) {
            result.add(defaultModel.trim());
        }

        try {
            AgentProperties props = AgentProperties.from(environment);
            for (var agent : props.getAgents().values()) {
                String m = agent.getModel();
                if (m != null && !m.isBlank()) result.add(m.trim());
            }
        } catch (Exception ignored) {
        }

        return result;
    }

    private static List<ModelInfo> defaultFallback() {
        return List.of(
                new ModelInfo("deepseek-chat", "DeepSeek Chat", "DeepSeek"),
                new ModelInfo("deepseek-reasoner", "DeepSeek Reasoner", "DeepSeek"),
                new ModelInfo("gpt-4o", "GPT-4o", "OpenAI"),
                new ModelInfo("gpt-4o-mini", "GPT-4o Mini", "OpenAI"),
                new ModelInfo("o3-mini", "o3-mini", "OpenAI"),
                new ModelInfo("claude-3.5-sonnet", "Claude 3.5 Sonnet", "Anthropic"),
                new ModelInfo("claude-3-haiku", "Claude 3 Haiku", "Anthropic")
        );
    }

    private static String inferGroup(String modelId) {
        return inferGroup(modelId, "");
    }

    private static String inferGroup(String modelId, String ownedBy) {
        String lowerId = modelId.toLowerCase();
        String lowerOwner = ownedBy.toLowerCase();
        if (lowerOwner.contains("deepseek") || lowerId.startsWith("deepseek")) return "DeepSeek";
        if (lowerOwner.contains("openai") || lowerId.startsWith("gpt") || lowerId.startsWith("o")) return "OpenAI";
        if (lowerOwner.contains("anthropic") || lowerId.startsWith("claude")) return "Anthropic";
        if (lowerOwner.contains("google") || lowerId.startsWith("gemini")) return "Google";
        if (lowerId.startsWith("ollama")) return "Ollama";
        return "Other";
    }

    private static String prettyName(String modelId) {
        String[] parts = modelId.split("/");
        String name = parts[parts.length - 1];
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }
}
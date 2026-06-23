package com.sakura.spring.ai.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakura.spring.ai.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 联网搜索工具 — 通过 Tavily API 搜索互联网获取实时信息
 */
@Component
public class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private final AgentProperties agentProperties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebSearchTool(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Search the web for current information. Returns titles, URLs, and content snippets. " +
                "Use this when you need up-to-date information, documentation, or answers to questions that require internet access.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "The search query"
                        ),
                        "max_results", Map.of(
                                "type", "integer",
                                "description", "Maximum number of results to return (default: 5, max: 10)"
                        ),
                        "search_depth", Map.of(
                                "type", "string",
                                "description", "Search depth: basic (fast) or deep (more thorough, default: basic)",
                                "enum", List.of("basic", "deep")
                        )
                ),
                "required", List.of("query")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String query = (String) args.get("query");
        if (query == null || query.isBlank()) {
            return ToolResult.error("Query is required");
        }

        int maxResults = args.containsKey("max_results")
                ? Math.min(((Number) args.get("max_results")).intValue(), 10)
                : 5;
        String searchDepth = args.containsKey("search_depth")
                ? (String) args.get("search_depth")
                : "basic";

        String apiKey = agentProperties.getWebSearch().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return ToolResult.error("Web search is not configured. Set TAVILY_API_KEY environment variable.");
        }

        String baseUrl = agentProperties.getWebSearch().getBaseUrl();

        try {
            // 构建 Tavily API 请求体
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "query", query,
                    "max_results", maxResults,
                    "search_depth", searchDepth,
                    "include_answer", true,
                    "include_raw_content", false
            ));

            String responseJson = webClient.post()
                    .uri(baseUrl + "/search")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(agentProperties.getWebSearch().getTimeoutSeconds()))
                    .block();

            return parseResponse(responseJson, maxResults);

        } catch (Exception e) {
            log.error("Web search failed: {}", e.getMessage());
            return ToolResult.error("Web search failed: " + e.getMessage());
        }
    }

    private ToolResult parseResponse(String responseJson, int maxResults) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);

            // 提取 answer（如果有的话）
            StringBuilder result = new StringBuilder();
            if (root.has("answer") && !root.get("answer").isNull()) {
                String answer = root.get("answer").asText();
                if (!answer.isBlank()) {
                    result.append("## Answer\n").append(answer).append("\n\n");
                }
            }

            // 提取搜索结果
            JsonNode results = root.get("results");
            if (results == null || !results.isArray() || results.isEmpty()) {
                return ToolResult.success("No results found for: " + (root.has("query") ? root.get("query").asText() : ""));
            }

            result.append("## Search Results\n\n");
            int count = 0;
            for (JsonNode item : results) {
                if (count >= maxResults) break;
                count++;

                String title = item.has("title") ? item.get("title").asText() : "No title";
                String url = item.has("url") ? item.get("url").asText() : "";
                String content = item.has("content") ? item.get("content").asText() : "";

                result.append("**").append(count).append(". ").append(title).append("**\n");
                result.append("URL: ").append(url).append("\n");
                if (!content.isBlank()) {
                    // 截断过长的内容
                    if (content.length() > 500) {
                        content = content.substring(0, 500) + "...";
                    }
                    result.append(content).append("\n");
                }
                result.append("\n");
            }

            return ToolResult.success(result.toString());

        } catch (Exception e) {
            log.error("Failed to parse search response: {}", e.getMessage());
            return ToolResult.error("Failed to parse search response: " + e.getMessage());
        }
    }
}

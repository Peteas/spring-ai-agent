package com.sakura.spring.ai.agent.tool;

import com.sakura.spring.ai.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 网页抓取工具 — 获取指定 URL 的内容并转为纯文本
 */
@Component
public class WebFetchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);
    private final AgentProperties agentProperties;
    private final WebClient webClient;

    // 去除 HTML 标签的正则
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    // 去除多余空白行
    private static final Pattern MULTI_NEWLINE = Pattern.compile("\\n{3,}");
    // 去除 script/style 内容
    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");

    public WebFetchTool(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public String description() {
        return "Fetch and read the content of a web page. Returns the page text content. " +
                "Use this to read documentation, articles, or any web page content. " +
                "Supports HTML pages; content is converted to plain text.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "url", Map.of(
                                "type", "string",
                                "description", "The URL to fetch (must start with http:// or https://)"
                        ),
                        "extract_mode", Map.of(
                                "type", "string",
                                "description", "Content extraction mode: text (plain text, default) or markdown (preserves structure)",
                                "enum", List.of("text", "markdown")
                        )
                ),
                "required", List.of("url")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String url = (String) args.get("url");
        if (url == null || url.isBlank()) {
            return ToolResult.error("URL is required");
        }

        // URL 安全校验
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.error("Only HTTP/HTTPS URLs are supported");
        }

        String extractMode = args.containsKey("extract_mode")
                ? (String) args.get("extract_mode")
                : "text";
        int maxSize = agentProperties.getWebFetch().getMaxContentSize();

        try {
            String html = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(agentProperties.getWebFetch().getTimeoutSeconds()))
                    .block();

            if (html == null || html.isBlank()) {
                return ToolResult.success("(empty page)");
            }

            String content = "markdown".equals(extractMode)
                    ? extractMarkdown(html)
                    : extractText(html);

            // 截断过长内容
            if (content.length() > maxSize) {
                content = content.substring(0, maxSize) + "\n\n... (content truncated at " + maxSize + " chars)";
            }

            if (content.isBlank()) {
                return ToolResult.success("(no readable content)");
            }

            return ToolResult.success("## Page Content\nURL: " + url + "\n\n" + content);

        } catch (Exception e) {
            log.error("Failed to fetch URL {}: {}", url, e.getMessage());
            return ToolResult.error("Failed to fetch URL: " + e.getMessage());
        }
    }

    /**
     * 从 HTML 提取纯文本
     */
    private String extractText(String html) {
        // 移除 script 和 style 标签及其内容
        String cleaned = SCRIPT_STYLE.matcher(html).replaceAll("");
        // 移除 HTML 标签
        cleaned = HTML_TAG.matcher(cleaned).replaceAll(" ");
        // 解码常见 HTML 实体
        cleaned = decodeHtmlEntities(cleaned);
        // 合并多余空白
        cleaned = cleaned.replaceAll("[ \\t]+", " ");
        cleaned = MULTI_NEWLINE.matcher(cleaned).replaceAll("\n\n");
        return cleaned.trim();
    }

    /**
     * 从 HTML 提取 Markdown 格式内容（保留基本结构）
     */
    private String extractMarkdown(String html) {
        String cleaned = SCRIPT_STYLE.matcher(html).replaceAll("");

        // 标题转换
        cleaned = cleaned.replaceAll("(?i)<h1[^>]*>", "\n# ");
        cleaned = cleaned.replaceAll("(?i)</h1>", "\n");
        cleaned = cleaned.replaceAll("(?i)<h2[^>]*>", "\n## ");
        cleaned = cleaned.replaceAll("(?i)</h2>", "\n");
        cleaned = cleaned.replaceAll("(?i)<h3[^>]*>", "\n### ");
        cleaned = cleaned.replaceAll("(?i)</h3>", "\n");
        cleaned = cleaned.replaceAll("(?i)<h[4-6][^>]*>", "\n#### ");
        cleaned = cleaned.replaceAll("(?i)</h[4-6]>", "\n");

        // 段落和换行
        cleaned = cleaned.replaceAll("(?i)<p[^>]*>", "\n");
        cleaned = cleaned.replaceAll("(?i)</p>", "\n");
        cleaned = cleaned.replaceAll("(?i)<br[^>]*>", "\n");

        // 列表
        cleaned = cleaned.replaceAll("(?i)<li[^>]*>", "\n- ");
        cleaned = cleaned.replaceAll("(?i)</li>", "");

        // 粗体和斜体
        cleaned = cleaned.replaceAll("(?i)<(strong|b)>", "**");
        cleaned = cleaned.replaceAll("(?i)</(strong|b)>", "**");
        cleaned = cleaned.replaceAll("(?i)<(em|i)>", "*");
        cleaned = cleaned.replaceAll("(?i)</(em|i)>", "*");

        // 链接
        cleaned = cleaned.replaceAll("(?i)<a[^>]*href=\"([^\"]+)\"[^>]*>", " [");
        cleaned = cleaned.replaceAll("(?i)</a>", "] ");

        // 代码块
        cleaned = cleaned.replaceAll("(?i)<code[^>]*>", "`");
        cleaned = cleaned.replaceAll("(?i)</code>", "`");
        cleaned = cleaned.replaceAll("(?i)<pre[^>]*>", "\n```\n");
        cleaned = cleaned.replaceAll("(?i)</pre>", "\n```\n");

        // 移除剩余 HTML 标签
        cleaned = HTML_TAG.matcher(cleaned).replaceAll("");
        cleaned = decodeHtmlEntities(cleaned);
        cleaned = MULTI_NEWLINE.matcher(cleaned).replaceAll("\n\n");
        return cleaned.trim();
    }

    private String decodeHtmlEntities(String text) {
        return text
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replace("&copy;", "©")
                .replace("&reg;", "®");
    }
}

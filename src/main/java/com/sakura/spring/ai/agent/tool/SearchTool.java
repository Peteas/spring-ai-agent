package com.sakura.spring.ai.agent.tool;

import com.sakura.spring.ai.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.FileVisitOption.FOLLOW_LINKS;

@Component
public class SearchTool implements Tool {

    private final AgentProperties agentProperties;
    private static final int MAX_WALK_DEPTH = 10;
    private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "target", ".idea", ".vscode", "build", "dist");

    public SearchTool(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
    }

    private Path workingDir() {
        return agentProperties.getWorkingDirPath();
    }

    @Override
    public String name() {
        return "search";
    }

    @Override
    public String description() {
        return "Search tool. Supports: glob (find files by name pattern like **/*.java), grep (search file content by regex pattern). Use 'action' to specify operation.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of(
                                "type", "string",
                                "description", "Search action: glob (find files by pattern) or grep (search content by regex)",
                                "enum", List.of("glob", "grep")
                        ),
                        "pattern", Map.of(
                                "type", "string",
                                "description", "For glob: file name pattern (e.g. **/*.java, src/**/*.ts). For grep: regex pattern to search in file content"
                        ),
                        "path", Map.of(
                                "type", "string",
                                "description", "Directory to search in (default: current directory)"
                        ),
                        "glob_filter", Map.of(
                                "type", "string",
                                "description", "For grep only: glob pattern to filter files (e.g. *.java)"
                        ),
                        "case_insensitive", Map.of(
                                "type", "boolean",
                                "description", "For grep only: case insensitive search (default: false)"
                        ),
                        "max_results", Map.of(
                                "type", "integer",
                                "description", "Maximum number of results to return (default: 50)"
                        )
                ),
                "required", List.of("action", "pattern")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String action = (String) args.get("action");
        String pattern = (String) args.get("pattern");
        String path = (String) args.getOrDefault("path", ".");
        int maxResults = args.containsKey("max_results") ? ((Number) args.get("max_results")).intValue() : 50;

        return switch (action) {
            case "glob" -> globSearch(pattern, path, maxResults);
            case "grep" -> grepSearch(pattern, path, args, maxResults);
            default -> ToolResult.error("Unknown action: " + action);
        };
    }

    private ToolResult globSearch(String pattern, String searchPath, int maxResults) {
        Path basePath = Paths.get(searchPath).normalize();
        Path resolvedPath = workingDir().resolve(basePath).normalize();
        if (!resolvedPath.startsWith(workingDir())) {
            return ToolResult.error("Access denied: path outside working directory");
        }
        if (!Files.exists(resolvedPath)) {
            return ToolResult.error("Search path not found: " + resolvedPath);
        }

        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<String> matches = new ArrayList<>();

            try (Stream<Path> walk = Files.walk(resolvedPath, MAX_WALK_DEPTH)) {
                walk.filter(p -> !shouldSkipDir(p))
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            Path relativePath = resolvedPath.relativize(p);
                            return matcher.matches(relativePath) || matcher.matches(p.getFileName());
                        })
                        .limit(maxResults)
                        .forEach(p -> matches.add(resolvedPath.relativize(p).toString()));
            }

            if (matches.isEmpty()) {
                return ToolResult.success("No files found matching pattern: " + pattern);
            }

            return ToolResult.success("Found " + matches.size() + " files:\n" + String.join("\n", matches));
        } catch (IOException e) {
            return ToolResult.error("Search failed: " + e.getMessage());
        }
    }

    private ToolResult grepSearch(String regex, String searchPath, Map<String, Object> args, int maxResults) {
        Path basePath = Paths.get(searchPath).normalize();
        Path resolvedPath = workingDir().resolve(basePath).normalize();
        if (!resolvedPath.startsWith(workingDir())) {
            return ToolResult.error("Access denied: path outside working directory");
        }
        if (!Files.exists(resolvedPath)) {
            return ToolResult.error("Search path not found: " + resolvedPath);
        }

        String globFilter = (String) args.get("glob_filter");
        boolean caseInsensitive = Boolean.TRUE.equals(args.get("case_insensitive"));

        int flags = caseInsensitive ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex, flags);
        } catch (Exception e) {
            return ToolResult.error("Invalid regex pattern: " + e.getMessage());
        }

        PathMatcher fileFilter = globFilter != null
                ? FileSystems.getDefault().getPathMatcher("glob:" + globFilter)
                : null;

        List<String> results = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(resolvedPath, MAX_WALK_DEPTH)) {
            walk.filter(p -> !shouldSkipDir(p))
                    .filter(Files::isRegularFile)
                    .filter(p -> !isBinaryFile(p))
                    .filter(p -> fileFilter == null || fileFilter.matches(p.getFileName()))
                    .forEach(p -> {
                        if (results.size() >= maxResults) return;
                        try (var reader = Files.newBufferedReader(p)) {
                            String relative = resolvedPath.relativize(p).toString();
                            String line;
                            int lineNum = 0;
                            while ((line = reader.readLine()) != null && results.size() < maxResults) {
                                lineNum++;
                                if (pattern.matcher(line).find()) {
                                    results.add(relative + ":" + lineNum + ": " + line.trim());
                                }
                            }
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            return ToolResult.error("Search failed: " + e.getMessage());
        }

        if (results.isEmpty()) {
            return ToolResult.success("No matches found for pattern: " + regex);
        }

        return ToolResult.success("Found " + results.size() + " matches:\n" + String.join("\n", results));
    }

    private boolean shouldSkipDir(Path path) {
        String name = path.getFileName().toString();
        return SKIP_DIRS.contains(name);
    }

    private boolean isBinaryFile(Path path) {
        try (var is = Files.newInputStream(path)) {
            byte[] buf = new byte[8192];
            int len = is.read(buf);
            if (len < 0) return false;
            for (int i = 0; i < len; i++) {
                if (buf[i] == 0) return true;
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }
}

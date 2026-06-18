package com.sakura.spring.ai.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakura.spring.ai.agent.mapper.ChatLogMapper;
import com.sakura.spring.ai.agent.mapper.EvaluationDailyMetricMapper;
import com.sakura.spring.ai.agent.model.ChatLog;
import com.sakura.spring.ai.agent.model.EvaluationDailyMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    private final ChatLogMapper chatLogMapper;
    private final EvaluationDailyMetricMapper dailyMetricMapper;
    private final ObjectMapper objectMapper;

    public EvaluationService(ChatLogMapper chatLogMapper, EvaluationDailyMetricMapper dailyMetricMapper) {
        this.chatLogMapper = chatLogMapper;
        this.dailyMetricMapper = dailyMetricMapper;
        this.objectMapper = new ObjectMapper();
    }

    // ==================== DTO ====================

    public record MetricsSummary(
            int totalRequests, int successCount, int errorCount, double successRate,
            LatencyStats latency, TokenStats tokens, ToolUsageStats toolUsage,
            ResponseQualityStats responseQuality, RatingStats ratings
    ) {}

    public record LatencyStats(double avgMs, long p50Ms, long p95Ms, long p99Ms) {}

    public record TokenStats(double avgPromptTokens, double avgCompletionTokens,
                             double avgTotalTokens, double promptCompletionRatio) {}

    public record ToolUsageStats(double avgToolCallsPerRequest, List<ToolFrequency> topTools) {}

    public record ToolFrequency(String name, long count) {}

    public record ResponseQualityStats(double avgResponseLength, double avgRoundCount) {}

    public record RatingStats(Double avgUserRating, Double avgQualityScore) {}

    public record DailyTrendEntry(String date, int totalRequests, double successRate,
                                  double avgLatencyMs, double avgTotalTokens, double avgToolCalls) {}

    // ==================== Public API ====================

    public MetricsSummary getGlobalMetrics(LocalDate from, LocalDate to) {
        List<ChatLog> logs = queryLogs(from, to, null, null);
        return computeMetrics(logs);
    }

    public MetricsSummary getUserMetrics(Long userId, LocalDate from, LocalDate to) {
        List<ChatLog> logs = queryLogs(from, to, userId, null);
        return computeMetrics(logs);
    }

    public MetricsSummary getSessionMetrics(String sessionId) {
        List<ChatLog> logs = queryLogs(null, null, null, sessionId);
        return computeMetrics(logs);
    }

    public List<DailyTrendEntry> getDailyTrends(String scopeType, String scopeId, LocalDate from, LocalDate to) {
        LambdaQueryWrapper<EvaluationDailyMetric> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EvaluationDailyMetric::getScopeType, scopeType);
        if (scopeId != null) {
            wrapper.eq(EvaluationDailyMetric::getScopeId, scopeId);
        } else {
            wrapper.isNull(EvaluationDailyMetric::getScopeId);
        }
        wrapper.between(EvaluationDailyMetric::getMetricDate, from, to);
        wrapper.orderByAsc(EvaluationDailyMetric::getMetricDate);

        return dailyMetricMapper.selectList(wrapper).stream()
                .map(m -> new DailyTrendEntry(
                        m.getMetricDate().toString(),
                        m.getTotalRequests(),
                        m.getSuccessRate(),
                        m.getAvgLatencyMs(),
                        m.getAvgTotalTokens(),
                        m.getAvgToolCalls()
                ))
                .toList();
    }

    public void rateChatLog(Long logId, Long userId, Integer rating) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }
        ChatLog log = chatLogMapper.selectById(logId);
        if (log == null) {
            throw new IllegalArgumentException("Chat log not found: " + logId);
        }
        if (log.getUserId() != null && !log.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Not your chat log");
        }
        log.setUserRating(rating);
        chatLogMapper.updateById(log);
    }

    @Async
    public void computeDailySnapshot(LocalDate date) {
        log.info("Computing daily snapshot for {}", date);
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();

        // Global snapshot
        List<ChatLog> allLogs = queryLogsByCreatedAt(dayStart, dayEnd);
        if (!allLogs.isEmpty()) {
            saveSnapshot(date, "global", null, allLogs);
        }

        // Per-user snapshots
            Map<Long, List<ChatLog>> byUser = allLogs.stream()
                    .filter(l -> l.getUserId() != null)
                    .collect(Collectors.groupingBy(ChatLog::getUserId));
        for (Map.Entry<Long, List<ChatLog>> entry : byUser.entrySet()) {
            saveSnapshot(date, "user", String.valueOf(entry.getKey()), entry.getValue());
        }

        // Per-session snapshots
        Map<String, List<ChatLog>> bySession = allLogs.stream()
                .collect(Collectors.groupingBy(ChatLog::getSessionId));
        for (Map.Entry<String, List<ChatLog>> entry : bySession.entrySet()) {
            saveSnapshot(date, "session", entry.getKey(), entry.getValue());
        }

        log.info("Daily snapshot for {} completed: {} total logs, {} users, {} sessions",
                date, allLogs.size(), byUser.size(), bySession.size());
    }

    @Scheduled(cron = "0 5 1 * * *")
    public void scheduledDailySnapshot() {
        computeDailySnapshot(LocalDate.now().minusDays(1));
    }

    // ==================== Internal ====================

    private List<ChatLog> queryLogs(LocalDate from, LocalDate to, Long userId, String sessionId) {
        LambdaQueryWrapper<ChatLog> wrapper = new LambdaQueryWrapper<>();
        if (from != null && to != null) {
            wrapper.between(ChatLog::getCreatedAt, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        }
        if (userId != null) {
            wrapper.eq(ChatLog::getUserId, userId);
        }
        if (sessionId != null) {
            wrapper.eq(ChatLog::getSessionId, sessionId);
        }
        wrapper.orderByDesc(ChatLog::getCreatedAt);
        return chatLogMapper.selectList(wrapper);
    }

    private List<ChatLog> queryLogsByCreatedAt(LocalDateTime from, LocalDateTime to) {
        LambdaQueryWrapper<ChatLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.between(ChatLog::getCreatedAt, from, to);
        return chatLogMapper.selectList(wrapper);
    }

    private MetricsSummary computeMetrics(List<ChatLog> logs) {
        if (logs.isEmpty()) {
            return new MetricsSummary(0, 0, 0, 0,
                    new LatencyStats(0, 0, 0, 0),
                    new TokenStats(0, 0, 0, 0),
                    new ToolUsageStats(0, List.of()),
                    new ResponseQualityStats(0, 0),
                    new RatingStats(null, null));
        }

        int total = logs.size();
        long errorCount = logs.stream().filter(l -> l.getError() != null).count();
        double successRate = (double) (total - errorCount) / total;

        // Latency
        List<Long> latencies = logs.stream()
                .map(l -> l.getLatencyMs() != null ? l.getLatencyMs() : 0L)
                .sorted()
                .toList();
        double avgLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        LatencyStats latencyStats = new LatencyStats(avgLatency,
                percentile(latencies, 50), percentile(latencies, 95), percentile(latencies, 99));

        // Tokens
        double avgPrompt = logs.stream().mapToInt(l -> nvl(l.getPromptTokens())).average().orElse(0);
        double avgCompletion = logs.stream().mapToInt(l -> nvl(l.getCompletionTokens())).average().orElse(0);
        double avgTotal = logs.stream().mapToInt(l -> nvl(l.getTotalTokens())).average().orElse(0);
        double tokenRatio = avgCompletion > 0 ? avgPrompt / avgCompletion : 0;
        TokenStats tokenStats = new TokenStats(avgPrompt, avgCompletion, avgTotal, tokenRatio);

        // Tool usage
        double avgToolCalls = logs.stream().mapToInt(l -> nvl(l.getToolCallCount())).average().orElse(0);
        Map<String, Long> toolFreq = new HashMap<>();
        for (ChatLog log : logs) {
            if (log.getToolsUsed() != null && !"[]".equals(log.getToolsUsed())) {
                try {
                    List<String> tools = objectMapper.readValue(log.getToolsUsed(), new TypeReference<>() {});
                    tools.forEach(t -> toolFreq.merge(t, 1L, Long::sum));
                } catch (JsonProcessingException e) {
                    // skip malformed entry
                }
            }
        }
        List<ToolFrequency> topTools = toolFreq.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> new ToolFrequency(e.getKey(), e.getValue()))
                .toList();
        ToolUsageStats toolStats = new ToolUsageStats(avgToolCalls, topTools);

        // Response quality
        double avgResponseLength = logs.stream()
                .filter(l -> l.getAssistantMessage() != null)
                .mapToInt(l -> l.getAssistantMessage().length())
                .average().orElse(0);
        double avgRoundCount = logs.stream().mapToInt(l -> nvl(l.getRoundCount())).average().orElse(0);
        ResponseQualityStats qualityStats = new ResponseQualityStats(avgResponseLength, avgRoundCount);

        // Ratings
        Double avgRating = logs.stream()
                .filter(l -> l.getUserRating() != null)
                .mapToInt(ChatLog::getUserRating)
                .average().orElse(Double.NaN);
        Double avgQuality = logs.stream()
                .filter(l -> l.getQualityScore() != null)
                .mapToDouble(ChatLog::getQualityScore)
                .average().orElse(Double.NaN);
        RatingStats ratingStats = new RatingStats(
                avgRating.isNaN() ? null : avgRating,
                avgQuality.isNaN() ? null : avgQuality);

        return new MetricsSummary(total, (int) (total - errorCount), (int) errorCount, successRate,
                latencyStats, tokenStats, toolStats, qualityStats, ratingStats);
    }

    private void saveSnapshot(LocalDate date, String scopeType, String scopeId, List<ChatLog> logs) {
        MetricsSummary metrics = computeMetrics(logs);

        // Upsert: delete existing then insert
        LambdaQueryWrapper<EvaluationDailyMetric> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(EvaluationDailyMetric::getMetricDate, date)
                .eq(EvaluationDailyMetric::getScopeType, scopeType);
        if (scopeId != null) {
            deleteWrapper.eq(EvaluationDailyMetric::getScopeId, scopeId);
        } else {
            deleteWrapper.isNull(EvaluationDailyMetric::getScopeId);
        }
        dailyMetricMapper.delete(deleteWrapper);

        EvaluationDailyMetric snapshot = new EvaluationDailyMetric();
        snapshot.setMetricDate(date);
        snapshot.setScopeType(scopeType);
        snapshot.setScopeId(scopeId);
        snapshot.setTotalRequests(metrics.totalRequests());
        snapshot.setSuccessCount(metrics.successCount());
        snapshot.setErrorCount(metrics.errorCount());
        snapshot.setSuccessRate(metrics.successRate());
        snapshot.setAvgLatencyMs(metrics.latency().avgMs());
        snapshot.setP50LatencyMs(metrics.latency().p50Ms());
        snapshot.setP95LatencyMs(metrics.latency().p95Ms());
        snapshot.setP99LatencyMs(metrics.latency().p99Ms());
        snapshot.setAvgPromptTokens(metrics.tokens().avgPromptTokens());
        snapshot.setAvgCompletionTokens(metrics.tokens().avgCompletionTokens());
        snapshot.setAvgTotalTokens(metrics.tokens().avgTotalTokens());
        snapshot.setAvgTokenRatio(metrics.tokens().promptCompletionRatio());
        snapshot.setAvgToolCalls(metrics.toolUsage().avgToolCallsPerRequest());
        snapshot.setAvgRoundCount(metrics.responseQuality().avgRoundCount());
        snapshot.setAvgResponseLength(metrics.responseQuality().avgResponseLength());
        snapshot.setAvgQualityScore(metrics.ratings().avgQualityScore());
        snapshot.setAvgUserRating(metrics.ratings().avgUserRating());
        try {
            snapshot.setTopTools(objectMapper.writeValueAsString(metrics.toolUsage().topTools()));
        } catch (JsonProcessingException e) {
            snapshot.setTopTools("[]");
        }
        snapshot.setCreatedAt(LocalDateTime.now());
        dailyMetricMapper.insert(snapshot);
    }

    private long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private int nvl(Integer value) {
        return value != null ? value : 0;
    }
}

package com.sakura.spring.ai.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("evaluation_daily_metrics")
public class EvaluationDailyMetric {

    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate metricDate;
    private String scopeType;       // "global", "user", "session"
    private String scopeId;
    private Integer totalRequests;
    private Integer successCount;
    private Integer errorCount;
    private Double successRate;
    private Double avgLatencyMs;
    private Long p50LatencyMs;
    private Long p95LatencyMs;
    private Long p99LatencyMs;
    private Double avgPromptTokens;
    private Double avgCompletionTokens;
    private Double avgTotalTokens;
    private Double avgTokenRatio;
    private Double avgToolCalls;
    private Double avgRoundCount;
    private Double avgResponseLength;
    private Double avgQualityScore;
    private Double avgUserRating;
    private String topTools;
    private LocalDateTime createdAt;

    public EvaluationDailyMetric() {}
}

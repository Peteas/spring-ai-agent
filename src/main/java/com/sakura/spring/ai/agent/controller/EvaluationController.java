package com.sakura.spring.ai.agent.controller;

import com.sakura.spring.ai.agent.security.JwtUserDetails;
import com.sakura.spring.ai.agent.service.EvaluationService;
import com.sakura.spring.ai.agent.service.UserService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/evaluation")
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final UserService userService;

    public EvaluationController(EvaluationService evaluationService, UserService userService) {
        this.evaluationService = evaluationService;
        this.userService = userService;
    }

    // ==================== DTOs ====================

    public record RateRequest(Long logId, Integer rating) {}

    // ==================== Endpoints ====================

    @GetMapping("/global")
    public ResponseEntity<?> getGlobalMetrics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal JwtUserDetails userDetails) {
        LocalDate effectiveFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        EvaluationService.MetricsSummary summary = evaluationService.getGlobalMetrics(effectiveFrom, effectiveTo);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @GetMapping("/global/trends")
    public ResponseEntity<?> getGlobalTrends(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal JwtUserDetails userDetails) {
        LocalDate effectiveFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        List<EvaluationService.DailyTrendEntry> trends =
                evaluationService.getDailyTrends("global", null, effectiveFrom, effectiveTo);
        return ResponseEntity.ok(ApiResponse.success(trends));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<?> getUserMetrics(
            @PathVariable Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal JwtUserDetails userDetails) {
        if (!userDetails.getUserId().equals(userId)) {
            return ResponseEntity.status(403).body(ApiResponse.error(403, "Access denied"));
        }
        LocalDate effectiveFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        EvaluationService.MetricsSummary summary = evaluationService.getUserMetrics(userId, effectiveFrom, effectiveTo);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @GetMapping("/user/{userId}/trends")
    public ResponseEntity<?> getUserTrends(
            @PathVariable Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal JwtUserDetails userDetails) {
        if (!userDetails.getUserId().equals(userId)) {
            return ResponseEntity.status(403).body(ApiResponse.error(403, "Access denied"));
        }
        LocalDate effectiveFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        List<EvaluationService.DailyTrendEntry> trends =
                evaluationService.getDailyTrends("user", String.valueOf(userId), effectiveFrom, effectiveTo);
        return ResponseEntity.ok(ApiResponse.success(trends));
    }

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<?> getSessionMetrics(
            @PathVariable String sessionId,
            @AuthenticationPrincipal JwtUserDetails userDetails) {
        if (userDetails != null && !userService.isSessionOwner(userDetails.getUserId(), sessionId)) {
            return ResponseEntity.status(403).body(ApiResponse.error(403, "Access denied"));
        }
        EvaluationService.MetricsSummary summary = evaluationService.getSessionMetrics(sessionId);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @PostMapping("/rate")
    public ResponseEntity<?> rateLog(
            @RequestBody RateRequest request,
            @AuthenticationPrincipal JwtUserDetails userDetails) {
        try {
            evaluationService.rateChatLog(request.logId(), userDetails.getUserId(), request.rating());
            return ResponseEntity.ok(ApiResponse.success());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/snapshot/trigger")
    public ResponseEntity<?> triggerSnapshot(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal JwtUserDetails userDetails) {
        LocalDate targetDate = date != null ? date : LocalDate.now().minusDays(1);
        evaluationService.computeDailySnapshot(targetDate);
        return ResponseEntity.ok(ApiResponse.success(Map.of("date", targetDate.toString())));
    }
}

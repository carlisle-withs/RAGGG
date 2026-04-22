package com.rag.api.rest.dashboard;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Dashboard 数据接口（stub 实现，返回空数据）
 * 路径：/api/v1/admin/dashboard
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
public class DashboardController {

    // ---- overview ----

    public record Kpi(double value, double delta, double deltaPct) {}

    public record Overview(
            String window,
            String compareWindow,
            long updatedAt,
            OverviewKpis kpis
    ) {}

    public record OverviewKpis(
            Kpi totalUsers,
            Kpi activeUsers,
            Kpi totalSessions,
            Kpi sessions24h,
            Kpi totalMessages,
            Kpi messages24h
    ) {}

    @GetMapping("/overview")
    public ResponseEntity<Overview> overview(@RequestParam(defaultValue = "24h") String window) {
        return ResponseEntity.ok(new Overview(
                window,
                "24h",
                System.currentTimeMillis(),
                new OverviewKpis(
                        new Kpi(0, 0, 0),
                        new Kpi(0, 0, 0),
                        new Kpi(0, 0, 0),
                        new Kpi(0, 0, 0),
                        new Kpi(0, 0, 0),
                        new Kpi(0, 0, 0)
                )
        ));
    }

    // ---- performance ----

    public record Performance(
            String window,
            double avgLatencyMs,
            double p95LatencyMs,
            double successRate,
            double errorRate,
            double noDocRate,
            double slowRate
    ) {}

    @GetMapping("/performance")
    public ResponseEntity<Performance> performance(@RequestParam(defaultValue = "24h") String window) {
        return ResponseEntity.ok(new Performance(window, 0, 0, 0, 0, 0, 0));
    }

    // ---- trends ----

    public record TrendPoint(long ts, double value) {}
    public record TrendSeries(String name, List<TrendPoint> data) {}
    public record Trends(String metric, String window, String granularity, List<TrendSeries> series) {}

    @GetMapping("/trends")
    public ResponseEntity<Trends> trends(
            @RequestParam String metric,
            @RequestParam(defaultValue = "7d") String window,
            @RequestParam(defaultValue = "day") String granularity) {
        return ResponseEntity.ok(new Trends(metric, window, granularity, Collections.emptyList()));
    }
}

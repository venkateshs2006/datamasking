package com.enterprise.seedm.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SystemMetricsServiceTest {

    @Test
    void testGetSystemMetrics_ReturnsValidMetrics() {
        SystemMetricsService service = new SystemMetricsService();
        Map<String, Object> metrics = service.getSystemMetrics();

        assertNotNull(metrics);

        // Verify Available Processors (cores) > 0
        assertTrue(metrics.containsKey("availableProcessors"));
        int cores = (Integer) metrics.get("availableProcessors");
        assertTrue(cores > 0, "Available processors must be greater than 0");

        // Verify Heap metrics
        assertTrue(metrics.containsKey("heapUsedMB"));
        assertTrue(metrics.containsKey("heapMaxMB"));
        long heapUsed = ((Number) metrics.get("heapUsedMB")).longValue();
        long heapMax = ((Number) metrics.get("heapMaxMB")).longValue();
        assertTrue(heapUsed >= 0, "Used heap must be non-negative");
        assertTrue(heapMax > 0, "Max heap must be positive");

        // Verify CPU load average
        assertTrue(metrics.containsKey("systemLoadAverage"));
        double load = ((Number) metrics.get("systemLoadAverage")).doubleValue();
        assertTrue(load >= 0.0, "System load average must be non-negative");

        // Verify CPU usage percent
        assertTrue(metrics.containsKey("cpuUsagePercent"));
        double cpuPercent = ((Number) metrics.get("cpuUsagePercent")).doubleValue();
        assertTrue(cpuPercent >= 0.0 && cpuPercent <= 100.0, "CPU percent must be between 0 and 100");
    }
}

package com.enterprise.seedm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.HashMap;
import java.util.Map;

/**
 * Service providing real-time system health and JVM resource metrics.
 */
@Service
@Slf4j
public class SystemMetricsService {

    public Map<String, Object> getSystemMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        try {
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;

            long heapUsedMB = usedMemory / (1024 * 1024);
            long heapMaxMB = maxMemory / (1024 * 1024);
            long heapTotalMB = totalMemory / (1024 * 1024);
            long heapFreeMB = freeMemory / (1024 * 1024);

            int availableProcessors = runtime.availableProcessors();

            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            double systemLoadAverage = osBean.getSystemLoadAverage();
            double cpuPercent = 0.0;

            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                double processCpu = sunOsBean.getCpuLoad();
                if (processCpu >= 0) {
                    cpuPercent = processCpu * 100.0;
                } else {
                    double sysCpu = sunOsBean.getSystemCpuLoad();
                    if (sysCpu >= 0) {
                        cpuPercent = sysCpu * 100.0;
                    }
                }
            }

            // In virtualized environments or Windows, systemLoadAverage may return -1.0
            if (systemLoadAverage < 0) {
                systemLoadAverage = (cpuPercent / 100.0) * availableProcessors;
            }

            metrics.put("heapUsedMB", Math.max(0, heapUsedMB));
            metrics.put("heapMaxMB", Math.max(1, heapMaxMB));
            metrics.put("heapTotalMB", Math.max(0, heapTotalMB));
            metrics.put("heapFreeMB", Math.max(0, heapFreeMB));
            metrics.put("availableProcessors", availableProcessors);
            metrics.put("systemLoadAverage", Math.max(0.0, Math.round(systemLoadAverage * 100.0) / 100.0));
            metrics.put("cpuUsagePercent", Math.max(0.0, Math.min(100.0, Math.round(cpuPercent * 100.0) / 100.0)));
        } catch (Exception e) {
            log.error("Failed to collect system metrics", e);
            metrics.put("heapUsedMB", 0);
            metrics.put("heapMaxMB", 1);
            metrics.put("availableProcessors", Runtime.getRuntime().availableProcessors());
            metrics.put("systemLoadAverage", 0.0);
            metrics.put("cpuUsagePercent", 0.0);
        }

        return metrics;
    }
}

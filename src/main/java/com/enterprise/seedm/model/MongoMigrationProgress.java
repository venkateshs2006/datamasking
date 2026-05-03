package com.enterprise.seedm.model;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Data
public class MongoMigrationProgress {
    private String status = "PENDING";
    private AtomicInteger totalCollections = new AtomicInteger(0);
    private AtomicInteger processedCollections = new AtomicInteger(0);
    private String errorMessage;
    private Long startTime;
    private Long endTime;

    public void incrementProcessedCollections() {
        this.processedCollections.incrementAndGet();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("status", status);
        map.put("totalCollections", totalCollections.get());
        map.put("processedCollections", processedCollections.get());
        map.put("errorMessage", errorMessage);
        map.put("startTime", startTime);
        map.put("endTime", endTime);
        return map;
    }
}

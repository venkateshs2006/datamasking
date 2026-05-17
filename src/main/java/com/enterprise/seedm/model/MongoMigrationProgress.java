package com.enterprise.seedm.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Data
public class MongoMigrationProgress {
    private String status = "PENDING";
    private AtomicInteger totalCollections = new AtomicInteger(0);
    private AtomicInteger processedCollections = new AtomicInteger(0);
    private String errorMessage;
    private Long startTime;
    private Long endTime;
    private List<Map<String, Object>> tableProgress = new CopyOnWriteArrayList<>();

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
        map.put("tableProgress", new ArrayList<>(tableProgress));
        return map;
    }
}
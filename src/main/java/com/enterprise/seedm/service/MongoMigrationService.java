package com.enterprise.seedm.service;

import com.enterprise.seedm.model.JobRequest;
import com.enterprise.seedm.model.MongoMigrationProgress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class MongoMigrationService {

    private final DbConnectionService dbConnectionService;
    private final MongoDiscoveryService mongoDiscoveryService;
    private final Map<String, MongoMigrationProgress> progressMap = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public void migrate(JobRequest jobRequest, String executionId) {
        MongoMigrationProgress progress = progressMap.computeIfAbsent(executionId, k -> new MongoMigrationProgress());
        progress.setStatus("RUNNING");
        progress.setStartTime(System.currentTimeMillis());

        log.info("Starting MongoDB migration for job: {}", jobRequest.getMigrationName());

        Map<String, Object> configDetails = (Map<String, Object>) jobRequest.getConfigDetails();
        Map<String, Object> sourceConfig = (Map<String, Object>) configDetails.get("source");
        Map<String, Object> destConfig = (Map<String, Object>) configDetails.get("dest");
        Map<String, Object> rulesConfig = (Map<String, Object>) configDetails.get("rules");

        Long sourceConnectionId = Long.parseLong(sourceConfig.get("id").toString());
        String sourceDatabaseName = sourceConfig.get("schema").toString();
        Long destConnectionId = Long.parseLong(destConfig.get("id").toString());
        String destDatabaseName = destConfig.get("schema").toString();

        try (MongoClient sourceClient = getClient(sourceConnectionId);
             MongoClient destClient = getClient(destConnectionId)) {

            MongoDatabase sourceDatabase = sourceClient.getDatabase(sourceDatabaseName);
            MongoDatabase destDatabase = destClient.getDatabase(destDatabaseName);

            List<String> collections = (List<String>) rulesConfig.get("targetTables");
            progress.getTotalCollections().set(collections.size());

            for (String collectionName : collections) {
                log.info("Migrating collection: {}", collectionName);
                MongoCollection<Document> sourceCollection = sourceDatabase.getCollection(collectionName);
                MongoCollection<Document> destCollection = destDatabase.getCollection(collectionName);

                // Clear destination collection before migration
                destCollection.deleteMany(new Document());

                // This is a simple copy, masking will be added later
                sourceCollection.find().forEach(destCollection::insertOne);
                progress.incrementProcessedCollections();
            }
            progress.setStatus("COMPLETED");
            progress.setEndTime(System.currentTimeMillis());
            log.info("MongoDB migration completed successfully.");
        } catch (Exception e) {
            log.error("Error during MongoDB migration", e);
            progress.setStatus("FAILED");
            progress.setErrorMessage(e.getMessage());
            progress.setEndTime(System.currentTimeMillis());
            throw new RuntimeException("Error during MongoDB migration", e);
        }
    }

    public Map<String, Object> getProgress(String executionId) {
        MongoMigrationProgress progress = progressMap.get(executionId);
        if (progress == null) {
            return Map.of("status", "NOT_FOUND");
        }
        return progress.toMap();
    }

    public List<Map<String, Object>> getAllExecutions() {
        List<Map<String, Object>> executions = new ArrayList<>();
        for (Map.Entry<String, MongoMigrationProgress> entry : progressMap.entrySet()) {
            Map<String, Object> execMap = new HashMap<>();
            execMap.put("id", entry.getKey());
            execMap.put("status", entry.getValue().getStatus());
            execMap.put("startTime", entry.getValue().getStartTime());
            executions.add(execMap);
        }
        return executions;
    }

    private MongoClient getClient(Long connectionId) {
        return MongoClients.create(dbConnectionService.getConnection(connectionId).getUrl());
    }
}

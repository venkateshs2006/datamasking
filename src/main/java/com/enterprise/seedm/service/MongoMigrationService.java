package com.enterprise.seedm.service;

import com.enterprise.seedm.model.JobRequest;
import com.enterprise.seedm.model.MigrationJob;
import com.enterprise.seedm.model.MongoMigrationDetails;
import com.enterprise.seedm.model.MongoMigrationProgress;
import com.enterprise.seedm.repository.MigrationJobRepository;
import com.enterprise.seedm.repository.MongoMigrationDetailsRepository;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class MongoMigrationService {

    private final DbConnectionService dbConnectionService;
    private final MongoDiscoveryService mongoDiscoveryService;
    private final Map<String, MongoMigrationProgress> progressMap = new ConcurrentHashMap<>();
    private final MigrationJobRepository migrationJobRepository;
    private final MongoMigrationDetailsRepository mongoDetailsRepository;
    
    @SuppressWarnings("unchecked")
    public void migrate(JobRequest jobRequest, String executionId) {
        MongoMigrationProgress progress = progressMap.computeIfAbsent(executionId, k -> new MongoMigrationProgress());
        progress.setStatus("RUNNING");
        progress.setStartTime(System.currentTimeMillis());

        // Persist initial job state to DB
        MigrationJob job = new MigrationJob();
        job.setJobId(executionId);
        job.setProjectId(jobRequest.getMigrationName());
        job.setSourceDbType("MONGO");
        job.setTargetDbType("MONGO"); // Just tracking as MONGO
        job.setJobStatus("RUNNING");
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        migrationJobRepository.save(job);

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
            if (collections == null) collections = new ArrayList<>();
            progress.getTotalCollections().set(collections.size());

            for (String collectionName : collections) {
                log.info("Migrating collection: {}", collectionName);
                Map<String, Object> colProgress = new ConcurrentHashMap<>();
                colProgress.put("tableName", collectionName);
                colProgress.put("readCount", 0L);
                colProgress.put("writeCount", 0L);
                colProgress.put("status", "STARTED");
                progress.getTableProgress().add(colProgress);
                
                // Initialize Collection tracking row in DB immediately
                saveCollectionMigration(executionId, collectionName, 0, 0, 0, "STARTED", "Processing");

                try {
                    MongoCollection<Document> sourceCollection = sourceDatabase.getCollection(collectionName);
                    MongoCollection<Document> destCollection = destDatabase.getCollection(collectionName);

                    // Clear destination collection before migration
                    destCollection.deleteMany(new Document());

                    long count = 0;
                    List<Document> batch = new ArrayList<>();
                    for (Document doc : sourceCollection.find()) {
                        batch.add(doc);
                        count++;
                        if (batch.size() >= 1000) {
                            destCollection.insertMany(batch);
                            batch.clear();
                            colProgress.put("readCount", count);
                            colProgress.put("writeCount", count);
                            
                            // Periodically persist progress back to database (Chunk level commit)
                            saveCollectionMigration(executionId, collectionName, count, count, 0, "RUNNING", "Processing in chunks");
                        }
                    }
                    if (!batch.isEmpty()) {
                        destCollection.insertMany(batch);
                        colProgress.put("readCount", count);
                        colProgress.put("writeCount", count);
                    }

                    colProgress.put("status", "COMPLETED");
                    progress.incrementProcessedCollections();
                    
                    // Final persist collection details to DB
                    saveCollectionMigration(executionId, collectionName, count, count, 0, "COMPLETED", "Success");
                    
                } catch (Exception ex) {
                    log.error("Failed migrating collection {}", collectionName, ex);
                    colProgress.put("status", "FAILED");
                    colProgress.put("error", ex.getMessage());
                    
                    // Persist failed collection details to DB
                    saveCollectionMigration(executionId, collectionName, 0, 0, 1, "FAILED", ex.getMessage());
                }
            }
            progress.setStatus("COMPLETED");
            progress.setEndTime(System.currentTimeMillis());
            
            // Update Job Status in DB
            Optional<MigrationJob> existingJob = migrationJobRepository.findByJobId(executionId);
            if(existingJob.isPresent()) {
                MigrationJob j = existingJob.get();
                j.setJobStatus("COMPLETED");
                j.setUpdatedAt(LocalDateTime.now());
                migrationJobRepository.save(j);
            }
            
            log.info("MongoDB migration completed successfully.");
        } catch (Exception e) {
            log.error("Error during MongoDB migration", e);
            progress.setStatus("FAILED");
            progress.setErrorMessage(e.getMessage());
            progress.setEndTime(System.currentTimeMillis());
            
            // Update Job Status in DB to FAILED
            Optional<MigrationJob> existingJob = migrationJobRepository.findByJobId(executionId);
            if(existingJob.isPresent()) {
                MigrationJob j = existingJob.get();
                j.setJobStatus("FAILED");
                j.setUpdatedAt(LocalDateTime.now());
                migrationJobRepository.save(j);
            }
        }
    }

    public Map<String, Object> getProgress(String executionId) {
        MongoMigrationProgress progress = progressMap.get(executionId);
        if (progress == null) {
            // Attempt to retrieve from DB if not in memory (e.g. after a restart)
            Optional<MigrationJob> jobOpt = migrationJobRepository.findByJobId(executionId);
            if (jobOpt.isPresent()) {
                MigrationJob job = jobOpt.get();
                List<MongoMigrationDetails> details = mongoDetailsRepository.findByJobId(executionId);
                
                Map<String, Object> map = new HashMap<>();
                map.put("status", job.getJobStatus());
                map.put("startTime", job.getCreatedAt() != null ? job.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : 0);
                map.put("endTime", job.getUpdatedAt() != null ? job.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : 0);
                
                map.put("totalCollections", details.size());
                map.put("processedCollections", details.stream().filter(d -> "COMPLETED".equals(d.getStatus())).count());
                
                List<Map<String, Object>> tableProgress = new ArrayList<>();
                for (MongoMigrationDetails d : details) {
                    Map<String, Object> tp = new HashMap<>();
                    tp.put("tableName", d.getCollectionName());
                    tp.put("readCount", d.getSourceCount());
                    tp.put("writeCount", d.getMigratedCount());
                    tp.put("status", d.getStatus());
                    if (d.getRemarks() != null && d.getStatus().equals("FAILED")) {
                        tp.put("error", d.getRemarks());
                    }
                    tableProgress.add(tp);
                }
                map.put("tableProgress", tableProgress);
                map.put("executionId", executionId);
                
                return map;
            }
            return Map.of("status", "NOT_FOUND");
        }
        return progress.toMap();
    }

    public List<Map<String, Object>> getAllExecutions() {
        List<Map<String, Object>> executions = new ArrayList<>();
        Set<String> dbJobIds = new HashSet<>();
        
        // 1. Get historical jobs from DB
        List<MigrationJob> dbJobs = migrationJobRepository.findBySourceDbType("MONGO");
        for (MigrationJob job : dbJobs) {
            dbJobIds.add(job.getJobId());
            Map<String, Object> execMap = new HashMap<>();
            execMap.put("id", job.getJobId());
            execMap.put("status", job.getJobStatus());
            execMap.put("startTime", job.getCreatedAt() != null ? job.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : 0);
            executions.add(execMap);
        }
        
        // 2. Combine with any actively running jobs in memory (just in case)
        for (Map.Entry<String, MongoMigrationProgress> entry : progressMap.entrySet()) {
            if (!dbJobIds.contains(entry.getKey())) {
                Map<String, Object> execMap = new HashMap<>();
                execMap.put("id", entry.getKey());
                execMap.put("status", entry.getValue().getStatus());
                execMap.put("startTime", entry.getValue().getStartTime());
                executions.add(execMap);
            }
        }
        
        return executions;
    }

    private MongoClient getClient(Long connectionId) {
        return MongoClients.create(dbConnectionService.getConnection(connectionId).getUrl());
    } 

    public String createMongoJob(String projectId) {
        String jobId = "MONGO-" + UUID.randomUUID();

        MigrationJob job = new MigrationJob();
        job.setJobId(jobId);
        job.setProjectId(projectId);
        job.setSourceDbType("MONGO");
        job.setTargetDbType("TARGET_DB");
        job.setJobStatus("CREATED");
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());

        migrationJobRepository.save(job);
        return jobId;
    }

    public void saveCollectionMigration(String jobId, String collectionName,
                                        long sourceCount, long migratedCount,
                                        long failedCount, String status, String remarks) {
                                            
        MongoMigrationDetails details = mongoDetailsRepository.findByJobIdAndCollectionName(jobId, collectionName)
                .orElse(new MongoMigrationDetails());

        details.setJobId(jobId);
        details.setCollectionName(collectionName);
        details.setSourceCount(sourceCount);
        details.setMigratedCount(migratedCount);
        details.setFailedCount(failedCount);
        details.setStatus(status);
        details.setRemarks(remarks);
        
        if (details.getStartedAt() == null) {
            details.setStartedAt(LocalDateTime.now());
        }
        if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
            details.setCompletedAt(LocalDateTime.now());
        }

        mongoDetailsRepository.save(details);
    }

    public void updateJobStatus(String jobId, String status) {
        MigrationJob job = migrationJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found"));
        job.setJobStatus(status);
        job.setUpdatedAt(LocalDateTime.now());
        migrationJobRepository.save(job);
    }
}
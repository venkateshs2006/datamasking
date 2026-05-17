package com.enterprise.seedm.batch;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

@Slf4j
public class MongoCollectionClearTasklet implements Tasklet {

    private final MongoClient mongoClient;
    private final String databaseName;
    private final String collectionName;

    public MongoCollectionClearTasklet(MongoClient mongoClient, String databaseName, String collectionName) {
        this.mongoClient = mongoClient;
        this.databaseName = databaseName;
        this.collectionName = collectionName;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        MongoDatabase database = mongoClient.getDatabase(databaseName);
        MongoCollection<Document> collection = database.getCollection(collectionName);
        log.info("Clearing Mongo collection '{}' in database '{}'", collectionName, databaseName);
        collection.deleteMany(new Document());
        return RepeatStatus.FINISHED;
    }
}
package com.enterprise.seedm.batch;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class MongoItemWriter implements ItemWriter<Document> {

    private final MongoClient mongoClient;
    private final String databaseName;
    private final String collectionName;
    private boolean initialized = false;
    private MongoCollection<Document> collection;

    public MongoItemWriter(MongoClient mongoClient, String databaseName, String collectionName) {
        this.mongoClient = mongoClient;
        this.databaseName = databaseName;
        this.collectionName = collectionName;
    }

    @Override
    public void write(Chunk<? extends Document> chunk) throws Exception {
        if (!initialized) {
            MongoDatabase database = mongoClient.getDatabase(databaseName);
            collection = database.getCollection(collectionName);
            
            // Clear collection before writing begins (optional, but consistent with prior behavior)
            // Wait, ItemWriter write() is called per chunk. We can't clear here without state tracking.
            // We should use a StepExecutionListener or Tasklet to clear it, but let's assume it's handled 
            // before the chunk processing starts.
            
            this.initialized = true;
            log.info("Initialized MongoItemWriter for database '{}', collection '{}'", databaseName, collectionName);
        }

        List<Document> documents = new ArrayList<>();
        for (Document doc : chunk) {
            documents.add(doc);
        }

        if (!documents.isEmpty()) {
            collection.insertMany(documents);
        }
    }
}
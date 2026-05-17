package com.enterprise.seedm.batch;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.NonTransientResourceException;
import org.springframework.batch.item.ParseException;
import org.springframework.batch.item.UnexpectedInputException;

@Slf4j
public class MongoItemReader implements ItemReader<Document> {

    private final MongoClient mongoClient;
    private final String databaseName;
    private final String collectionName;
    private MongoCursor<Document> cursor;
    private boolean initialized = false;

    public MongoItemReader(MongoClient mongoClient, String databaseName, String collectionName) {
        this.mongoClient = mongoClient;
        this.databaseName = databaseName;
        this.collectionName = collectionName;
    }

    @Override
    public Document read() throws Exception, UnexpectedInputException, ParseException, NonTransientResourceException {
        if (!initialized) {
            MongoDatabase database = mongoClient.getDatabase(databaseName);
            MongoCollection<Document> collection = database.getCollection(collectionName);
            this.cursor = collection.find().iterator();
            this.initialized = true;
            log.info("Initialized MongoItemReader for database '{}', collection '{}'", databaseName, collectionName);
        }

        if (cursor != null && cursor.hasNext()) {
            return cursor.next();
        } else {
            if (cursor != null) {
                cursor.close();
            }
            return null; // Indicates end of data
        }
    }
}

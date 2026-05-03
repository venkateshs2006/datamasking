package com.enterprise.seedm.service;

import com.enterprise.seedm.model.DbConnection;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class MongoDiscoveryService {

    private final DbConnectionService dbConnectionService;

    private MongoClient getClient(Long connectionId) {
        DbConnection connection = dbConnectionService.getConnection(connectionId);
        if (connection == null) {
            throw new IllegalArgumentException("Connection not found");
        }
        return MongoClients.create(connection.getUrl());
    }

    public List<String> getDatabases(Long connectionId) {
        try (MongoClient client = getClient(connectionId)) {
            List<String> databases = new ArrayList<>();
            client.listDatabaseNames().into(databases);
            return databases;
        } catch (Exception e) {
            log.error("Error getting databases", e);
            throw new RuntimeException("Error getting databases", e);
        }
    }

    public List<String> getCollections(Long connectionId, String databaseName) {
        try (MongoClient client = getClient(connectionId)) {
            MongoDatabase database = client.getDatabase(databaseName);
            List<String> collections = new ArrayList<>();
            database.listCollectionNames().into(collections);
            return collections;
        } catch (Exception e) {
            log.error("Error getting collections", e);
            throw new RuntimeException("Error getting collections", e);
        }
    }

    public List<String> getFields(Long connectionId, String databaseName, String collectionName) {
        try (MongoClient client = getClient(connectionId)) {
            MongoDatabase database = client.getDatabase(databaseName);
            MongoCollection<Document> collection = database.getCollection(collectionName);
            Set<String> fields = new HashSet<>();
            for (Document doc : collection.find().limit(10)) {
                extractFields("", doc, fields);
            }
            return new ArrayList<>(fields);
        } catch (Exception e) {
            log.error("Error getting fields", e);
            throw new RuntimeException("Error getting fields", e);
        }
    }

    private void extractFields(String prefix, Document doc, Set<String> fields) {
        for (String key : doc.keySet()) {
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = doc.get(key);
            if (value instanceof Document) {
                fields.add(fullKey); // also add the parent
                extractFields(fullKey, (Document) value, fields);
            } else {
                fields.add(fullKey);
            }
        }
    }
}

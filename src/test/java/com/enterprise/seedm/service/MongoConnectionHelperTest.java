package com.enterprise.seedm.service;

import com.enterprise.seedm.model.DbConnection;
import com.mongodb.client.MongoClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class MongoConnectionHelperTest {

    private DbConnectionService dbConnectionService;
    private VaultService vaultService;
    private MongoConnectionHelper helper;

    @BeforeEach
    void setUp() {
        dbConnectionService = mock(DbConnectionService.class);
        vaultService = mock(VaultService.class);
        helper = new MongoConnectionHelper(dbConnectionService, vaultService);
    }

    @Test
    void testCreateClientWithDirectCredentials() {
        DbConnection conn = new DbConnection();
        conn.setId(1L);
        conn.setUrl("mongodb://localhost:27017");
        conn.setUsername("admin");
        conn.setPassword("Mongo@123456");

        when(dbConnectionService.getConnection(1L)).thenReturn(conn);

        try (MongoClient client = helper.createClient(1L)) {
            assertNotNull(client);
        }
    }

    @Test
    void testCreateClientWithEmbeddedUriCredentials() {
        DbConnection conn = new DbConnection();
        conn.setId(2L);
        conn.setUrl("mongodb://admin:Mongo%40123456@localhost:27017/?authSource=admin");

        when(dbConnectionService.getConnection(2L)).thenReturn(conn);

        try (MongoClient client = helper.createClient(2L)) {
            assertNotNull(client);
        }
    }
}

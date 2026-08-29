package com.enterprise.seedm.controller;

import com.enterprise.seedm.service.MongoDiscoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/mongo")
@RequiredArgsConstructor
public class MongoDiscoveryController {

    private final MongoDiscoveryService mongoDiscoveryService;

    @GetMapping("/databases/{connectionId}")
    public ResponseEntity<List<String>> getDatabasesByPath(@PathVariable Long connectionId) {
        return ResponseEntity.ok(mongoDiscoveryService.getDatabases(connectionId));
    }

    @GetMapping("/databases")
    public ResponseEntity<List<String>> getDatabases(@RequestParam Long connectionId) {
        return ResponseEntity.ok(mongoDiscoveryService.getDatabases(connectionId));
    }

    @GetMapping("/collections/{connectionId}/{database}")
    public ResponseEntity<List<String>> getCollectionsByPath(
            @PathVariable Long connectionId,
            @PathVariable String database) {
        return ResponseEntity.ok(mongoDiscoveryService.getCollections(connectionId, database));
    }

    @GetMapping("/collections")
    public ResponseEntity<List<String>> getCollections(
            @RequestParam Long connectionId,
            @RequestParam String database) {
        return ResponseEntity.ok(mongoDiscoveryService.getCollections(connectionId, database));
    }

    @GetMapping("/fields/{connectionId}/{database}/{collection}")
    public ResponseEntity<List<String>> getFieldsByPath(
            @PathVariable Long connectionId,
            @PathVariable String database,
            @PathVariable String collection) {
        return ResponseEntity.ok(mongoDiscoveryService.getFields(connectionId, database, collection));
    }

    @GetMapping("/fields")
    public ResponseEntity<List<String>> getFields(
            @RequestParam Long connectionId,
            @RequestParam String database,
            @RequestParam String collection) {
        return ResponseEntity.ok(mongoDiscoveryService.getFields(connectionId, database, collection));
    }
}


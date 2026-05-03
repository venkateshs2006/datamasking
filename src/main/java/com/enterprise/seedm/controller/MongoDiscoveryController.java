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

    @GetMapping("/databases")
    public ResponseEntity<List<String>> getDatabases(@RequestParam Long connectionId) {
        return ResponseEntity.ok(mongoDiscoveryService.getDatabases(connectionId));
    }

    @GetMapping("/collections")
    public ResponseEntity<List<String>> getCollections(@RequestParam Long connectionId, @RequestParam String database) {
        return ResponseEntity.ok(mongoDiscoveryService.getCollections(connectionId, database));
    }

    @GetMapping("/fields")
    public ResponseEntity<List<String>> getFields(@RequestParam Long connectionId, @RequestParam String database, @RequestParam String collection) {
        return ResponseEntity.ok(mongoDiscoveryService.getFields(connectionId, database, collection));
    }
}

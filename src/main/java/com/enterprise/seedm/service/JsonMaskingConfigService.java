package com.enterprise.seedm.service;

import com.enterprise.seedm.model.JsonMigrationConfig;
import org.springframework.stereotype.Service;

@Service
public class JsonMaskingConfigService {

    private JsonMigrationConfig currentConfig = new JsonMigrationConfig();

    public JsonMigrationConfig getConfig() {
        return currentConfig;
    }

    public void updateConfig(JsonMigrationConfig newConfig) {
        this.currentConfig = newConfig;
    }
}

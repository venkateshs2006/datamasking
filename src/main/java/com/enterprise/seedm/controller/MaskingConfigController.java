package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.JsonMigrationConfig;
import com.enterprise.seedm.model.MaskingConfig;
import com.enterprise.seedm.service.JsonMaskingConfigService;
import com.enterprise.seedm.service.MaskingConfigService;
import com.enterprise.seedm.service.TableDiscoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class MaskingConfigController {

    private final MaskingConfigService maskingConfigService;
    private final JsonMaskingConfigService jsonMaskingConfigService;
    private final TableDiscoveryService tableDiscoveryService;

    @GetMapping
    public MaskingConfig getConfig() {
        return maskingConfigService.getConfig();
    }

    @GetMapping("/random-salt")
    public Map<String, String> getRandomSalt() {
        String randomSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return Map.of("saltPrefix", randomSuffix);
    }

    @PostMapping
    public void updateConfig(@RequestBody MaskingConfig config) {
        if (config.getMaskingKey() != null && config.getMaskingKey().length() == 16) {
            // Valid FPH key format provided by user
        } else {
            // Keep existing key if an invalid/empty one is sent
            config.setMaskingKey(maskingConfigService.getConfig().getMaskingKey());
        }
        maskingConfigService.updateConfig(config);
    }
    
    @PostMapping("/target-tables")
    public void updateTargetTables(@RequestBody List<String> tables) {
        MaskingConfig current = maskingConfigService.getConfig();
        current.setTargetTables(tables);
        maskingConfigService.updateConfig(current);
    }

    @GetMapping("/tables")
    public List<String> getTables() throws SQLException {
        return tableDiscoveryService.discoverTables();
    }

    @GetMapping("/columns/{tableName}")
    public List<String> getColumns(@PathVariable String tableName) {
        return tableDiscoveryService.getTableColumns(tableName);
    }

    @PostMapping("/json")
    public void updateJsonConfig(@RequestBody JsonMigrationConfig config) {
        jsonMaskingConfigService.updateConfig(config);
    }
}

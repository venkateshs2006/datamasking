package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.MaskingConfig;
import com.enterprise.seedm.service.MaskingConfigService;
import com.enterprise.seedm.service.TableDiscoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.List;

@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class MaskingConfigController {

    private final MaskingConfigService maskingConfigService;
    private final TableDiscoveryService tableDiscoveryService;

    @GetMapping
    public MaskingConfig getConfig() {
        return maskingConfigService.getConfig();
    }

    @PostMapping
    public void updateConfig(@RequestBody MaskingConfig config) {
        maskingConfigService.updateConfig(config);
    }

    @GetMapping("/tables")
    public List<String> getTables() throws SQLException {
        return tableDiscoveryService.discoverTables();
    }

    @GetMapping("/columns/{tableName}")
    public List<String> getColumns(@PathVariable String tableName) {
        return tableDiscoveryService.getTableColumns(tableName);
    }
}

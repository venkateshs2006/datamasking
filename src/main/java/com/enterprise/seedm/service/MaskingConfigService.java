package com.enterprise.seedm.service;

import com.enterprise.seedm.model.MaskingConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MaskingConfigService {

    private MaskingConfig currentConfig;

    public MaskingConfigService(
            @Value("${seedm.migration.masking-columns:}") List<String> initialMaskingColumns,
            @Value("${seedm.migration.masking-constraints:}") List<String> initialConstraintColumns,
            @Value("${seedm.migration.masking-key:DefaultSecretKey123}") String initialKey) {
        
        this.currentConfig = new MaskingConfig();
        this.currentConfig.setMaskingColumns(initialMaskingColumns);
        this.currentConfig.setConstraintColumns(initialConstraintColumns);
        this.currentConfig.setMaskingKey(initialKey);
    }

    public MaskingConfig getConfig() {
        return currentConfig;
    }

    public void updateConfig(MaskingConfig newConfig) {
        this.currentConfig = newConfig;
    }
}

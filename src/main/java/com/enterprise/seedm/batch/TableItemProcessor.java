package com.enterprise.seedm.batch;

import com.enterprise.seedm.service.DataMaskingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;

import java.util.Map;

/**
 * Table Item Processor
 * Processes each row, applying data masking if configured
 */
@Slf4j
public class TableItemProcessor implements ItemProcessor<Map<String, Object>, Map<String, Object>> {

    private final String tableName;
    private final DataMaskingService dataMaskingService;

    public TableItemProcessor(String tableName, DataMaskingService dataMaskingService) {
        this.tableName = tableName;
        this.dataMaskingService = dataMaskingService;
    }

    @Override
    public Map<String, Object> process(Map<String, Object> item) throws Exception {
        return dataMaskingService.maskData(tableName, item);
    }
}

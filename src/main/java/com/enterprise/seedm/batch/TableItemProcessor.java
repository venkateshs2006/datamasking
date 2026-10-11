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
    private final java.util.List<String> maskingColumns;
    private final java.util.List<String> constraintColumns;
    private final java.util.List<String> partialMaskingColumns;

    private final java.util.List<String> nullifyColumns;
    private final java.util.Map<String, String> defaultValues;
    private final java.util.Map<String, String> customFormats;

    public TableItemProcessor(String tableName, DataMaskingService dataMaskingService) {
        this(tableName, dataMaskingService, null, null, null, null, null, null);
    }

    public TableItemProcessor(String tableName, DataMaskingService dataMaskingService,
                              java.util.List<String> maskingColumns,
                              java.util.List<String> constraintColumns,
                              java.util.List<String> partialMaskingColumns) {
        this(tableName, dataMaskingService, maskingColumns, constraintColumns, partialMaskingColumns, null, null, null);
    }

    public TableItemProcessor(String tableName, DataMaskingService dataMaskingService,
                              java.util.List<String> maskingColumns,
                              java.util.List<String> constraintColumns,
                              java.util.List<String> partialMaskingColumns,
                              java.util.List<String> nullifyColumns,
                              java.util.Map<String, String> defaultValues) {
        this(tableName, dataMaskingService, maskingColumns, constraintColumns, partialMaskingColumns, nullifyColumns, defaultValues, null);
    }

    public TableItemProcessor(String tableName, DataMaskingService dataMaskingService,
                              java.util.List<String> maskingColumns,
                              java.util.List<String> constraintColumns,
                              java.util.List<String> partialMaskingColumns,
                              java.util.List<String> nullifyColumns,
                              java.util.Map<String, String> defaultValues,
                              java.util.Map<String, String> customFormats) {
        this.tableName = tableName;
        this.dataMaskingService = dataMaskingService;
        this.maskingColumns = maskingColumns;
        this.constraintColumns = constraintColumns;
        this.partialMaskingColumns = partialMaskingColumns;
        this.nullifyColumns = nullifyColumns;
        this.defaultValues = defaultValues;
        this.customFormats = customFormats;
    }

    @Override
    public Map<String, Object> process(Map<String, Object> item) throws Exception {
        if (maskingColumns != null || constraintColumns != null || partialMaskingColumns != null || nullifyColumns != null || defaultValues != null || customFormats != null) {
            return dataMaskingService.maskDataWithRules(tableName, item, maskingColumns, constraintColumns, partialMaskingColumns, nullifyColumns, defaultValues, customFormats);
        }
        return dataMaskingService.maskData(tableName, item);
    }
}

package com.enterprise.seedm.batch;

import com.enterprise.seedm.service.DataMaskingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.batch.item.ItemProcessor;

import java.util.List;
import java.util.Map;

@Slf4j
public class MongoItemProcessor implements ItemProcessor<Document, Document> {

    private final String collectionName;
    private final DataMaskingService dataMaskingService;
    private final java.util.List<String> maskingColumns;
    private final java.util.List<String> constraintColumns;
    private final java.util.List<String> partialMaskingColumns;

    private final java.util.List<String> nullifyColumns;
    private final java.util.Map<String, String> defaultValues;
    private final java.util.Map<String, String> customFormats;

    public MongoItemProcessor(String collectionName, DataMaskingService dataMaskingService) {
        this(collectionName, dataMaskingService, null, null, null, null, null, null);
    }

    public MongoItemProcessor(String collectionName, DataMaskingService dataMaskingService,
                              java.util.List<String> maskingColumns,
                              java.util.List<String> constraintColumns,
                              java.util.List<String> partialMaskingColumns) {
        this(collectionName, dataMaskingService, maskingColumns, constraintColumns, partialMaskingColumns, null, null, null);
    }

    public MongoItemProcessor(String collectionName, DataMaskingService dataMaskingService,
                              java.util.List<String> maskingColumns,
                              java.util.List<String> constraintColumns,
                              java.util.List<String> partialMaskingColumns,
                              java.util.List<String> nullifyColumns,
                              java.util.Map<String, String> defaultValues) {
        this(collectionName, dataMaskingService, maskingColumns, constraintColumns, partialMaskingColumns, nullifyColumns, defaultValues, null);
    }

    public MongoItemProcessor(String collectionName, DataMaskingService dataMaskingService,
                              java.util.List<String> maskingColumns,
                              java.util.List<String> constraintColumns,
                              java.util.List<String> partialMaskingColumns,
                              java.util.List<String> nullifyColumns,
                              java.util.Map<String, String> defaultValues,
                              java.util.Map<String, String> customFormats) {
        this.collectionName = collectionName;
        this.dataMaskingService = dataMaskingService;
        this.maskingColumns = maskingColumns;
        this.constraintColumns = constraintColumns;
        this.partialMaskingColumns = partialMaskingColumns;
        this.nullifyColumns = nullifyColumns;
        this.defaultValues = defaultValues;
        this.customFormats = customFormats;
    }

    @Override
    public Document process(Document item) throws Exception {
        Map<String, Object> masked = (maskingColumns != null || constraintColumns != null || partialMaskingColumns != null || nullifyColumns != null || defaultValues != null || customFormats != null)
                ? dataMaskingService.maskDataWithRules(collectionName, item, maskingColumns, constraintColumns, partialMaskingColumns, nullifyColumns, defaultValues, customFormats)
                : dataMaskingService.maskNoSqlData(collectionName, item);
        return new Document(masked);
    }
}
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

    public MongoItemProcessor(String collectionName, DataMaskingService dataMaskingService) {
        this(collectionName, dataMaskingService, null, null, null);
    }

    public MongoItemProcessor(String collectionName, DataMaskingService dataMaskingService,
                              java.util.List<String> maskingColumns,
                              java.util.List<String> constraintColumns,
                              java.util.List<String> partialMaskingColumns) {
        this.collectionName = collectionName;
        this.dataMaskingService = dataMaskingService;
        this.maskingColumns = maskingColumns;
        this.constraintColumns = constraintColumns;
        this.partialMaskingColumns = partialMaskingColumns;
    }

    @Override
    public Document process(Document item) throws Exception {
        Map<String, Object> masked = (maskingColumns != null || constraintColumns != null || partialMaskingColumns != null)
                ? dataMaskingService.maskDataWithRules(collectionName, item, maskingColumns, constraintColumns, partialMaskingColumns)
                : dataMaskingService.maskNoSqlData(collectionName, item);
        return new Document(masked);
    }
}
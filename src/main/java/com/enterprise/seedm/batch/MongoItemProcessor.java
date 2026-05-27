package com.enterprise.seedm.batch;

import com.enterprise.seedm.service.DataMaskingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.batch.item.ItemProcessor;

@Slf4j
@RequiredArgsConstructor
public class MongoItemProcessor implements ItemProcessor<Document, Document> {

    private final String collectionName;
    private final DataMaskingService dataMaskingService;

    @Override
    public Document process(Document item) throws Exception {
        // DataMaskingService expects a Map<String, Object>
        // Document extends LinkedHashMap<String, Object>, so it can be passed directly
        return new Document(dataMaskingService.maskNoSqlData(collectionName, item));
    }
}
package coms.seedm.batch;

import com.seedm.config.RulesConfig;
import com.seedm.masking.ColumnClassifier;
import com.seedm.masking.MaskingService;
import com.seedm.model.ColumnMetadata;
import com.seedm.model.ColumnRole;
import com.seedm.model.TableMetadata;
import org.springframework.batch.item.ItemProcessor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies rules.maskingColumns / partialMaskingColumns / constraintColumns to
 * a single row (represented as an ordered column-name -> value map).
 */
public class MaskingItemProcessor implements ItemProcessor<Map<String, Object>, Map<String, Object>> {

    private final String tableName;
    private final TableMetadata tableMetadata;
    private final RulesConfig rules;
    private final ColumnClassifier classifier;
    private final MaskingService maskingService;

    public MaskingItemProcessor(String tableName,
                                 TableMetadata tableMetadata,
                                 RulesConfig rules,
                                 ColumnClassifier classifier,
                                 MaskingService maskingService) {
        this.tableName = tableName;
        this.tableMetadata = tableMetadata;
        this.rules = rules;
        this.classifier = classifier;
        this.maskingService = maskingService;
    }

    @Override
    public Map<String, Object> process(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row.size());

        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String columnName = entry.getKey();
            Object value = entry.getValue();

            ColumnRole role = classifier.classify(rules, tableName, columnName);
            ColumnMetadata columnMeta = tableMetadata.column(columnName);

            Object outputValue = switch (role) {
                case CONSTRAINT, PLAIN -> value;
                case MASK -> columnMeta != null
                        ? maskingService.fullyMask(value, tableName, columnMeta, rules.getMaskingKey())
                        : value;
                case PARTIAL_MASK -> columnMeta != null
                        ? maskingService.partiallyMask(value, tableName, columnMeta, rules.getMaskingKey())
                        : value;
            };

            result.put(columnName, outputValue);
        }

        return result;
    }
}

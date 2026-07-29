package coms.seedm.masking;

import com.seedm.config.RulesConfig;
import com.seedm.model.ColumnRole;
import org.springframework.stereotype.Component;

@Component
public class ColumnClassifier {

    public ColumnRole classify(RulesConfig rules, String table, String column) {
        String key = (table + "." + column).toLowerCase();

        if (rules.constraintColumnsLower().contains(key)) {
            return ColumnRole.CONSTRAINT;
        }
        if (rules.partialMaskingColumnsLower().contains(key)) {
            return ColumnRole.PARTIAL_MASK;
        }
        if (rules.maskingColumnsLower().contains(key)) {
            return ColumnRole.MASK;
        }
        return ColumnRole.PLAIN;
    }
}

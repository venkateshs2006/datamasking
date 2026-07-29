package coms.seedm.masking;

import com.seedm.model.ColumnMetadata;
import com.seedm.util.HmacSeedUtil;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component
public class BooleanMaskingStrategy implements MaskingStrategy {

    @Override
    public Object mask(Object value, String table, ColumnMetadata meta, String maskingKey) {
        if (value == null) {
            return null;
        }
        long seed = HmacSeedUtil.seedFor(maskingKey, table, meta.getName(), value.toString());
        Random random = new Random(seed);
        return random.nextBoolean();
    }
}

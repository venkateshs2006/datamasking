package coms.seedm.masking;

import com.seedm.model.ColumnMetadata;
import com.seedm.util.HmacSeedUtil;
import org.springframework.stereotype.Component;

/**
 * Deterministically replaces a string with a synthetic-looking value of similar
 * shape. Same (key, table, column, value) always yields the same output, so
 * masked exports stay internally consistent (e.g. two rows with the same
 * customer name mask to the same pseudonym).
 */
@Component
public class StringMaskingStrategy implements MaskingStrategy {

    @Override
    public Object mask(Object value, String table, ColumnMetadata meta, String maskingKey) {
        if (value == null) {
            return null;
        }
        String original = value.toString();
        if (original.isEmpty()) {
            return original;
        }

        String hex = HmacSeedUtil.hmacHex(maskingKey, table + "." + meta.getName() + "=" + original);
        String token = hex.substring(0, Math.min(10, hex.length())).toUpperCase();

        String columnLower = meta.getName().toLowerCase();
        String prefix;
        if (columnLower.contains("first_name")) {
            prefix = "FName";
        } else if (columnLower.contains("last_name")) {
            prefix = "LName";
        } else if (columnLower.contains("name")) {
            prefix = "Name";
        } else if (columnLower.contains("reason")) {
            prefix = "Reason";
        } else if (columnLower.contains("status")) {
            prefix = "Status";
        } else if (columnLower.contains("category")) {
            prefix = "Category";
        } else if (columnLower.contains("country")) {
            prefix = "Country";
        } else if (columnLower.contains("currency")) {
            prefix = "CUR";
        } else if (columnLower.contains("phone")) {
            return maskPhone(hex);
        } else {
            prefix = "Masked";
        }

        // keep it a plausible length relative to the original so column width
        // constraints (varchar(n)) are unlikely to be violated
        String candidate = prefix + "_" + token;
        if (candidate.length() > original.length() && original.length() >= 4) {
            candidate = candidate.substring(0, Math.max(4, original.length()));
        }
        return candidate;
    }

    private String maskPhone(String hex) {
        StringBuilder digits = new StringBuilder();
        for (char c : hex.toCharArray()) {
            if (digits.length() == 10) break;
            int d = Character.digit(c, 16);
            if (d >= 0) digits.append(d % 10);
        }
        while (digits.length() < 10) digits.append('0');
        return digits.toString();
    }
}

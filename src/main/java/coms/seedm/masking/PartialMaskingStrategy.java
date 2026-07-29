package coms.seedm.masking;

import com.seedm.model.ColumnMetadata;
import org.springframework.stereotype.Component;

/**
 * Used for columns in rules.partialMaskingColumns, where some of the value is
 * kept visible for usability (e.g. "card_number_masked", "email").
 */
@Component
public class PartialMaskingStrategy implements MaskingStrategy {

    @Override
    public Object mask(Object value, String table, ColumnMetadata meta, String maskingKey) {
        if (value == null) {
            return null;
        }
        String original = value.toString();
        if (original.isEmpty()) {
            return original;
        }

        String columnLower = meta.getName().toLowerCase();
        if (columnLower.contains("email")) {
            return maskEmail(original);
        }
        if (columnLower.contains("card")) {
            return maskKeepingLastN(original, 4);
        }
        return maskGeneric(original);
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return maskGeneric(email);
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        String maskedLocal;
        if (local.length() <= 2) {
            maskedLocal = "*".repeat(local.length());
        } else {
            maskedLocal = local.charAt(0) + "*".repeat(local.length() - 2) + local.charAt(local.length() - 1);
        }
        return maskedLocal + domain;
    }

    /** Keeps the last N visible characters (digits or otherwise), masks everything before with 'X'. */
    private String maskKeepingLastN(String value, int n) {
        if (value.length() <= n) {
            return "X".repeat(value.length());
        }
        StringBuilder sb = new StringBuilder();
        int visibleFrom = value.length() - n;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (i >= visibleFrom || !Character.isLetterOrDigit(c)) {
                sb.append(c);
            } else {
                sb.append('X');
            }
        }
        return sb.toString();
    }

    private String maskGeneric(String value) {
        if (value.length() <= 4) {
            return "*".repeat(value.length());
        }
        String first = value.substring(0, 2);
        String last = value.substring(value.length() - 2);
        return first + "*".repeat(value.length() - 4) + last;
    }
}

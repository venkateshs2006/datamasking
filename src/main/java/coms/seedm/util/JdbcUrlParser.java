package coms.seedm.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sample payload's "url" field is a human label plus the real JDBC URL in
 * parentheses, e.g.  "Finanace DB (jdbc:postgresql://localhost:5432/BankDB)".
 * This pulls the real "jdbc:..." URL out, or returns the input unchanged if it
 * is already a bare JDBC URL.
 */
public final class JdbcUrlParser {

    private static final Pattern PAREN_PATTERN = Pattern.compile("\\(([^)]+)\\)");

    private JdbcUrlParser() {
    }

    public static String extractJdbcUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("source.url must not be blank");
        }
        String trimmed = rawUrl.trim();

        if (trimmed.startsWith("jdbc:")) {
            return trimmed;
        }

        Matcher matcher = PAREN_PATTERN.matcher(trimmed);
        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            if (candidate.startsWith("jdbc:")) {
                return candidate;
            }
        }

        throw new IllegalArgumentException(
                "Could not find a jdbc:... URL inside source.url='" + rawUrl + "'. " +
                "Expected either a bare JDBC URL or a label with the URL in parentheses.");
    }
}

package com.enterprise.seedm.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Standard RFC-4180 compliant CSV utility for parsing and writing CSV lines and files.
 * Handles quoting, embedded commas, double-quote escaping (""), and newlines.
 */
public final class CsvUtils {

    private CsvUtils() {
    }

    /**
     * Parses a single CSV line into a list of field values.
     */
    public static List<String> parseLine(String line) {
        List<String> result = new ArrayList<>();
        if (line == null) {
            return result;
        }

        StringBuilder curVal = new StringBuilder();
        boolean inQuotes = false;
        int len = line.length();

        for (int i = 0; i < len; i++) {
            char ch = line.charAt(i);

            if (inQuotes) {
                if (ch == '\"') {
                    if (i + 1 < len && line.charAt(i + 1) == '\"') {
                        // Escaped quote: "" -> "
                        curVal.append('\"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    curVal.append(ch);
                }
            } else {
                if (ch == '\"') {
                    inQuotes = true;
                } else if (ch == ',') {
                    result.add(curVal.toString().trim());
                    curVal.setLength(0);
                } else {
                    curVal.append(ch);
                }
            }
        }
        result.add(curVal.toString().trim());
        return result;
    }

    /**
     * Formats a list of field values into an RFC-4180 compliant CSV line.
     */
    public static String formatLine(List<?> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            Object v = values.get(i);
            String str = (v != null) ? v.toString() : "";
            boolean needsQuotes = str.contains(",") || str.contains("\"") || str.contains("\n") || str.contains("\r");
            if (needsQuotes) {
                sb.append('\"').append(str.replace("\"", "\"\"")).append('\"');
            } else {
                sb.append(str);
            }
        }
        return sb.toString();
    }

    /**
     * Reads all rows from a CSV reader.
     */
    public static List<List<String>> readRows(Reader reader) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(reader)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    rows.add(parseLine(line));
                }
            }
        }
        return rows;
    }

    /**
     * Reads the header line from a CSV file.
     */
    public static List<String> readHeader(Path csvFile) throws IOException {
        if (!Files.exists(csvFile)) return new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    return parseLine(line);
                }
            }
        }
        return new ArrayList<>();
    }

    /**
     * Counts the number of data rows in a CSV file (excluding header).
     */
    public static long countDataRows(Path csvFile) throws IOException {
        if (!Files.exists(csvFile)) return 0;
        long count = 0;
        try (BufferedReader br = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8)) {
            String line = br.readLine(); // Skip header
            if (line == null) return 0;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    count++;
                }
            }
        }
        return count;
    }
}

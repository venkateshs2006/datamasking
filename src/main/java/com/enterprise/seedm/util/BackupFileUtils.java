package com.enterprise.seedm.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.util.unit.DataSize;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utility class for multi-part secure export/import chunk management,
 * natural sorting of backup parts, AES-256 encryption/decryption, and directory scanning.
 */
public final class BackupFileUtils {

    private static final Logger log = LoggerFactory.getLogger(BackupFileUtils.class);

    /**
     * Default maximum backup file size: 100 MB.
     */
    public static final long DEFAULT_MAX_FILE_SIZE = 100L * 1024 * 1024;

    private static volatile Long configuredMaxFileSizeBytes = null;

    private static final Pattern PART_PATTERN_1 = Pattern.compile("[-_.]part(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PART_PATTERN_2 = Pattern.compile("[-_](\\d+)\\.[^.]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_SIZE_REGEX = Pattern.compile("^(\\d+)\\s*([a-zA-Z]+)$");

    private BackupFileUtils() {
    }

    /**
     * Sets the maximum file size in bytes dynamically (e.g. from application.yml).
     */
    public static void setMaxFileSizeBytes(long maxBytes) {
        if (maxBytes > 0) {
            configuredMaxFileSizeBytes = maxBytes;
            log.info("BackupFileUtils: configured max export file size set to {} bytes", maxBytes);
        }
    }

    /**
     * Sets the maximum file size dynamically from a string representation (e.g., "100MB", "50MB").
     */
    public static void setMaxFileSize(String sizeStr) {
        if (sizeStr != null && !sizeStr.trim().isEmpty()) {
            setMaxFileSizeBytes(parseDataSizeToBytes(sizeStr));
        }
    }

    /**
     * Resets the dynamically configured max file size.
     */
    public static void resetMaxFileSizeBytes() {
        configuredMaxFileSizeBytes = null;
    }

    /**
     * Returns the maximum chunk file size in bytes (defaults to 100MB).
     * Priority:
     * 1. explicitly configured via setMaxFileSizeBytes / setMaxFileSize (from application.yml)
     * 2. system property 'seedm.export.split-file-size'
     * 3. system property 'seedm.export.max-file-size'
     * 4. DEFAULT_MAX_FILE_SIZE (100MB)
     */
    public static long getMaxFileSizeBytes() {
        if (configuredMaxFileSizeBytes != null && configuredMaxFileSizeBytes > 0) {
            return configuredMaxFileSizeBytes;
        }

        String splitProp = System.getProperty("seedm.export.split-file-size");
        if (splitProp != null && !splitProp.trim().isEmpty()) {
            try {
                return parseDataSizeToBytes(splitProp);
            } catch (Exception e) {
                log.warn("Failed to parse system property 'seedm.export.split-file-size': {}", splitProp, e);
            }
        }

        String maxProp = System.getProperty("seedm.export.max-file-size");
        if (maxProp != null && !maxProp.trim().isEmpty()) {
            try {
                return parseDataSizeToBytes(maxProp);
            } catch (Exception e) {
                log.warn("Failed to parse system property 'seedm.export.max-file-size': {}", maxProp, e);
            }
        }

        return DEFAULT_MAX_FILE_SIZE;
    }

    /**
     * Parses human-readable data size string (e.g., "100MB", "50MB", "1GB", "250", "250B") into bytes.
     * Defaults to DEFAULT_MAX_FILE_SIZE if parsing fails or input is blank.
     */
    public static long parseDataSizeToBytes(String sizeStr) {
        if (sizeStr == null || sizeStr.trim().isEmpty()) {
            return DEFAULT_MAX_FILE_SIZE;
        }
        String trimmed = sizeStr.trim();
        if (trimmed.matches("^\\d+$")) {
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException e) {
                log.warn("Could not parse numeric string as long: {}", trimmed);
                return DEFAULT_MAX_FILE_SIZE;
            }
        }

        try {
            return DataSize.parse(trimmed).toBytes();
        } catch (Exception ignored) {
        }

        Matcher m = DATA_SIZE_REGEX.matcher(trimmed);
        if (m.matches()) {
            try {
                long val = Long.parseLong(m.group(1));
                String unit = m.group(2).toUpperCase();
                switch (unit) {
                    case "B":
                        return val;
                    case "K":
                    case "KB":
                        return val * 1024L;
                    case "M":
                    case "MB":
                        return val * 1024L * 1024L;
                    case "G":
                    case "GB":
                        return val * 1024L * 1024L * 1024L;
                    case "T":
                    case "TB":
                        return val * 1024L * 1024L * 1024L * 1024L;
                    default:
                        break;
                }
            } catch (Exception e) {
                log.warn("Error parsing size with unit: {}", trimmed, e);
            }
        }

        return DEFAULT_MAX_FILE_SIZE;
    }

    /**
     * Extracts numerical part index from file name (e.g., secure-export-part2.sql.enc -> 2).
     * Returns 0 if no part number is found.
     */
    public static int extractPartNumber(String fileName) {
        if (fileName == null) return 0;
        Matcher m1 = PART_PATTERN_1.matcher(fileName);
        if (m1.find()) {
            try {
                return Integer.parseInt(m1.group(1));
            } catch (NumberFormatException ignored) {}
        }
        Matcher m2 = PART_PATTERN_2.matcher(fileName);
        if (m2.find()) {
            try {
                return Integer.parseInt(m2.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    /**
     * Sorts backup part files in sequential order (e.g. part1, part2, ..., part10).
     */
    public static List<Path> sortBackupFiles(List<Path> files) {
        if (files == null || files.isEmpty()) {
            return Collections.emptyList();
        }
        List<Path> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparingInt((Path p) -> extractPartNumber(p.getFileName().toString()))
                .thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return sorted;
    }

    /**
     * Encrypts a source file to targetEncryptedFile using AES/CBC/PKCS5Padding with SHA-256 derived key
     * and random 16-byte IV prefixed in the output file.
     */
    public static void encryptFileWithSalt(Path sourceFile, Path targetEncryptedFile, String saltKey) throws Exception {
        if (!Files.exists(sourceFile)) {
            log.warn("Source file does not exist for encryption: {}", sourceFile);
            return;
        }

        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = sha.digest(saltKey.trim().getBytes(StandardCharsets.UTF_8));
        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");

        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);

        try (InputStream in = Files.newInputStream(sourceFile);
             OutputStream out = Files.newOutputStream(targetEncryptedFile)) {
            out.write(iv);
            try (CipherOutputStream cipherOut = new CipherOutputStream(out, cipher)) {
                byte[] buffer = new byte[65536];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    cipherOut.write(buffer, 0, bytesRead);
                }
                cipherOut.flush();
            }
        }
    }

    /**
     * Creates an InputStream to read a file, decrypting it on the fly if it ends with .enc.
     */
    public static InputStream createDecryptedInputStream(Path file, String secretKey) throws Exception {
        InputStream rawIn = Files.newInputStream(file);
        if (!file.getFileName().toString().endsWith(".enc")) {
            return rawIn;
        }

        byte[] iv = new byte[16];
        int read = rawIn.read(iv);
        if (read < 16) {
            rawIn.close();
            throw new IllegalArgumentException("Invalid encrypted file: file size too small for initialization vector (" + file.getFileName() + ")");
        }

        if (secretKey == null || secretKey.trim().isEmpty()) {
            rawIn.close();
            throw new IllegalArgumentException("Secret key is required for encrypted file: " + file.getFileName());
        }

        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = sha.digest(secretKey.trim().getBytes(StandardCharsets.UTF_8));
        SecretKeySpec secretKeySpec = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec);
        return new CipherInputStream(rawIn, cipher);
    }

    /**
     * Resolves all matching backup files in a given folder or file location.
     */
    public static List<Path> findBackupFiles(Path folderOrFile, String baseNamePrefix, String... extensions) {
        if (folderOrFile == null || !Files.exists(folderOrFile)) {
            return Collections.emptyList();
        }

        try {
            if (Files.isRegularFile(folderOrFile)) {
                Path parent = folderOrFile.getParent();
                if (parent != null) {
                    String fname = folderOrFile.getFileName().toString();
                    if (fname.contains("-part") || fname.contains("_part") || fname.contains(".part")) {
                        String prefix = fname.substring(0, fname.indexOf("part"));
                        List<Path> siblings = new ArrayList<>();
                        try (Stream<Path> stream = Files.list(parent)) {
                            stream.filter(Files::isRegularFile)
                                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                                    .filter(p -> matchesAnyExtension(p.getFileName().toString(), extensions))
                                    .forEach(siblings::add);
                        }
                        if (!siblings.isEmpty()) {
                            return sortBackupFiles(siblings);
                        }
                    }
                }
                return List.of(folderOrFile);
            }

            if (Files.isDirectory(folderOrFile)) {
                List<Path> found = new ArrayList<>();
                try (Stream<Path> stream = Files.list(folderOrFile)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> matchesAnyExtension(p.getFileName().toString(), extensions))
                            .forEach(found::add);
                }

                if (baseNamePrefix != null && !baseNamePrefix.isBlank()) {
                    List<Path> matchingPrefix = found.stream()
                            .filter(p -> p.getFileName().toString().toLowerCase().startsWith(baseNamePrefix.toLowerCase()))
                            .collect(Collectors.toList());
                    if (!matchingPrefix.isEmpty()) {
                        return sortBackupFiles(matchingPrefix);
                    }
                }

                return sortBackupFiles(found);
            }
        } catch (IOException e) {
            log.warn("Failed to find backup files in {}: {}", folderOrFile, e.getMessage());
        }

        return Collections.emptyList();
    }

    private static boolean matchesAnyExtension(String name, String... extensions) {
        String lower = name.toLowerCase();
        for (String ext : extensions) {
            if (lower.endsWith(ext.toLowerCase())) return true;
        }
        return false;
    }
}

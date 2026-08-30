package com.enterprise.seedm.service;

import com.enterprise.seedm.model.CosConnection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class IbmCosService {

    private final CosConnectionService cosConnectionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, S3Client> clientCache = new ConcurrentHashMap<>();
    private final Map<String, String> iamTokenCache = new ConcurrentHashMap<>();
    private final Map<String, Long> iamTokenExpiryCache = new ConcurrentHashMap<>();

    public S3Client getS3Client(CosConnection conn) {
        if (conn == null) {
            throw new IllegalArgumentException("CosConnection cannot be null");
        }

        String cacheKey = (conn.getId() != null ? String.valueOf(conn.getId()) : "unsaved") + "_"
                + conn.getBucketUrl() + "_" + conn.getAuthenticationType();

        return clientCache.computeIfAbsent(cacheKey, k -> buildS3Client(conn));
    }

    public S3Client buildS3Client(CosConnection conn) {
        S3ClientBuilder builder = S3Client.builder();

        // 1. Endpoint configuration
        String endpoint = conn.getBucketUrl();
        if (endpoint != null && !endpoint.trim().isEmpty()) {
            if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                endpoint = "https://" + endpoint.trim();
            }
            builder.endpointOverride(URI.create(endpoint.trim()));
        }

        // 2. Region / Location configuration
        String location = conn.getLocation();
        if (location != null && !location.trim().isEmpty()) {
            builder.region(Region.of(location.trim().toLowerCase()));
        } else {
            builder.region(Region.US_EAST_1);
        }

        // 3. Force path-style access (required for IBM COS and S3-compatible object stores)
        builder.serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .chunkedEncodingEnabled(false)
                .build());

        // 4. Authentication credentials
        String authType = conn.getAuthenticationType() != null ? conn.getAuthenticationType().trim().toUpperCase() : "HMAC";

        if ("HMAC".equalsIgnoreCase(authType) || (conn.getAccessKey() != null && !conn.getAccessKey().trim().isEmpty())) {
            String accessKey = conn.getAccessKey() != null ? conn.getAccessKey().trim() : "";
            String secretKey = conn.getSecretKey() != null ? conn.getSecretKey().trim() : "";
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        } else if ("IAM".equalsIgnoreCase(authType)) {
            String apiKey = conn.getApiKey() != null ? conn.getApiKey().trim() : "";
            if (!apiKey.isEmpty()) {
                // Fetch IAM token or use token-based credentials
                try {
                    String token = getOrFetchIamToken(apiKey);
                    if (token != null && !token.isEmpty()) {
                        builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(apiKey, token)));
                    } else {
                        builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(apiKey, apiKey)));
                    }
                } catch (Exception e) {
                    log.warn("Could not fetch IAM token for apiKey, using raw apiKey credentials: {}", e.getMessage());
                    builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(apiKey, apiKey)));
                }
            } else {
                builder.credentialsProvider(AnonymousCredentialsProvider.create());
            }
        } else {
            builder.credentialsProvider(AnonymousCredentialsProvider.create());
        }

        return builder.build();
    }

    public Map<String, Object> testConnection(CosConnection conn) {
        Map<String, Object> result = new HashMap<>();
        try {
            if ("Local".equalsIgnoreCase(conn.getStorageType())) {
                String path = conn.getStorageLocation();
                if (path == null || path.trim().isEmpty()) {
                    result.put("status", "ERROR");
                    result.put("message", "Storage location path is required for Local storage.");
                    return result;
                }
                Path localPath = Path.of(path.trim());
                if (!Files.exists(localPath)) {
                    Files.createDirectories(localPath);
                }
                result.put("status", "SUCCESS");
                result.put("message", "Local storage directory accessible: " + localPath.toAbsolutePath());
                return result;
            }

            // COS Storage validation
            String bucket = getEffectiveBucketName(conn);
            if (bucket == null || bucket.trim().isEmpty()) {
                result.put("status", "ERROR");
                result.put("message", "Bucket Name or Bucket ID is required for IBM Cloud Object Storage.");
                return result;
            }

            S3Client s3Client = buildS3Client(conn);

            // Test 1: Check bucket existence or head bucket
            try {
                HeadBucketRequest headReq = HeadBucketRequest.builder().bucket(bucket).build();
                s3Client.headBucket(headReq);
            } catch (NoSuchBucketException e) {
                result.put("status", "ERROR");
                result.put("message", "Bucket not found: " + bucket);
                return result;
            } catch (Exception e) {
                // If headBucket fails with 403 or S3 error, try listObjects with maxKeys=1
                ListObjectsV2Request listReq = ListObjectsV2Request.builder().bucket(bucket).maxKeys(1).build();
                s3Client.listObjectsV2(listReq);
            }

            result.put("status", "SUCCESS");
            result.put("message", "Successfully connected to IBM Cloud Object Storage bucket: " + bucket);
            return result;
        } catch (Exception e) {
            log.error("IBM COS test connection failed", e);
            result.put("status", "ERROR");
            result.put("message", "Connection failed: " + e.getMessage());
            return result;
        }
    }

    public List<Map<String, Object>> listObjects(CosConnection conn, String prefix) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (conn == null) return list;

        if ("Local".equalsIgnoreCase(conn.getStorageType())) {
            return listLocalFiles(conn.getStorageLocation(), prefix);
        }

        try {
            String bucket = getEffectiveBucketName(conn);
            S3Client s3Client = getS3Client(conn);

            ListObjectsV2Request.Builder reqBuilder = ListObjectsV2Request.builder().bucket(bucket);
            if (prefix != null && !prefix.trim().isEmpty()) {
                reqBuilder.prefix(prefix.trim());
            }

            ListObjectsV2Response res = s3Client.listObjectsV2(reqBuilder.build());
            for (S3Object s3Obj : res.contents()) {
                Map<String, Object> item = new HashMap<>();
                item.put("name", s3Obj.key());
                item.put("key", s3Obj.key());
                item.put("sizeBytes", s3Obj.size());
                item.put("lastModified", s3Obj.lastModified() != null ? s3Obj.lastModified().toEpochMilli() : 0);
                item.put("encrypted", s3Obj.key().endsWith(".enc"));
                list.add(item);
            }
        } catch (Exception e) {
            log.warn("Failed to list objects in COS bucket {}: {}", conn.getBucketName(), e.getMessage());
        }
        return list;
    }

    public void uploadFile(CosConnection conn, String objectKey, Path localFilePath) throws IOException {
        if (!Files.exists(localFilePath)) {
            throw new FileNotFoundException("Local file to upload not found: " + localFilePath);
        }

        if (conn == null || "Local".equalsIgnoreCase(conn.getStorageType())) {
            Path destDir = conn != null && conn.getStorageLocation() != null ? Path.of(conn.getStorageLocation()) : Path.of("secure-export");
            Files.createDirectories(destDir);
            Path destFile = destDir.resolve(objectKey != null ? objectKey : localFilePath.getFileName().toString());
            Files.copy(localFilePath, destFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("Copied file locally to: {}", destFile);
            return;
        }

        String bucket = getEffectiveBucketName(conn);
        S3Client s3Client = getS3Client(conn);

        PutObjectRequest putReq = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey != null ? objectKey : localFilePath.getFileName().toString())
                .contentLength(Files.size(localFilePath))
                .build();

        s3Client.putObject(putReq, RequestBody.fromFile(localFilePath));
        log.info("Uploaded file {} ({} bytes) to COS bucket {}/{}", localFilePath.getFileName(), Files.size(localFilePath), bucket, objectKey);
    }

    public void putObject(CosConnection conn, String objectKey, byte[] data) {
        if (conn == null || "Local".equalsIgnoreCase(conn.getStorageType())) {
            try {
                Path destDir = conn != null && conn.getStorageLocation() != null ? Path.of(conn.getStorageLocation()) : Path.of("secure-export");
                Files.createDirectories(destDir);
                Path destFile = destDir.resolve(objectKey);
                Files.write(destFile, data);
            } catch (Exception e) {
                log.error("Failed to write local file", e);
            }
            return;
        }

        String bucket = getEffectiveBucketName(conn);
        S3Client s3Client = getS3Client(conn);

        PutObjectRequest putReq = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentLength((long) data.length)
                .build();

        s3Client.putObject(putReq, RequestBody.fromBytes(data));
        log.info("Put object to COS bucket {}/{}: {} bytes", bucket, objectKey, data.length);
    }

    public void downloadFile(CosConnection conn, String objectKey, Path destinationLocalFile) throws IOException {
        if (conn == null || "Local".equalsIgnoreCase(conn.getStorageType())) {
            Path srcDir = conn != null && conn.getStorageLocation() != null ? Path.of(conn.getStorageLocation()) : Path.of("secure-export");
            Path srcFile = srcDir.resolve(objectKey);
            if (Files.exists(srcFile)) {
                if (destinationLocalFile.getParent() != null) Files.createDirectories(destinationLocalFile.getParent());
                Files.copy(srcFile, destinationLocalFile, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            throw new FileNotFoundException("Local source file not found: " + srcFile);
        }

        String bucket = getEffectiveBucketName(conn);
        S3Client s3Client = getS3Client(conn);

        GetObjectRequest getReq = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();

        if (destinationLocalFile.getParent() != null) {
            Files.createDirectories(destinationLocalFile.getParent());
        }

        try (ResponseInputStream<GetObjectResponse> s3Stream = s3Client.getObject(getReq)) {
            Files.copy(s3Stream, destinationLocalFile, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("Downloaded COS object {}/{} to local path: {}", bucket, objectKey, destinationLocalFile);
    }

    public InputStream getObjectInputStream(CosConnection conn, String objectKey) throws IOException {
        if (conn == null || "Local".equalsIgnoreCase(conn.getStorageType())) {
            Path srcDir = conn != null && conn.getStorageLocation() != null ? Path.of(conn.getStorageLocation()) : Path.of("secure-export");
            Path srcFile = srcDir.resolve(objectKey);
            return Files.newInputStream(srcFile);
        }

        String bucket = getEffectiveBucketName(conn);
        S3Client s3Client = getS3Client(conn);

        GetObjectRequest getReq = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();

        return s3Client.getObject(getReq);
    }

    public JsonNode readJson(CosConnection conn, String objectKey) throws IOException {
        try (InputStream in = getObjectInputStream(conn, objectKey)) {
            return objectMapper.readTree(in);
        }
    }

    public Set<String> extractJsonKeys(CosConnection conn, String objectKey) {
        Set<String> keys = new TreeSet<>();
        try {
            JsonNode root = readJson(conn, objectKey);
            extractKeysRecursive(root, "", keys);
        } catch (Exception e) {
            log.error("Failed to extract JSON keys from COS object {}/{}", conn.getBucketName(), objectKey, e);
        }
        return keys;
    }

    private void extractKeysRecursive(JsonNode node, String currentPath, Set<String> keys) {
        if (node == null) return;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String fieldPath = currentPath.isEmpty() ? entry.getKey() : currentPath + "." + entry.getKey();
                if (entry.getValue().isValueNode()) {
                    keys.add(fieldPath);
                } else {
                    extractKeysRecursive(entry.getValue(), fieldPath, keys);
                }
            }
        } else if (node.isArray()) {
            for (int i = 0; i < Math.min(node.size(), 10); i++) {
                extractKeysRecursive(node.get(i), currentPath, keys);
            }
        }
    }

    public String getEffectiveBucketName(CosConnection conn) {
        if (conn == null) return null;
        if (conn.getBucketName() != null && !conn.getBucketName().trim().isEmpty()) {
            return conn.getBucketName().trim();
        }
        if (conn.getBucketId() != null && !conn.getBucketId().trim().isEmpty()) {
            return conn.getBucketId().trim();
        }
        return "default";
    }

    private List<Map<String, Object>> listLocalFiles(String basePath, String prefix) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            Path base = basePath != null && !basePath.trim().isEmpty() ? Path.of(basePath.trim()) : Path.of("secure-export");
            if (Files.exists(base) && Files.isDirectory(base)) {
                try (var stream = Files.list(base)) {
                    stream.filter(Files::isRegularFile).forEach(p -> {
                        String name = p.getFileName().toString();
                        if (prefix == null || prefix.isEmpty() || name.startsWith(prefix)) {
                            Map<String, Object> item = new HashMap<>();
                            item.put("name", name);
                            item.put("key", name);
                            try { item.put("sizeBytes", Files.size(p)); } catch (Exception e) { item.put("sizeBytes", 0); }
                            try { item.put("lastModified", Files.getLastModifiedTime(p).toMillis()); } catch (Exception e) { item.put("lastModified", 0); }
                            item.put("encrypted", name.endsWith(".enc"));
                            list.add(item);
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.warn("Error listing local files: {}", e.getMessage());
        }
        return list;
    }

    private String getOrFetchIamToken(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) return null;

        Long expiry = iamTokenExpiryCache.get(apiKey);
        if (expiry != null && System.currentTimeMillis() < expiry) {
            return iamTokenCache.get(apiKey);
        }

        try {
            URL url = new URL("https://iam.cloud.ibm.com/identity/token");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            String postData = "grant_type=" + URLEncoder.encode("urn:ibm:params:oauth:grant-type:apikey", StandardCharsets.UTF_8)
                    + "&apikey=" + URLEncoder.encode(apiKey.trim(), StandardCharsets.UTF_8);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(postData.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                try (InputStream is = connection.getInputStream()) {
                    JsonNode node = objectMapper.readTree(is);
                    String accessToken = node.path("access_token").asText();
                    long expiresIn = node.path("expires_in").asLong(3600);
                    if (accessToken != null && !accessToken.isEmpty()) {
                        iamTokenCache.put(apiKey, accessToken);
                        iamTokenExpiryCache.put(apiKey, System.currentTimeMillis() + ((expiresIn - 60) * 1000));
                        return accessToken;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("IAM token exchange failed: {}", e.getMessage());
        }
        return null;
    }
}

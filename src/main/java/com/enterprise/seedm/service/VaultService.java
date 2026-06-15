package com.enterprise.seedm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;

import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class VaultService {

    private final VaultTemplate vaultTemplate;

    @Value("${spring.cloud.vault.uri:https://localhost:8200}")
    private String vaultUri;

    public Map<String, Object> getDatabaseCredentials(String path) {
        log.info("Fetching database credentials from Vault at path: {}", path);
        try {
            VaultResponse response = vaultTemplate.read(path);
            if (response != null && response.getData() != null) {
                return response.getData();
            } else {
                log.warn("No data found in Vault at path: {}", path);
                return null;
            }
        } catch (Exception e) {
            log.error("Error fetching credentials from Vault: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to retrieve database credentials from Vault", e);
        }
    }
}
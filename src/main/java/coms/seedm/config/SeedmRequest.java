package coms.seedm.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Mirrors the exact input JSON shape:
 * {
 *   "source": {...},
 *   "storage": {...},
 *   "rules": {...}
 * }
 */
public class SeedmRequest {

    @Valid
    @NotNull
    @JsonProperty("source")
    private SourceConfig source;

    @Valid
    @NotNull
    @JsonProperty("storage")
    private StorageConfig storage;

    @Valid
    @NotNull
    @JsonProperty("rules")
    private RulesConfig rules;

    public SourceConfig getSource() {
        return source;
    }

    public void setSource(SourceConfig source) {
        this.source = source;
    }

    public StorageConfig getStorage() {
        return storage;
    }

    public void setStorage(StorageConfig storage) {
        this.storage = storage;
    }

    public RulesConfig getRules() {
        return rules;
    }

    public void setRules(RulesConfig rules) {
        this.rules = rules;
    }

    @Override
    public String toString() {
        return "SeedmRequest{source=" + source + ", storage=" + storage + ", rules=" + rules + '}';
    }
}

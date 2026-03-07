package com.enterprise.seedm.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class MaskingConfig {
    private List<String> maskingColumns = new ArrayList<>();
    private List<String> constraintColumns = new ArrayList<>();
    private List<String> partialMaskingColumns = new ArrayList<>();
    private String maskingKey = "DefaultSecretKey123";
}

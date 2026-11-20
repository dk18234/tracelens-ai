package dev.dineshrama.tracelens;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("trace")
public record TraceProperties(
    @Size(min=24) String adminToken,
    @NotBlank String ollamaUrl,
    @NotBlank String chatModel,
    @NotBlank String embeddingModel,
    @Min(1) @Max(300) int modelTimeoutSeconds,
    boolean workerEnabled,
    @Min(30) int leaseSeconds,
    String webhookLabUrl,
    String webhookAdminToken) {}

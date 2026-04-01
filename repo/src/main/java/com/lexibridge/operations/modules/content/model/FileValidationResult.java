package com.lexibridge.operations.modules.content.model;

public record FileValidationResult(
    boolean valid,
    String detectedMime,
    String checksumSha256,
    String reason,
    String antivirusEngine,
    String antivirusMessage
) {
}

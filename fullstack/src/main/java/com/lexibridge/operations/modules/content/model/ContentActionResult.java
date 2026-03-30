package com.lexibridge.operations.modules.content.model;

public record ContentActionResult(
    long contentItemId,
    int versionNo,
    String status,
    boolean duplicate,
    Long duplicateItemId
) {
}

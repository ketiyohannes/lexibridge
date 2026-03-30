package com.lexibridge.operations.modules.content.model;

import java.util.List;

public record ContentImportPreviewResult(
    long jobId,
    int totalRows,
    int duplicates,
    int validRows,
    int invalidRows,
    List<RowResult> rows
) {
    public record RowResult(
        int rowNo,
        String status,
        String message,
        Long duplicateItemId,
        String suggestedAction
    ) {
    }
}

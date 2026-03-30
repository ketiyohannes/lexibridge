package com.lexibridge.operations.modules.content.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexibridge.operations.governance.AuditLogService;
import com.lexibridge.operations.modules.content.model.ContentActionResult;
import com.lexibridge.operations.modules.content.model.ContentCreateRequest;
import com.lexibridge.operations.modules.content.repository.ContentRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class ContentService {

    private final ContentRepository contentRepository;
    private final ContentNormalizationService normalizationService;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    public ContentService(ContentRepository contentRepository,
                          ContentNormalizationService normalizationService,
                          ObjectMapper objectMapper,
                          AuditLogService auditLogService) {
        this.contentRepository = contentRepository;
        this.normalizationService = normalizationService;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
    }

    public Map<String, Object> dashboardSummary() {
        return contentRepository.summary(null);
    }

    public Map<String, Object> dashboardSummary(Long locationId) {
        return contentRepository.summary(locationId);
    }

    @Transactional
    public ContentActionResult createDraft(ContentCreateRequest request) {
        String normalizedTerm = normalizationService.normalizeTerm(request.term());
        String normalizedPhonetic = normalizationService.normalizePhonetic(request.phonetic());

        Long duplicateId = contentRepository.findDuplicate(
            request.locationId(), normalizedTerm, normalizedPhonetic
        ).orElse(null);

        if (duplicateId != null) {
            return new ContentActionResult(duplicateId, contentRepository.currentVersionNo(duplicateId), "DUPLICATE", true, duplicateId);
        }

        long itemId = contentRepository.createItem(
            request.locationId(),
            request.term(),
            normalizedTerm,
            request.phonetic(),
            normalizedPhonetic,
            request.category(),
            request.createdBy()
        );

        contentRepository.insertVersion(
            itemId,
            1,
            request.grammarPoint(),
            request.phraseText(),
            request.exampleSentence(),
            request.definitionText(),
            toJson(request.metadata()),
            request.createdBy()
        );

        enforceVersionRetention(itemId);
        auditLogService.logUserEvent(request.createdBy(), "CONTENT_CREATED", "content_item", String.valueOf(itemId), request.locationId(), Map.of("version", 1));
        return new ContentActionResult(itemId, 1, "DRAFT", false, null);
    }

    @Transactional
    public ContentActionResult addRevision(long contentItemId,
                                           long actorUserId,
                                           String grammarPoint,
                                           String phraseText,
                                           String exampleSentence,
                                           String definitionText,
                                           Map<String, Object> metadata) {
        int nextVersionNo = contentRepository.currentVersionNo(contentItemId) + 1;
        contentRepository.insertVersion(
            contentItemId,
            nextVersionNo,
            grammarPoint,
            phraseText,
            exampleSentence,
            definitionText,
            toJson(metadata),
            actorUserId
        );
        contentRepository.setCurrentVersion(contentItemId, nextVersionNo);
        enforceVersionRetention(contentItemId);
        auditLogService.logUserEvent(actorUserId, "CONTENT_REVISED", "content_item", String.valueOf(contentItemId), null, Map.of("version", nextVersionNo));
        return new ContentActionResult(contentItemId, nextVersionNo, "DRAFT", false, null);
    }

    @Transactional
    public void publish(long contentItemId) {
        contentRepository.setStatus(contentItemId, "PUBLISHED");
        auditLogService.logSystemEvent("CONTENT_PUBLISHED", "content_item", String.valueOf(contentItemId), null, Map.of());
    }

    @Transactional
    public void unpublish(long contentItemId) {
        contentRepository.setStatus(contentItemId, "UNPUBLISHED");
        auditLogService.logSystemEvent("CONTENT_UNPUBLISHED", "content_item", String.valueOf(contentItemId), null, Map.of());
    }

    @Transactional
    public ContentActionResult rollback(long contentItemId, int targetVersionNo) {
        if (!contentRepository.versionExists(contentItemId, targetVersionNo)) {
            throw new IllegalArgumentException("Target version does not exist.");
        }
        contentRepository.setCurrentVersion(contentItemId, targetVersionNo);
        contentRepository.setStatus(contentItemId, "DRAFT");
        auditLogService.logSystemEvent("CONTENT_ROLLBACK", "content_item", String.valueOf(contentItemId), null, Map.of("targetVersion", targetVersionNo));
        return new ContentActionResult(contentItemId, targetVersionNo, "DRAFT", false, null);
    }

    private void enforceVersionRetention(long contentItemId) {
        int currentVersion = contentRepository.currentVersionNo(contentItemId);
        int count = contentRepository.countVersions(contentItemId);
        while (count > 20) {
            contentRepository.pruneOldestNonCurrentVersion(contentItemId, currentVersion);
            count = contentRepository.countVersions(contentItemId);
        }
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize metadata.", e);
        }
    }

    public byte[] export(long locationId, String format) {
        List<Map<String, Object>> rows = contentRepository.exportRows(locationId);
        String normalized = format == null ? "csv" : format.toLowerCase();
        return switch (normalized) {
            case "json" -> exportJson(rows);
            case "xlsx" -> exportXlsx(rows);
            default -> exportCsv(rows);
        };
    }

    public List<Map<String, Object>> recentItems(long locationId, int limit) {
        int effectiveLimit = limit <= 0 ? 30 : Math.min(limit, 100);
        return contentRepository.recentItems(locationId, effectiveLimit);
    }

    public List<Map<String, Object>> versions(long contentItemId) {
        return contentRepository.versions(contentItemId);
    }

    private byte[] exportCsv(List<Map<String, Object>> rows) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(out, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.withHeader(
                    "id", "location_id", "term", "phonetic", "category", "status", "current_version_no",
                    "grammar_point", "phrase_text", "example_sentence", "definition_text", "metadata_json",
                    "created_at", "updated_at"
                ));
            for (Map<String, Object> row : rows) {
                printer.printRecord(
                    row.get("id"),
                    row.get("location_id"),
                    row.get("term"),
                    row.get("phonetic"),
                    row.get("category"),
                    row.get("status"),
                    row.get("current_version_no"),
                    row.get("grammar_point"),
                    row.get("phrase_text"),
                    row.get("example_sentence"),
                    row.get("definition_text"),
                    row.get("metadata_json"),
                    row.get("created_at"),
                    row.get("updated_at")
                );
            }
            printer.flush();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to generate CSV export.", ex);
        }
    }

    private byte[] exportJson(List<Map<String, Object>> rows) {
        try {
            return objectMapper.writeValueAsBytes(rows);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to generate JSON export.", ex);
        }
    }

    private byte[] exportXlsx(List<Map<String, Object>> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("content_export");
            String[] headers = {
                "id", "location_id", "term", "phonetic", "category", "status", "current_version_no",
                "grammar_point", "phrase_text", "example_sentence", "definition_text", "metadata_json",
                "created_at", "updated_at"
            };
            var headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }
            int rowIndex = 1;
            for (Map<String, Object> row : rows) {
                var xlsxRow = sheet.createRow(rowIndex++);
                for (int i = 0; i < headers.length; i++) {
                    Object value = row.get(headers[i]);
                    xlsxRow.createCell(i).setCellValue(value == null ? "" : String.valueOf(value));
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to generate XLSX export.", ex);
        }
    }
}

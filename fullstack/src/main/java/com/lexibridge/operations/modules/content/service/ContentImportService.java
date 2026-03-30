package com.lexibridge.operations.modules.content.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexibridge.operations.modules.content.model.ContentCreateRequest;
import com.lexibridge.operations.modules.content.model.ContentImportPreviewResult;
import com.lexibridge.operations.modules.content.model.ImportDecision;
import com.lexibridge.operations.modules.content.model.ImportRowPayload;
import com.lexibridge.operations.modules.content.repository.ContentRepository;
import jakarta.transaction.Transactional;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ContentImportService {

    private final ContentRepository contentRepository;
    private final ContentNormalizationService normalizationService;
    private final ContentService contentService;
    private final ObjectMapper objectMapper;

    public ContentImportService(ContentRepository contentRepository,
                                ContentNormalizationService normalizationService,
                                ContentService contentService,
                                ObjectMapper objectMapper) {
        this.contentRepository = contentRepository;
        this.normalizationService = normalizationService;
        this.contentService = contentService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ContentImportPreviewResult preview(long locationId,
                                              long uploadedBy,
                                              String filename,
                                              String format,
                                              byte[] bytes) {
        return processImport(locationId, uploadedBy, filename, format, bytes, Map.of(), true);
    }

    @Transactional
    public ContentImportPreviewResult execute(long locationId,
                                              long uploadedBy,
                                              String filename,
                                              String format,
                                              byte[] bytes,
                                              Map<Integer, ImportDecision> decisions) {
        return processImport(locationId, uploadedBy, filename, format, bytes, decisions, false);
    }

    private ContentImportPreviewResult processImport(long locationId,
                                                     long uploadedBy,
                                                     String filename,
                                                     String format,
                                                     byte[] bytes,
                                                     Map<Integer, ImportDecision> decisions,
                                                     boolean dryRun) {
        List<ImportRowPayload> rows = parse(format, bytes);

        List<ContentImportPreviewResult.RowResult> rowResults = new ArrayList<>();
        int duplicates = 0;
        int valid = 0;
        int invalid = 0;

        long jobId = contentRepository.createImportJob(
            locationId,
            uploadedBy,
            filename,
            format,
            dryRun ? "REVIEWING" : "PROCESSING",
            "{}"
        );

        for (ImportRowPayload row : rows) {
            String error = validate(row);
            if (error != null) {
                invalid++;
                contentRepository.createImportRowResult(jobId, row.rowNo(), null, "INVALID", "VALIDATION_ERROR", error, null);
                rowResults.add(new ContentImportPreviewResult.RowResult(row.rowNo(), "INVALID", error, null, null));
                continue;
            }

            String normalizedTerm = normalizationService.normalizeTerm(row.term());
            String normalizedPhonetic = normalizationService.normalizePhonetic(row.phonetic());
            Long duplicateId = contentRepository.findDuplicate(locationId, normalizedTerm, normalizedPhonetic).orElse(null);
            if (duplicateId != null) {
                duplicates++;
                ImportDecision decision = decisions.getOrDefault(row.rowNo(), ImportDecision.SKIP);
                contentRepository.createImportRowResult(
                    jobId,
                    row.rowNo(),
                    decision.name(),
                    dryRun ? "DUPLICATE_NEEDS_DECISION" : "DUPLICATE",
                    null,
                    "Potential duplicate found",
                    duplicateId
                );

                if (!dryRun) {
                    applyDecision(locationId, uploadedBy, row, duplicateId, decision, jobId);
                }

                rowResults.add(new ContentImportPreviewResult.RowResult(
                    row.rowNo(),
                    "DUPLICATE",
                    "Potential duplicate found",
                    duplicateId,
                    decision.name()
                ));
                continue;
            }

            valid++;
            contentRepository.createImportRowResult(jobId, row.rowNo(), ImportDecision.CREATE_NEW.name(), "VALID", null, null, null);
            rowResults.add(new ContentImportPreviewResult.RowResult(row.rowNo(), "VALID", "Ready to import", null, ImportDecision.CREATE_NEW.name()));
            if (!dryRun) {
                contentService.createDraft(new ContentCreateRequest(
                    locationId,
                    uploadedBy,
                    row.term(),
                    row.phonetic(),
                    row.category(),
                    row.grammarPoint(),
                    row.phraseText(),
                    row.exampleSentence(),
                    row.definitionText(),
                    row.metadata()
                ));
            }
        }

        Map<String, Object> summary = Map.of(
            "totalRows", rows.size(),
            "duplicates", duplicates,
            "validRows", valid,
            "invalidRows", invalid,
            "mode", dryRun ? "PREVIEW" : "EXECUTE"
        );
        contentRepository.updateImportJobStatus(jobId, dryRun ? "REVIEWING" : "COMPLETED", toJson(summary));

        return new ContentImportPreviewResult(jobId, rows.size(), duplicates, valid, invalid, rowResults);
    }

    public List<Map<String, Object>> rowsForErrorReport(long jobId) {
        return contentRepository.importRowsForReport(jobId);
    }

    private void applyDecision(long locationId,
                               long uploadedBy,
                               ImportRowPayload row,
                               long duplicateId,
                               ImportDecision decision,
                               long jobId) {
        switch (decision) {
            case SKIP -> contentRepository.updateImportRowResult(jobId, row.rowNo(), decision.name(), "SKIPPED", null, "Skipped duplicate row");
            case CREATE_NEW -> {
                contentService.createDraft(new ContentCreateRequest(
                    locationId,
                    uploadedBy,
                    row.term(),
                    row.phonetic(),
                    row.category(),
                    row.grammarPoint(),
                    row.phraseText(),
                    row.exampleSentence(),
                    row.definitionText(),
                    row.metadata()
                ));
                contentRepository.updateImportRowResult(jobId, row.rowNo(), decision.name(), "IMPORTED", null, null);
            }
            case MERGE_INTO_EXISTING -> {
                contentService.addRevision(
                    duplicateId,
                    uploadedBy,
                    row.grammarPoint(),
                    row.phraseText(),
                    row.exampleSentence(),
                    row.definitionText(),
                    row.metadata()
                );
                contentRepository.updateImportRowResult(jobId, row.rowNo(), decision.name(), "MERGED", null, null);
            }
        }
    }

    private List<ImportRowPayload> parse(String format, byte[] bytes) {
        return switch (format.toLowerCase()) {
            case "csv" -> parseCsv(bytes);
            case "json" -> parseJson(bytes);
            case "xlsx", "excel" -> parseExcel(bytes);
            default -> throw new IllegalArgumentException("Unsupported format: " + format);
        };
    }

    private List<ImportRowPayload> parseCsv(byte[] bytes) {
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .build()
            .parse(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            List<ImportRowPayload> rows = new ArrayList<>();
            int rowNo = 2;
            for (CSVRecord record : parser) {
                rows.add(fromMap(rowNo++, toMap(record.toMap())));
            }
            return rows;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse CSV", e);
        }
    }

    private List<ImportRowPayload> parseJson(byte[] bytes) {
        try {
            List<Map<String, Object>> list = objectMapper.readValue(bytes, List.class);
            List<ImportRowPayload> rows = new ArrayList<>();
            int rowNo = 1;
            for (Map<String, Object> item : list) {
                rows.add(fromMap(rowNo++, item));
            }
            return rows;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse JSON", e);
        }
    }

    private List<ImportRowPayload> parseExcel(byte[] bytes) {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            Map<Integer, String> columns = new HashMap<>();
            for (Cell cell : header) {
                columns.put(cell.getColumnIndex(), cell.getStringCellValue());
            }

            List<ImportRowPayload> rows = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                Map<String, Object> map = new HashMap<>();
                for (Map.Entry<Integer, String> column : columns.entrySet()) {
                    Cell cell = row.getCell(column.getKey());
                    map.put(column.getValue(), cell == null ? null : cell.toString());
                }
                rows.add(fromMap(i + 1, map));
            }
            return rows;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse Excel", e);
        }
    }

    private ImportRowPayload fromMap(int rowNo, Map<String, Object> map) {
        return new ImportRowPayload(
            rowNo,
            asString(map.get("term")),
            asString(map.get("phonetic")),
            asString(map.get("category")),
            asString(map.get("grammarPoint")),
            asString(map.get("phraseText")),
            asString(map.get("exampleSentence")),
            asString(map.get("definitionText")),
            map.containsKey("metadata") && map.get("metadata") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of()
        );
    }

    private String validate(ImportRowPayload row) {
        if (row.term() == null || row.term().isBlank()) {
            return "Term is required";
        }
        if (row.category() == null || row.category().isBlank()) {
            return "Category is required";
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> toMap(Map<String, String> input) {
        return new HashMap<>(input);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize summary json", e);
        }
    }
}

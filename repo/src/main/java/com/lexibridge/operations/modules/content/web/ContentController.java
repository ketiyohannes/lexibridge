package com.lexibridge.operations.modules.content.web;

import com.lexibridge.operations.modules.content.model.ContentActionResult;
import com.lexibridge.operations.modules.content.model.ContentCreateRequest;
import com.lexibridge.operations.modules.content.model.ContentImportPreviewResult;
import com.lexibridge.operations.modules.content.model.ImportDecision;
import com.lexibridge.operations.modules.content.service.ContentImportService;
import com.lexibridge.operations.modules.content.service.ContentMediaService;
import com.lexibridge.operations.modules.content.service.ContentService;
import com.lexibridge.operations.security.service.AuthorizationScopeService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;

@Controller
public class ContentController {

    private final ContentService contentService;
    private final ContentImportService contentImportService;
    private final ContentMediaService contentMediaService;
    private final AuthorizationScopeService authorizationScopeService;

    public ContentController(ContentService contentService,
                             ContentImportService contentImportService,
                             ContentMediaService contentMediaService,
                             AuthorizationScopeService authorizationScopeService) {
        this.contentService = contentService;
        this.contentImportService = contentImportService;
        this.contentMediaService = contentMediaService;
        this.authorizationScopeService = authorizationScopeService;
    }

    @GetMapping("/portal/content")
    public String content(Model model) {
        populateBaseModel(model);
        if (!model.containsAttribute("importRequest")) {
            model.addAttribute("importRequest", ImportRequest.defaults());
        }
        if (!model.containsAttribute("createDraftForm")) {
            model.addAttribute("createDraftForm", CreateDraftForm.defaults());
        }
        if (!model.containsAttribute("revisionForm")) {
            model.addAttribute("revisionForm", RevisionForm.defaults());
        }
        if (!model.containsAttribute("rollbackForm")) {
            model.addAttribute("rollbackForm", RollbackForm.defaults());
        }
        model.addAttribute("contentItems", contentService.recentItems(1L, 40));
        return "portal/content";
    }

    @PostMapping("/portal/content/items")
    public String createDraft(@ModelAttribute CreateDraftForm form, Model model) {
        populateBaseModel(model);
        model.addAttribute("importRequest", ImportRequest.defaults());
        model.addAttribute("createDraftForm", form);
        model.addAttribute("revisionForm", RevisionForm.defaults());
        model.addAttribute("rollbackForm", RollbackForm.defaults());

        if (form.getLocationId() == null || form.getLocationId() <= 0 || form.getTerm() == null || form.getTerm().isBlank() || form.getCategory() == null || form.getCategory().isBlank()) {
            model.addAttribute("importError", "Location, term, and category are required.");
            model.addAttribute("contentItems", contentService.recentItems(1L, 40));
            return "portal/content";
        }

        authorizationScopeService.assertLocationAccess(form.getLocationId());
        long actorUserId = authorizationScopeService.requireCurrentUserId();
        ContentActionResult result = contentService.createDraft(new ContentCreateRequest(
            form.getLocationId(),
            actorUserId,
            form.getTerm(),
            form.getPhonetic(),
            form.getCategory(),
            form.getGrammarPoint(),
            form.getPhraseText(),
            form.getExampleSentence(),
            form.getDefinitionText(),
            Map.of("source", "portal")
        ));
        model.addAttribute("draftResult", result);
        model.addAttribute("importSuccess", "Draft created.");
        model.addAttribute("contentItems", contentService.recentItems(form.getLocationId(), 40));
        model.addAttribute("selectedContentItemId", result.contentItemId());
        model.addAttribute("selectedContentVersions", contentService.versions(result.contentItemId()));
        model.addAttribute("selectedContentMedia", contentMediaService.list(result.contentItemId()));
        return "portal/content";
    }

    @PostMapping("/portal/content/items/{itemId}/revisions")
    public String addRevision(@PathVariable long itemId,
                              @ModelAttribute RevisionForm form,
                              Model model) {
        populateBaseModel(model);
        model.addAttribute("importRequest", ImportRequest.defaults());
        model.addAttribute("createDraftForm", CreateDraftForm.defaults());
        model.addAttribute("revisionForm", form);
        model.addAttribute("rollbackForm", RollbackForm.defaults());

        authorizationScopeService.assertContentItemScope(itemId);
        long actorUserId = authorizationScopeService.requireCurrentUserId();
        ContentActionResult result = contentService.addRevision(
            itemId,
            actorUserId,
            form.getGrammarPoint(),
            form.getPhraseText(),
            form.getExampleSentence(),
            form.getDefinitionText(),
            Map.of("source", "portal")
        );
        model.addAttribute("revisionResult", result);
        model.addAttribute("importSuccess", "Revision added.");
        model.addAttribute("contentItems", contentService.recentItems(1L, 40));
        model.addAttribute("selectedContentItemId", itemId);
        model.addAttribute("selectedContentVersions", contentService.versions(itemId));
        model.addAttribute("selectedContentMedia", contentMediaService.list(itemId));
        return "portal/content";
    }

    @PostMapping("/portal/content/items/{itemId}/publish")
    public String publish(@PathVariable long itemId, Model model) {
        return applyStatusAction(itemId, "publish", model);
    }

    @PostMapping("/portal/content/items/{itemId}/unpublish")
    public String unpublish(@PathVariable long itemId, Model model) {
        return applyStatusAction(itemId, "unpublish", model);
    }

    @PostMapping("/portal/content/items/{itemId}/rollback")
    public String rollback(@PathVariable long itemId,
                           @ModelAttribute RollbackForm form,
                           Model model) {
        populateBaseModel(model);
        model.addAttribute("importRequest", ImportRequest.defaults());
        model.addAttribute("createDraftForm", CreateDraftForm.defaults());
        model.addAttribute("revisionForm", RevisionForm.defaults());
        model.addAttribute("rollbackForm", form);

        if (form.getVersionNo() == null || form.getVersionNo() <= 0) {
            model.addAttribute("importError", "Rollback version must be positive.");
            model.addAttribute("contentItems", contentService.recentItems(1L, 40));
            return "portal/content";
        }
        authorizationScopeService.assertContentItemScope(itemId);
        ContentActionResult result = contentService.rollback(itemId, form.getVersionNo());
        model.addAttribute("rollbackResult", result);
        model.addAttribute("importSuccess", "Rolled back to version " + form.getVersionNo() + ".");
        model.addAttribute("contentItems", contentService.recentItems(1L, 40));
        model.addAttribute("selectedContentItemId", itemId);
        model.addAttribute("selectedContentVersions", contentService.versions(itemId));
        model.addAttribute("selectedContentMedia", contentMediaService.list(itemId));
        return "portal/content";
    }

    @PostMapping("/portal/content/items/{itemId}/media")
    public String uploadMedia(@PathVariable long itemId,
                              @RequestParam("file") MultipartFile file,
                              Model model) {
        populateBaseModel(model);
        model.addAttribute("importRequest", ImportRequest.defaults());
        model.addAttribute("createDraftForm", CreateDraftForm.defaults());
        model.addAttribute("revisionForm", RevisionForm.defaults());
        model.addAttribute("rollbackForm", RollbackForm.defaults());

        try {
            authorizationScopeService.assertContentItemScope(itemId);
            Map<String, Object> uploadResult = contentMediaService.upload(
                itemId,
                file.getOriginalFilename() == null ? "content-media.bin" : file.getOriginalFilename(),
                file.getBytes(),
                currentUserId()
            );
            model.addAttribute("mediaUploadResult", uploadResult);
            model.addAttribute("importSuccess", "Media uploaded.");
            model.addAttribute("selectedContentItemId", itemId);
            model.addAttribute("selectedContentMedia", contentMediaService.list(itemId));
            model.addAttribute("selectedContentVersions", contentService.versions(itemId));
        } catch (RuntimeException | IOException ex) {
            model.addAttribute("importError", ex.getMessage());
        }
        return "portal/content";
    }

    @PostMapping("/portal/content/imports/preview")
    public String previewImport(@ModelAttribute ImportRequest importRequest,
                                @RequestParam("file") MultipartFile file,
                                Model model) {
        populateBaseModel(model);
        model.addAttribute("importRequest", importRequest);

        String validationError = validateRequest(importRequest);
        if (validationError != null) {
            model.addAttribute("importError", validationError);
            return "portal/content";
        }
        if (file == null || file.isEmpty()) {
            model.addAttribute("importError", "Please upload a CSV, JSON, or XLSX file before previewing.");
            return "portal/content";
        }

        String filename = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
            ? "upload"
            : file.getOriginalFilename();
        String format = normalizeFormat(importRequest.getFormat(), filename);
        authorizationScopeService.assertLocationAccess(importRequest.getLocationId());
        long actorUserId = currentUserId();

        try {
            byte[] bytes = file.getBytes();
            ContentImportPreviewResult previewResult = contentImportService.preview(
                importRequest.getLocationId(),
                actorUserId,
                filename,
                format,
                bytes
            );
            model.addAttribute("previewResult", previewResult);
            model.addAttribute("importFile", new ImportFilePayload(
                filename,
                format,
                Base64.getEncoder().encodeToString(bytes)
            ));
        } catch (IllegalArgumentException | IOException ex) {
            model.addAttribute("importError", ex.getMessage());
        }

        return "portal/content";
    }

    @PostMapping("/portal/content/imports/execute")
    public String executeImport(@ModelAttribute ImportRequest importRequest,
                                @RequestParam String filename,
                                @RequestParam String format,
                                @RequestParam String fileBase64,
                                @RequestParam Map<String, String> params,
                                Model model) {
        populateBaseModel(model);
        model.addAttribute("importRequest", importRequest);

        String validationError = validateRequest(importRequest);
        if (validationError != null) {
            model.addAttribute("importError", validationError);
            return "portal/content";
        }
        if (fileBase64 == null || fileBase64.isBlank()) {
            model.addAttribute("importError", "Import file payload is missing. Preview again before executing.");
            return "portal/content";
        }

        authorizationScopeService.assertLocationAccess(importRequest.getLocationId());
        long actorUserId = currentUserId();

        try {
            ContentImportPreviewResult executeResult = contentImportService.execute(
                importRequest.getLocationId(),
                actorUserId,
                filename,
                normalizeFormat(format, filename),
                Base64.getDecoder().decode(fileBase64),
                parseDecisions(params)
            );
            model.addAttribute("executeResult", executeResult);
            model.addAttribute("importSuccess", "Import job completed and decisions were applied.");
        } catch (IllegalArgumentException ex) {
            model.addAttribute("importError", ex.getMessage());
        }

        return "portal/content";
    }

    @GetMapping(value = "/portal/content/imports/{jobId}/errors.csv", produces = "text/csv")
    public ResponseEntity<String> downloadImportErrorReport(@PathVariable long jobId) {
        List<Map<String, Object>> rows = contentImportService.rowsForErrorReport(jobId);
        StringBuilder csv = new StringBuilder();
        csv.append("row_no,status,action_taken,error_code,error_message,duplicate_item_id\n");
        for (Map<String, Object> row : rows) {
            csv.append(csvCell(row.get("row_no"))).append(',')
                .append(csvCell(row.get("status"))).append(',')
                .append(csvCell(row.get("action_taken"))).append(',')
                .append(csvCell(row.get("error_code"))).append(',')
                .append(csvCell(row.get("error_message"))).append(',')
                .append(csvCell(row.get("duplicate_content_item_id")))
                .append('\n');
        }

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=import-job-" + jobId + "-errors.csv")
            .body(csv.toString());
    }

    static Map<Integer, ImportDecision> parseDecisions(Map<String, String> params) {
        Map<Integer, ImportDecision> decisions = new LinkedHashMap<>();
        params.forEach((key, value) -> {
            if (!key.startsWith("decision_") || value == null || value.isBlank()) {
                return;
            }
            try {
                int rowNo = Integer.parseInt(key.substring("decision_".length()));
                decisions.put(rowNo, ImportDecision.valueOf(value.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        });
        return decisions;
    }

    static String normalizeFormat(String requestedFormat, String filename) {
        if (requestedFormat != null && !requestedFormat.isBlank()) {
            return requestedFormat.toLowerCase(Locale.ROOT);
        }
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        if (dot >= 0 && dot < filename.length() - 1) {
            return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        return "csv";
    }

    private static String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        String escaped = text.replace("\"", "\"\"");
        return '"' + escaped + '"';
    }

    private String validateRequest(ImportRequest importRequest) {
        if (importRequest.getLocationId() == null || importRequest.getLocationId() <= 0) {
            return "Location ID must be a positive number.";
        }
        return null;
    }

    private long currentUserId() {
        return authorizationScopeService.requireCurrentUserId();
    }

    private void populateBaseModel(Model model) {
        model.addAttribute("summary", contentService.dashboardSummary());
        if (!model.containsAttribute("importRequest")) {
            model.addAttribute("importRequest", ImportRequest.defaults());
        }
        if (!model.containsAttribute("createDraftForm")) {
            model.addAttribute("createDraftForm", CreateDraftForm.defaults());
        }
        if (!model.containsAttribute("revisionForm")) {
            model.addAttribute("revisionForm", RevisionForm.defaults());
        }
        if (!model.containsAttribute("rollbackForm")) {
            model.addAttribute("rollbackForm", RollbackForm.defaults());
        }
        if (!model.containsAttribute("contentItems")) {
            model.addAttribute("contentItems", contentService.recentItems(1L, 40));
        }
    }

    private String applyStatusAction(long itemId, String action, Model model) {
        populateBaseModel(model);
        model.addAttribute("importRequest", ImportRequest.defaults());
        model.addAttribute("createDraftForm", CreateDraftForm.defaults());
        model.addAttribute("revisionForm", RevisionForm.defaults());
        model.addAttribute("rollbackForm", RollbackForm.defaults());

        authorizationScopeService.assertContentItemScope(itemId);
        if ("publish".equals(action)) {
            contentService.publish(itemId);
            model.addAttribute("importSuccess", "Item published.");
        } else {
            contentService.unpublish(itemId);
            model.addAttribute("importSuccess", "Item unpublished.");
        }
        model.addAttribute("contentItems", contentService.recentItems(1L, 40));
        model.addAttribute("selectedContentItemId", itemId);
        model.addAttribute("selectedContentVersions", contentService.versions(itemId));
        model.addAttribute("selectedContentMedia", contentMediaService.list(itemId));
        return "portal/content";
    }

    public static final class ImportRequest {
        private Long locationId;
        private Long uploadedBy;
        private String format;

        public static ImportRequest defaults() {
            ImportRequest request = new ImportRequest();
            request.setLocationId(1L);
            request.setUploadedBy(1L);
            request.setFormat("csv");
            return request;
        }

        public Long getLocationId() {
            return locationId;
        }

        public void setLocationId(Long locationId) {
            this.locationId = locationId;
        }

        public Long getUploadedBy() {
            return uploadedBy;
        }

        public void setUploadedBy(Long uploadedBy) {
            this.uploadedBy = uploadedBy;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }
    }

    public static final class CreateDraftForm {
        private Long locationId;
        private String term;
        private String phonetic;
        private String category;
        private String grammarPoint;
        private String phraseText;
        private String exampleSentence;
        private String definitionText;

        public static CreateDraftForm defaults() {
            CreateDraftForm form = new CreateDraftForm();
            form.setLocationId(1L);
            form.setCategory("VOCABULARY");
            return form;
        }

        public Long getLocationId() { return locationId; }
        public void setLocationId(Long locationId) { this.locationId = locationId; }
        public String getTerm() { return term; }
        public void setTerm(String term) { this.term = term; }
        public String getPhonetic() { return phonetic; }
        public void setPhonetic(String phonetic) { this.phonetic = phonetic; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getGrammarPoint() { return grammarPoint; }
        public void setGrammarPoint(String grammarPoint) { this.grammarPoint = grammarPoint; }
        public String getPhraseText() { return phraseText; }
        public void setPhraseText(String phraseText) { this.phraseText = phraseText; }
        public String getExampleSentence() { return exampleSentence; }
        public void setExampleSentence(String exampleSentence) { this.exampleSentence = exampleSentence; }
        public String getDefinitionText() { return definitionText; }
        public void setDefinitionText(String definitionText) { this.definitionText = definitionText; }
    }

    public static final class RevisionForm {
        private String grammarPoint;
        private String phraseText;
        private String exampleSentence;
        private String definitionText;

        public static RevisionForm defaults() {
            return new RevisionForm();
        }

        public String getGrammarPoint() { return grammarPoint; }
        public void setGrammarPoint(String grammarPoint) { this.grammarPoint = grammarPoint; }
        public String getPhraseText() { return phraseText; }
        public void setPhraseText(String phraseText) { this.phraseText = phraseText; }
        public String getExampleSentence() { return exampleSentence; }
        public void setExampleSentence(String exampleSentence) { this.exampleSentence = exampleSentence; }
        public String getDefinitionText() { return definitionText; }
        public void setDefinitionText(String definitionText) { this.definitionText = definitionText; }
    }

    public static final class RollbackForm {
        private Integer versionNo;

        public static RollbackForm defaults() {
            RollbackForm form = new RollbackForm();
            form.setVersionNo(1);
            return form;
        }

        public Integer getVersionNo() { return versionNo; }
        public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    }

    public record ImportFilePayload(
        String filename,
        String format,
        String fileBase64
    ) {
    }
}

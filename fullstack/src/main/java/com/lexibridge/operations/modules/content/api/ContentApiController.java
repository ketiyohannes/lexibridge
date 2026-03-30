package com.lexibridge.operations.modules.content.api;

import com.lexibridge.operations.modules.content.model.ContentActionResult;
import com.lexibridge.operations.modules.content.model.ContentCreateRequest;
import com.lexibridge.operations.modules.content.model.ContentImportPreviewResult;
import com.lexibridge.operations.modules.content.model.FileValidationResult;
import com.lexibridge.operations.modules.content.model.ImportDecision;
import com.lexibridge.operations.modules.content.service.ContentService;
import com.lexibridge.operations.modules.content.service.ContentImportService;
import com.lexibridge.operations.modules.content.service.ContentMediaService;
import com.lexibridge.operations.modules.content.service.MediaValidationService;
import com.lexibridge.operations.security.service.AuthorizationScopeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/content")
@PreAuthorize("hasAnyRole('ADMIN','CONTENT_EDITOR','DEVICE_SERVICE')")
public class ContentApiController {

    private final ContentService contentService;
    private final ContentImportService contentImportService;
    private final ContentMediaService contentMediaService;
    private final MediaValidationService mediaValidationService;
    private final AuthorizationScopeService authorizationScopeService;

    public ContentApiController(ContentService contentService,
                                ContentImportService contentImportService,
                                ContentMediaService contentMediaService,
                                MediaValidationService mediaValidationService,
                                AuthorizationScopeService authorizationScopeService) {
        this.contentService = contentService;
        this.contentImportService = contentImportService;
        this.contentMediaService = contentMediaService;
        this.mediaValidationService = mediaValidationService;
        this.authorizationScopeService = authorizationScopeService;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Long locationId = authorizationScopeService.currentLocationScope().orElse(null);
        return contentService.dashboardSummary(locationId);
    }

    @PostMapping("/items")
    public ContentActionResult createDraft(@Valid @RequestBody CreateContentCommand command) {
        authorizationScopeService.assertLocationAccess(command.locationId);
        authorizationScopeService.assertActorUser(command.createdBy);
        return contentService.createDraft(new ContentCreateRequest(
            command.locationId,
            command.createdBy,
            command.term,
            command.phonetic,
            command.category,
            command.grammarPoint,
            command.phraseText,
            command.exampleSentence,
            command.definitionText,
            command.metadata
        ));
    }

    @PostMapping("/items/{itemId}/publish")
    public Map<String, String> publish(@PathVariable long itemId) {
        authorizationScopeService.assertContentItemScope(itemId);
        contentService.publish(itemId);
        return Map.of("status", "PUBLISHED");
    }

    @PostMapping("/items/{itemId}/unpublish")
    public Map<String, String> unpublish(@PathVariable long itemId) {
        authorizationScopeService.assertContentItemScope(itemId);
        contentService.unpublish(itemId);
        return Map.of("status", "UNPUBLISHED");
    }

    @PostMapping("/items/{itemId}/rollback/{versionNo}")
    public ContentActionResult rollback(@PathVariable long itemId, @PathVariable int versionNo) {
        authorizationScopeService.assertContentItemScope(itemId);
        return contentService.rollback(itemId, versionNo);
    }

    @PostMapping("/imports/preview")
    public ContentImportPreviewResult previewImport(@RequestParam long locationId,
                                                    @RequestParam long uploadedBy,
                                                    @RequestParam String format,
                                                    @RequestParam("file") MultipartFile file) throws IOException {
        authorizationScopeService.assertLocationAccess(locationId);
        authorizationScopeService.assertActorUser(uploadedBy);
        return contentImportService.preview(
            locationId,
            uploadedBy,
            file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename(),
            format,
            file.getBytes()
        );
    }

    @PostMapping("/imports/execute")
    public ContentImportPreviewResult executeImport(@Valid @RequestBody ExecuteImportCommand command) {
        authorizationScopeService.assertLocationAccess(command.locationId);
        authorizationScopeService.assertActorUser(command.uploadedBy);
        Map<Integer, ImportDecision> decisions = new HashMap<>();
        if (command.decisions != null) {
            command.decisions.forEach((rowNo, action) -> decisions.put(rowNo, ImportDecision.valueOf(action.toUpperCase())));
        }
        return contentImportService.execute(
            command.locationId,
            command.uploadedBy,
            command.filename,
            command.format,
            Base64.getDecoder().decode(command.fileBase64),
            decisions
        );
    }

    @PostMapping("/media/validate")
    public FileValidationResult validateMedia(@RequestParam("file") MultipartFile file) throws IOException {
        return mediaValidationService.validate(
            file.getOriginalFilename(),
            file.getBytes()
        );
    }

    @PostMapping("/items/{itemId}/media")
    public Map<String, Object> uploadMedia(@PathVariable long itemId,
                                           @RequestParam("file") MultipartFile file,
                                           @RequestParam long actorUserId) throws IOException {
        authorizationScopeService.assertContentItemScope(itemId);
        authorizationScopeService.assertActorUser(actorUserId);
        return contentMediaService.upload(
            itemId,
            file.getOriginalFilename() == null ? "content-media.bin" : file.getOriginalFilename(),
            file.getBytes(),
            actorUserId
        );
    }

    @GetMapping("/items/{itemId}/media")
    public java.util.List<Map<String, Object>> media(@PathVariable long itemId) {
        authorizationScopeService.assertContentItemScope(itemId);
        return contentMediaService.list(itemId);
    }

    @GetMapping("/items/{itemId}/media/{mediaId}/download")
    public ResponseEntity<byte[]> downloadMedia(@PathVariable long itemId,
                                                @PathVariable long mediaId) {
        authorizationScopeService.assertContentItemScope(itemId);
        var binary = contentMediaService.download(itemId, mediaId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(binary.mimeType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=content-media-" + mediaId)
            .body(binary.payload());
    }

    @GetMapping("/exports")
    public ResponseEntity<byte[]> export(@RequestParam long locationId,
                                         @RequestParam(defaultValue = "csv") String format) {
        authorizationScopeService.assertLocationAccess(locationId);
        byte[] bytes = contentService.export(locationId, format);
        String normalized = format.toLowerCase();
        String extension = switch (normalized) {
            case "json" -> "json";
            case "xlsx" -> "xlsx";
            default -> "csv";
        };
        MediaType mediaType = switch (extension) {
            case "json" -> MediaType.APPLICATION_JSON;
            case "xlsx" -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            default -> MediaType.parseMediaType("text/csv");
        };
        return ResponseEntity.ok()
            .contentType(mediaType)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=content-export-location-" + locationId + "." + extension)
            .body(bytes);
    }

    public static class CreateContentCommand {
        @NotNull
        public Long locationId;
        @NotNull
        public Long createdBy;
        @NotBlank
        public String term;
        public String phonetic;
        @NotBlank
        public String category;
        public String grammarPoint;
        public String phraseText;
        public String exampleSentence;
        public String definitionText;
        public Map<String, Object> metadata;
    }

    public static class ExecuteImportCommand {
        @NotNull
        public Long locationId;
        @NotNull
        public Long uploadedBy;
        @NotBlank
        public String filename;
        @NotBlank
        public String format;
        @NotBlank
        public String fileBase64;
        public Map<Integer, String> decisions;
    }
}

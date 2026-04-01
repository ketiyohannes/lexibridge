package com.lexibridge.operations.modules.content.service;

import com.lexibridge.operations.governance.AuditLogService;
import com.lexibridge.operations.modules.content.model.FileValidationResult;
import com.lexibridge.operations.modules.content.repository.ContentRepository;
import com.lexibridge.operations.storage.service.BinaryStorageService;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ContentMediaService {

    private final ContentRepository contentRepository;
    private final MediaValidationService mediaValidationService;
    private final AuditLogService auditLogService;
    private final BinaryStorageService binaryStorageService;

    public ContentMediaService(ContentRepository contentRepository,
                               MediaValidationService mediaValidationService,
                               AuditLogService auditLogService,
                               BinaryStorageService binaryStorageService) {
        this.contentRepository = contentRepository;
        this.mediaValidationService = mediaValidationService;
        this.auditLogService = auditLogService;
        this.binaryStorageService = binaryStorageService;
    }

    @Transactional
    public Map<String, Object> upload(long contentItemId,
                                      String originalFilename,
                                      byte[] content,
                                      long actorUserId) {
        FileValidationResult validation = mediaValidationService.validate(originalFilename, content);
        if (!validation.valid()) {
            throw new IllegalArgumentException(validation.reason());
        }
        String extension = extension(originalFilename);
        String storagePath = "content/" + contentItemId + "/" + validation.checksumSha256() + "." + extension;
        String mediaType = mediaType(validation.detectedMime());
        binaryStorageService.store(storagePath, validation.checksumSha256(), validation.detectedMime(), content);
        contentRepository.insertMedia(
            contentItemId,
            mediaType,
            storagePath,
            validation.detectedMime(),
            content.length,
            validation.checksumSha256()
        );
        auditLogService.logUserEvent(
            actorUserId,
            "CONTENT_MEDIA_UPLOADED",
            "content_item",
            String.valueOf(contentItemId),
            null,
            Map.of("mimeType", validation.detectedMime(), "bytes", content.length)
        );
        return Map.of(
            "contentItemId", contentItemId,
            "mediaType", mediaType,
            "mimeType", validation.detectedMime(),
            "storagePath", storagePath,
            "checksum", validation.checksumSha256(),
            "sizeBytes", content.length
        );
    }

    public List<Map<String, Object>> list(long contentItemId) {
        return contentRepository.mediaForItem(contentItemId);
    }

    public BinaryStorageService.DownloadedBinary download(long contentItemId, long mediaId) {
        Map<String, Object> media = contentRepository.mediaById(mediaId);
        if (media == null) {
            throw new IllegalArgumentException("Content media not found.");
        }
        long actualItemId = ((Number) media.get("content_item_id")).longValue();
        if (actualItemId != contentItemId) {
            throw new IllegalArgumentException("Content media does not belong to requested item.");
        }
        return binaryStorageService.read(String.valueOf(media.get("storage_path")));
    }

    private String extension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "bin";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    private String mediaType(String mime) {
        if (mime == null) {
            return "BINARY";
        }
        if (mime.startsWith("image/")) {
            return "IMAGE";
        }
        if (mime.startsWith("audio/")) {
            return "AUDIO";
        }
        if (mime.startsWith("video/")) {
            return "VIDEO";
        }
        return "BINARY";
    }
}

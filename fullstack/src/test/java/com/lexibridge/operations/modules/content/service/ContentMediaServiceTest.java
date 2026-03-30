package com.lexibridge.operations.modules.content.service;

import com.lexibridge.operations.governance.AuditLogService;
import com.lexibridge.operations.modules.content.model.FileValidationResult;
import com.lexibridge.operations.modules.content.repository.ContentRepository;
import com.lexibridge.operations.storage.service.BinaryStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentMediaServiceTest {

    @Mock
    private ContentRepository contentRepository;
    @Mock
    private MediaValidationService mediaValidationService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private BinaryStorageService binaryStorageService;

    private ContentMediaService contentMediaService;

    @BeforeEach
    void setUp() {
        contentMediaService = new ContentMediaService(contentRepository, mediaValidationService, auditLogService, binaryStorageService);
    }

    @Test
    void upload_shouldRejectInvalidMedia() {
        when(mediaValidationService.validate(eq("bad.exe"), any())).thenReturn(
            new FileValidationResult(false, null, null, "Unsupported media type", "clamav", "blocked")
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            contentMediaService.upload(12L, "bad.exe", new byte[]{1, 2, 3}, 5L)
        );

        assertEquals("Unsupported media type", ex.getMessage());
    }

    @Test
    void upload_shouldPersistMediaMetadataAndAudit() {
        when(mediaValidationService.validate(eq("voice.mp3"), any())).thenReturn(
            new FileValidationResult(true, "audio/mpeg", "abc123", null, "clamav", "clean")
        );

        Map<String, Object> result = contentMediaService.upload(9L, "voice.mp3", new byte[]{1, 2}, 7L);

        assertEquals("AUDIO", result.get("mediaType"));
        assertEquals("content/9/abc123.mp3", result.get("storagePath"));
        verify(binaryStorageService).store(eq("content/9/abc123.mp3"), eq("abc123"), eq("audio/mpeg"), any());
        verify(contentRepository).insertMedia(9L, "AUDIO", "content/9/abc123.mp3", "audio/mpeg", 2, "abc123");
        verify(auditLogService).logUserEvent(eq(7L), eq("CONTENT_MEDIA_UPLOADED"), eq("content_item"), eq("9"), eq(null), any());
    }

    @Test
    void list_shouldReturnRepositoryRows() {
        when(contentRepository.mediaForItem(4L)).thenReturn(List.of(Map.of("id", 1L)));

        List<Map<String, Object>> rows = contentMediaService.list(4L);

        assertEquals(1, rows.size());
        verify(contentRepository).mediaForItem(4L);
    }
}

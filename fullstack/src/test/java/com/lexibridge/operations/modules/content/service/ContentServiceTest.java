package com.lexibridge.operations.modules.content.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexibridge.operations.governance.AuditLogService;
import com.lexibridge.operations.modules.content.model.ContentActionResult;
import com.lexibridge.operations.modules.content.model.ContentCreateRequest;
import com.lexibridge.operations.modules.content.repository.ContentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentServiceTest {

    @Mock
    private ContentRepository contentRepository;
    @Mock
    private AuditLogService auditLogService;

    private ContentService contentService;

    @BeforeEach
    void setUp() {
        contentService = new ContentService(contentRepository, new ContentNormalizationService(), new ObjectMapper(), auditLogService);
    }

    @Test
    void createDraft_shouldReturnDuplicateWhenNormalizedPairExists() {
        when(contentRepository.findDuplicate(1L, "hello", "h e l o")).thenReturn(Optional.of(22L));
        when(contentRepository.currentVersionNo(22L)).thenReturn(2);

        ContentActionResult result = contentService.createDraft(new ContentCreateRequest(
            1L,
            2L,
            "Hello",
            "h.e.l.o",
            "VOCABULARY",
            null,
            null,
            null,
            "x",
            Map.of()
        ));

        assertEquals(true, result.duplicate());
        assertEquals(22L, result.contentItemId());
    }

    @Test
    void rollback_shouldSetCurrentVersionWhenVersionExists() {
        when(contentRepository.versionExists(10L, 3)).thenReturn(true);

        ContentActionResult result = contentService.rollback(10L, 3);

        assertEquals(3, result.versionNo());
        verify(contentRepository).setCurrentVersion(10L, 3);
        verify(contentRepository).setStatus(10L, "DRAFT");
    }

    @Test
    void addRevision_shouldIncrementVersion() {
        when(contentRepository.currentVersionNo(10L)).thenReturn(2);
        when(contentRepository.countVersions(10L)).thenReturn(3);

        ContentActionResult result = contentService.addRevision(10L, 1L, "g", "p", "e", "d", Map.of("k", "v"));

        assertEquals(3, result.versionNo());
        verify(contentRepository).insertVersion(anyLong(), anyInt(), any(), any(), any(), any(), anyString(), anyLong());
    }
}

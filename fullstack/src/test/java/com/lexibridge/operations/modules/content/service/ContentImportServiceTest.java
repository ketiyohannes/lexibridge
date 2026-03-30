package com.lexibridge.operations.modules.content.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexibridge.operations.modules.content.model.ContentImportPreviewResult;
import com.lexibridge.operations.modules.content.model.ImportDecision;
import com.lexibridge.operations.modules.content.repository.ContentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentImportServiceTest {

    @Mock
    private ContentRepository contentRepository;
    @Mock
    private ContentService contentService;

    private ContentImportService contentImportService;

    @BeforeEach
    void setUp() {
        contentImportService = new ContentImportService(
            contentRepository,
            new ContentNormalizationService(),
            contentService,
            new ObjectMapper()
        );
    }

    @Test
    void preview_shouldDetectDuplicateRows() {
        String csv = "term,phonetic,category\nhello,h e l o,VOCABULARY\n";
        when(contentRepository.createImportJob(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(1L);
        when(contentRepository.findDuplicate(1L, "hello", "h e l o")).thenReturn(Optional.of(10L));

        ContentImportPreviewResult result = contentImportService.preview(1L, 2L, "file.csv", "csv", csv.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, result.duplicates());
        assertEquals("DUPLICATE", result.rows().getFirst().status());
    }

    @Test
    void execute_shouldMergeDuplicateWhenDecisionProvided() {
        String json = "[{\"term\":\"hello\",\"phonetic\":\"h e l o\",\"category\":\"VOCABULARY\"}]";
        when(contentRepository.createImportJob(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(2L);
        when(contentRepository.findDuplicate(1L, "hello", "h e l o")).thenReturn(Optional.of(11L));

        ContentImportPreviewResult result = contentImportService.execute(
            1L,
            2L,
            "file.json",
            "json",
            json.getBytes(StandardCharsets.UTF_8),
            Map.of(1, ImportDecision.MERGE_INTO_EXISTING)
        );

        assertEquals(1, result.duplicates());
        verify(contentService).addRevision(anyLong(), anyLong(), any(), any(), any(), any(), any());
    }
}

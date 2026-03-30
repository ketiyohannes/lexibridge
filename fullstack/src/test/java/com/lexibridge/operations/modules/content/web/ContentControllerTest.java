package com.lexibridge.operations.modules.content.web;

import com.lexibridge.operations.modules.content.model.ContentImportPreviewResult;
import com.lexibridge.operations.modules.content.model.ContentActionResult;
import com.lexibridge.operations.modules.content.model.ImportDecision;
import com.lexibridge.operations.modules.content.service.ContentImportService;
import com.lexibridge.operations.modules.content.service.ContentMediaService;
import com.lexibridge.operations.modules.content.service.ContentService;
import com.lexibridge.operations.security.service.AuthorizationScopeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentControllerTest {

    @Mock
    private ContentService contentService;
    @Mock
    private ContentImportService contentImportService;
    @Mock
    private ContentMediaService contentMediaService;
    @Mock
    private AuthorizationScopeService authorizationScopeService;

    @Test
    void parseDecisions_shouldReadValidDecisionParametersOnly() {
        Map<Integer, ImportDecision> result = ContentController.parseDecisions(Map.of(
            "decision_2", "skip",
            "decision_4", "MERGE_INTO_EXISTING",
            "decision_bad", "CREATE_NEW",
            "random", "ignored"
        ));

        assertEquals(2, result.size());
        assertEquals(ImportDecision.SKIP, result.get(2));
        assertEquals(ImportDecision.MERGE_INTO_EXISTING, result.get(4));
    }

    @Test
    void normalizeFormat_shouldFallbackToFilenameExtension() {
        assertEquals("json", ContentController.normalizeFormat("", "batch.json"));
        assertEquals("xlsx", ContentController.normalizeFormat(null, "batch.xlsx"));
        assertEquals("csv", ContentController.normalizeFormat(null, "batch"));
    }

    @Test
    void previewImport_shouldReturnValidationErrorWhenFileIsMissing() {
        when(contentService.dashboardSummary()).thenReturn(Map.of("items", 10));
        ContentController controller = new ContentController(contentService, contentImportService, contentMediaService, authorizationScopeService);
        ContentController.ImportRequest request = ContentController.ImportRequest.defaults();
        MultipartFile file = new org.springframework.mock.web.MockMultipartFile(
            "file", "empty.csv", "text/csv", new byte[0]
        );
        Model model = new ExtendedModelMap();

        String view = controller.previewImport(request, file, model);

        assertEquals("portal/content", view);
        assertTrue(model.containsAttribute("importError"));
        verify(contentImportService, never()).preview(anyLong(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void executeImport_shouldForwardDecisionsToService() {
        when(contentService.dashboardSummary()).thenReturn(Map.of());
        ContentImportPreviewResult result = new ContentImportPreviewResult(7L, 1, 1, 0, 0, List.of());
        when(contentImportService.execute(anyLong(), anyLong(), anyString(), anyString(), any(), anyMap())).thenReturn(result);

        ContentController controller = new ContentController(contentService, contentImportService, contentMediaService, authorizationScopeService);
        ContentController.ImportRequest request = ContentController.ImportRequest.defaults();
        Model model = new ExtendedModelMap();

        String view = controller.executeImport(
            request,
            "test.csv",
            "csv",
            java.util.Base64.getEncoder().encodeToString("term,category\nhello,VOCABULARY\n".getBytes(StandardCharsets.UTF_8)),
            Map.of("decision_3", "CREATE_NEW"),
            model
        );

        ArgumentCaptor<Map<Integer, ImportDecision>> decisionsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(contentImportService).execute(anyLong(), anyLong(), anyString(), anyString(), any(), decisionsCaptor.capture());

        assertEquals("portal/content", view);
        assertEquals(ImportDecision.CREATE_NEW, decisionsCaptor.getValue().get(3));
        assertTrue(model.containsAttribute("executeResult"));
    }

    @Test
    void downloadImportErrorReport_shouldReturnCsvBody() {
        var row = new java.util.HashMap<String, Object>();
        row.put("row_no", 3);
        row.put("status", "INVALID");
        row.put("action_taken", "");
        row.put("error_code", "VALIDATION_ERROR");
        row.put("error_message", "Term is required");
        row.put("duplicate_content_item_id", null);
        when(contentImportService.rowsForErrorReport(44L)).thenReturn(List.of(row));

        ContentController controller = new ContentController(contentService, contentImportService, contentMediaService, authorizationScopeService);
        var response = controller.downloadImportErrorReport(44L);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("row_no,status,action_taken,error_code,error_message,duplicate_item_id"));
        assertTrue(response.getBody().contains("\"INVALID\""));
    }

    @Test
    void createDraft_shouldUseAuthenticatedActor() {
        when(contentService.dashboardSummary()).thenReturn(Map.of());
        when(contentService.recentItems(anyLong(), anyInt())).thenReturn(List.of());
        when(authorizationScopeService.requireCurrentUserId()).thenReturn(9L);
        when(contentService.createDraft(any())).thenReturn(new ContentActionResult(10L, 1, "DRAFT", false, null));

        ContentController controller = new ContentController(contentService, contentImportService, contentMediaService, authorizationScopeService);
        ContentController.CreateDraftForm form = ContentController.CreateDraftForm.defaults();
        form.setTerm("hello");
        form.setCategory("VOCABULARY");
        Model model = new ExtendedModelMap();

        String view = controller.createDraft(form, model);

        assertEquals("portal/content", view);
        verify(authorizationScopeService).requireCurrentUserId();
        assertTrue(model.containsAttribute("draftResult"));
    }
}

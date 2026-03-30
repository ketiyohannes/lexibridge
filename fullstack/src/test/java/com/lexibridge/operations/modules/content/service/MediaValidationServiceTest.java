package com.lexibridge.operations.modules.content.service;

import com.lexibridge.operations.modules.content.model.FileValidationResult;
import com.lexibridge.operations.modules.content.repository.ContentRepository;
import com.lexibridge.operations.security.antivirus.MalwareScanResult;
import com.lexibridge.operations.security.antivirus.MalwareScannerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaValidationServiceTest {

    @Mock
    private ContentRepository contentRepository;
    @Mock
    private MalwareScannerService malwareScannerService;

    private MediaValidationService service;

    @BeforeEach
    void setUp() {
        service = new MediaValidationService(contentRepository, malwareScannerService);
    }

    @Test
    void validate_shouldRejectExtensionMimeMismatch() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        FileValidationResult result = service.validate("audio.mp3", png);
        assertFalse(result.valid());
    }

    @Test
    void validate_shouldRejectDuplicateChecksum() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        when(contentRepository.checksumExists(anyString())).thenReturn(true);
        FileValidationResult result = service.validate("image.png", png);
        assertFalse(result.valid());
    }

    @Test
    void validate_shouldAcceptValidPngWhenNotDuplicate() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        when(contentRepository.checksumExists(anyString())).thenReturn(false);
        when(malwareScannerService.scan(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(new MalwareScanResult(true, "CLAMAV", "clean"));
        FileValidationResult result = service.validate("image.png", png);
        assertTrue(result.valid());
    }

    @Test
    void validate_shouldRejectWhenAntivirusFlagsFile() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        when(contentRepository.checksumExists(anyString())).thenReturn(false);
        when(malwareScannerService.scan(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(new MalwareScanResult(false, "CLAMAV", "Eicar-Test-Signature"));
        FileValidationResult result = service.validate("image.png", png);
        assertFalse(result.valid());
    }
}

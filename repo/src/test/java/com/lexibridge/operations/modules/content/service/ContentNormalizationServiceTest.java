package com.lexibridge.operations.modules.content.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContentNormalizationServiceTest {

    private final ContentNormalizationService normalizationService = new ContentNormalizationService();

    @Test
    void normalizeTerm_shouldLowercaseTrimCollapseAndRemovePunctuationAndDiacritics() {
        String normalized = normalizationService.normalizeTerm("  Café---Au   Lait!  ");
        assertEquals("cafe au lait", normalized);
    }

    @Test
    void normalizePhonetic_shouldNormalizeSymbolsAndSpaces() {
        String normalized = normalizationService.normalizePhonetic(" /ˈhæp.i/  ");
        assertEquals("ˈhæp i", normalized);
    }
}

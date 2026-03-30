package com.lexibridge.operations.modules.content.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;

@Component
public class ContentNormalizationService {

    public String normalizeTerm(String value) {
        return normalizeCommon(value);
    }

    public String normalizePhonetic(String value) {
        return normalizeCommon(value);
    }

    private String normalizeCommon(String value) {
        if (value == null) {
            return "";
        }

        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .replaceAll("[\\p{Punct}]", " ")
            .toLowerCase()
            .trim()
            .replaceAll("\\s+", " ");
        return normalized;
    }
}

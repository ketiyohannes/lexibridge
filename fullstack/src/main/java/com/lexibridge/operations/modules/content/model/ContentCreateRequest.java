package com.lexibridge.operations.modules.content.model;

import java.util.Map;

public record ContentCreateRequest(
    long locationId,
    long createdBy,
    String term,
    String phonetic,
    String category,
    String grammarPoint,
    String phraseText,
    String exampleSentence,
    String definitionText,
    Map<String, Object> metadata
) {
}

package com.lexibridge.operations.modules.moderation.model;

public record ModerationCaseCommand(
    long locationId,
    String targetType,
    long targetId,
    String contentText
) {
}

package com.sytion06.backend.api.dto;

import java.util.Map;
import java.util.UUID;

public record QuestionDto(
        UUID id,
        UUID documentId,
        int pageIndex,
        String numberLabel,
        String stem,
        Map<String, String> choices,   // <-- real object
        String category,
        double confidence,
        boolean needsReview,
        String reviewReason
) {}

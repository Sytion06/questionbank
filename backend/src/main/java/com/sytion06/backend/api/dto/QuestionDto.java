package com.sytion06.backend.api.dto;

import com.sytion06.backend.model.Question;

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
        String reviewReason,
        boolean hasFigure,
        String pageImageUrl
) {
    public static QuestionDto from(Question q,
                                   Map<String, String> parsedChoices,
                                   String pageImageUrl) {
        return new QuestionDto(
                q.getId(),
                q.getDocumentId(),
                q.getPageIndex(),
                q.getNumberLabel(),
                q.getStem(),
                parsedChoices,
                q.getCategory(),
                q.getConfidence(),
                q.isNeedsReview(),
                q.getReviewReason(),
                q.isHasFigure(),
                pageImageUrl
        );
    }
}

package com.sytion06.backend.api;

import com.sytion06.backend.repo.QuestionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sytion06.backend.api.dto.QuestionDto;
import com.sytion06.backend.model.Question;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.RequestParam;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class QuestionController {

    private final ObjectMapper om = new ObjectMapper();

    private final QuestionRepository questionRepo;

    public QuestionController(QuestionRepository questionRepo) {
        this.questionRepo = questionRepo;
    }

    @GetMapping("/api/questions/categories")
    public List<Map<String, Object>> categoryCounts() {
        return questionRepo.countByCategory().stream()
                .map(arr -> Map.of(
                        "category", arr[0],
                        "count", arr[1]
                ))
                .toList();
    }

    @GetMapping("/api/questions")
    public Page<QuestionDto> allQuestions(
            @RequestParam(required = false) String category,
            @RequestParam(required = false, name = "q") String search,
            Pageable pageable
    ) {
        Page<Question> page;

        if (category != null && !category.isBlank() && search != null && !search.isBlank()) {
            page = questionRepo.findByCategoryIgnoreCaseAndStemContainingIgnoreCase(category, search, pageable);
        } else if (category != null && !category.isBlank()) {
            page = questionRepo.findByCategoryIgnoreCase(category, pageable);
        } else if (search != null && !search.isBlank()) {
            page = questionRepo.findByStemContainingIgnoreCase(search, pageable);
        } else {
            page = questionRepo.findAll(pageable);
        }

        return page.map(q -> new QuestionDto(
                q.getId(),
                q.getDocumentId(),
                q.getPageIndex(),
                q.getNumberLabel(),
                q.getStem(),
                parseChoices(q.getChoicesJson()),
                q.getCategory(),
                q.getConfidence(),
                q.isNeedsReview(),
                q.getReviewReason(),
                q.isHasFigure(),
                null // pageImageUrl (we’ll wire this later if you want)
        ));
    }

    @GetMapping("/api/questions/{id}")
    public ResponseEntity<?> getQuestion(@PathVariable UUID id) {
        return questionRepo.findById(id)
                .<ResponseEntity<?>>map(question -> ResponseEntity.ok(new QuestionDto(
                        question.getId(),
                        question.getDocumentId(),
                        question.getPageIndex(),
                        question.getNumberLabel(),
                        question.getStem(),
                        parseChoices(question.getChoicesJson()),
                        question.getCategory(),
                        question.getConfidence(),
                        question.isNeedsReview(),
                        question.getReviewReason(),
                        question.isHasFigure(),
                        buildPageImageUrl(question)
                )))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Not found")));
    }

    private Map<String, String> parseChoices(String choicesJson) {
        try {
            if (choicesJson == null || choicesJson.isBlank()) return null;
            return om.readValue(choicesJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private String buildPageImageUrl(Question q) {
        // Only return a URL if we actually have a file name
        if (q.getPageImageFile() == null || q.getPageImageFile().isBlank()) return null;

        // IMPORTANT:
        // Your DocumentController serves images at:
        //   /api/documents/{docId}/pages/{fileName}
        // Your stored file path is storage/{docId}/pages/{fileName}
        //
        // In Question entity, documentId is the owning document id. Use that.
        return "/api/documents/" + q.getDocumentId() + "/pages/" + q.getPageImageFile();
    }
}
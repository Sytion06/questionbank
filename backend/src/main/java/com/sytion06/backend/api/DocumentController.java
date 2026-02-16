package com.sytion06.backend.api;

import com.sytion06.backend.model.Document;
import com.sytion06.backend.model.DocumentStatus;
import com.sytion06.backend.repo.DocumentRepository;
import com.sytion06.backend.repo.QuestionRepository;
import com.sytion06.backend.service.DocumentProcessingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.scheduling.annotation.Async;
import java.util.concurrent.CompletableFuture;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sytion06.backend.api.dto.QuestionDto;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final Path storageDir = Paths.get("storage");
    private final DocumentRepository documents;
    private final DocumentProcessingService processing;
    private final QuestionRepository questionRepo;
    private final ObjectMapper om = new ObjectMapper();

    public DocumentController(DocumentRepository documents, DocumentProcessingService processing,
                              QuestionRepository questionRepo) {
        this.documents = documents;
        this.processing = processing;
        this.questionRepo = questionRepo;
    }

    @PostMapping
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!name.toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only PDF is supported"));
        }

        Files.createDirectories(storageDir);

        Document doc = new Document();
        doc.setFilename(name);
        doc.setStatus(DocumentStatus.UPLOADED);
        doc = documents.save(doc); // generates id via @PrePersist

        Path target = storageDir.resolve(doc.getId() + ".pdf");
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

        return ResponseEntity.ok(Map.of(
                "docId", doc.getId().toString(),
                "filename", doc.getFilename(),
                "status", doc.getStatus().name()
        ));
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return documents.findAll().stream().sorted(Comparator.comparing(Document::getCreatedAt).reversed())
                .map(d -> Map.<String, Object>of(
                        "docId", d.getId().toString(),
                        "filename", d.getFilename(),
                        "status", d.getStatus().name(),
                        "createdAt", d.getCreatedAt().toString()
                ))
                .toList();
    }

    @GetMapping("/{docId}")
    public ResponseEntity<?> get(@PathVariable UUID docId) {
        return documents.findById(docId)
                .<ResponseEntity<?>>map(d -> ResponseEntity.ok(Map.of(
                        "docId", d.getId().toString(),
                        "filename", d.getFilename(),
                        "status", d.getStatus().name(),
                        "createdAt", d.getCreatedAt().toString()
                )))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Not found")));
    }

    @PostMapping("/{docId}/process")
    public ResponseEntity<?> process(@PathVariable UUID docId) {
        Document doc = documents.findById(docId).orElse(null);
        if (doc == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Not found"));
        }

        if (doc.getStatus() == DocumentStatus.PROCESSING) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", "Document is already processing"));
        }

        runAsync(docId);
        return ResponseEntity.ok(Map.of("docId", docId.toString(), "status", "PROCESSING"));
    }

    @GetMapping("/{docId}/pages/{fileName}")
    public ResponseEntity<Resource> getPageImage(
            @PathVariable UUID docId,
            @PathVariable String fileName) throws Exception {

        Path file = Paths.get("storage")
                .resolve(docId.toString())
                .resolve("pages")
                .resolve(fileName);

        if (!Files.exists(file)) {
            return ResponseEntity.status(404).build();
        }

        Resource resource = new UrlResource(file.toUri());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "image/png")
                .body(resource);
    }

    @Async
    public CompletableFuture<Void> runAsync(UUID docId) {
        try {
            processing.process(docId);
        } catch (Exception e) {
            e.printStackTrace();

            documents.findById(docId).ifPresent(d -> {
                // Only mark FAILED if still processing (prevents overwriting DONE from a later run)
                if (d.getStatus() == DocumentStatus.PROCESSING) {
                    d.setStatus(DocumentStatus.FAILED);
                    d.setLastError(e.getMessage());
                    documents.save(d);
                }
            });
        }
        return CompletableFuture.completedFuture(null);
    }

    @GetMapping("/{docId}/questions")
    public List<QuestionDto> questions(@PathVariable UUID docId) {
        return questionRepo.findByDocumentIdOrderByPageIndexAsc(docId).stream()
                .map(q -> new QuestionDto(
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
                        "/api/documents/"
                                + q.getDocumentId()
                                + "/pages/"
                                + q.getPageImageFile()
                ))
                .toList();
    }

    private Map<String, String> parseChoices(String choicesJson) {
        try {
            if (choicesJson == null || choicesJson.isBlank()) return null;
            return om.readValue(choicesJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}

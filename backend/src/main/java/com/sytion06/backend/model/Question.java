package com.sytion06.backend.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
public class Question {
    @Id
    private UUID id;

    private UUID documentId;

    private int pageIndex;        // 0-based page
    private String numberLabel;   // e.g. "1", "13", "填空9"

    @Lob
    private String stem;          // main question text

    @Lob
    private String choicesJson;   // store choices as JSON string for MVP (A/B/C/D)

    @Column(name = "category")
    private String category;      // algebra, trig, vectors, geometry, probability...
    private double confidence;    // 0..1
    private boolean needsReview;  // true if low confidence / blurry / unclear
    private String reviewReason;

    private Instant createdAt;

    private boolean hasFigure;
    @Column(name = "page_image_file")
    private String pageImageFile;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    // getters/setters omitted for brevity (generate in IDE)
    // ...

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public void setPageIndex(int pageIndex) {
        this.pageIndex = pageIndex;
    }

    public String getNumberLabel() {
        return numberLabel;
    }

    public void setNumberLabel(String numberLabel) {
        this.numberLabel = numberLabel;
    }

    public String getStem() {
        return stem;
    }

    public void setStem(String stem) {
        this.stem = stem;
    }

    public String getChoicesJson() {
        return choicesJson;
    }

    public void setChoicesJson(String choicesJson) {
        this.choicesJson = choicesJson;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public boolean isNeedsReview() {
        return needsReview;
    }

    public void setNeedsReview(boolean needsReview) {
        this.needsReview = needsReview;
    }

    public String getReviewReason() {
        return reviewReason;
    }

    public void setReviewReason(String reviewReason) {
        this.reviewReason = reviewReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isHasFigure() {
        return hasFigure;
    }

    public void setHasFigure(boolean hasFigure) {
        this.hasFigure = hasFigure;
    }

    public String getPageImageFile() {
        return pageImageFile;
    }

    public void setPageImageFile(String pageImageFile) {
        this.pageImageFile = pageImageFile;
    }
}

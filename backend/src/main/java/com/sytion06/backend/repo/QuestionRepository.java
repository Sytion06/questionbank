package com.sytion06.backend.repo;

import com.sytion06.backend.model.Question;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QuestionRepository extends JpaRepository<Question, UUID> {
    List<Question> findByDocumentIdOrderByPageIndexAsc(UUID documentId);

    void deleteByDocumentId(UUID documentId);
}

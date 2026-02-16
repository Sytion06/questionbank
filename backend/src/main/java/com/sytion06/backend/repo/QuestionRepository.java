package com.sytion06.backend.repo;

import com.sytion06.backend.model.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface QuestionRepository extends JpaRepository<Question, UUID> {
    List<Question> findByDocumentIdOrderByPageIndexAsc(UUID documentId);

    void deleteByDocumentId(UUID documentId);

    Page<Question> findByCategory(String category, Pageable pageable);

    Page<Question> findByCategoryOrderByCreatedAtDesc(String category, Pageable pageable);

    @Query("SELECT q.category, COUNT(q) FROM Question q GROUP BY q.category")
    List<Object[]> countByCategory();

    Page<Question> findByCategoryIgnoreCase(String category, Pageable pageable);

    Page<Question> findByStemContainingIgnoreCase(String stem, Pageable pageable);

    Page<Question> findByCategoryIgnoreCaseAndStemContainingIgnoreCase(String category, String stem, Pageable pageable);
}

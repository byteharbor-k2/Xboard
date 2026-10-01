package com.sinx.platform.content.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sinx.platform.content.domain.KnowledgeArticle;

public interface KnowledgeArticleRepository
    extends JpaRepository<KnowledgeArticle, UUID> {

    /** The admin list: sort ASC, newest id first on ties. */
    List<KnowledgeArticle> findAllByOrderBySortAscIdDesc();

    /** The user listing of one article language, shown ones only. */
    List<KnowledgeArticle>
        findByShownTrueAndLanguageOrderBySortAscIdDesc(String language);

    List<KnowledgeArticle> findAllByOrderByCategoryAsc();

    /** One shown article, whatever its language - the detail view. */
    Optional<KnowledgeArticle> findByIdAndShownTrue(UUID id);

    @Query("""
        SELECT a FROM KnowledgeArticle a
        WHERE a.shown = TRUE
          AND a.language = :language
          AND (LOWER(a.title) LIKE LOWER(:keyword)
               OR LOWER(a.body) LIKE LOWER(:keyword))
        ORDER BY a.sort ASC, a.id DESC
        """)
    List<KnowledgeArticle> searchShown(
        @Param("language") String language,
        @Param("keyword") String keyword
    );
}

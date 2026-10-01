package com.sinx.platform.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.content.domain.KnowledgeArticle;
import com.sinx.platform.content.repository.KnowledgeArticleRepository;
import com.sinx.platform.shared.web.ApiProblemException;

/**
 * Creates, edits and retires knowledge-base articles on the
 * Xboard-compatible admin surface. Categories are free-form strings the
 * operator types: the original panel never governed them beyond being
 * required, and grouping the help centre is the list's job, not a table's.
 *
 * Like notices, articles new ones hide ({@code show}) until the operator
 * turns them on.
 */
@Service
public class AdminKnowledgeService {

    private final KnowledgeArticleRepository articles;
    private final Clock clock;

    public AdminKnowledgeService(
        KnowledgeArticleRepository articles,
        Clock clock
    ) {
        this.articles = articles;
        this.clock = clock;
    }

    /**
     * The admin list, sort ASC then newest id first on ties. Full = the one
     * row with its body, mirroring the original fetch-by-id answer.
     */
    @Transactional(readOnly = true)
    public List<AdminKnowledgeView> list() {
        return articles
            .findAll(Sort.by(Sort.Direction.ASC, "sort")
                .and(Sort.by(Sort.Direction.DESC, "id")))
            .stream()
            .map(article -> AdminKnowledgeView.from(article, false))
            .toList();
    }

    @Transactional(readOnly = true)
    public Optional<AdminKnowledgeView> get(UUID id) {
        return articles.findById(id)
            .map(article -> AdminKnowledgeView.from(article, true));
    }

    /** The categories some article actually uses, alphabetically. */
    @Transactional(readOnly = true)
    public List<String> categories() {
        return articles.findAllByOrderByCategoryAsc().stream()
            .map(KnowledgeArticle::getCategory)
            .distinct()
            .toList();
    }

    @Transactional
    public AdminKnowledgeView save(KnowledgeDraft draft) {
        if (draft.category() == null || draft.category().isBlank()) {
            throw invalid("The article category is required");
        }
        if (draft.language() == null || draft.language().isBlank()) {
            throw invalid("The article language is required");
        }
        if (draft.title() == null || draft.title().isBlank()) {
            throw invalid("The article title is required");
        }
        if (draft.body() == null || draft.body().isBlank()) {
            throw invalid("The article body is required");
        }
        Instant now = Instant.now(clock);
        KnowledgeArticle article;
        if (draft.id() == null) {
            article = KnowledgeArticle.create(
                draft.category().trim(),
                draft.language().trim(),
                draft.title().trim(),
                now
            );
            article.redefine(
                article.getCategory(),
                article.getLanguage(),
                article.getTitle(),
                draft.body(),
                draft.shown(),
                draft.sort(),
                now
            );
        } else {
            article = articles.findById(draft.id())
                .orElseThrow(() -> problem(
                    HttpStatus.NOT_FOUND,
                    "KNOWLEDGE_NOT_FOUND",
                    "The article does not exist"
                ));
            article.redefine(
                draft.category().trim(),
                draft.language().trim(),
                draft.title().trim(),
                draft.body(),
                draft.shown(),
                draft.sort(),
                now
            );
        }
        articles.save(article);
        return AdminKnowledgeView.fullOf(article);
    }

    /** Toggles the help centre visibility, as-is like the original's show. */
    @Transactional
    public AdminKnowledgeView toggleShow(UUID id) {
        KnowledgeArticle article = articles.findById(id)
            .orElseThrow(() -> problem(
                HttpStatus.NOT_FOUND,
                "KNOWLEDGE_NOT_FOUND",
                "The article does not exist"
            ));
        article.toggleShown(Instant.now(clock));
        return AdminKnowledgeView.summaryOf(article);
    }

    @Transactional
    public void delete(UUID id) {
        KnowledgeArticle article = articles.findById(id)
            .orElseThrow(() -> problem(
                HttpStatus.NOT_FOUND,
                "KNOWLEDGE_NOT_FOUND",
                "The article does not exist"
            ));
        articles.delete(article);
    }

    /** Each id gets its list position as sort, starting at one. */
    @Transactional
    public void applySort(List<UUID> ids) {
        if (ids == null) {
            throw invalid("The sorted id list is required");
        }
        int position = 1;
        for (UUID id : ids) {
            KnowledgeArticle article = articles.findById(id)
                .orElseThrow(() -> problem(
                    HttpStatus.NOT_FOUND,
                    "KNOWLEDGE_NOT_FOUND",
                    "The article does not exist"
                ));
            article.redefine(
                article.getCategory(),
                article.getLanguage(),
                article.getTitle(),
                article.getBody(),
                article.isShown(),
                position++,
                Instant.now(clock)
            );
            articles.save(article);
        }
    }

    private ApiProblemException invalid(String detail) {
        return problem(
            HttpStatus.BAD_REQUEST,
            "KNOWLEDGE_DEFINITION_INVALID",
            detail
        );
    }

    private ApiProblemException problem(
        HttpStatus status,
        String code,
        String detail
    ) {
        return new ApiProblemException(status, code, detail);
    }

    /** One operator submission. Null optionals keep what was there. */
    public record KnowledgeDraft(
        UUID id,
        String category,
        String language,
        String title,
        String body,
        Boolean shown,
        Integer sort
    ) {
    }
}

package com.sinx.platform.content.graphql;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import com.sinx.platform.content.domain.KnowledgeArticle;
import com.sinx.platform.content.domain.Notice;
import com.sinx.platform.content.repository.KnowledgeArticleRepository;
import com.sinx.platform.content.repository.NoticeRepository;

/**
 * The account-facing content queries: the dashboard's announcement carousel
 * and the help centre's articles, both behind the viewer scope like the other
 * viewer fields. Only shown rows are ever served - hiding is the operator's
 * editorial decision, not the viewer's.
 */
@Controller
public class ContentQueryController {

    private static final String DEFAULT_LANGUAGE = "zh-CN";

    private final NoticeRepository notices;
    private final KnowledgeArticleRepository articles;

    public ContentQueryController(
        NoticeRepository notices,
        KnowledgeArticleRepository articles
    ) {
        this.notices = notices;
        this.articles = articles;
    }

    /**
     * The dashboard carousel, sort ASC then newest id first - exactly like
     * the original user notice fetch minus its paging, which the carousel
     * never used for anything but a cap.
     */
    @QueryMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    List<NoticePayload> viewerNotices() {
        return notices.findByShownTrueOrderBySortAscIdDesc().stream()
            .map(NoticePayload::from)
            .toList();
    }

    /**
     * The help centre listing, grouped by category in a stable order. Optional
     * keyword filters title or body, mirroring the original's keyword search.
     */
    @QueryMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    List<KnowledgeGroupPayload> viewerKnowledge(
        @AuthenticationPrincipal Jwt jwt,
        @Argument String language,
        @Argument String keyword
    ) {
        String selectedLanguage = language == null || language.isBlank()
            ? DEFAULT_LANGUAGE
            : language.trim();
        List<KnowledgeArticle> shown =
            keyword == null || keyword.isBlank()
                ? articles.findByShownTrueAndLanguageOrderBySortAscIdDesc(
                    selectedLanguage)
                : articles.searchShown(
                    selectedLanguage,
                    "%" + keyword.trim() + "%");
        Map<String, List<KnowledgeArticlePayload>> grouped =
            new LinkedHashMap<>();
        for (KnowledgeArticle article : shown) {
            grouped.computeIfAbsent(
                article.getCategory(),
                category -> new ArrayList<>()
            ).add(KnowledgeArticlePayload.from(article, true));
        }
        return grouped.entrySet().stream()
            .map(entry -> new KnowledgeGroupPayload(
                entry.getKey(),
                entry.getValue()
            ))
            .toList();
    }

    @QueryMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    KnowledgeArticlePayload viewerKnowledgeArticle(@Argument UUID id) {
        return articles.findByIdAndShownTrue(id)
            .map(article -> KnowledgeArticlePayload.from(article, false))
            .orElse(null);
    }

    /**
     * One carousel announcement. {@code publishedAt} is the row's creation,
     * serialized to the gateway's timestamp string like the viewer's.
     */
    record NoticePayload(
        UUID id,
        String title,
        String content,
        String imgUrl,
        boolean popup,
        Instant publishedAt
    ) {

        static NoticePayload from(Notice notice) {
            return new NoticePayload(
                notice.getId(),
                notice.getTitle(),
                notice.getContent(),
                notice.getImgUrl(),
                notice.isPopup(),
                notice.getCreatedAt()
            );
        }
    }

    record KnowledgeGroupPayload(
        String category,
        List<KnowledgeArticlePayload> articles
    ) {
    }

    /**
     * One article; the listing passes only id, title and updatedAt, while the
     * detail view adds category, language and the body itself.
     */
    record KnowledgeArticlePayload(
        UUID id,
        String title,
        String category,
        String language,
        String body,
        Instant updatedAt
    ) {

        static KnowledgeArticlePayload from(
            KnowledgeArticle article,
            boolean listing
        ) {
            return new KnowledgeArticlePayload(
                article.getId(),
                article.getTitle(),
                listing ? null : article.getCategory(),
                listing ? null : article.getLanguage(),
                listing ? null : article.getBody(),
                article.getUpdatedAt()
            );
        }
    }
}

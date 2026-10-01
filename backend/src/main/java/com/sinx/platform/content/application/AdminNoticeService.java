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

import com.sinx.platform.content.domain.Notice;
import com.sinx.platform.content.repository.NoticeRepository;
import com.sinx.platform.shared.web.ApiProblemException;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Creates, edits and retires portal notices on the Xboard-compatible admin
 * surface. A notice the operator forgets to {@code show} simply never reaches
 * the account dashboard - invisible by mistake beats visible by accident, so
 * new notices hide until the operator turns them on, like the original panel's
 * save when {@code show} is left out.
 */
@Service
public class AdminNoticeService {

    private static final TypeReference<List<String>> STRING_LIST =
        new TypeReference<>() {
        };

    private final NoticeRepository notices;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AdminNoticeService(
        NoticeRepository notices,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.notices = notices;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Everything the operator wrote, carousel order first: sort ASC then
     * newest id first, exactly like the original fetch.
     */
    @Transactional(readOnly = true)
    public List<AdminNoticeView> list() {
        return notices
            .findAll(Sort.by(Sort.Direction.ASC, "sort")
                .and(Sort.by(Sort.Direction.DESC, "id")))
            .stream()
            .map(this::viewOf)
            .toList();
    }

    @Transactional(readOnly = true)
    public Optional<AdminNoticeView> get(UUID id) {
        return notices.findById(id).map(this::viewOf);
    }

    @Transactional
    public AdminNoticeView save(NoticeDraft draft) {
        if (draft.title() == null || draft.title().isBlank()) {
            throw invalid("The notice title is required");
        }
        if (draft.content() == null || draft.content().isBlank()) {
            throw invalid("The notice content is required");
        }
        Instant now = Instant.now(clock);
        Notice notice;
        if (draft.id() == null) {
            notice = Notice.create(
                draft.title().trim(),
                draft.content(),
                now
            );
            notice.redefine(
                notice.getTitle(),
                notice.getContent(),
                draft.imgUrl(),
                encodedTags(draft.tags()),
                draft.shown(),
                draft.popup(),
                draft.sort(),
                now
            );
        } else {
            notice = notices.findById(draft.id()).orElseThrow(() -> problem(
                HttpStatus.NOT_FOUND,
                "NOTICE_NOT_FOUND",
                "The notice does not exist"
            ));
            notice.redefine(
                draft.title().trim(),
                draft.content(),
                draft.imgUrl(),
                encodedTags(draft.tags()),
                draft.shown(),
                draft.popup(),
                draft.sort(),
                now
            );
        }
        notices.save(notice);
        return viewOf(notice);
    }

    /** Toggles the carousel visibility, as-is like the original's show. */
    @Transactional
    public AdminNoticeView toggleShow(UUID id) {
        Notice notice = notices.findById(id).orElseThrow(() -> problem(
            HttpStatus.NOT_FOUND,
            "NOTICE_NOT_FOUND",
            "The notice does not exist"
        ));
        notice.toggleShown(Instant.now(clock));
        return viewOf(notice);
    }

    @Transactional
    public void delete(UUID id) {
        Notice notice = notices.findById(id).orElseThrow(() -> problem(
            HttpStatus.NOT_FOUND,
            "NOTICE_NOT_FOUND",
            "The notice does not exist"
        ));
        notices.delete(notice);
    }

    /**
     * Applies the drag order the admin page submits: each id gets its list
     * position as sort, starting at one.
     */
    @Transactional
    public void applySort(List<UUID> ids) {
        if (ids == null) {
            throw invalid("The sorted id list is required");
        }
        int position = 1;
        for (UUID id : ids) {
            Notice notice = notices.findById(id).orElseThrow(() -> problem(
                HttpStatus.NOT_FOUND,
                "NOTICE_NOT_FOUND",
                "The notice does not exist"
            ));
            notice.redefine(
                notice.getTitle(),
                notice.getContent(),
                null,
                null,
                notice.isShown(),
                notice.isPopup(),
                position++,
                Instant.now(clock)
            );
            notices.save(notice);
        }
    }

    private String encodedTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "[]";
        }
        return objectMapper.writeValueAsString(tags);
    }

    List<String> decodedTags(Notice notice) {        try {
            List<String> tags =
                objectMapper.readValue(notice.getTags(), STRING_LIST);
            return tags == null ? List.of() : tags;
        } catch (RuntimeException exception) {
            // A malformed tag list must not silently hide what was written,
            // so surfacing the breakage beats rendering an empty list.
            throw problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "NOTICE_MISCONFIGURED",
                "The notice tags could not be read"
            );
        }
    }

    private AdminNoticeView viewOf(Notice notice) {
        return AdminNoticeView.from(notice, decodedTags(notice));
    }

    private ApiProblemException invalid(String detail) {
        return problem(HttpStatus.BAD_REQUEST, "NOTICE_DEFINITION_INVALID", detail);
    }

    private ApiProblemException problem(
        HttpStatus status,
        String code,
        String detail
    ) {
        return new ApiProblemException(status, code, detail);
    }

    /** One operator submission. Null optionals keep what was there. */
    public record NoticeDraft(
        UUID id,
        String title,
        String content,
        String imgUrl,
        List<String> tags,
        Boolean shown,
        Boolean popup,
        Integer sort
    ) {
    }
}

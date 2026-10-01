package com.sinx.platform.content.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sinx.platform.content.application.AdminKnowledgeService;
import com.sinx.platform.content.application.AdminKnowledgeView;
import com.sinx.platform.shared.web.ApiProblemException;

/**
 * Knowledge-base administration, mirroring the original panel's admin paths:
 * {@code /api/v2/admin/knowledge/*} with fetch/getCategory/save/drop/show/sort
 * and the same XboardResponse envelope. {@code fetch} returns the summary
 * list without bodies, or the one full article when an id is given - exactly
 * like the original.
 */
@RestController
@RequestMapping("/api/v2/admin/knowledge")
public class AdminKnowledgeController {

    private final AdminKnowledgeService knowledge;

    public AdminKnowledgeController(AdminKnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping("/fetch")
    XboardResponse<?> fetch(
        @RequestParam(name = "id", required = false) UUID id
    ) {
        if (id == null) {
            return XboardResponse.of(knowledge.list());
        }
        AdminKnowledgeView article = knowledge.get(id)
            .orElseThrow(() -> new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "KNOWLEDGE_NOT_FOUND",
                "The article does not exist"
            ));
        return XboardResponse.of(article);
    }

    @GetMapping("/getCategory")
    XboardResponse<List<String>> getCategories() {
        return XboardResponse.of(knowledge.categories());
    }

    @PostMapping("/save")
    XboardResponse<AdminKnowledgeView> save(@RequestBody KnowledgeRequest request) {
        return XboardResponse.of(knowledge.save(request.toDraft()));
    }

    @PostMapping("/show")
    XboardResponse<AdminKnowledgeView> show(@RequestBody IdRequest request) {
        return XboardResponse.of(knowledge.toggleShow(request.id()));
    }

    @PostMapping("/drop")
    XboardResponse<Boolean> drop(@RequestBody IdRequest request) {
        knowledge.delete(request.id());
        return XboardResponse.of(true);
    }

    @PostMapping("/sort")
    XboardResponse<Boolean> sort(@RequestBody SortRequest request) {
        knowledge.applySort(request.ids());
        return XboardResponse.of(true);
    }

    /**
     * One operator submission. {@code id} is only read by save-as-edit;
     * {@code show} and {@code sort} stay null when the edit form does not
     * carry them.
     */
    record KnowledgeRequest(
        UUID id,
        String category,
        String language,
        String title,
        String body,
        Boolean show,
        Integer sort
    ) {

        AdminKnowledgeService.KnowledgeDraft toDraft() {
            return new AdminKnowledgeService.KnowledgeDraft(
                id,
                category,
                language,
                title,
                body,
                show,
                sort
            );
        }
    }

    record IdRequest(UUID id) {
    }

    record SortRequest(List<UUID> ids) {
    }

    record XboardResponse<T>(T data) {
        static <T> XboardResponse<T> of(T data) {
            return new XboardResponse<>(data);
        }
    }
}

package com.sinx.platform.content.web;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sinx.platform.content.application.AdminNoticeService;
import com.sinx.platform.content.application.AdminNoticeView;

/**
 * Notice administration, mirroring the original panel's admin paths:
 * {@code /api/v2/admin/notice/*} with fetch/save/drop/show/sort and the
 * same XboardResponse envelope. Access is already decided by the security
 * configuration, which requires both the admin role and the admin token
 * scope for everything under {@code /api/v2/admin}.
 *
 * The original's inline {@code update} duplicate of {@code save} is not
 * carried over: one save endpoint takes its place - create when no id,
 * edit when any.
 */
@RestController
@RequestMapping("/api/v2/admin/notice")
public class AdminNoticeController {

    private final AdminNoticeService notices;

    public AdminNoticeController(AdminNoticeService notices) {
        this.notices = notices;
    }

    @GetMapping("/fetch")
    XboardResponse<List<AdminNoticeView>> fetch() {
        return XboardResponse.of(notices.list());
    }

    @PostMapping("/save")
    XboardResponse<AdminNoticeView> save(@RequestBody NoticeRequest request) {
        return XboardResponse.of(notices.save(request.toDraft()));
    }

    @PostMapping("/show")
    XboardResponse<AdminNoticeView> show(@RequestBody IdRequest request) {
        return XboardResponse.of(notices.toggleShow(request.id()));
    }

    @PostMapping("/drop")
    XboardResponse<Boolean> drop(@RequestBody IdRequest request) {
        notices.delete(request.id());
        return XboardResponse.of(true);
    }

    @PostMapping("/sort")
    XboardResponse<Boolean> sort(@RequestBody SortRequest request) {
        notices.applySort(request.ids());
        return XboardResponse.of(true);
    }

    /**
     * One operator submission. {@code id} is only read by save-as-edit;
     * null optionals keep what was already there, so a partial edit form
     * cannot silently blank a notice's image or tags.
     */
    record NoticeRequest(
        UUID id,
        String title,
        String content,
        @com.fasterxml.jackson.annotation.JsonProperty("img_url") String imgUrl,
        List<String> tags,
        Boolean show,
        Boolean popup,
        Integer sort
    ) {

        AdminNoticeService.NoticeDraft toDraft() {
            return new AdminNoticeService.NoticeDraft(
                id,
                title,
                content,
                imgUrl,
                tags,
                show,
                popup,
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

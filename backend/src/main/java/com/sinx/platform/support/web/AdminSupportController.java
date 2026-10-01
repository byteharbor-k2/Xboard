package com.sinx.platform.support.web;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sinx.platform.support.application.SupportChatService;
import com.sinx.platform.support.application.SupportChatService.ConversationView;
import com.sinx.platform.support.application.SupportChatService.MessageView;

@RestController
@RequestMapping("/api/v2/admin/support")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('SCOPE_ADMIN')")
public class AdminSupportController {

    private final SupportChatService support;

    public AdminSupportController(SupportChatService support) {
        this.support = support;
    }

    @GetMapping("/conversations")
    XboardResponse<List<ConversationView>> conversations() {
        return XboardResponse.of(support.conversations());
    }

    @GetMapping("/conversations/{userId}/messages")
    XboardResponse<List<MessageView>> messages(@PathVariable UUID userId) {
        return XboardResponse.of(support.adminMessages(userId));
    }

    @PostMapping("/conversations/{userId}/reply")
    XboardResponse<MessageView> reply(
        @PathVariable UUID userId,
        @RequestBody ReplyRequest request
    ) {
        return XboardResponse.of(support.reply(userId, request.content()));
    }

    record ReplyRequest(String content) { }

    record XboardResponse<T>(T data) {
        static <T> XboardResponse<T> of(T data) { return new XboardResponse<>(data); }
    }
}

package com.sinx.platform.support.application;

import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.shared.web.ApiProblemException;

/** Persistence and ownership rules for the one-to-one user support chat. */
@Service
public class SupportChatService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SupportChatService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<MessageView> userMessages(UUID userId) {
        return jdbc.query("""
            SELECT m.sender, m.content, m.created_at
            FROM support_conversations c
            JOIN support_messages m ON m.conversation_id = c.id
            WHERE c.user_id = ?
            ORDER BY m.created_at, m.id
            """, (rs, row) -> new MessageView(
                rs.getString("sender"), rs.getString("content"),
                rs.getTimestamp("created_at").toInstant()
            ), userId);
    }

    @Transactional
    public MessageView sendUserMessage(UUID userId, String content) {
        String message = requiredContent(content);
        Instant now = Instant.now(clock);
        UUID conversationId = jdbc.queryForObject("""
            INSERT INTO support_conversations (id, user_id, created_at, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE SET updated_at = EXCLUDED.updated_at
            RETURNING id
            """, UUID.class, UUID.randomUUID(), userId, Timestamp.from(now), Timestamp.from(now));
        return insertMessage(conversationId, "USER", message, now);
    }

    @Transactional(readOnly = true)
    public List<ConversationView> conversations() {
        return jdbc.query("""
            SELECT c.user_id, u.display_name, u.email, c.updated_at,
                   latest.content AS last_message, latest.sender AS last_sender
            FROM support_conversations c
            JOIN users u ON u.id = c.user_id
            LEFT JOIN LATERAL (
                SELECT content, sender FROM support_messages
                WHERE conversation_id = c.id
                ORDER BY created_at DESC, id DESC LIMIT 1
            ) latest ON TRUE
            ORDER BY c.updated_at DESC
            """, (rs, row) -> new ConversationView(
                rs.getObject("user_id", UUID.class), rs.getString("display_name"),
                rs.getString("email"), rs.getString("last_message"),
                rs.getString("last_sender"), rs.getTimestamp("updated_at").toInstant()
            ));
    }

    @Transactional(readOnly = true)
    public List<MessageView> adminMessages(UUID userId) {
        return userMessages(userId);
    }

    @Transactional
    public MessageView reply(UUID userId, String content) {
        String message = requiredContent(content);
        UUID conversationId = jdbc.query("""
            SELECT id FROM support_conversations WHERE user_id = ?
            """, rs -> rs.next() ? rs.getObject("id", UUID.class) : null, userId);
        if (conversationId == null) {
            throw new ApiProblemException(
                HttpStatus.NOT_FOUND, "SUPPORT_CONVERSATION_NOT_FOUND",
                "The support conversation does not exist"
            );
        }
        Instant now = Instant.now(clock);
        jdbc.update("UPDATE support_conversations SET updated_at = ? WHERE id = ?", Timestamp.from(now), conversationId);
        return insertMessage(conversationId, "ADMIN", message, now);
    }

    private MessageView insertMessage(UUID conversationId, String sender, String content, Instant createdAt) {
        jdbc.update("""
            INSERT INTO support_messages (id, conversation_id, sender, content, created_at)
            VALUES (?, ?, ?, ?, ?)
            """, UUID.randomUUID(), conversationId, sender, content, Timestamp.from(createdAt));
        return new MessageView(sender, content, createdAt);
    }

    private String requiredContent(String content) {
        if (content == null || content.isBlank()) {
            throw new ApiProblemException(
                HttpStatus.BAD_REQUEST, "SUPPORT_MESSAGE_REQUIRED",
                "Message content is required"
            );
        }
        return content.trim();
    }

    public record MessageView(String sender, String content, Instant createdAt) { }

    public record ConversationView(
        UUID userId,
        String displayName,
        String email,
        String lastMessage,
        String lastSender,
        Instant updatedAt
    ) { }
}

CREATE TABLE support_conversations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE support_messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES support_conversations(id) ON DELETE CASCADE,
    sender VARCHAR(8) NOT NULL CHECK (sender IN ('USER', 'ADMIN')),
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_support_messages_conversation ON support_messages (conversation_id, created_at, id);
CREATE INDEX idx_support_conversations_updated ON support_conversations (updated_at DESC);

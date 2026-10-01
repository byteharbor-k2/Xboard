-- Portal notices and knowledge articles, mirroring the original panel's
-- v2_notice and v2_knowledge tables (field sets: title/content/img_url/tags/
-- show/popup/sort for notices; category/language/title/body/show/sort for
-- knowledge). Column is_shown carries the original's boolean `show`, which is
-- kept out of the physical schema so the reserved word never needs quoting.
CREATE TABLE notices (
    id UUID PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    content TEXT NOT NULL,
    img_url VARCHAR(500),
    tags TEXT NOT NULL DEFAULT '[]',
    is_shown BOOLEAN NOT NULL DEFAULT FALSE,
    popup BOOLEAN NOT NULL DEFAULT FALSE,
    sort INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- The dashboard carousel reads notices sort ASC then newest id first,
-- exactly like the original fetch.
CREATE INDEX idx_notices_sorting ON notices (sort, id DESC);

CREATE TABLE knowledge_articles (
    id UUID PRIMARY KEY,
    category VARCHAR(120) NOT NULL,
    language VARCHAR(10) NOT NULL DEFAULT 'zh-CN',
    title VARCHAR(120) NOT NULL,
    body TEXT NOT NULL,
    is_shown BOOLEAN NOT NULL DEFAULT FALSE,
    sort INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_knowledge_articles_sorting ON knowledge_articles (sort, id DESC);
CREATE INDEX idx_knowledge_articles_category ON knowledge_articles (category);

import { useEffect, useMemo, useState } from "react";

import { AppShell } from "../components/AppShell";
import { formatDateTime } from "../lib/subscription";
import {
  fetchViewerKnowledge,
  fetchViewerKnowledgeArticle,
  type ViewerKnowledgeArticle,
  type ViewerKnowledgeGroup
} from "../lib/content";
import { useUserPreferences } from "../store/userPreferences";
import { useAuthStore } from "../store/auth";

const copy = {
  "zh-CN": {
    eyebrow: "HELP CENTER",
    title: "使用文档",
    description: "查找客户端安装、订阅导入和常见问题的说明。",
    search: "搜索使用文档",
    searchPlaceholder: "输入关键词搜索文章",
    categories: "文档分类",
    allCategories: "全部分类",
    articles: "文章列表",
    detailBack: "返回文章列表",
    lastUpdated: "最后更新",
    loading: "正在加载文章…",
    empty: "暂无已发布文档",
    emptyDescription: "管理员在知识库发布内容后，文章会显示在这里。",
    emptySearch: "没有匹配的文章",
    emptySearchDescription: "换个关键词再试一次。",
    loadFailed: "使用文档加载失败"
  },
  "en-US": {
    eyebrow: "HELP CENTER",
    title: "Guides",
    description:
      "Find instructions for client installation, subscription import, and common questions.",
    search: "Search guides",
    searchPlaceholder: "Search articles",
    categories: "Categories",
    allCategories: "All categories",
    articles: "Articles",
    detailBack: "Back to articles",
    lastUpdated: "Updated",
    loading: "Loading articles…",
    empty: "No published guides",
    emptyDescription:
      "Articles will appear here after an administrator publishes them in the knowledge base.",
    emptySearch: "No matching articles",
    emptySearchDescription: "Try a different keyword.",
    loadFailed: "Failed to load guides"
  }
};

export function KnowledgeBasePage() {
  const accessToken = useAuthStore((state) => state.accessToken)!;
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const [groups, setGroups] = useState<ViewerKnowledgeGroup[]>();
  const [failed, setFailed] = useState(false);
  const [keyword, setKeyword] = useState("");
  const [activeArticle, setActiveArticle] =
    useState<ViewerKnowledgeArticle | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    setGroups(undefined);
    setFailed(false);
    setActiveArticle(null);
    setSelectedCategory(null);
    fetchViewerKnowledge(accessToken, language, keyword)
      .then((result) => {
        if (active) {
          setGroups(result);
        }
      })
      .catch(() => {
        if (active) {
          setFailed(true);
        }
      });
    return () => {
      active = false;
    };
  }, [accessToken, language, keyword]);

  const visibleGroups = useMemo(() => {
    if (!groups) {
      return [];
    }
    return selectedCategory === null
      ? groups
      : groups.filter((group) => group.category === selectedCategory);
  }, [groups, selectedCategory]);

  function openArticle(id: string) {
    setDetailLoading(true);
    setActiveArticle(null);
    fetchViewerKnowledgeArticle(accessToken, id)
      .then((article) => setActiveArticle(article))
      .catch(() => setActiveArticle(null))
      .finally(() => setDetailLoading(false));
  }

  function backToList() {
    setActiveArticle(null);
  }

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">{labels.eyebrow}</p>
        <h1>{labels.title}</h1>
        <p className="muted">{labels.description}</p>
      </header>
      {activeArticle === null && (
        <section className="panel knowledge-search-panel">
          <label htmlFor="knowledge-search">{labels.search}</label>
          <input
            id="knowledge-search"
            placeholder={labels.searchPlaceholder}
            type="search"
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
          />
        </section>
      )}
      <section className="knowledge-base-layout">
        {!activeArticle && (
          <aside className="panel knowledge-category-panel">
            <h2>{labels.categories}</h2>
            {selectedCategory === null ? (
              <p className="knowledge-category-list">
                <span>{labels.allCategories}</span>
                {(groups ?? []).map((group) => (
                  <span
                    key={group.category}
                    className="knowledge-category-item"
                    role="button"
                    tabIndex={0}
                    onClick={() => setSelectedCategory(group.category)}
                    onKeyDown={(event) => {
                      if (event.key === "Enter") {
                        setSelectedCategory(group.category);
                      }
                    }}
                  >
                    {group.category}
                  </span>
                ))}
              </p>
            ) : (
              <button
                className="secondary-button compact-link"
                type="button"
                onClick={() => setSelectedCategory(null)}
              >
                ← {labels.allCategories}
              </button>
            )}
          </aside>
        )}
        <section className="panel knowledge-article-panel">
          {activeArticle ? (
            <article>
              <button
                className="secondary-button compact-link"
                type="button"
                onClick={backToList}
              >
                ← {labels.detailBack}
              </button>
              <h2>{activeArticle.title}</h2>
              <p className="muted">
                {labels.lastUpdated}:{" "}
                {formatDateTime(activeArticle.updatedAt, language)}
              </p>
              <div className="knowledge-article-body">
                {activeArticle.body.split("\n").map((line, index) => (
                  <p key={index}>{line}</p>
                ))}
              </div>
            </article>
          ) : detailLoading ? (
            <div className="skeleton-line wide" />
          ) : (
            <>
              <h2>{labels.articles}</h2>
              {groups === undefined && !failed && (
                <div className="skeleton-line wide" />
              )}
              {failed ? (
                <div className="user-empty-state">
                  <strong>{labels.loadFailed}</strong>
                </div>
              ) : (
                <>
                  {groups && groups.length === 0 && (
                    <div className="user-empty-state">
                      <span aria-hidden="true">⌕</span>
                      <strong>{labels.empty}</strong>
                      <p>{labels.emptyDescription}</p>
                    </div>
                  )}
                  {groups &&
                    groups.length > 0 &&
                    visibleGroups.length === 0 && (
                      <div className="user-empty-state">
                        <strong>{labels.emptySearch}</strong>
                        <p>{labels.emptySearchDescription}</p>
                      </div>
                    )}
                  {visibleGroups.map((group) => (
                    <div key={group.category} className="knowledge-category">
                      <h3>{group.category}</h3>
                      {group.articles.map((article) => (
                        <button
                          key={article.id}
                          className="knowledge-article-link"
                          type="button"
                          onClick={() => openArticle(article.id)}
                        >
                          <strong>{article.title}</strong>
                          <span className="muted">
                            {formatDateTime(article.updatedAt, language)}
                          </span>
                        </button>
                      ))}
                    </div>
                  ))}
                </>
              )}
            </>
          )}
        </section>
      </section>
    </AppShell>
  );
}

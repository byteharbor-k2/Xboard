import { AppShell } from "../components/AppShell";
import { useUserPreferences } from "../store/userPreferences";

const copy = {
  "zh-CN": {
    eyebrow: "HELP CENTER",
    title: "使用文档",
    description: "查找客户端安装、订阅导入和常见问题的说明。",
    search: "搜索使用文档",
    searchPlaceholder: "输入关键词搜索文章",
    categories: "文档分类",
    articles: "文章列表",
    empty: "暂无已发布文档",
    emptyDescription: "管理员在知识库发布内容后，文章会显示在这里。"
  },
  "en-US": {
    eyebrow: "HELP CENTER",
    title: "Guides",
    description:
      "Find instructions for client installation, subscription import, and common questions.",
    search: "Search guides",
    searchPlaceholder: "Search articles",
    categories: "Categories",
    articles: "Articles",
    empty: "No published guides",
    emptyDescription:
      "Articles will appear here after an administrator publishes them in the knowledge base."
  }
};

export function KnowledgeBasePage() {
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">{labels.eyebrow}</p>
        <h1>{labels.title}</h1>
        <p className="muted">{labels.description}</p>
      </header>
      <section className="panel knowledge-search-panel">
        <label htmlFor="knowledge-search">{labels.search}</label>
        <input
          disabled
          id="knowledge-search"
          placeholder={labels.searchPlaceholder}
          type="search"
        />
      </section>
      <section className="knowledge-base-layout">
        <aside className="panel knowledge-category-panel">
          <h2>{labels.categories}</h2>
          <div className="skeleton-line wide" />
          <div className="skeleton-line medium" />
          <div className="skeleton-line short" />
        </aside>
        <section className="panel knowledge-article-panel">
          <h2>{labels.articles}</h2>
          <div className="user-empty-state">
            <span aria-hidden="true">⌕</span>
            <strong>{labels.empty}</strong>
            <p>{labels.emptyDescription}</p>
          </div>
        </section>
      </section>
    </AppShell>
  );
}

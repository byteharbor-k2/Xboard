import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent } from "react";

import {
  deleteKnowledge,
  getKnowledge,
  listKnowledge,
  listKnowledgeCategories,
  saveKnowledge,
  toggleKnowledgeShow,
  type AdminKnowledgeSummary,
  type KnowledgeDraft
} from "../admin/knowledgeManagementApi";
import { AdminShell } from "../components/AdminShell";
import { MarkdownEditor } from "../components/MarkdownEditor";
import { ConfirmBar, type ConfirmRequest } from "../components/ConfirmBar";
import { ApiError } from "../lib/http";
import { useAdminAuthStore } from "../store/adminAuth";
import { useAdminPreferences } from "../store/adminPreferences";

const languages = ["zh-CN", "en-US"];

const copy = {
  "zh-CN": {
    eyebrow: "系统管理",
    title: "知识库管理",
    description: "管理帮助文章、分类和展示状态。",
    create: "新建文章",
    loading: "正在加载文章…",
    empty: "还没有文章，创建后用户即可在使用文档看到。",
    loadFailed: "数据加载失败",
    titleColumn: "标题",
    category: "分类",
    language: "语言",
    sort: "排序",
    state: "状态",
    updated: "更新时间",
    actions: "操作",
    shown: "显示中",
    hidden: "未显示",
    edit: "编辑",
    show: "显示",
    hide: "隐藏",
    remove: "删除",
    removeConfirm: "删除后帮助中心立即不再出现该文章，不可恢复。",
    editTitle: "编辑文章",
    createTitle: "创建文章",
    titleLabel: "标题",
    categoryLabel: "分类（填写或选择已有分类）",
    languageLabel: "语言",
    bodyLabel: "正文（支持多行）",
    showToggle: "发布（在使用文档显示）",
    cancel: "取消",
    save: "保存",
    saving: "保存中…",
    operationFailed: "操作失败"
  },
  "en-US": {
    eyebrow: "System",
    title: "Knowledge base",
    description: "Manage help articles, categories, and visibility.",
    create: "New article",
    loading: "Loading articles…",
    empty: "No articles yet. Created ones appear in the help centre.",
    loadFailed: "Failed to load articles",
    titleColumn: "Title",
    category: "Category",
    language: "Language",
    sort: "Sort",
    state: "State",
    updated: "Updated",
    actions: "Actions",
    shown: "Shown",
    hidden: "Hidden",
    edit: "Edit",
    show: "Show",
    hide: "Hide",
    remove: "Delete",
    removeConfirm:
      "The help centre stops showing it immediately; deletion cannot be undone.",
    editTitle: "Edit article",
    createTitle: "New article",
    titleLabel: "Title",
    categoryLabel: "Category (type one or pick an existing one)",
    languageLabel: "Language",
    bodyLabel: "Body (multi-line)",
    showToggle: "Published (shown in the help centre)",
    cancel: "Cancel",
    save: "Save",
    saving: "Saving…",
    operationFailed: "Operation failed"
  }
};

type FormState = {
  category: string;
  language: string;
  title: string;
  body: string;
  show: boolean;
};

function emptyForm(categoryHint: string): FormState {
  return {
    category: categoryHint,
    language: "zh-CN",
    title: "",
    body: "",
    show: false
  };
}

export function AdminKnowledgePage() {
  const language = useAdminPreferences((state) => state.language);
  const accessToken = useAdminAuthStore((state) => state.accessToken);
  const queryClient = useQueryClient();
  const text = copy[language];
  const [pendingConfirmation, setPendingConfirmation] =
    useState<ConfirmRequest | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [pageError, setPageError] = useState("");
  const [editing, setEditing] = useState<AdminKnowledgeSummary | null | undefined>();
  const [form, setForm] = useState<FormState>(() => emptyForm(""));
  const [formError, setFormError] = useState("");

  const knowledgeQuery = useQuery({
    queryKey: ["admin", "knowledge"],
    queryFn: () => listKnowledge(accessToken!),
    enabled: Boolean(accessToken)
  });

  const categories = useQuery({
    queryKey: ["admin", "knowledge", "categories"],
    queryFn: () => listKnowledgeCategories(accessToken!),
    enabled: Boolean(accessToken)
  });

  // The edit dialog needs the body, which the bare list answer omits - the
  // same fetch-by-id the original admin page made before opening its form.
  useEffect(() => {
    if (!editing) {
      return;
    }
    let active = true;
    getKnowledge(accessToken!, editing.id)
      .then((full) => {
        if (active) {
          setForm({
            category: full.category,
            language: full.language,
            title: full.title,
            body: full.body,
            show: full.show
          });
        }
      })
      .catch((error) =>
        setPageError(errorMessage(error, text.operationFailed))
      );
    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editing]);

  const saveMutation = useMutation({
    mutationFn: (draft: KnowledgeDraft) => saveKnowledge(accessToken!, draft),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin", "knowledge"] });
      setEditing(undefined);
    }
  });

  const toggleMutation = useMutation({
    mutationFn: (id: string) => toggleKnowledgeShow(accessToken!, id),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ["admin", "knowledge"] })
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteKnowledge(accessToken!, id),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ["admin", "knowledge"] })
  });

  function openCreate() {
    const firstExisting = (categories.data ?? [])[0] ?? "";
    setForm(emptyForm(firstExisting));
    setFormError("");
    setEditing(null);
  }

  function openEdit(article: AdminKnowledgeSummary) {
    setForm(emptyForm(article.category));
    setFormError("");
    setEditing(article);
  }

  function toggleShow(article: AdminKnowledgeSummary) {
    setPageError("");
    toggleMutation.mutate(article.id, {
      onError: (error) =>
        setPageError(errorMessage(error, text.operationFailed))
    });
  }

  function remove(article: AdminKnowledgeSummary) {
    setPendingConfirmation({
      message: `${article.title} — ${text.removeConfirm}`,
      confirmLabel: text.remove,
      danger: true,
      run: () => deleteMutation.mutateAsync(article.id)
    });
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setFormError("");
    saveMutation.mutate(
      {
        ...(editing ? { id: editing.id } : {}),
        category: form.category.trim(),
        language: form.language,
        title: form.title.trim(),
        body: form.body,
        show: form.show
      },
      {
        onError: (error) =>
          setFormError(errorMessage(error, text.operationFailed))
      }
    );
  }

  async function confirmPendingAction() {
    if (!pendingConfirmation || confirming) return;
    setConfirming(true);
    setPageError("");
    try {
      await pendingConfirmation.run();
      setPendingConfirmation(null);
    } catch (error) {
      setPageError(errorMessage(error, text.operationFailed));
    } finally {
      setConfirming(false);
    }
  }

  return (
    <AdminShell>
      <header className="admin-page-heading">
        <div>
          <p>{text.eyebrow}</p>
          <h1>{text.title}</h1>
          <span>{text.description}</span>
        </div>
        <button className="plan-primary-button" onClick={openCreate} type="button">
          ＋ {text.create}
        </button>
      </header>

      {pendingConfirmation && (
        <ConfirmBar
          busy={confirming}
          language={language}
          onCancel={() => setPendingConfirmation(null)}
          onConfirm={() => void confirmPendingAction()}
          request={pendingConfirmation}
        />
      )}
      {pageError && <p className="admin-operation-error">{pageError}</p>}

      <section className="admin-card" style={{ paddingBottom: 4 }}>
        <div className="admin-table-wrap">
          {knowledgeQuery.isPending ? (
            <p className="admin-table-empty">{text.loading}</p>
          ) : knowledgeQuery.isError ? (
            <p className="admin-table-empty">{text.loadFailed}</p>
          ) : (knowledgeQuery.data ?? []).length === 0 ? (
            <p className="admin-table-empty">{text.empty}</p>
          ) : (
            <table className="admin-table">
              <thead>
                <tr>
                  <th>{text.titleColumn}</th>
                  <th>{text.category}</th>
                  <th>{text.sort}</th>
                  <th>{text.state}</th>
                  <th>{text.updated}</th>
                  <th>{text.actions}</th>
                </tr>
              </thead>
              <tbody>
                {(knowledgeQuery.data ?? []).map((article) => (
                  <tr key={article.id}>
                    <td>
                      <strong>{article.title}</strong>
                    </td>
                    <td>{article.category}</td>
                    <td>{article.sort}</td>
                    <td>
                      <span
                        className={`status-pill ${article.show ? "" : "order-status-cancelled"}`}
                      >
                        {article.show ? text.shown : text.hidden}
                      </span>
                    </td>
                    <td>
                      {new Intl.DateTimeFormat(language, {
                        dateStyle: "short"
                      }).format(new Date(article.updated_at * 1000))}
                    </td>
                    <td>
                      <div className="machine-actions">
                        <button
                          onClick={() => openEdit(article)}
                          type="button"
                        >
                          {text.edit}
                        </button>
                        <button
                          disabled={toggleMutation.isPending}
                          onClick={() => toggleShow(article)}
                          type="button"
                        >
                          {article.show ? text.hide : text.show}
                        </button>
                        <button
                          className="danger"
                          disabled={deleteMutation.isPending}
                          onClick={() => remove(article)}
                          type="button"
                        >
                          {text.remove}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </section>

      {editing !== undefined && (
        <div className="admin-modal-backdrop" role="presentation">
          <form
            aria-label={editing ? text.editTitle : text.createTitle}
            className="admin-modal machine-modal markdown-admin-modal"
            onSubmit={submit}
          >
            <header>
              <h2>{editing ? text.editTitle : text.createTitle}</h2>
              <button
                aria-label={text.cancel}
                onClick={() => setEditing(undefined)}
                type="button"
              >
                ×
              </button>
            </header>
            <div className="machine-form">
              <label>
                {text.titleLabel}
                <input
                  autoFocus
                  required
                  value={form.title}
                  onChange={(event) =>
                    setForm((current) => ({
                      ...current,
                      title: event.target.value
                    }))
                  }
                />
              </label>
              <label>
                {text.categoryLabel}
                <input
                  list="knowledge-categories"
                  required
                  value={form.category}
                  onChange={(event) =>
                    setForm((current) => ({
                      ...current,
                      category: event.target.value
                    }))
                  }
                />
                <datalist id="knowledge-categories">
                  {(categories.data ?? []).map((category) => (
                    <option key={category} value={category} />
                  ))}
                </datalist>
              </label>
              <label>
                {text.languageLabel}
                <select
                  value={form.language}
                  onChange={(event) =>
                    setForm((current) => ({
                      ...current,
                      language: event.target.value
                    }))
                  }
                >
                  {languages.map((value) => (
                    <option key={value} value={value}>
                      {value}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                {text.bodyLabel}
                <MarkdownEditor value={form.body} rows={12} language={language} onChange={(body) => setForm((current) => ({ ...current, body }))} />
              </label>
              <label className="machine-check">
                <input
                  checked={form.show}
                  type="checkbox"
                  onChange={(event) =>
                    setForm((current) => ({
                      ...current,
                      show: event.target.checked
                    }))
                  }
                />
                <span>{text.showToggle}</span>
              </label>
              {(formError || saveMutation.isError) && (
                <p className="admin-operation-error">
                  {formError ||
                    errorMessage(saveMutation.error, text.operationFailed)}
                </p>
              )}
            </div>
            <footer>
              <button onClick={() => setEditing(undefined)} type="button">
                {text.cancel}
              </button>
              <button
                className="primary"
                disabled={saveMutation.isPending}
                type="submit"
              >
                {saveMutation.isPending ? text.saving : text.save}
              </button>
            </footer>
          </form>
        </div>
      )}
    </AdminShell>
  );
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback;
}

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";

import {
  deleteNotice,
  listNotices,
  saveNotice,
  toggleNoticeShow,
  type AdminNotice,
  type NoticeDraft
} from "../admin/noticeManagementApi";
import { AdminShell } from "../components/AdminShell";
import { ConfirmBar, type ConfirmRequest } from "../components/ConfirmBar";
import { ApiError } from "../lib/http";
import { useAdminAuthStore } from "../store/adminAuth";
import { useAdminPreferences } from "../store/adminPreferences";

const copy = {
  "zh-CN": {
    eyebrow: "系统管理",
    title: "公告管理",
    description: "创建、编辑站内公告，控制是否在仪表盘轮播显示。",
    create: "新建公告",
    loading: "正在加载公告…",
    empty: "还没有公告，创建后用户即可在仪表盘看到。",
    loadFailed: "公告数据加载失败",
    titleColumn: "标题",
    contentBegin: "内容",
    popup: "弹窗",
    sort: "排序",
    state: "状态",
    updated: "更新时间",
    actions: "操作",
    shown: "显示中",
    hidden: "未显示",
    yes: "是",
    no: "否",
    edit: "编辑",
    show: "显示",
    hide: "隐藏",
    remove: "删除",
    removeConfirm: "删除后仪表盘轮播立即不再出现该公告，不可恢复。",
    editTitle: "编辑公告",
    createTitle: "创建公告",
    titleLabel: "标题",
    contentLabel: "内容（支持多行，显示在公告轮播的首行会作为摘要）",
    imgUrl: "图片 URL（可选）",
    popupToggle: "允许在仪表盘弹窗提示",
    showToggle: "发布（在轮播显示）",
    cancel: "取消",
    save: "保存",
    saving: "保存中…",
    operationFailed: "操作失败"
  },
  "en-US": {
    eyebrow: "System",
    title: "Notices",
    description:
      "Create and edit portal notices, and control whether the dashboard carousel shows them.",
    create: "New notice",
    loading: "Loading notices…",
    empty: "No notices yet. Created ones appear on the user dashboard.",
    loadFailed: "Failed to load notices",
    titleColumn: "Title",
    contentBegin: "Content",
    popup: "Popup",
    sort: "Sort",
    state: "State",
    updated: "Updated",
    actions: "Actions",
    shown: "Shown",
    hidden: "Hidden",
    yes: "Yes",
    no: "No",
    edit: "Edit",
    show: "Show",
    hide: "Hide",
    remove: "Delete",
    removeConfirm:
      "The carousel stops showing it immediately; deletion cannot be undone.",
    editTitle: "Edit notice",
    createTitle: "Create notice",
    titleLabel: "Title",
    contentLabel:
      "Content (multi-line; the first line becomes the carousel summary)",
    imgUrl: "Image URL (optional)",
    popupToggle: "May be raised as a dashboard popup",
    showToggle: "Published (shown in the carousel)",
    cancel: "Cancel",
    save: "Save",
    saving: "Saving…",
    operationFailed: "Operation failed"
  }
};

type FormState = {
  title: string;
  content: string;
  imgUrl: string;
  popup: boolean;
  show: boolean;
};

function emptyForm(): FormState {
  return { title: "", content: "", imgUrl: "", popup: false, show: false };
}

function formFromNotice(notice: AdminNotice): FormState {
  return {
    title: notice.title,
    content: notice.content,
    imgUrl: notice.img_url ?? "",
    popup: notice.popup,
    show: notice.show
  };
}

export function AdminNoticesPage() {
  const language = useAdminPreferences((state) => state.language);
  const accessToken = useAdminAuthStore((state) => state.accessToken);
  const queryClient = useQueryClient();
  const text = copy[language];
  const [pendingConfirmation, setPendingConfirmation] =
    useState<ConfirmRequest | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [pageError, setPageError] = useState("");
  const [editing, setEditing] = useState<AdminNotice | null | undefined>();
  const [form, setForm] = useState<FormState>(emptyForm);
  const [formError, setFormError] = useState("");

  const noticesQuery = useQuery({
    queryKey: ["admin", "notices"],
    queryFn: () => listNotices(accessToken!),
    enabled: Boolean(accessToken)
  });

  const saveMutation = useMutation({
    mutationFn: (draft: NoticeDraft) => saveNotice(accessToken!, draft),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin", "notices"] });
      setEditing(undefined);
    }
  });

  const toggleMutation = useMutation({
    mutationFn: (noticeId: string) => toggleNoticeShow(accessToken!, noticeId),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ["admin", "notices"] })
  });

  const deleteMutation = useMutation({
    mutationFn: (noticeId: string) => deleteNotice(accessToken!, noticeId),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ["admin", "notices"] })
  });

  function openCreate() {
    setForm(emptyForm());
    setFormError("");
    setEditing(null);
  }

  function openEdit(notice: AdminNotice) {
    setForm(formFromNotice(notice));
    setFormError("");
    setEditing(notice);
  }

  function toggleShow(notice: AdminNotice) {
    setPageError("");
    toggleMutation.mutate(notice.id, {
      onError: (error) =>
        setPageError(errorMessage(error, text.operationFailed))
    });
  }

  function remove(notice: AdminNotice) {
    setPendingConfirmation({
      message: `${notice.title} — ${text.removeConfirm}`,
      confirmLabel: text.remove,
      danger: true,
      run: () => deleteMutation.mutateAsync(notice.id)
    });
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setFormError("");
    saveMutation.mutate(
      {
        ...(editing ? { id: editing.id } : {}),
        title: form.title.trim(),
        content: form.content,
        img_url: form.imgUrl.trim() || undefined,
        popup: form.popup,
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
          {noticesQuery.isPending ? (
            <p className="admin-table-empty">{text.loading}</p>
          ) : noticesQuery.isError ? (
            <p className="admin-table-empty">{text.loadFailed}</p>
          ) : (noticesQuery.data ?? []).length === 0 ? (
            <p className="admin-table-empty">{text.empty}</p>
          ) : (
            <table className="admin-table">
              <thead>
                <tr>
                  <th>{text.titleColumn}</th>
                  <th>{text.contentBegin}</th>
                  <th>{text.popup}</th>
                  <th>{text.sort}</th>
                  <th>{text.state}</th>
                  <th>{text.updated}</th>
                  <th>{text.actions}</th>
                </tr>
              </thead>
              <tbody>
                {(noticesQuery.data ?? []).map((notice) => (
                  <tr key={notice.id}>
                    <td>
                      <strong>{notice.title}</strong>
                    </td>
                    <td className="notice-content-cell">
                      {notice.content.split("\n").at(0)}
                    </td>
                    <td>{notice.popup ? text.yes : text.no}</td>
                    <td>{notice.sort}</td>
                    <td>
                      <span
                        className={`status-pill ${notice.show ? "" : "order-status-cancelled"}`}
                      >
                        {notice.show ? text.shown : text.hidden}
                      </span>
                    </td>
                    <td>
                      {new Intl.DateTimeFormat(language, {
                        dateStyle: "short"
                      }).format(new Date(notice.updated_at * 1000))}
                    </td>
                    <td>
                      <div className="machine-actions">
                        <button
                          onClick={() => openEdit(notice)}
                          type="button"
                        >
                          {text.edit}
                        </button>
                        <button
                          disabled={toggleMutation.isPending}
                          onClick={() => toggleShow(notice)}
                          type="button"
                        >
                          {notice.show ? text.hide : text.show}
                        </button>
                        <button
                          className="danger"
                          disabled={deleteMutation.isPending}
                          onClick={() => remove(notice)}
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
            className="admin-modal machine-modal"
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
                {text.contentLabel}
                <textarea
                  required
                  rows={6}
                  value={form.content}
                  onChange={(event) =>
                    setForm((current) => ({
                      ...current,
                      content: event.target.value
                    }))
                  }
                />
              </label>
              <label>
                {text.imgUrl}
                <input
                  type="url"
                  value={form.imgUrl}
                  onChange={(event) =>
                    setForm((current) => ({
                      ...current,
                      imgUrl: event.target.value
                    }))
                  }
                />
              </label>
              <label className="machine-check">
                <input
                  checked={form.popup}
                  type="checkbox"
                  onChange={(event) =>
                    setForm((current) => ({
                      ...current,
                      popup: event.target.checked
                    }))
                  }
                />
                <span>{text.popupToggle}</span>
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

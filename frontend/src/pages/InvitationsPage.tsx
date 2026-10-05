import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import { AppShell } from "../components/AppShell";
import { copyText } from "../lib/clipboard";
import {
  createInvitationCode,
  fetchViewerInvitations,
  type InvitationCode
} from "../lib/invitations";
import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";

const copy = {
  "zh-CN": {
    eyebrow: "REFERRALS",
    title: "我的邀请",
    description: "创建邀请码，邀请朋友注册并查看已建立的邀请关系。",
    availableCodes: "可用邀请码",
    invitedUsers: "已邀请用户",
    codes: "可用邀请码",
    createdAt: "创建时间",
    usage: "使用方式",
    action: "操作",
    reusable: "可重复使用",
    singleUse: "成功注册后失效",
    create: "创建邀请码",
    creating: "创建中…",
    limit: (limit: number) => `最多可同时持有 ${limit} 个可用邀请码。`,
    generationDisabled: "当前邀请码生成上限为 0，暂不能创建邀请码。",
    expiryNever: "邀请码不会过期，可重复用于注册。",
    expiryOnce: "邀请码成功用于一次注册后即失效。",
    loading: "正在加载邀请码…",
    loadFailed: "邀请码加载失败，请稍后重试。",
    createFailed: "邀请码创建失败，请稍后重试。",
    empty: "暂无可用邀请码",
    emptyDescription: "创建邀请码后，可在这里复制邀请码或专属注册链接。",
    copyCode: "复制邀请码",
    copyLink: "复制注册链接",
    copiedCode: "邀请码已复制",
    copiedLink: "注册链接已复制",
    copyFailed: "复制失败，请重试。",
    createSuccess: "邀请码已创建。"
  },
  "en-US": {
    eyebrow: "REFERRALS",
    title: "My invitations",
    description: "Create invitation codes and see the accounts registered through them.",
    availableCodes: "Available codes",
    invitedUsers: "Invited users",
    codes: "Available invitation codes",
    createdAt: "Created",
    usage: "Use",
    action: "Actions",
    reusable: "Reusable",
    singleUse: "One successful signup",
    create: "Create invitation code",
    creating: "Creating…",
    limit: (limit: number) => `You can hold up to ${limit} available invitation codes at a time.`,
    generationDisabled: "The invitation-code limit is 0, so new codes cannot be created.",
    expiryNever: "Codes do not expire and can be reused for registration.",
    expiryOnce: "A code becomes unavailable after one successful registration.",
    loading: "Loading invitation codes…",
    loadFailed: "Could not load invitation codes. Try again later.",
    createFailed: "Could not create an invitation code. Try again later.",
    empty: "No available invitation codes",
    emptyDescription: "Create a code to copy it or its personal registration link here.",
    copyCode: "Copy code",
    copyLink: "Copy registration link",
    copiedCode: "Invitation code copied",
    copiedLink: "Registration link copied",
    copyFailed: "Copy failed. Please try again.",
    createSuccess: "Invitation code created."
  }
};

function formatDate(value: string, language: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(language, {
    dateStyle: "medium",
    timeStyle: "short"
  }).format(date);
}

export function InvitationsPage() {
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const accessToken = useAuthStore((state) => state.accessToken) ?? "";
  const viewer = useAuthStore((state) => state.viewer);
  const invitationQueryKey = ["viewer-invitations", viewer?.id] as const;
  const queryClient = useQueryClient();
  const [message, setMessage] = useState("");
  const invitations = useQuery({
    queryKey: invitationQueryKey,
    queryFn: () => fetchViewerInvitations(accessToken),
    enabled: Boolean(viewer?.id && accessToken)
  });
  const creation = useMutation({
    mutationFn: () => createInvitationCode(accessToken),
    onSuccess: async () => {
      setMessage(labels.createSuccess);
      await queryClient.invalidateQueries({ queryKey: invitationQueryKey });
    },
    onError: (error: Error) => setMessage(error.message || labels.createFailed)
  });

  const summary = invitations.data;
  const atLimit = summary
    ? summary.generationLimit <= 0
      || summary.availableCodeCount >= summary.generationLimit
    : false;

  async function copyValue(value: string, success: string) {
    try {
      await copyText(value);
      setMessage(success);
    } catch {
      setMessage(labels.copyFailed);
    }
  }

  function registerUrl(code: InvitationCode) {
    return `${window.location.origin}/register?invite=${encodeURIComponent(code.code)}`;
  }

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">{labels.eyebrow}</p>
        <h1>{labels.title}</h1>
        <p className="muted">{labels.description}</p>
      </header>
      <section className="referral-summary-grid">
        <article className="panel">
          <span>{labels.availableCodes}</span>
          <strong>{summary?.availableCodeCount ?? "—"}</strong>
        </article>
        <article className="panel">
          <span>{labels.invitedUsers}</span>
          <strong>{summary?.invitedUserCount ?? "—"}</strong>
        </article>
      </section>
      <section className="panel user-record-panel">
        <h2>{labels.codes}</h2>
        {summary && (
          <div className="traffic-retention-notice">
            <span aria-hidden="true">i</span>
            {summary.generationLimit <= 0
              ? labels.generationDisabled
              : labels.limit(summary.generationLimit)}{" "}
            {summary.neverExpire ? labels.expiryNever : labels.expiryOnce}
          </div>
        )}
        {invitations.isError && (
          <p className="error-message">{labels.loadFailed}</p>
        )}
        {message && <p role="status">{message}</p>}
        <button
          className="freedom-button primary"
          disabled={!summary || atLimit || creation.isPending}
          onClick={() => {
            setMessage("");
            creation.mutate();
          }}
          type="button"
        >
          {creation.isPending ? labels.creating : labels.create}
        </button>
        <div className="user-table-wrap">
          <table className="user-data-table">
            <thead>
              <tr>
                <th>{labels.codes}</th>
                <th>{labels.createdAt}</th>
                <th>{labels.usage}</th>
                <th>{labels.action}</th>
              </tr>
            </thead>
            <tbody>
              {invitations.isPending && (
                <tr>
                  <td colSpan={4}>
                    <div className="user-empty-state compact">
                      <strong>{labels.loading}</strong>
                    </div>
                  </td>
                </tr>
              )}
              {!invitations.isPending && (summary?.codes.length ?? 0) > 0 &&
                summary!.codes.map((code) => (
                  <tr key={code.id}>
                    <td><span className="order-number">{code.code}</span></td>
                    <td>{formatDate(code.createdAt, language)}</td>
                    <td>{summary!.neverExpire ? labels.reusable : labels.singleUse}</td>
                    <td>
                      <button
                        className="order-number-link"
                        onClick={() => void copyValue(code.code, labels.copiedCode)}
                        type="button"
                      >
                        {labels.copyCode}
                      </button>{" "}
                      <button
                        className="order-number-link"
                        onClick={() => void copyValue(registerUrl(code), labels.copiedLink)}
                        type="button"
                      >
                        {labels.copyLink}
                      </button>
                    </td>
                  </tr>
                ))}
              {!invitations.isPending && (summary?.codes.length ?? 0) === 0 && (
                <tr>
                  <td colSpan={4}>
                    <div className="user-empty-state compact">
                      <span aria-hidden="true">＋</span>
                      <strong>{labels.empty}</strong>
                      <p>{labels.emptyDescription}</p>
                    </div>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
    </AppShell>
  );
}

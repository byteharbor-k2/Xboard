import { useState } from "react";

import { AppShell } from "../components/AppShell";
import { ApiError, requestEmailVerification } from "../lib/http";
import { useAuthStore } from "../store/auth";

function formatDate(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "long"
  }).format(new Date(value));
}

export function AccountOverviewPage() {
  const accessToken = useAuthStore((state) => state.accessToken)!;
  const viewer = useAuthStore((state) => state.viewer)!;
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [sending, setSending] = useState(false);

  async function resendVerification() {
    setSending(true);
    setNotice("");
    setError("");
    try {
      await requestEmailVerification(accessToken);
      setNotice("验证邮件已重新发送。");
    } catch (caught) {
      setError(
        caught instanceof ApiError ? caught.message : "邮件发送失败"
      );
    } finally {
      setSending(false);
    }
  }

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">Account</p>
        <h1>你好，{viewer.displayName}</h1>
        <p className="muted">在这里管理账户资料与安全状态。</p>
      </header>
      {!viewer.emailVerified && (
        <section className="account-alert">
          <div>
            <strong>邮箱尚未验证</strong>
            <p>完成验证后，账户恢复与重要通知会更加可靠。</p>
          </div>
          <button
            className="secondary-button compact-button"
            disabled={sending}
            onClick={resendVerification}
          >
            {sending ? "正在发送…" : "重新发送邮件"}
          </button>
        </section>
      )}
      {notice && <p className="account-inline-message success">{notice}</p>}
      {error && <p className="error-message">{error}</p>}
      <section className="account-summary-grid">
        <article className="account-summary-card">
          <span>账户邮箱</span>
          <strong>{viewer.email}</strong>
          <small>{viewer.emailVerified ? "已验证" : "等待验证"}</small>
        </article>
        <article className="account-summary-card">
          <span>账户身份</span>
          <strong>{viewer.roles.includes("ADMIN") ? "管理员" : "用户"}</strong>
          <small>权限由系统角色控制</small>
        </article>
        <article className="account-summary-card">
          <span>加入时间</span>
          <strong>{formatDate(viewer.createdAt)}</strong>
          <small>SinX Cloud 账户</small>
        </article>
      </section>
    </AppShell>
  );
}

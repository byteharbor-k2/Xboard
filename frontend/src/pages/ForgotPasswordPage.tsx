import { useState, type FormEvent } from "react";

import { AppLink } from "../components/AppLink";
import { AuthLayout } from "../components/AuthLayout";
import { requestPasswordReset } from "../lib/http";

export function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [sent, setSent] = useState(false);
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      await requestPasswordReset(email);
      setSent(true);
    } catch {
      setError("暂时无法提交请求，请稍后重试。");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout
      title="重置密码"
      description="输入注册邮箱，我们会发送一次性重置链接。"
      eyebrow="Account recovery"
      footer={<AppLink href="/login">返回登录</AppLink>}
    >
      {sent ? (
        <div className="freedom-message success">
          如果该邮箱对应有效账户，重置邮件已经发送。
        </div>
      ) : (
        <form className="freedom-form" onSubmit={handleSubmit}>
          <label>
            邮箱
            <input
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </label>
          {error && <p className="error-message">{error}</p>}
          <button
            className="freedom-button primary submit"
            disabled={submitting}
          >
            {submitting ? "正在提交…" : "发送重置邮件"}
          </button>
        </form>
      )}
    </AuthLayout>
  );
}

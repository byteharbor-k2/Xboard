import { useState, type FormEvent } from "react";

import { AppLink } from "../components/AppLink";
import { AuthLayout } from "../components/AuthLayout";
import { requestPasswordReset } from "../lib/http";
import { useUserPreferences } from "../store/userPreferences";

const copy = {
  "zh-CN": {
    title: "重置密码",
    description: "输入注册邮箱，我们会发送一次性重置链接。",
    back: "返回登录",
    sent: "如果该邮箱对应有效账户，重置邮件已经发送。",
    email: "邮箱",
    failed: "暂时无法提交请求，请稍后重试。",
    submitting: "正在提交…",
    submit: "发送重置邮件"
  },
  "en-US": {
    title: "Reset password",
    description: "Enter your account email to receive a one-time reset link.",
    back: "Back to sign in",
    sent: "If the email belongs to an account, a reset message has been sent.",
    email: "Email",
    failed: "The request could not be submitted. Try again later.",
    submitting: "Submitting…",
    submit: "Send reset email"
  }
};

export function ForgotPasswordPage() {
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
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
      setError(labels.failed);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout
      title={labels.title}
      description={labels.description}
      eyebrow="Account recovery"
      footer={<AppLink href="/login">{labels.back}</AppLink>}
    >
      {sent ? (
        <div className="freedom-message success">
          {labels.sent}
        </div>
      ) : (
        <form className="freedom-form" onSubmit={handleSubmit}>
          <label>
            {labels.email}
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
            {submitting ? labels.submitting : labels.submit}
          </button>
        </form>
      )}
    </AuthLayout>
  );
}

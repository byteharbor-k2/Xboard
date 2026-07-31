import { useState, type FormEvent } from "react";

import { AppLink } from "../components/AppLink";
import { AuthLayout } from "../components/AuthLayout";
import { ApiError, confirmPasswordReset } from "../lib/http";
import { useUserPreferences } from "../store/userPreferences";

const copy = {
  "zh-CN": {
    title: "设置新密码",
    description: "重置完成后，所有已登录设备都会退出。",
    back: "返回登录",
    missing: "重置链接缺少有效凭证",
    mismatch: "两次输入的密码不一致",
    failed: "重置失败，请重新申请链接",
    complete: "密码已更新，请使用新密码重新登录。",
    password: "新密码",
    confirmation: "确认新密码",
    submitting: "正在重置…",
    submit: "更新密码"
  },
  "en-US": {
    title: "Set a new password",
    description: "All signed-in devices will be logged out after the reset.",
    back: "Back to sign in",
    missing: "The reset link is missing a valid credential.",
    mismatch: "The passwords do not match.",
    failed: "Reset failed. Request a new link.",
    complete: "Your password was updated. Sign in with the new password.",
    password: "New password",
    confirmation: "Confirm new password",
    submitting: "Resetting…",
    submit: "Update password"
  }
};

export function ResetPasswordPage() {
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const token = new URLSearchParams(window.location.search).get("token") ?? "";
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [error, setError] = useState("");
  const [complete, setComplete] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    if (!token) {
      setError(labels.missing);
      return;
    }
    if (password !== confirmation) {
      setError(labels.mismatch);
      return;
    }
    setSubmitting(true);
    try {
      await confirmPasswordReset(token, password);
      setComplete(true);
    } catch (caught) {
      setError(
        caught instanceof ApiError
          ? caught.message
          : labels.failed
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout
      title={labels.title}
      description={labels.description}
      eyebrow="Password reset"
      footer={<AppLink href="/login">{labels.back}</AppLink>}
    >
      {complete ? (
        <div className="freedom-message success">
          {labels.complete}
        </div>
      ) : (
        <form className="freedom-form" onSubmit={handleSubmit}>
          <label>
            {labels.password}
            <input
              type="password"
              autoComplete="new-password"
              minLength={12}
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          <label>
            {labels.confirmation}
            <input
              type="password"
              autoComplete="new-password"
              minLength={12}
              required
              value={confirmation}
              onChange={(event) => setConfirmation(event.target.value)}
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

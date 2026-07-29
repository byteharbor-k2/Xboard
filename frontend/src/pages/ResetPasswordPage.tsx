import { useState, type FormEvent } from "react";

import { AppLink } from "../components/AppLink";
import { AuthLayout } from "../components/AuthLayout";
import { ApiError, confirmPasswordReset } from "../lib/http";

export function ResetPasswordPage() {
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
      setError("重置链接缺少有效凭证");
      return;
    }
    if (password !== confirmation) {
      setError("两次输入的密码不一致");
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
          : "重置失败，请重新申请链接"
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout
      title="设置新密码"
      description="重置完成后，所有已登录设备都会退出。"
      eyebrow="Password reset"
      footer={<AppLink href="/login">返回登录</AppLink>}
    >
      {complete ? (
        <div className="freedom-message success">
          密码已更新，请使用新密码重新登录。
        </div>
      ) : (
        <form className="freedom-form" onSubmit={handleSubmit}>
          <label>
            新密码
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
            确认新密码
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
            {submitting ? "正在重置…" : "更新密码"}
          </button>
        </form>
      )}
    </AuthLayout>
  );
}

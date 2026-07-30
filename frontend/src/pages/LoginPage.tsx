import { useState, type FormEvent } from "react";

import { AppLink } from "../components/AppLink";
import { AuthLayout } from "../components/AuthLayout";
import { ApiError, login } from "../lib/http";
import { navigate } from "../lib/navigation";
import { useAuthStore } from "../store/auth";

export function LoginPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const setSession = useAuthStore((state) => state.setSession);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      const result = await login(
        email,
        password,
        navigator.userAgent.slice(0, 120)
      );
      finishLogin(result);
    } catch (caught) {
      setError(
        caught instanceof ApiError
          ? caught.message
          : "登录失败，请稍后重试"
      );
    } finally {
      setSubmitting(false);
    }
  }

  function finishLogin(session: Parameters<typeof setSession>[0]) {
    setSession(session);
    const requested = new URLSearchParams(window.location.search).get(
      "returnTo"
    );
    const returnTo =
      requested?.startsWith("/") && !requested.startsWith("//")
        ? requested
        : "/account";
    navigate(returnTo, true);
  }

  return (
    <AuthLayout
      title="欢迎回来"
      description="登录以管理账户与已授权设备。"
      eyebrow="Account security"
      footer={
        <p>
          还没有账户？ <AppLink href="/register">创建账户</AppLink>
        </p>
      }
    >
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
          <label>
            密码
            <input
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          <div className="freedom-form-meta">
            <span />
            <AppLink href="/forgot-password">忘记密码？</AppLink>
          </div>
          {error && <p className="error-message">{error}</p>}
          <button className="freedom-button primary submit" disabled={submitting}>
            {submitting ? "正在登录…" : "登录"}
          </button>
        </form>
    </AuthLayout>
  );
}

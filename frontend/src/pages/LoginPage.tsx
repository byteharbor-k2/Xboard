import { useState, type FormEvent } from "react";

import { ApiError, login } from "../lib/http";
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
      const session = await login(
        email,
        password,
        navigator.userAgent.slice(0, 120)
      );
      setSession(session);
      const requested = new URLSearchParams(window.location.search).get(
        "returnTo"
      );
      const returnTo =
        requested?.startsWith("/") && !requested.startsWith("//")
          ? requested
          : "/security/sessions";
      window.location.replace(returnTo);
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

  return (
    <main className="auth-page">
      <section className="auth-card">
        <div className="brand auth-brand">
          <span className="brand-mark">S</span>
          <span>SinX Cloud</span>
        </div>
        <div>
          <p className="eyebrow">Account security</p>
          <h1>欢迎回来</h1>
          <p className="muted">登录以管理账户与已授权设备。</p>
        </div>
        <form onSubmit={handleSubmit}>
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
          {error && <p className="error-message">{error}</p>}
          <button className="primary-button" disabled={submitting}>
            {submitting ? "正在登录…" : "登录"}
          </button>
        </form>
      </section>
    </main>
  );
}

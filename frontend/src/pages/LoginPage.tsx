import { useState, type FormEvent } from "react";

import { AppLink } from "../components/AppLink";
import { AuthLayout } from "../components/AuthLayout";
import { ApiError, login } from "../lib/http";
import { navigate } from "../lib/navigation";
import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";

const copy = {
  "zh-CN": {
    title: "欢迎回来",
    description: "登录以管理账户与已授权设备。",
    question: "还没有账户？",
    create: "创建账户",
    email: "邮箱",
    password: "密码",
    forgot: "忘记密码？",
    submitting: "正在登录…",
    submit: "登录",
    failed: "登录失败，请稍后重试"
  },
  "en-US": {
    title: "Welcome back",
    description: "Sign in to manage your account and authorized devices.",
    question: "No account yet?",
    create: "Create account",
    email: "Email",
    password: "Password",
    forgot: "Forgot password?",
    submitting: "Signing in…",
    submit: "Sign in",
    failed: "Sign-in failed. Try again later."
  }
};

export function LoginPage() {
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
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
          : labels.failed
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
      title={labels.title}
      description={labels.description}
      eyebrow="Account security"
      footer={
        <p>
          {labels.question} <AppLink href="/register">{labels.create}</AppLink>
        </p>
      }
    >
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
          <label>
            {labels.password}
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
            <AppLink href="/forgot-password">{labels.forgot}</AppLink>
          </div>
          {error && <p className="error-message">{error}</p>}
          <button className="freedom-button primary submit" disabled={submitting}>
            {submitting ? labels.submitting : labels.submit}
          </button>
        </form>
    </AuthLayout>
  );
}

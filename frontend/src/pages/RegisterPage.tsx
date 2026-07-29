import { useState, type FormEvent } from "react";

import { AppLink } from "../components/AppLink";
import { AuthLayout } from "../components/AuthLayout";
import { ApiError, register } from "../lib/http";
import { navigate } from "../lib/navigation";
import { useAuthStore } from "../store/auth";

export function RegisterPage() {
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const setSession = useAuthStore((state) => state.setSession);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    if (password !== confirmation) {
      setError("两次输入的密码不一致");
      return;
    }
    setSubmitting(true);
    try {
      const session = await register(
        email,
        password,
        displayName,
        navigator.userAgent.slice(0, 120)
      );
      setSession(session);
      navigate("/account", true);
    } catch (caught) {
      setError(
        caught instanceof ApiError
          ? caught.message
          : "注册失败，请稍后重试"
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout
      title="创建账户"
      description="使用邮箱创建你的 SinX Cloud 账户。"
      eyebrow="New account"
      footer={
        <p>
          已有账户？ <AppLink href="/login">立即登录</AppLink>
        </p>
      }
    >
      <form className="freedom-form" onSubmit={handleSubmit}>
        <label>
          昵称
          <input
            autoComplete="name"
            maxLength={80}
            required
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
          />
        </label>
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
            autoComplete="new-password"
            minLength={12}
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
          <small>至少 12 个字符</small>
        </label>
        <label>
          确认密码
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
        <button className="freedom-button primary submit" disabled={submitting}>
          {submitting ? "正在创建…" : "创建账户"}
        </button>
      </form>
    </AuthLayout>
  );
}

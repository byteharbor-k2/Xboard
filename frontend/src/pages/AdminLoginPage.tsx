import { useEffect, useState, type FormEvent } from "react";

import { AuthLayout } from "../components/AuthLayout";
import {
  adminLogin,
  ApiError,
  completeAdminMfaLogin,
  confirmAdminMfaEnrollment,
  startAdminMfaEnrollment
} from "../lib/http";
import { navigate } from "../lib/navigation";
import { useAdminAuthStore } from "../store/adminAuth";
import type { MfaEnrollment } from "../types";

type LoginStep = "password" | "mfa" | "enrollment" | "recovery";

export function AdminLoginPage() {
  const viewer = useAdminAuthStore((state) => state.viewer);
  const setSession = useAdminAuthStore((state) => state.setSession);
  const [step, setStep] = useState<LoginStep>("password");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [challengeToken, setChallengeToken] = useState("");
  const [enrollmentToken, setEnrollmentToken] = useState("");
  const [enrollment, setEnrollment] = useState<MfaEnrollment | null>(null);
  const [code, setCode] = useState("");
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (viewer?.roles.includes("ADMIN")) {
      navigate("/admin/dashboard", true);
    }
  }, [viewer]);

  async function handlePassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError("");
    try {
      const result = await adminLogin(
        email,
        password,
        navigator.userAgent.slice(0, 120)
      );
      setPassword("");
      if (result.mfaRequired) {
        setChallengeToken(result.challengeToken);
        setStep("mfa");
        return;
      }
      setEnrollmentToken(result.enrollmentToken);
      setEnrollment(
        await startAdminMfaEnrollment(result.enrollmentToken)
      );
      setStep("enrollment");
    } catch (caught) {
      showError(caught, "管理员登录失败，请稍后重试");
    } finally {
      setSubmitting(false);
    }
  }

  async function handleMfa(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError("");
    try {
      const session = await completeAdminMfaLogin(
        challengeToken,
        code
      );
      setSession(session);
      navigate("/admin/dashboard", true);
    } catch (caught) {
      showError(caught, "二次验证失败，请重新登录后再试");
    } finally {
      setSubmitting(false);
    }
  }

  async function handleEnrollment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError("");
    try {
      const result = await confirmAdminMfaEnrollment(
        enrollmentToken,
        code
      );
      setRecoveryCodes(result.recoveryCodes);
      setCode("");
      setStep("recovery");
    } catch (caught) {
      showError(caught, "验证器配置失败，请重新登录后再试");
    } finally {
      setSubmitting(false);
    }
  }

  function resetLogin() {
    setStep("password");
    setPassword("");
    setCode("");
    setChallengeToken("");
    setEnrollmentToken("");
    setEnrollment(null);
    setRecoveryCodes([]);
    setError("");
  }

  function showError(caught: unknown, fallback: string) {
    setError(caught instanceof ApiError ? caught.message : fallback);
  }

  return (
    <AuthLayout
      title="管理后台登录"
      description="管理员会话与用户账户会话相互独立。"
      eyebrow="Administration"
      footer={null}
    >
      {step === "password" && (
        <form className="freedom-form" onSubmit={handlePassword}>
          <label>
            管理员邮箱
            <input
              autoComplete="username"
              required
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </label>
          <label>
            密码
            <input
              autoComplete="current-password"
              required
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          {error && <p className="error-message">{error}</p>}
          <button
            className="freedom-button primary submit"
            disabled={submitting}
          >
            {submitting ? "正在验证…" : "继续"}
          </button>
        </form>
      )}

      {step === "mfa" && (
        <form className="freedom-form" onSubmit={handleMfa}>
          <p className="security-notice">
            输入验证器中的 6 位验证码，或使用一枚恢复码。
          </p>
          <label>
            验证码或恢复码
            <input
              autoComplete="one-time-code"
              autoFocus
              maxLength={32}
              minLength={6}
              required
              value={code}
              onChange={(event) => setCode(event.target.value)}
            />
          </label>
          {error && <p className="error-message">{error}</p>}
          <button
            className="freedom-button primary submit"
            disabled={submitting}
          >
            {submitting ? "正在验证…" : "登录管理后台"}
          </button>
          <button className="text-button" type="button" onClick={resetLogin}>
            返回密码登录
          </button>
        </form>
      )}

      {step === "enrollment" && enrollment && (
        <form className="freedom-form" onSubmit={handleEnrollment}>
          <div className="security-notice">
            <strong>首次管理员登录必须配置 MFA</strong>
            <p>在验证器中打开链接，或手动输入密钥。</p>
            <a href={enrollment.otpauthUri}>在验证器中打开</a>
            <code className="secret-code">{enrollment.secret}</code>
          </div>
          <label>
            当前 6 位验证码
            <input
              autoComplete="one-time-code"
              inputMode="numeric"
              maxLength={6}
              minLength={6}
              pattern="[0-9]{6}"
              required
              value={code}
              onChange={(event) => setCode(event.target.value)}
            />
          </label>
          {error && <p className="error-message">{error}</p>}
          <button
            className="freedom-button primary submit"
            disabled={submitting}
          >
            {submitting ? "正在启用…" : "启用 MFA"}
          </button>
          <button className="text-button" type="button" onClick={resetLogin}>
            取消并重新登录
          </button>
        </form>
      )}

      {step === "recovery" && (
        <div className="freedom-form">
          <div className="recovery-box">
            <strong>恢复码只显示这一次</strong>
            <p>请离线保存，每枚恢复码只能使用一次。</p>
            <div className="recovery-grid">
              {recoveryCodes.map((recoveryCode) => (
                <code key={recoveryCode}>{recoveryCode}</code>
              ))}
            </div>
          </div>
          <button
            className="freedom-button primary submit"
            type="button"
            onClick={resetLogin}
          >
            已保存，返回登录
          </button>
        </div>
      )}
    </AuthLayout>
  );
}

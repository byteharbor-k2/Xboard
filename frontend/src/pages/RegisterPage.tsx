import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent
} from "react";

import { AppLink } from "../components/AppLink";
import { AuthLayout } from "../components/AuthLayout";
import { TurnstileWidget } from "../components/TurnstileWidget";
import {
  ApiError,
  getRegistrationConfig,
  register,
  requestRegistrationCode
} from "../lib/http";
import { navigate } from "../lib/navigation";
import { useAuthStore } from "../store/auth";
import type { RegistrationConfig } from "../types";

export function RegisterPage() {
  const emailInput = useRef<HTMLInputElement>(null);
  const [config, setConfig] = useState<RegistrationConfig | null>(null);
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [emailCode, setEmailCode] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [turnstileToken, setTurnstileToken] = useState("");
  const [turnstileReset, setTurnstileReset] = useState(0);
  const [codeSent, setCodeSent] = useState(false);
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);
  const setSession = useAuthStore((state) => state.setSession);
  const handleTurnstileToken = useCallback(
    (token: string) => setTurnstileToken(token),
    []
  );

  useEffect(() => {
    void getRegistrationConfig()
      .then(setConfig)
      .catch((caught) => {
        setError(
          caught instanceof ApiError
            ? caught.message
            : "注册安全配置暂时不可用"
        );
      });
  }, []);

  async function sendCode() {
    if (!emailInput.current?.reportValidity()) return;
    if (config?.turnstileEnabled && !turnstileToken) {
      setError("请先完成人机验证");
      return;
    }
    setSendingCode(true);
    setError("");
    try {
      await requestRegistrationCode(email, turnstileToken);
      setCodeSent(true);
    } catch (caught) {
      setError(
        caught instanceof ApiError
          ? caught.message
          : "验证码发送失败，请稍后重试"
      );
    } finally {
      setSendingCode(false);
      resetTurnstile();
    }
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    if (password !== confirmation) {
      setError("两次输入的密码不一致");
      return;
    }
    if (!codeSent || !/^\d{6}$/.test(emailCode)) {
      setError("请先完成邮箱验证码验证");
      return;
    }
    if (config?.turnstileEnabled && !turnstileToken) {
      setError("提交注册前请再次完成人机验证");
      return;
    }
    setSubmitting(true);
    try {
      const session = await register(
        email,
        password,
        displayName,
        navigator.userAgent.slice(0, 120),
        emailCode,
        turnstileToken
      );
      setSession(session);
      navigate("/account", true);
    } catch (caught) {
      setError(
        caught instanceof ApiError
          ? caught.message
          : "注册失败，请稍后重试"
      );
      resetTurnstile();
    } finally {
      setSubmitting(false);
    }
  }

  function resetTurnstile() {
    setTurnstileToken("");
    setTurnstileReset((value) => value + 1);
  }

  return (
    <AuthLayout
      title="创建账户"
      description="邮箱验证完成后才会创建 SinX Cloud 账户。"
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
            ref={emailInput}
            autoComplete="email"
            required
            type="email"
            value={email}
            onChange={(event) => {
              setEmail(event.target.value);
              setCodeSent(false);
              setEmailCode("");
            }}
          />
        </label>
        <label>
          邮箱验证码
          <span className="verification-code-row">
            <input
              autoComplete="one-time-code"
              inputMode="numeric"
              maxLength={6}
              minLength={6}
              pattern="[0-9]{6}"
              required
              value={emailCode}
              onChange={(event) => setEmailCode(event.target.value)}
            />
            <button
              disabled={sendingCode || !config}
              type="button"
              onClick={() => void sendCode()}
            >
              {sendingCode
                ? "发送中…"
                : codeSent
                  ? "重新发送"
                  : "发送验证码"}
            </button>
          </span>
          {codeSent && <small>验证码已发送，5 分钟内有效</small>}
        </label>
        <label>
          密码
          <input
            autoComplete="new-password"
            minLength={12}
            required
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
          <small>至少 12 个字符</small>
        </label>
        <label>
          确认密码
          <input
            autoComplete="new-password"
            minLength={12}
            required
            type="password"
            value={confirmation}
            onChange={(event) => setConfirmation(event.target.value)}
          />
        </label>
        {config?.turnstileEnabled && config.turnstileSiteKey && (
          <>
            <TurnstileWidget
              onToken={handleTurnstileToken}
              resetCounter={turnstileReset}
              siteKey={config.turnstileSiteKey}
            />
            {codeSent && (
              <small>验证码发送后，请再次完成人机验证再提交注册。</small>
            )}
          </>
        )}
        {config?.turnstileEnabled && !config.turnstileSiteKey && (
          <p className="error-message">人机验证配置不完整。</p>
        )}
        {error && <p className="error-message">{error}</p>}
        <button
          className="freedom-button primary submit"
          disabled={submitting || !config}
        >
          {submitting ? "正在创建…" : "验证并创建账户"}
        </button>
      </form>
    </AuthLayout>
  );
}

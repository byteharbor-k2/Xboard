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
import { useUserPreferences } from "../store/userPreferences";
import type { RegistrationConfig } from "../types";

const copy = {
  "zh-CN": {
    title: "创建账户",
    description: "完成注册安全验证后创建 SinX Cloud 账户。",
    question: "已有账户？",
    login: "立即登录",
    nickname: "昵称",
    email: "邮箱",
    emailDomain: "邮箱域名",
    noDomains: "管理员尚未配置可注册的邮箱域名",
    emailCode: "邮箱验证码",
    codeSent: "验证码已发送，5 分钟内有效",
    sendCode: "发送验证码",
    resendCode: "重新发送",
    sendingCode: "发送中…",
    password: "密码",
    passwordHint: "至少 12 个字符",
    confirmation: "确认密码",
    inviteCode: "邀请码",
    inviteOptional: "选填",
    inviteRequired: "必填",
    finishInvitation: "请输入邀请码",
    verifyAgain: "验证码发送后，请再次完成人机验证再提交注册。",
    turnstileIncomplete: "人机验证配置不完整。",
    termsPrefix: "我已阅读并同意",
    terms: "用户条款",
    submitting: "正在创建…",
    submit: "验证并创建账户",
    configUnavailable: "注册安全配置暂时不可用",
    finishHumanCheck: "请先完成人机验证",
    finishHumanCheckAgain: "提交注册前请再次完成人机验证",
    sendCodeFailed: "验证码发送失败，请稍后重试",
    passwordMismatch: "两次输入的密码不一致",
    finishEmailCheck: "请先完成邮箱验证码验证",
    acceptTerms: "请先阅读并同意用户条款",
    registrationFailed: "注册失败，请稍后重试"
  },
  "en-US": {
    title: "Create account",
    description: "Complete the registration checks to create your SinX Cloud account.",
    question: "Already have an account?",
    login: "Sign in",
    nickname: "Display name",
    email: "Email",
    emailDomain: "Email domain",
    noDomains: "No registration email domains have been configured",
    emailCode: "Email verification code",
    codeSent: "Code sent and valid for 5 minutes",
    sendCode: "Send code",
    resendCode: "Send again",
    sendingCode: "Sending…",
    password: "Password",
    passwordHint: "At least 12 characters",
    confirmation: "Confirm password",
    inviteCode: "Invitation code",
    inviteOptional: "Optional",
    inviteRequired: "Required",
    finishInvitation: "Enter an invitation code",
    verifyAgain: "Complete the human check again before submitting.",
    turnstileIncomplete: "Human verification is not fully configured.",
    termsPrefix: "I have read and agree to the",
    terms: "Terms of Service",
    submitting: "Creating…",
    submit: "Verify and create account",
    configUnavailable: "Registration security settings are unavailable",
    finishHumanCheck: "Complete the human verification first",
    finishHumanCheckAgain: "Complete the human verification again before registering",
    sendCodeFailed: "The verification code could not be sent. Try again later.",
    passwordMismatch: "The passwords do not match",
    finishEmailCheck: "Complete email verification first",
    acceptTerms: "Read and accept the Terms of Service first",
    registrationFailed: "Registration failed. Try again later."
  }
};

export function RegisterPage() {
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const emailInput = useRef<HTMLInputElement>(null);
  const [config, setConfig] = useState<RegistrationConfig | null>(null);
  const [displayName, setDisplayName] = useState("");
  const [emailAddress, setEmailAddress] = useState("");
  const [emailLocalPart, setEmailLocalPart] = useState("");
  const [emailDomain, setEmailDomain] = useState("");
  const [emailCode, setEmailCode] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [inviteCode, setInviteCode] = useState("");
  const [turnstileToken, setTurnstileToken] = useState("");
  const [turnstileReset, setTurnstileReset] = useState(0);
  const [termsAccepted, setTermsAccepted] = useState(false);
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
            : labels.configUnavailable
        );
      });
  }, []);

  useEffect(() => {
    if (!config?.emailDomainAllowlistEnabled) return;
    setEmailDomain((current) =>
      config.allowedEmailDomains.includes(current)
        ? current
        : (config.allowedEmailDomains[0] ?? "")
    );
  }, [config]);

  const domainSelectionEnabled =
    config?.emailDomainAllowlistEnabled ?? false;
  const allowedEmailDomains = config?.allowedEmailDomains ?? [];
  const domainPolicyReady =
    !domainSelectionEnabled || Boolean(allowedEmailDomains.length);
  const turnstileReady =
    !config?.turnstileEnabled || Boolean(config.turnstileSiteKey);
  const registrationReady = domainPolicyReady && turnstileReady;
  const email = domainSelectionEnabled
    ? emailDomain
      ? `${emailLocalPart}@${emailDomain}`
      : ""
    : emailAddress;

  async function sendCode() {
    if (!domainPolicyReady) {
      setError(labels.noDomains);
      return;
    }
    if (!emailInput.current?.reportValidity()) return;
    if (config?.turnstileEnabled && !turnstileToken) {
      setError(labels.finishHumanCheck);
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
          : labels.sendCodeFailed
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
      setError(labels.passwordMismatch);
      return;
    }
    if (
      config?.emailVerificationRequired
        && (!codeSent || !/^\d{6}$/.test(emailCode))
    ) {
      setError(labels.finishEmailCheck);
      return;
    }
    if (config?.turnstileEnabled && !turnstileToken) {
      setError(labels.finishHumanCheckAgain);
      return;
    }
    if (config?.termsUrl && !termsAccepted) {
      setError(labels.acceptTerms);
      return;
    }
    if (config?.invitationRequired && !inviteCode.trim()) {
      setError(labels.finishInvitation);
      return;
    }
    setSubmitting(true);
    try {
      const session = await register(
        email,
        password,
        displayName,
        navigator.userAgent.slice(0, 120),
        config?.emailVerificationRequired ? emailCode : null,
        turnstileToken,
        inviteCode.trim() || null
      );
      setSession(session);
      navigate("/account", true);
    } catch (caught) {
      setError(
        caught instanceof ApiError
          ? caught.message
          : labels.registrationFailed
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

  function resetEmailVerification() {
    setCodeSent(false);
    setEmailCode("");
  }

  return (
    <AuthLayout
      title={labels.title}
      description={labels.description}
      eyebrow="New account"
      footer={
        <p>
          {labels.question} <AppLink href="/login">{labels.login}</AppLink>
        </p>
      }
    >
      <form className="freedom-form" onSubmit={handleSubmit}>
        <label>
          {labels.nickname}
          <input
            autoComplete="name"
            maxLength={80}
            required
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
          />
        </label>
        <label>
          {labels.email}
          {domainSelectionEnabled ? (
            allowedEmailDomains.length ? (
              <span className="freedom-email-domain-row">
                <input
                  ref={emailInput}
                  autoComplete="username"
                  inputMode="email"
                  maxLength={64}
                  pattern="[^@\s]+"
                  required
                  value={emailLocalPart}
                  onChange={(event) => {
                    setEmailLocalPart(event.target.value);
                    resetEmailVerification();
                  }}
                />
                <select
                  aria-label={labels.emailDomain}
                  value={emailDomain}
                  onChange={(event) => {
                    setEmailDomain(event.target.value);
                    resetEmailVerification();
                  }}
                >
                  {allowedEmailDomains.map((domain) => (
                    <option key={domain} value={domain}>
                      @{domain}
                    </option>
                  ))}
                </select>
              </span>
            ) : (
              <input
                ref={emailInput}
                disabled
                placeholder={labels.noDomains}
              />
            )
          ) : (
            <input
              ref={emailInput}
              autoComplete="email"
              required
              type="email"
              value={emailAddress}
              onChange={(event) => {
                setEmailAddress(event.target.value);
                resetEmailVerification();
              }}
            />
          )}
        </label>
        {config?.emailVerificationRequired && (
          <label>
            {labels.emailCode}
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
                disabled={sendingCode || !config || !registrationReady}
                type="button"
                onClick={() => void sendCode()}
              >
                {sendingCode
                  ? labels.sendingCode
                  : codeSent
                    ? labels.resendCode
                    : labels.sendCode}
              </button>
            </span>
            {codeSent && <small>{labels.codeSent}</small>}
          </label>
        )}
        <label>
          {labels.password}
          <input
            autoComplete="new-password"
            minLength={12}
            required
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
          <small>{labels.passwordHint}</small>
        </label>
        <label>
          {labels.confirmation}
          <input
            autoComplete="new-password"
            minLength={12}
            required
            type="password"
            value={confirmation}
            onChange={(event) => setConfirmation(event.target.value)}
          />
        </label>
        <label>
          {labels.inviteCode}{" "}
          <small>
            ({config?.invitationRequired
              ? labels.inviteRequired
              : labels.inviteOptional})
          </small>
          <input
            autoComplete="off"
            maxLength={32}
            required={config?.invitationRequired}
            value={inviteCode}
            onChange={(event) => setInviteCode(event.target.value)}
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
              <small>{labels.verifyAgain}</small>
            )}
          </>
        )}
        {config?.turnstileEnabled && !config.turnstileSiteKey && (
          <p className="error-message">{labels.turnstileIncomplete}</p>
        )}
        {config?.termsUrl && (
          <label className="freedom-terms">
            <input
              checked={termsAccepted}
              required
              type="checkbox"
              onChange={(event) => setTermsAccepted(event.target.checked)}
            />
            <span>
              {labels.termsPrefix}
              <a
                href={config.termsUrl}
                rel="noreferrer"
                target="_blank"
              >
                {labels.terms}
              </a>
            </span>
          </label>
        )}
        {error && <p className="error-message">{error}</p>}
        <button
          className="freedom-button primary submit"
          disabled={submitting || !config || !registrationReady}
        >
          {submitting ? labels.submitting : labels.submit}
        </button>
      </form>
    </AuthLayout>
  );
}

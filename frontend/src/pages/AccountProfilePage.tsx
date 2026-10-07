import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";

import { AppShell } from "../components/AppShell";
import {
  ApiError,
  changeEmail,
  changePassword,
  graphQl,
  requestEmailChangeCode
} from "../lib/http";
import { formatMinorMoney } from "../lib/subscription";
import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";
import type { Viewer } from "../types";

function formatDate(value: string, locale: "zh-CN" | "en-US") {
  return new Intl.DateTimeFormat(locale, {
    dateStyle: "long"
  }).format(new Date(value));
}

const updateProfileMutation = `
  mutation UpdateViewerProfile($displayName: String!) {
    updateViewerProfile(displayName: $displayName) {
      id email displayName emailVerified roles createdAt balanceMinor remindExpire remindTraffic
    }
  }
`;

const updateRemindersMutation = `
  mutation UpdateViewerReminders($remindExpire: Boolean!, $remindTraffic: Boolean!) {
    updateViewerReminders(remindExpire: $remindExpire, remindTraffic: $remindTraffic) {
      id email displayName emailVerified roles createdAt balanceMinor remindExpire remindTraffic
    }
  }
`;

/**
 * The balance is money, so it is read fresh rather than taken from the session
 * the app booted with: a purchase made in this tab changes it, and a stale
 * figure on an account page is worse than none.
 */
const balanceQuery = `query ViewerBalance { viewer { balanceMinor } }`;

const copy = {
  "zh-CN": {
    title: "个人中心",
    description: "管理显示名称、邮箱、密码和提醒邮件设置。",
    accountDetails: "账户信息",
    accountDetailsDescription: "查看当前账户的基础资料。",
    accountEmail: "账户邮箱",
    emailVerified: "邮箱已验证",
    emailUnverified: "邮箱未验证",
    identity: "账户身份",
    user: "用户",
    balance: "账户余额",
    balanceUnavailable: "读取中…",
    balanceFailed: "余额暂不可用。",
    retry: "重试",
    joined: "加入时间",
    profile: "个人资料",
    email: "邮箱",
    changeEmail: "更换邮箱",
    changeEmailDescription: "验证新邮箱并输入当前密码后即可完成更换。当前设备会保持登录，其他用户设备会话将被撤销。",
    newEmail: "新邮箱",
    emailCurrentPassword: "当前密码",
    emailCode: "邮箱验证码",
    sendCode: "发送验证码",
    sendingCode: "发送中…",
    confirmEmail: "确认更换邮箱",
    confirmingEmail: "确认中…",
    emailCodeSent: "验证码已发送至目标邮箱，请查收。",
    emailChanged: "邮箱已更换并完成验证。当前设备保持登录，其他用户设备会话已撤销。",
    emailCodeRequired: "请先发送验证码。",
    emailCodeFormat: "请输入 6 位数字验证码。",
    nameRequired: "显示名称不能为空。",
    passwordInvalid: "密码必须为 12 至 128 个字符。",
    emailCooldown: (seconds: number) => `验证码发送过于频繁，请在 ${seconds} 秒后重试。`,
    emailFailed: "邮箱更换失败",
    emailCodeFailed: "验证码发送失败",
    targetChanged: "目标邮箱或当前密码已变化，请重新发送验证码。",
    displayName: "显示名称",
    saveProfile: "保存资料",
    profileSaved: "个人资料已保存。",
    saveFailed: "保存失败",
    password: "修改密码",
    currentPassword: "当前密码",
    newPassword: "新密码",
    confirmPassword: "确认新密码",
    updatePassword: "更新密码",
    mismatch: "两次输入的新密码不一致",
    passwordUpdated: "密码已更新，当前设备保持登录。",
    passwordFailed: "密码更新失败",
    reminders: "提醒邮件",
    remindersDescription: "选择系统每天可以发送给你的提醒。",
    remindExpire: "到期提醒（服务到期前 24 小时）",
    remindTraffic: "流量提醒（用量达到 80% 时）",
    saveReminders: "保存提醒设置",
    remindersSaved: "提醒设置已保存。",
    remindersFailed: "提醒设置保存失败"
  },
  "en-US": {
    title: "Personal center",
    description: "Manage your display name, email, password, and reminder preferences.",
    accountDetails: "Account information",
    accountDetailsDescription: "Review the basic details of this account.",
    accountEmail: "Account email",
    emailVerified: "Verified",
    emailUnverified: "Not verified",
    identity: "Account role",
    user: "User",
    balance: "Account balance",
    balanceUnavailable: "Loading…",
    balanceFailed: "Balance is temporarily unavailable.",
    retry: "Retry",
    joined: "Joined",
    profile: "Profile",
    email: "Email",
    changeEmail: "Change email",
    changeEmailDescription: "Verify the new address and confirm with your current password. This device stays signed in; other user-device sessions are revoked.",
    newEmail: "New email",
    emailCurrentPassword: "Current password",
    emailCode: "Email verification code",
    sendCode: "Send code",
    sendingCode: "Sending…",
    confirmEmail: "Confirm email change",
    confirmingEmail: "Confirming…",
    emailCodeSent: "A verification code was sent to the target address.",
    emailChanged: "Your email has been changed and verified. This device remains signed in; other user-device sessions have been revoked.",
    emailCodeRequired: "Send a verification code first.",
    emailCodeFormat: "Enter the six-digit code.",
    nameRequired: "Display name cannot be blank.",
    passwordInvalid: "Passwords must be between 12 and 128 characters.",
    emailCooldown: (seconds: number) => `A code was sent recently. Try again in ${seconds} seconds.`,
    emailFailed: "Email change failed",
    emailCodeFailed: "Could not send the verification code",
    targetChanged: "The target email or current password changed. Send a new verification code.",
    displayName: "Display name",
    saveProfile: "Save profile",
    profileSaved: "Your profile has been saved.",
    saveFailed: "Profile update failed",
    password: "Change password",
    currentPassword: "Current password",
    newPassword: "New password",
    confirmPassword: "Confirm new password",
    updatePassword: "Update password",
    mismatch: "The new passwords do not match",
    passwordUpdated: "Password updated. This device remains signed in.",
    passwordFailed: "Password update failed",
    reminders: "Reminder emails",
    remindersDescription: "Choose which daily reminders the system may send you.",
    remindExpire: "Expiry reminder (24 hours before your service ends)",
    remindTraffic: "Traffic reminder (when usage reaches 80%)",
    saveReminders: "Save reminder settings",
    remindersSaved: "Reminder settings saved.",
    remindersFailed: "Failed to save reminder settings"
  }
};

export function AccountProfilePage() {
  const viewer = useAuthStore((state) => state.viewer);
  return viewer ? <AccountProfileForm key={viewer.id} /> : null;
}

function AccountProfileForm() {
  const accessToken = useAuthStore((state) => state.accessToken)!;
  const viewer = useAuthStore((state) => state.viewer)!;
  const setViewer = useAuthStore((state) => state.setViewer);
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const [displayName, setDisplayName] = useState(viewer.displayName);
  const [remindExpire, setRemindExpire] = useState(viewer.remindExpire);
  const [remindTraffic, setRemindTraffic] = useState(viewer.remindTraffic);
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [targetEmail, setTargetEmail] = useState("");
  const [emailCurrentPassword, setEmailCurrentPassword] = useState("");
  const [emailCode, setEmailCode] = useState("");
  const [emailCodeSent, setEmailCodeSent] = useState(false);
  const [emailError, setEmailError] = useState("");
  const [emailMessage, setEmailMessage] = useState("");
  const [sendingCode, setSendingCode] = useState(false);
  const [confirmingEmail, setConfirmingEmail] = useState(false);
  const [sentTarget, setSentTarget] = useState("");
  const [sentPassword, setSentPassword] = useState("");
  const queryClient = useQueryClient();
  const [profileMessage, setProfileMessage] = useState("");
  const [reminderMessage, setReminderMessage] = useState("");
  const [passwordMessage, setPasswordMessage] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const balance = useQuery({
    queryKey: ["viewer-balance", viewer.id],
    queryFn: () =>
      graphQl<{ viewer: { balanceMinor: string } }>(accessToken, balanceQuery),
    retry: false,
    refetchOnMount: "always"
  });
  const balanceMinor = balance.isSuccess && !balance.isFetching
    ? balance.data.viewer.balanceMinor
    : undefined;

  async function updateProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmedName = displayName.trim();
    if (!trimmedName || trimmedName.length > 80) {
      setError(labels.nameRequired);
      return;
    }
    setSubmitting(true);
    setError("");
    setProfileMessage("");
    try {
      const result = await graphQl<{ updateViewerProfile: Viewer }>(
        accessToken,
        updateProfileMutation,
        { displayName: trimmedName }
      );
      setViewer(result.updateViewerProfile);
      setDisplayName(result.updateViewerProfile.displayName);
      setProfileMessage(labels.profileSaved);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : labels.saveFailed);
    } finally {
      setSubmitting(false);
    }
  }

  async function updateReminders(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    setReminderMessage("");
    try {
      const result = await graphQl<{ updateViewerReminders: Viewer }>(
        accessToken,
        updateRemindersMutation,
        { remindExpire, remindTraffic }
      );
      setViewer(result.updateViewerReminders);
      setReminderMessage(labels.remindersSaved);
    } catch (caught) {
      setError(
        caught instanceof ApiError ? caught.message : labels.remindersFailed
      );
    } finally {
      setSubmitting(false);
    }
  }

  async function updatePassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    setPasswordMessage("");
    if (newPassword !== confirmation) {
      setError(labels.mismatch);
      return;
    }
    if (!currentPassword || newPassword.length < 12 || newPassword.length > 128) {
      setError(labels.passwordInvalid);
      return;
    }
    setSubmitting(true);
    try {
      await changePassword(accessToken, currentPassword, newPassword);
      setCurrentPassword("");
      setNewPassword("");
      setConfirmation("");
      clearEmailChallenge();
      setEmailCurrentPassword("");
      setPasswordMessage(labels.passwordUpdated);
    } catch (caught) {
      setError(
        caught instanceof ApiError ? caught.message : labels.passwordFailed
      );
    } finally {
      setSubmitting(false);
    }
  }

  function clearEmailChallenge() {
    if (emailCodeSent) setEmailError(labels.targetChanged);
    setEmailCodeSent(false);
    setEmailCode("");
    setSentTarget("");
    setSentPassword("");
    setEmailMessage("");
  }

  async function sendEmailCode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setEmailError("");
    setEmailMessage("");
    const normalizedEmail = targetEmail.trim().toLowerCase();
    if (!normalizedEmail || !emailCurrentPassword) return;
    setSendingCode(true);
    try {
      await requestEmailChangeCode(accessToken, normalizedEmail, emailCurrentPassword);
      setTargetEmail(normalizedEmail);
      setSentTarget(normalizedEmail);
      setSentPassword(emailCurrentPassword);
      setEmailCodeSent(true);
      setEmailCode("");
      setEmailMessage(labels.emailCodeSent);
    } catch (caught) {
      setEmailError(
        caught instanceof ApiError && caught.status === 429 && caught.retryAfterSeconds !== undefined
          ? labels.emailCooldown(caught.retryAfterSeconds)
          : caught instanceof ApiError ? caught.message : labels.emailCodeFailed
      );
    } finally {
      setSendingCode(false);
    }
  }

  async function confirmEmailChange(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setEmailError("");
    setEmailMessage("");
    const normalizedEmail = targetEmail.trim().toLowerCase();
    if (!emailCodeSent || normalizedEmail !== sentTarget || emailCurrentPassword !== sentPassword) {
      setEmailError(emailCodeSent ? labels.targetChanged : labels.emailCodeRequired);
      return;
    }
    if (!/^\d{6}$/.test(emailCode)) {
      setEmailError(labels.emailCodeFormat);
      return;
    }
    setConfirmingEmail(true);
    try {
      const updatedViewer = await changeEmail(
        accessToken,
        normalizedEmail,
        emailCurrentPassword,
        emailCode
      );
      setViewer(updatedViewer);
      setTargetEmail("");
      setEmailCurrentPassword("");
      setEmailCode("");
      setEmailCodeSent(false);
      setSentTarget("");
      setSentPassword("");
      setEmailMessage(labels.emailChanged);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["viewer-balance", viewer.id] }),
        queryClient.invalidateQueries({ queryKey: ["viewer-balance-ledger", viewer.id] }),
        queryClient.invalidateQueries({ queryKey: ["viewer-commission", viewer.id] }),
        queryClient.invalidateQueries({ queryKey: ["viewer-invitations", viewer.id] }),
        queryClient.invalidateQueries({ queryKey: ["viewer-traffic-daily"] }),
        queryClient.invalidateQueries({ queryKey: ["viewer-orders"] }),
        queryClient.invalidateQueries({ queryKey: ["device-sessions"] }),
        queryClient.invalidateQueries({ queryKey: ["viewer"] })
      ]);
    } catch (caught) {
      setEmailError(caught instanceof ApiError ? caught.message : labels.emailFailed);
    } finally {
      setConfirmingEmail(false);
    }
  }

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">Profile</p>
        <h1>{labels.title}</h1>
        <p className="muted">{labels.description}</p>
      </header>
      <section className="panel profile-account-summary">
        <header>
          <h2>{labels.accountDetails}</h2>
          <p>{labels.accountDetailsDescription}</p>
        </header>
        <dl>
          <div>
            <dt>{labels.accountEmail}</dt>
            <dd>{viewer.email} · {viewer.emailVerified ? labels.emailVerified : labels.emailUnverified}</dd>
          </div>
          <div>
            <dt>{labels.identity}</dt>
            <dd>{labels.user}</dd>
          </div>
          <div>
            <dt>{labels.balance}</dt>
            <dd>
              {balanceMinor === undefined
                ? labels.balanceUnavailable
                : formatMinorMoney(balanceMinor, "CNY", language)}
              {balance.isError && (
                <span className="profile-balance-retry">
                  {labels.balanceFailed} <button onClick={() => void balance.refetch()} type="button">{labels.retry}</button>
                </span>
              )}
            </dd>
          </div>
          <div>
            <dt>{labels.joined}</dt>
            <dd>{formatDate(viewer.createdAt, language)}</dd>
          </div>
        </dl>
      </section>
      <div className="account-settings-grid">
        <section className="panel account-form-panel">
          <h2>{labels.profile}</h2>
          <form onSubmit={updateProfile}>
            <label>
              {labels.email}
              <input disabled value={viewer.email} />
            </label>
            <label>
              {labels.displayName}
              <input
                maxLength={80}
                minLength={1}
                required
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
              />
            </label>
            {profileMessage && (
              <p className="account-inline-message success">
                {profileMessage}
              </p>
            )}
            <button
              className="primary-button compact-button"
              disabled={submitting}
            >
              {labels.saveProfile}
            </button>
          </form>
        </section>
        <section className="panel account-form-panel">
          <h2>{labels.changeEmail}</h2>
          <p className="muted">{labels.changeEmailDescription}</p>
          <form onSubmit={sendEmailCode}>
            <label>
              {labels.newEmail}
              <input
                autoComplete="email"
                maxLength={320}
                required
                type="email"
                value={targetEmail}
                onChange={(event) => {
                  clearEmailChallenge();
                  setTargetEmail(event.target.value);
                }}
              />
            </label>
            <label>
              {labels.emailCurrentPassword}
              <input
                autoComplete="current-password"
                maxLength={128}
                required
                type="password"
                value={emailCurrentPassword}
                onChange={(event) => {
                  clearEmailChallenge();
                  setEmailCurrentPassword(event.target.value);
                }}
              />
            </label>
            {emailMessage && <p className="account-inline-message success" role="status">{emailMessage}</p>}
            {emailError && <p className="error-message" role="alert">{emailError}</p>}
            <button
              className="primary-button compact-button"
              disabled={sendingCode || confirmingEmail || !targetEmail.trim() || !emailCurrentPassword}
              type="submit"
            >
              {sendingCode ? labels.sendingCode : labels.sendCode}
            </button>
          </form>
          <form onSubmit={confirmEmailChange}>
            <label>
              {labels.emailCode}
              <input
                autoComplete="one-time-code"
                inputMode="numeric"
                maxLength={6}
                pattern="[0-9]{6}"
                required
                value={emailCode}
                onChange={(event) => setEmailCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
              />
            </label>
            <button
              className="primary-button compact-button"
              disabled={!emailCodeSent || sendingCode || confirmingEmail}
              type="submit"
            >
              {confirmingEmail ? labels.confirmingEmail : labels.confirmEmail}
            </button>
          </form>
        </section>
        <section className="panel account-form-panel">
          <h2>{labels.reminders}</h2>
          <p className="muted">{labels.remindersDescription}</p>
          <form onSubmit={updateReminders}>
            <label className="account-reminder-toggle">
              <input
                type="checkbox"
                checked={remindExpire}
                onChange={(event) => setRemindExpire(event.target.checked)}
              />
              <span>{labels.remindExpire}</span>
            </label>
            <label className="account-reminder-toggle">
              <input
                type="checkbox"
                checked={remindTraffic}
                onChange={(event) => setRemindTraffic(event.target.checked)}
              />
              <span>{labels.remindTraffic}</span>
            </label>
            {reminderMessage && (
              <p className="account-inline-message success">
                {reminderMessage}
              </p>
            )}
            <button
              className="primary-button compact-button"
              disabled={submitting}
            >
              {labels.saveReminders}
            </button>
          </form>
        </section>
        <section className="panel account-form-panel">
          <h2>{labels.password}</h2>
          <form onSubmit={updatePassword}>
            <label>
              {labels.currentPassword}
              <input
                type="password"
                autoComplete="current-password"
                maxLength={128}
                required
                value={currentPassword}
                onChange={(event) => setCurrentPassword(event.target.value)}
              />
            </label>
            <label>
              {labels.newPassword}
              <input
                type="password"
                autoComplete="new-password"
                minLength={12}
                maxLength={128}
                required
                value={newPassword}
                onChange={(event) => setNewPassword(event.target.value)}
              />
            </label>
            <label>
              {labels.confirmPassword}
              <input
                type="password"
                autoComplete="new-password"
                minLength={12}
                maxLength={128}
                required
                value={confirmation}
                onChange={(event) => setConfirmation(event.target.value)}
              />
            </label>
            {passwordMessage && (
              <p className="account-inline-message success">
                {passwordMessage}
              </p>
            )}
            <button
              className="primary-button compact-button"
              disabled={submitting}
            >
              {labels.updatePassword}
            </button>
          </form>
        </section>
      </div>
      {error && <p className="error-message account-page-error">{error}</p>}
    </AppShell>
  );
}

import { useState, type FormEvent } from "react";

import { AppShell } from "../components/AppShell";
import { ApiError, changePassword, graphQl } from "../lib/http";
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
      id email displayName emailVerified roles createdAt
    }
  }
`;

const copy = {
  "zh-CN": {
    title: "个人中心",
    description: "更新显示名称或更换登录密码。",
    accountDetails: "账户信息",
    accountDetailsDescription: "查看当前账户的基础资料。",
    accountEmail: "账户邮箱",
    identity: "账户身份",
    user: "用户",
    joined: "加入时间",
    profile: "个人资料",
    email: "邮箱",
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
    passwordFailed: "密码更新失败"
  },
  "en-US": {
    title: "Personal center",
    description: "Update your display name or change your password.",
    accountDetails: "Account information",
    accountDetailsDescription: "Review the basic details of this account.",
    accountEmail: "Account email",
    identity: "Account role",
    user: "User",
    joined: "Joined",
    profile: "Profile",
    email: "Email",
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
    passwordFailed: "Password update failed"
  }
};

export function AccountProfilePage() {
  const accessToken = useAuthStore((state) => state.accessToken)!;
  const viewer = useAuthStore((state) => state.viewer)!;
  const setViewer = useAuthStore((state) => state.setViewer);
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const [displayName, setDisplayName] = useState(viewer.displayName);
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [profileMessage, setProfileMessage] = useState("");
  const [passwordMessage, setPasswordMessage] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function updateProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    setProfileMessage("");
    try {
      const result = await graphQl<{ updateViewerProfile: Viewer }>(
        accessToken,
        updateProfileMutation,
        { displayName }
      );
      setViewer(result.updateViewerProfile);
      setProfileMessage(labels.profileSaved);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : labels.saveFailed);
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
    setSubmitting(true);
    try {
      await changePassword(accessToken, currentPassword, newPassword);
      setCurrentPassword("");
      setNewPassword("");
      setConfirmation("");
      setPasswordMessage(labels.passwordUpdated);
    } catch (caught) {
      setError(
        caught instanceof ApiError ? caught.message : labels.passwordFailed
      );
    } finally {
      setSubmitting(false);
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
            <dd>{viewer.email}</dd>
          </div>
          <div>
            <dt>{labels.identity}</dt>
            <dd>{labels.user}</dd>
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
          <h2>{labels.password}</h2>
          <form onSubmit={updatePassword}>
            <label>
              {labels.currentPassword}
              <input
                type="password"
                autoComplete="current-password"
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

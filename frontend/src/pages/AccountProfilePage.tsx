import { useState, type FormEvent } from "react";

import { AppShell } from "../components/AppShell";
import { ApiError, changePassword, graphQl } from "../lib/http";
import { useAuthStore } from "../store/auth";
import type { Viewer } from "../types";

const updateProfileMutation = `
  mutation UpdateViewerProfile($displayName: String!) {
    updateViewerProfile(displayName: $displayName) {
      id email displayName emailVerified roles createdAt
    }
  }
`;

export function AccountProfilePage() {
  const accessToken = useAuthStore((state) => state.accessToken)!;
  const viewer = useAuthStore((state) => state.viewer)!;
  const setViewer = useAuthStore((state) => state.setViewer);
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
      setProfileMessage("个人资料已保存。");
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "保存失败");
    } finally {
      setSubmitting(false);
    }
  }

  async function updatePassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    setPasswordMessage("");
    if (newPassword !== confirmation) {
      setError("两次输入的新密码不一致");
      return;
    }
    setSubmitting(true);
    try {
      await changePassword(accessToken, currentPassword, newPassword);
      setCurrentPassword("");
      setNewPassword("");
      setConfirmation("");
      setPasswordMessage("密码已更新，当前设备保持登录。");
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "密码更新失败");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">Profile</p>
        <h1>账户资料</h1>
        <p className="muted">更新显示名称或更换登录密码。</p>
      </header>
      <div className="account-settings-grid">
        <section className="panel account-form-panel">
          <h2>个人资料</h2>
          <form onSubmit={updateProfile}>
            <label>
              邮箱
              <input disabled value={viewer.email} />
            </label>
            <label>
              显示名称
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
              保存资料
            </button>
          </form>
        </section>
        <section className="panel account-form-panel">
          <h2>修改密码</h2>
          <form onSubmit={updatePassword}>
            <label>
              当前密码
              <input
                type="password"
                autoComplete="current-password"
                required
                value={currentPassword}
                onChange={(event) => setCurrentPassword(event.target.value)}
              />
            </label>
            <label>
              新密码
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
            {passwordMessage && (
              <p className="account-inline-message success">
                {passwordMessage}
              </p>
            )}
            <button
              className="primary-button compact-button"
              disabled={submitting}
            >
              更新密码
            </button>
          </form>
        </section>
      </div>
      {error && <p className="error-message account-page-error">{error}</p>}
    </AppShell>
  );
}

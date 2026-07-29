import { useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";

import { AdminShell } from "../components/AdminShell";
import {
  ApiError,
  confirmMfaEnrollment,
  disableMfa,
  getMfaStatus,
  startMfaEnrollment
} from "../lib/http";
import { useAuthStore } from "../store/auth";
import type { MfaEnrollment } from "../types";

export function AdminMfaPage() {
  const accessToken = useAuthStore((state) => state.accessToken)!;
  const [enrollment, setEnrollment] = useState<MfaEnrollment | null>(null);
  const [confirmationCode, setConfirmationCode] = useState("");
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);
  const [disablePassword, setDisablePassword] = useState("");
  const [disableCode, setDisableCode] = useState("");
  const [error, setError] = useState("");
  const [working, setWorking] = useState(false);

  const status = useQuery({
    queryKey: ["admin-mfa-status"],
    queryFn: () => getMfaStatus(accessToken)
  });

  async function beginEnrollment() {
    setWorking(true);
    setError("");
    try {
      setEnrollment(await startMfaEnrollment(accessToken));
      setRecoveryCodes([]);
    } catch (caught) {
      showError(caught);
    } finally {
      setWorking(false);
    }
  }

  async function confirmEnrollment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setWorking(true);
    setError("");
    try {
      const result = await confirmMfaEnrollment(
        accessToken,
        confirmationCode
      );
      setRecoveryCodes(result.recoveryCodes);
      setEnrollment(null);
      setConfirmationCode("");
      await status.refetch();
    } catch (caught) {
      showError(caught);
    } finally {
      setWorking(false);
    }
  }

  async function handleDisable(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setWorking(true);
    setError("");
    try {
      await disableMfa(
        accessToken,
        disablePassword,
        disableCode
      );
      setDisablePassword("");
      setDisableCode("");
      setRecoveryCodes([]);
      await status.refetch();
    } catch (caught) {
      showError(caught);
    } finally {
      setWorking(false);
    }
  }

  function showError(caught: unknown) {
    setError(
      caught instanceof ApiError
        ? caught.message
        : "安全设置未能完成，请稍后重试"
    );
  }

  return (
    <AdminShell>
      <header className="page-header">
        <p className="eyebrow">Administration</p>
        <h1>管理员 MFA</h1>
        <p className="muted">
          管理员密码泄漏后，攻击者仍需一次性验证码才能建立会话。
        </p>
      </header>
      <section className="panel mfa-panel">
        {status.isPending && <p className="muted">正在读取安全状态…</p>}
        {status.isError && (
          <p className="error-message">MFA 状态读取失败。</p>
        )}
        {status.data && (
          <div className="mfa-status">
            <div>
              <strong>
                {status.data.enabled ? "MFA 已启用" : "MFA 尚未启用"}
              </strong>
              <p className="muted">
                {status.data.enabledAt
                  ? `启用时间：${new Date(
                      status.data.enabledAt
                    ).toLocaleString("zh-CN")}`
                  : "仅管理员账户可配置此项。"}
              </p>
            </div>
            <span
              className={
                status.data.enabled
                  ? "status-pill"
                  : "status-pill status-neutral"
              }
            >
              {status.data.enabled ? "受保护" : "未配置"}
            </span>
          </div>
        )}

        {!status.data?.enabled && !enrollment && (
          <button
            className="primary-button compact-button"
            disabled={working || status.isPending}
            onClick={() => void beginEnrollment()}
          >
            开始配置验证器
          </button>
        )}

        {enrollment && (
          <form className="mfa-enrollment" onSubmit={confirmEnrollment}>
            <div className="security-notice">
              <strong>第 1 步：添加到验证器</strong>
              <p>
                在验证器应用中打开下面的链接，或手动输入密钥。
              </p>
              <a href={enrollment.otpauthUri}>在验证器中打开</a>
              <code className="secret-code">{enrollment.secret}</code>
            </div>
            <label>
              第 2 步：输入当前 6 位验证码
              <input
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                pattern="[0-9]{6}"
                minLength={6}
                maxLength={6}
                required
                value={confirmationCode}
                onChange={(event) =>
                  setConfirmationCode(event.target.value)
                }
              />
            </label>
            <button className="primary-button" disabled={working}>
              {working ? "正在确认…" : "确认并启用 MFA"}
            </button>
          </form>
        )}

        {recoveryCodes.length > 0 && (
          <div className="recovery-box">
            <strong>恢复码只显示这一次</strong>
            <p className="muted">
              请离线保存。每枚恢复码只能使用一次。
            </p>
            <div className="recovery-grid">
              {recoveryCodes.map((code) => (
                <code key={code}>{code}</code>
              ))}
            </div>
          </div>
        )}

        {status.data?.enabled && (
          <form className="danger-zone" onSubmit={handleDisable}>
            <div>
              <strong>关闭 MFA</strong>
              <p className="muted">
                需要当前密码和验证码；其他设备会话将被撤销。
              </p>
            </div>
            <label>
              当前密码
              <input
                type="password"
                autoComplete="current-password"
                required
                value={disablePassword}
                onChange={(event) =>
                  setDisablePassword(event.target.value)
                }
              />
            </label>
            <label>
              验证码或恢复码
              <input
                type="text"
                autoComplete="one-time-code"
                minLength={6}
                maxLength={32}
                required
                value={disableCode}
                onChange={(event) => setDisableCode(event.target.value)}
              />
            </label>
            <button className="danger-button" disabled={working}>
              {working ? "正在关闭…" : "关闭 MFA"}
            </button>
          </form>
        )}
        {error && <p className="error-message">{error}</p>}
      </section>
    </AdminShell>
  );
}

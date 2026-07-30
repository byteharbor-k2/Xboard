import { useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";

import { AdminShell } from "../components/AdminShell";
import {
  adminLogout,
  ApiError,
  disableMfa,
  getMfaStatus
} from "../lib/http";
import { navigate } from "../lib/navigation";
import { useAdminAuthStore } from "../store/adminAuth";

export function AdminMfaPage() {
  const accessToken = useAdminAuthStore((state) => state.accessToken)!;
  const clearSession = useAdminAuthStore((state) => state.clearSession);
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [error, setError] = useState("");
  const [working, setWorking] = useState(false);

  const status = useQuery({
    queryKey: ["admin-mfa-status"],
    queryFn: () => getMfaStatus(accessToken)
  });

  async function handleDisable(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (working) return;
    setWorking(true);
    setError("");
    try {
      await disableMfa(accessToken, password, code);
      await adminLogout();
      clearSession();
      navigate("/admin/login", true);
    } catch (caught) {
      setError(
        caught instanceof ApiError
          ? caught.message
          : "安全设置未能完成，请稍后重试"
      );
    } finally {
      setWorking(false);
    }
  }

  return (
    <AdminShell>
      <header className="page-header">
        <p className="eyebrow">Administration</p>
        <h1>管理员 MFA</h1>
        <p className="muted">
          管理员会话必须通过密码和一次性验证码双重验证。
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
                  : "请退出后从管理员登录入口完成首次配置。"}
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

        {status.data?.enabled && (
          <form className="danger-zone" onSubmit={handleDisable}>
            <div>
              <strong>关闭 MFA</strong>
              <p className="muted">
                关闭后全部管理员会话会被撤销，下次登录必须重新配置。
              </p>
            </div>
            <label>
              当前密码
              <input
                autoComplete="current-password"
                required
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
            </label>
            <label>
              验证码或恢复码
              <input
                autoComplete="one-time-code"
                maxLength={32}
                minLength={6}
                required
                value={code}
                onChange={(event) => setCode(event.target.value)}
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

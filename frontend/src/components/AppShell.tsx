import type { PropsWithChildren } from "react";

import { logout } from "../lib/http";
import { useAuthStore } from "../store/auth";
import { AppLink } from "./AppLink";

export function AppShell({ children }: PropsWithChildren) {
  const viewer = useAuthStore((state) => state.viewer);
  const clearSession = useAuthStore((state) => state.clearSession);

  async function handleLogout() {
    try {
      await logout();
    } finally {
      clearSession();
      window.location.replace("/login");
    }
  }

  return (
    <div className="app-frame">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark">S</span>
          <span>SinX Cloud</span>
        </div>
        <nav>
          <AppLink
            className={
              window.location.pathname === "/security/sessions"
                ? "active"
                : undefined
            }
            href="/security/sessions"
          >
            登录设备
          </AppLink>
          {viewer?.roles.includes("ADMIN") && (
            <>
              <AppLink
                className={
                  window.location.pathname === "/admin/audit"
                    ? "active"
                    : undefined
                }
                href="/admin/audit"
              >
                管理审计
              </AppLink>
              <AppLink
                className={
                  window.location.pathname === "/admin/mfa"
                    ? "active"
                    : undefined
                }
                href="/admin/mfa"
              >
                管理员 MFA
              </AppLink>
            </>
          )}
        </nav>
        <div className="account">
          <strong>{viewer?.displayName}</strong>
          <span>{viewer?.email}</span>
          <button className="text-button" onClick={handleLogout}>
            退出登录
          </button>
        </div>
      </aside>
      <main className="content">{children}</main>
    </div>
  );
}

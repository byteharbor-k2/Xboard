import type { PropsWithChildren } from "react";

import { logout } from "../lib/http";
import { navigate } from "../lib/navigation";
import { useAuthStore } from "../store/auth";
import { AppLink } from "./AppLink";
import { FreedomBrand } from "./FreedomBrand";

export function AppShell({ children }: PropsWithChildren) {
  const viewer = useAuthStore((state) => state.viewer);
  const clearSession = useAuthStore((state) => state.clearSession);

  async function handleLogout() {
    try {
      await logout();
    } finally {
      clearSession();
      navigate("/login", true);
    }
  }

  return (
    <div className="app-frame">
      <aside className="sidebar">
        <FreedomBrand href="/account" />
        <nav>
          <AppLink
            className={
              window.location.pathname === "/account"
                ? "active"
                : undefined
            }
            href="/account"
          >
            账户概览
          </AppLink>
          <AppLink
            className={
              window.location.pathname === "/account/profile"
                ? "active"
                : undefined
            }
            href="/account/profile"
          >
            账户资料
          </AppLink>
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
                  window.location.pathname === "/admin/mfa"
                    ? "active"
                    : undefined
                }
                href="/admin/mfa"
              >
                管理员 MFA
              </AppLink>
              <AppLink href="/admin/dashboard">管理后台</AppLink>
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

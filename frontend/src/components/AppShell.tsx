import type { PropsWithChildren } from "react";

import { logout } from "../lib/http";
import { useAuthStore } from "../store/auth";

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
          <a className="active" href="/security/sessions">
            登录设备
          </a>
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

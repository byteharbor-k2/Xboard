import type { PropsWithChildren } from "react";

import { logout } from "../lib/http";
import { navigate } from "../lib/navigation";
import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";
import { AppLink } from "./AppLink";
import { FreedomBrand } from "./FreedomBrand";

const copy = {
  "zh-CN": {
    overview: "账户概览",
    profile: "账户资料",
    plans: "套餐",
    devices: "登录设备",
    logout: "退出登录",
    switchLanguage: "Switch to English"
  },
  "en-US": {
    overview: "Overview",
    profile: "Profile",
    plans: "Plans",
    devices: "Devices",
    logout: "Sign out",
    switchLanguage: "切换到中文"
  }
};

export function AppShell({ children }: PropsWithChildren) {
  const viewer = useAuthStore((state) => state.viewer);
  const clearSession = useAuthStore((state) => state.clearSession);
  const language = useUserPreferences((state) => state.language);
  const setLanguage = useUserPreferences((state) => state.setLanguage);
  const labels = copy[language];

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
        <button
          aria-label={labels.switchLanguage}
          className="sidebar-language-switch"
          onClick={() =>
            setLanguage(language === "zh-CN" ? "en-US" : "zh-CN")
          }
          type="button"
        >
          {language === "zh-CN" ? "EN" : "中文"}
        </button>
        <nav>
          <AppLink
            className={
              window.location.pathname === "/account"
                ? "active"
                : undefined
            }
            href="/account"
          >
            {labels.overview}
          </AppLink>
          <AppLink
            className={
              window.location.pathname === "/account/profile"
                ? "active"
                : undefined
            }
            href="/account/profile"
          >
            {labels.profile}
          </AppLink>
          <AppLink
            className={
              window.location.pathname === "/plans"
                ? "active"
                : undefined
            }
            href="/plans"
          >
            {labels.plans}
          </AppLink>
          <AppLink
            className={
              window.location.pathname === "/security/sessions"
                ? "active"
                : undefined
            }
            href="/security/sessions"
          >
            {labels.devices}
          </AppLink>
        </nav>
        <div className="account">
          <strong>{viewer?.displayName}</strong>
          <span>{viewer?.email}</span>
          <button className="text-button" onClick={handleLogout}>
            {labels.logout}
          </button>
        </div>
      </aside>
      <main className="content">{children}</main>
    </div>
  );
}

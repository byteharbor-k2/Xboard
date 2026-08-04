import { useState, type PropsWithChildren } from "react";

import { logout } from "../lib/http";
import { navigate } from "../lib/navigation";
import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";
import { AppLink } from "./AppLink";
import { FreedomBrand } from "./FreedomBrand";
import { SupportWidget } from "./SupportWidget";

const copy = {
  "zh-CN": {
    workspace: "用户中心",
    dashboard: "仪表盘",
    profile: "个人中心",
    personalCenter: "个人中心",
    plans: "订阅方案",
    docs: "使用文档",
    orders: "我的订单",
    invitations: "我的邀请",
    tickets: "我的工单",
    traffic: "流量明细",
    devices: "安全设备",
    logout: "退出登录",
    switchLanguage: "Switch to English",
    network: "全球网络",
    online: "服务在线"
  },
  "en-US": {
    workspace: "Workspace",
    dashboard: "Dashboard",
    profile: "Personal center",
    personalCenter: "Personal center",
    plans: "Subscriptions",
    docs: "Guides",
    orders: "My orders",
    invitations: "My invitations",
    tickets: "My tickets",
    traffic: "Traffic details",
    devices: "Security devices",
    logout: "Sign out",
    switchLanguage: "切换到中文",
    network: "Global network",
    online: "Service online"
  }
};

function NavigationIcon({
  name
}: {
  name:
    | "dashboard"
    | "plans"
    | "docs"
    | "profile"
    | "orders"
    | "invitations"
    | "tickets"
    | "traffic"
    | "devices";
}) {
  const paths = {
    dashboard: (
      <>
        <path d="M4 4h6v6H4zM14 4h6v9h-6zM4 14h6v6H4zM14 17h6v3h-6z" />
      </>
    ),
    plans: (
      <>
        <path d="M5 6.5h14M7 3.5h10a2 2 0 0 1 2 2v13H5v-13a2 2 0 0 1 2-2Z" />
        <path d="M8 11h8M8 15h5" />
      </>
    ),
    docs: (
      <>
        <path d="M5 4.5h10a3 3 0 0 1 3 3v12H8a3 3 0 0 1-3-3z" />
        <path d="M8 7.5h7M8 11h7M8 14.5h5" />
      </>
    ),
    profile: (
      <>
        <circle cx="12" cy="8" r="3.2" />
        <path d="M5.5 20c.7-4 3-6 6.5-6s5.8 2 6.5 6" />
      </>
    ),
    orders: (
      <>
        <path d="M5 4h14v16H5z" />
        <path d="M8 8h8M8 12h8M8 16h5" />
      </>
    ),
    invitations: (
      <>
        <circle cx="9" cy="9" r="3" />
        <path d="M3.5 20c.5-3.6 2.3-5.5 5.5-5.5 1.5 0 2.7.4 3.6 1.2" />
        <path d="M17 13v7M13.5 16.5h7" />
      </>
    ),
    tickets: (
      <>
        <path d="M4 5h16v12H9l-4 3v-3H4z" />
        <path d="M8 9h8M8 13h5" />
      </>
    ),
    traffic: (
      <>
        <path d="M5 19V11M12 19V5M19 19v-9" />
      </>
    ),
    devices: (
      <>
        <rect x="4" y="3.5" width="16" height="12" rx="2" />
        <path d="M9 20h6M12 15.5V20" />
      </>
    )
  };
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24">
      <g
        fill={name === "dashboard" ? "currentColor" : "none"}
        stroke="currentColor"
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth="1.7"
      >
        {paths[name]}
      </g>
    </svg>
  );
}

export function AppShell({ children }: PropsWithChildren) {
  const viewer = useAuthStore((state) => state.viewer);
  const clearSession = useAuthStore((state) => state.clearSession);
  const language = useUserPreferences((state) => state.language);
  const setLanguage = useUserPreferences((state) => state.setLanguage);
  const labels = copy[language];
  const currentPath = window.location.pathname;
  const currentSection =
    currentPath === "/plans"
      ? labels.plans
      : currentPath === "/docs"
        ? labels.docs
      : currentPath === "/account/profile"
        ? labels.profile
        : currentPath === "/account/orders"
          ? labels.orders
          : currentPath === "/account/invitations"
            ? labels.invitations
            : currentPath === "/account/tickets"
              ? labels.tickets
              : currentPath === "/account/traffic"
                ? labels.traffic
        : currentPath === "/security/sessions"
          ? labels.devices
          : labels.dashboard;
  const [accountNavigationOpen, setAccountNavigationOpen] = useState(
    currentPath.startsWith("/account/")
  );

  async function handleLogout() {
    try {
      await logout();
    } finally {
      clearSession();
      navigate("/login", true);
    }
  }

  return (
    <div className="app-frame user-app-frame">
      <aside className="sidebar user-sidebar">
        <FreedomBrand href="/dashboard" />
        <div className="user-sidebar-status">
          <span aria-hidden="true" />
          <div>
            <strong>{labels.network}</strong>
            <small>{labels.online}</small>
          </div>
        </div>
        <nav aria-label={labels.workspace}>
          <p>{labels.workspace}</p>
          <AppLink
            className={
              currentPath === "/dashboard"
                ? "active"
                : undefined
            }
            href="/dashboard"
          >
            <NavigationIcon name="dashboard" />
            {labels.dashboard}
          </AppLink>
          <AppLink
            className={
              currentPath === "/plans"
                ? "active"
                : undefined
            }
            href="/plans"
          >
            <NavigationIcon name="plans" />
            {labels.plans}
          </AppLink>
          <AppLink
            className={
              currentPath === "/docs"
                ? "active"
                : undefined
            }
            href="/docs"
          >
            <NavigationIcon name="docs" />
            {labels.docs}
          </AppLink>
          <div className="user-nav-group">
            <div
              className={`user-nav-group-trigger${
                currentPath.startsWith("/account/") ? " active" : ""
              }`}
            >
              <AppLink href="/account/profile">
                <NavigationIcon name="profile" />
                <span>{labels.profile}</span>
              </AppLink>
              <button
                aria-expanded={accountNavigationOpen}
                aria-label={labels.personalCenter}
                onClick={() =>
                  setAccountNavigationOpen((current) => !current)
                }
                type="button"
              >
                <span
                  className={
                    accountNavigationOpen
                      ? "user-nav-chevron open"
                      : "user-nav-chevron"
                  }
                  aria-hidden="true"
                >
                  ⌄
                </span>
              </button>
            </div>
            {accountNavigationOpen && (
              <div className="user-nav-children">
              <AppLink
                className={
                  currentPath === "/account/orders"
                    ? "active"
                    : undefined
                }
                href="/account/orders"
              >
                {labels.orders}
              </AppLink>
              <AppLink
                className={
                  currentPath === "/account/invitations"
                    ? "active"
                    : undefined
                }
                href="/account/invitations"
              >
                {labels.invitations}
              </AppLink>
              <AppLink
                className={
                  currentPath === "/account/tickets"
                    ? "active"
                    : undefined
                }
                href="/account/tickets"
              >
                {labels.tickets}
              </AppLink>
              <AppLink
                className={
                  currentPath === "/account/traffic"
                    ? "active"
                    : undefined
                }
                href="/account/traffic"
              >
                {labels.traffic}
              </AppLink>
              </div>
            )}
          </div>
          <AppLink
            className={
              currentPath === "/security/sessions"
                ? "active"
                : undefined
            }
            href="/security/sessions"
          >
            <NavigationIcon name="devices" />
            {labels.devices}
          </AppLink>
        </nav>
        <div className="account">
          <div className="user-avatar" aria-hidden="true">
            {viewer?.displayName?.slice(0, 1).toUpperCase()}
          </div>
          <div>
            <strong>{viewer?.displayName}</strong>
            <span>{viewer?.email}</span>
          </div>
          <button className="text-button" onClick={handleLogout}>
            {labels.logout}
          </button>
        </div>
      </aside>
      <section className="user-workspace">
        <header className="user-topbar">
          <span className="user-topbar-route">{currentSection}</span>
          <div className="user-topbar-actions">
            <button
              aria-label={labels.switchLanguage}
              className="user-language-switch"
              onClick={() =>
                setLanguage(language === "zh-CN" ? "en-US" : "zh-CN")
              }
              type="button"
            >
              <span aria-hidden="true">文</span>
              {language === "zh-CN" ? "English" : "中文"}
            </button>
            <details className="user-account-menu">
              <summary className="user-topbar-account">
                <span>{viewer?.displayName}</span>
                <span className="user-avatar small" aria-hidden="true">
                  {viewer?.displayName?.slice(0, 1).toUpperCase()}
                </span>
                <span className="user-account-chevron" aria-hidden="true">
                  ⌄
                </span>
              </summary>
              <div className="user-account-dropdown">
                <AppLink href="/account/profile">
                  <NavigationIcon name="profile" />
                  {labels.personalCenter}
                </AppLink>
                <button onClick={handleLogout} type="button">
                  <span aria-hidden="true">↪</span>
                  {labels.logout}
                </button>
              </div>
            </details>
          </div>
        </header>
        <main className="content user-content">{children}</main>
      </section>
      <SupportWidget />
    </div>
  );
}

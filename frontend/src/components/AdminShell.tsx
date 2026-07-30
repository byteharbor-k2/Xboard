import { useMemo, useState, type PropsWithChildren } from "react";

import { adminNavigation } from "../admin/adminNavigation";
import { adminLogout } from "../lib/http";
import { usePathname } from "../lib/navigation";
import { useAdminPreferences } from "../store/adminPreferences";
import { useAdminAuthStore } from "../store/adminAuth";
import { AppLink } from "./AppLink";

const shellCopy = {
  "zh-CN": {
    search: "搜索菜单和功能…",
    noResult: "没有匹配的功能",
    language: "语言",
    chinese: "中文",
    english: "English",
    logout: "退出登录",
    menu: "菜单",
    collapse: "收起侧边栏"
  },
  "en-US": {
    search: "Search menus and features…",
    noResult: "No matching feature",
    language: "Language",
    chinese: "中文",
    english: "English",
    logout: "Sign out",
    menu: "Menu",
    collapse: "Collapse sidebar"
  }
};

export function AdminShell({ children }: PropsWithChildren) {
  const pathname = usePathname();
  const viewer = useAdminAuthStore((state) => state.viewer);
  const clearSession = useAdminAuthStore((state) => state.clearSession);
  const language = useAdminPreferences((state) => state.language);
  const setLanguage = useAdminPreferences((state) => state.setLanguage);
  const [search, setSearch] = useState("");
  const [mobileOpen, setMobileOpen] = useState(false);
  const copy = shellCopy[language];
  const activeNavHref = useMemo(
    () =>
      adminNavigation
        .flatMap((group) => group.items)
        .filter(
          (item) =>
            pathname === item.href ||
            pathname.startsWith(`${item.href}/`)
        )
        .sort((left, right) => right.href.length - left.href.length)[0]?.href,
    [pathname]
  );

  const searchResults = useMemo(() => {
    const query = search.trim().toLocaleLowerCase(language);
    if (!query) {
      return [];
    }
    return adminNavigation
      .flatMap((group) => group.items)
      .filter((item) =>
        `${item.label[language]} ${item.description[language]}`
          .toLocaleLowerCase(language)
          .includes(query)
      )
      .slice(0, 8);
  }, [language, search]);

  async function handleLogout() {
    try {
      await adminLogout();
    } finally {
      clearSession();
      window.location.replace("/admin/login");
    }
  }

  return (
    <div className="admin-frame">
      <aside className={`admin-sidebar ${mobileOpen ? "is-open" : ""}`}>
        <div className="admin-brand">
          <span className="admin-brand-mark" aria-hidden="true">
            S
          </span>
          <div>
            <strong>SinX Cloud</strong>
            <span>Control Center</span>
          </div>
        </div>

        <nav className="admin-navigation" aria-label={copy.menu}>
          {adminNavigation.map((group) => (
            <section className="admin-nav-group" key={group.id}>
              <p>{group.label[language]}</p>
              {group.items.map((item) => (
                <AppLink
                  className={
                    activeNavHref === item.href
                      ? "admin-nav-link active"
                      : "admin-nav-link"
                  }
                  href={item.href}
                  key={item.id}
                  onClick={() => setMobileOpen(false)}
                >
                  <span className="admin-nav-glyph" aria-hidden="true">
                    {item.glyph}
                  </span>
                  <span>{item.label[language]}</span>
                </AppLink>
              ))}
            </section>
          ))}
        </nav>

        <div className="admin-account">
          <span className="admin-avatar">
            {viewer?.displayName?.slice(0, 1).toUpperCase() ?? "A"}
          </span>
          <div>
            <strong>{viewer?.displayName ?? "Administrator"}</strong>
            <span>{viewer?.email}</span>
          </div>
          <button type="button" onClick={() => void handleLogout()}>
            {copy.logout}
          </button>
        </div>
      </aside>

      {mobileOpen && (
        <button
          aria-label={copy.collapse}
          className="admin-sidebar-scrim"
          onClick={() => setMobileOpen(false)}
          type="button"
        />
      )}

      <section className="admin-workspace">
        <header className="admin-topbar">
          <button
            aria-label={copy.menu}
            className="admin-menu-button"
            onClick={() => setMobileOpen((open) => !open)}
            type="button"
          >
            ☰
          </button>
          <div className="admin-search">
            <span aria-hidden="true">⌕</span>
            <input
              aria-label={copy.search}
              placeholder={copy.search}
              type="search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
            <kbd>⌘K</kbd>
            {search && (
              <div className="admin-search-results">
                {searchResults.length > 0 ? (
                  searchResults.map((item) => (
                    <AppLink
                      href={item.href}
                      key={item.id}
                      onClick={() => setSearch("")}
                    >
                      <span className="admin-nav-glyph">{item.glyph}</span>
                      <span>
                        <strong>{item.label[language]}</strong>
                        <small>{item.description[language]}</small>
                      </span>
                    </AppLink>
                  ))
                ) : (
                  <p>{copy.noResult}</p>
                )}
              </div>
            )}
          </div>

          <label className="admin-language">
            <span className="sr-only">{copy.language}</span>
            <select
              aria-label={copy.language}
              value={language}
              onChange={(event) =>
                setLanguage(event.target.value === "en-US" ? "en-US" : "zh-CN")
              }
            >
              <option value="zh-CN">🇨🇳 {copy.chinese}</option>
              <option value="en-US">🇬🇧 {copy.english}</option>
            </select>
          </label>
        </header>

        <main className="admin-content">{children}</main>
      </section>
    </div>
  );
}

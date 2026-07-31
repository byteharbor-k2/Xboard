import type { PropsWithChildren, ReactNode } from "react";

import { FreedomBrand } from "./FreedomBrand";
import { useUserPreferences } from "../store/userPreferences";

type AuthLayoutProps = PropsWithChildren<{
  title: string;
  description: string;
  eyebrow?: string;
  footer?: ReactNode;
}>;

export function AuthLayout({
  title,
  description,
  eyebrow = "Secure account",
  footer,
  children
}: AuthLayoutProps) {
  const language = useUserPreferences((state) => state.language);
  const setLanguage = useUserPreferences((state) => state.setLanguage);

  return (
    <main className="freedom-auth-page">
      <AppLinkHome />
      <section className="freedom-auth-card">
        <button
          aria-label={
            language === "zh-CN" ? "Switch to English" : "切换到中文"
          }
          className="freedom-language-switch"
          onClick={() =>
            setLanguage(language === "zh-CN" ? "en-US" : "zh-CN")
          }
          type="button"
        >
          {language === "zh-CN" ? "EN" : "中文"}
        </button>
        <FreedomBrand />
        <header>
          <p className="freedom-kicker">{eyebrow}</p>
          <h1>{title}</h1>
          <p>{description}</p>
        </header>
        {children}
        {footer && <footer className="freedom-auth-footer">{footer}</footer>}
      </section>
    </main>
  );
}

function AppLinkHome() {
  return (
    <div className="freedom-auth-backdrop" aria-hidden="true">
      <span />
      <span />
      <span />
    </div>
  );
}

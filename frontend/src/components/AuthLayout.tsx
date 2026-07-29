import type { PropsWithChildren, ReactNode } from "react";

import { FreedomBrand } from "./FreedomBrand";

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
  return (
    <main className="freedom-auth-page">
      <AppLinkHome />
      <section className="freedom-auth-card">
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

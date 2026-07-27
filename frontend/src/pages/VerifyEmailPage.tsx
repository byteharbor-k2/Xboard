import { useEffect, useState } from "react";

import { confirmEmail } from "../lib/http";

export function VerifyEmailPage() {
  const [state, setState] = useState<"loading" | "success" | "error">(
    "loading"
  );

  useEffect(() => {
    const searchParams = new URLSearchParams(window.location.search);
    const token = searchParams.get("token");
    if (!token) {
      setState("error");
      return;
    }
    void confirmEmail(token)
      .then(() => setState("success"))
      .catch(() => setState("error"));
  }, []);

  return (
    <main className="auth-page">
      <section className="auth-card verification-card">
        <p className="eyebrow">Email verification</p>
        {state === "loading" && <h1>正在验证邮箱…</h1>}
        {state === "success" && (
          <>
            <h1>邮箱已验证</h1>
            <p className="muted">你的账户邮箱已完成确认。</p>
            <a className="primary-button link-button" href="/login">
              返回登录
            </a>
          </>
        )}
        {state === "error" && (
          <>
            <h1>验证链接不可用</h1>
            <p className="muted">链接可能已过期、被替换或已经使用。</p>
            <a className="secondary-button link-button" href="/login">
              返回登录
            </a>
          </>
        )}
      </section>
    </main>
  );
}

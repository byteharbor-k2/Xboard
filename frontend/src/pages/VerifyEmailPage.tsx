import { useEffect, useState } from "react";

import { AppLink } from "../components/AppLink";
import { AuthLayout } from "../components/AuthLayout";
import { confirmEmail } from "../lib/http";
import { useAuthStore } from "../store/auth";

export function VerifyEmailPage() {
  const viewer = useAuthStore((state) => state.viewer);
  const setViewer = useAuthStore((state) => state.setViewer);
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
      .then(() => {
        const currentViewer = useAuthStore.getState().viewer;
        if (currentViewer) {
          setViewer({ ...currentViewer, emailVerified: true });
        }
        setState("success");
      })
      .catch(() => setState("error"));
  }, [setViewer]);

  return (
    <AuthLayout
      title={
        state === "loading"
          ? "正在验证邮箱…"
          : state === "success"
            ? "邮箱已验证"
            : "验证链接不可用"
      }
      description={
        state === "loading"
          ? "请稍候，我们正在确认这条验证链接。"
          : state === "success"
            ? "你的账户邮箱已完成确认。"
            : "链接可能已过期、被替换或已经使用。"
      }
      eyebrow="Email verification"
    >
      <div className="verification-card">
        {state === "loading" && <p className="muted">正在读取验证凭据…</p>}
        {state === "success" && (
          <AppLink
            className="freedom-button primary submit"
            href={viewer ? "/account" : "/login"}
          >
            {viewer ? "返回账户" : "返回登录"}
          </AppLink>
        )}
        {state === "error" && (
          <AppLink className="freedom-button ghost submit" href="/login">
            返回登录
          </AppLink>
        )}
      </div>
    </AuthLayout>
  );
}

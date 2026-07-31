import { useEffect, useState } from "react";

import { AppLink } from "../components/AppLink";
import { AuthLayout } from "../components/AuthLayout";
import { confirmEmail } from "../lib/http";
import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";

const copy = {
  "zh-CN": {
    loadingTitle: "正在验证邮箱…",
    successTitle: "邮箱已验证",
    errorTitle: "验证链接不可用",
    loadingDescription: "请稍候，我们正在确认这条验证链接。",
    successDescription: "你的账户邮箱已完成确认。",
    errorDescription: "链接可能已过期、被替换或已经使用。",
    reading: "正在读取验证凭据…",
    account: "返回账户",
    login: "返回登录"
  },
  "en-US": {
    loadingTitle: "Verifying email…",
    successTitle: "Email verified",
    errorTitle: "Verification link unavailable",
    loadingDescription: "Please wait while we validate this link.",
    successDescription: "Your account email has been confirmed.",
    errorDescription: "The link may be expired, replaced, or already used.",
    reading: "Reading verification credential…",
    account: "Back to account",
    login: "Back to sign in"
  }
};

export function VerifyEmailPage() {
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
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
          ? labels.loadingTitle
          : state === "success"
            ? labels.successTitle
            : labels.errorTitle
      }
      description={
        state === "loading"
          ? labels.loadingDescription
          : state === "success"
            ? labels.successDescription
            : labels.errorDescription
      }
      eyebrow="Email verification"
    >
      <div className="verification-card">
        {state === "loading" && <p className="muted">{labels.reading}</p>}
        {state === "success" && (
          <AppLink
            className="freedom-button primary submit"
            href={viewer ? "/account" : "/login"}
          >
            {viewer ? labels.account : labels.login}
          </AppLink>
        )}
        {state === "error" && (
          <AppLink className="freedom-button ghost submit" href="/login">
            {labels.login}
          </AppLink>
        )}
      </div>
    </AuthLayout>
  );
}

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { AppShell } from "../components/AppShell";
import { ConfirmBar } from "../components/ConfirmBar";
import { ApiError, graphQl } from "../lib/http";
import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";
import type { DeviceSession } from "../types";

const sessionsQuery = `
  query DeviceSessions {
    deviceSessions {
      id
      deviceLabel
      createdAt
      lastUsedAt
      expiresAt
      current
    }
  }
`;

const revokeMutation = `
  mutation RevokeDeviceSession($id: ID!) {
    revokeDeviceSession(id: $id)
  }
`;

const rotateMutation = `
  mutation RotateSubscriptionCredential {
    rotateSubscriptionCredential
  }
`;

function formatDate(value: string, locale: "zh-CN" | "en-US") {
  return new Intl.DateTimeFormat(locale, {
    dateStyle: "medium",
    timeStyle: "short"
  }).format(new Date(value));
}

const copy = {
  "zh-CN": {
    title: "登录设备",
    description: "查看当前仍可刷新登录状态的设备，并撤销不再信任的会话。",
    loading: "正在读取设备…",
    failed: "设备会话读取失败。",
    current: "当前设备",
    lastUsed: "最近使用",
    signedIn: "登录时间",
    signOut: "退出此设备",
    revoke: "撤销",
    credentialTitle: "订阅凭据",
    credentialDescription:
      "订阅地址本身就是凭据，任何拿到它的人都能读取你的节点配置。如果链接曾经外泄，在这里重置：旧地址立即失效，已导入的客户端需要重新导入。",
    rotate: "重置订阅链接",
    rotateConfirm:
      "重置后旧地址立即失效，已经导入的客户端需要重新导入。确定继续吗？",
    rotateFailed: "订阅链接重置失败",
    rotated: "订阅链接已重置。请到仪表盘的「快速开始使用」重新导入客户端。"
  },
  "en-US": {
    title: "Signed-in devices",
    description:
      "Review devices that can still refresh their sessions and revoke any you no longer trust.",
    loading: "Loading devices…",
    failed: "Device sessions could not be loaded.",
    current: "Current device",
    lastUsed: "Last used",
    signedIn: "Signed in",
    signOut: "Sign out this device",
    revoke: "Revoke",
    credentialTitle: "Subscription credential",
    credentialDescription:
      "The subscription address is the credential itself — anyone who has it can read your node config. Reset it here if the link ever leaked: the old address stops working at once, and clients that already imported it must import the new one.",
    rotate: "Reset subscription link",
    rotateConfirm:
      "The old link stops working immediately and clients that already imported it must import the new one. Continue?",
    rotateFailed: "The subscription link could not be reset",
    rotated:
      "The subscription link has been reset. Re-import your client from the dashboard's Quick start."
  }
};

export function DeviceSessionsPage() {
  const accessToken = useAuthStore((state) => state.accessToken)!;
  const clearSession = useAuthStore((state) => state.clearSession);
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const queryClient = useQueryClient();
  const [confirmRotate, setConfirmRotate] = useState(false);
  const sessions = useQuery({
    queryKey: ["device-sessions"],
    queryFn: () =>
      graphQl<{ deviceSessions: DeviceSession[] }>(
        accessToken,
        sessionsQuery
      )
  });
  const revoke = useMutation({
    mutationFn: (id: string) =>
      graphQl<{ revokeDeviceSession: boolean }>(
        accessToken,
        revokeMutation,
        { id }
      ),
    onSuccess: async (_, id) => {
      const current = sessions.data?.deviceSessions.find(
        (session) => session.id === id
      );
      if (current?.current) {
        clearSession();
        window.location.assign("/login");
        return;
      }
      await queryClient.invalidateQueries({
        queryKey: ["device-sessions"]
      });
    }
  });

  /**
   * Retires the subscription link and hands back its replacement.
   *
   * The replacement is deliberately dropped rather than stored: this page has
   * nowhere to show it, and the dashboard re-reads the current address on every
   * mount, so keeping a copy here would only risk displaying a stale one.
   */
  const rotate = useMutation({
    mutationFn: () =>
      graphQl<{ rotateSubscriptionCredential: string | null }>(
        accessToken,
        rotateMutation
      )
  });

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">Security</p>
        <h1>{labels.title}</h1>
        <p className="muted">{labels.description}</p>
      </header>
      <section className="panel">
        {sessions.isPending && <p className="muted">{labels.loading}</p>}
        {sessions.isError && (
          <p className="error-message">{labels.failed}</p>
        )}
        <div className="session-list">
          {sessions.data?.deviceSessions.map((session) => (
            <article className="session-row" key={session.id}>
              <div className="device-icon" aria-hidden="true">
                ◇
              </div>
              <div className="session-copy">
                <div className="session-title">
                  <strong>{session.deviceLabel}</strong>
                  {session.current && (
                    <span className="status-pill">{labels.current}</span>
                  )}
                </div>
                <span>
                  {labels.lastUsed}: {formatDate(session.lastUsedAt, language)}
                </span>
                <span>
                  {labels.signedIn}: {formatDate(session.createdAt, language)}
                </span>
              </div>
              <button
                className="danger-button"
                disabled={revoke.isPending}
                onClick={() => revoke.mutate(session.id)}
              >
                {session.current ? labels.signOut : labels.revoke}
              </button>
            </article>
          ))}
        </div>
      </section>
      <section className="panel subscription-credential-panel">
        <div className="panel-heading">
          <div>
            <h2>{labels.credentialTitle}</h2>
            <p className="muted">{labels.credentialDescription}</p>
          </div>
          <button
            className="danger-button"
            disabled={rotate.isPending}
            onClick={() => {
              rotate.reset();
              setConfirmRotate(true);
            }}
            type="button"
          >
            {labels.rotate}
          </button>
        </div>
        {rotate.isError && (
          <p className="error-message">
            {rotate.error instanceof ApiError
              ? rotate.error.message
              : labels.rotateFailed}
          </p>
        )}
        {rotate.isSuccess && (
          <p className="account-inline-message success">{labels.rotated}</p>
        )}
        {confirmRotate && (
          <ConfirmBar
            busy={rotate.isPending}
            language={language}
            onCancel={() => setConfirmRotate(false)}
            onConfirm={() => {
              setConfirmRotate(false);
              rotate.mutate();
            }}
            request={{
              message: labels.rotateConfirm,
              confirmLabel: labels.rotate,
              danger: true,
              run: () => rotate.mutateAsync()
            }}
          />
        )}
      </section>
    </AppShell>
  );
}

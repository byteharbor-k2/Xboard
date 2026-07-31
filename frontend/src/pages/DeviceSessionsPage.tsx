import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { AppShell } from "../components/AppShell";
import { graphQl } from "../lib/http";
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
    revoke: "撤销"
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
    revoke: "Revoke"
  }
};

export function DeviceSessionsPage() {
  const accessToken = useAuthStore((state) => state.accessToken)!;
  const clearSession = useAuthStore((state) => state.clearSession);
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const queryClient = useQueryClient();
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
    </AppShell>
  );
}

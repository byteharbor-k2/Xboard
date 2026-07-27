import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { AppShell } from "../components/AppShell";
import { graphQl } from "../lib/http";
import { useAuthStore } from "../store/auth";
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

function formatDate(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "medium",
    timeStyle: "short"
  }).format(new Date(value));
}

export function DeviceSessionsPage() {
  const accessToken = useAuthStore((state) => state.accessToken)!;
  const clearSession = useAuthStore((state) => state.clearSession);
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
        <h1>登录设备</h1>
        <p className="muted">
          查看当前仍可刷新登录状态的设备，并撤销不再信任的会话。
        </p>
      </header>
      <section className="panel">
        {sessions.isPending && <p className="muted">正在读取设备…</p>}
        {sessions.isError && (
          <p className="error-message">设备会话读取失败。</p>
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
                    <span className="status-pill">当前设备</span>
                  )}
                </div>
                <span>最近使用：{formatDate(session.lastUsedAt)}</span>
                <span>登录时间：{formatDate(session.createdAt)}</span>
              </div>
              <button
                className="danger-button"
                disabled={revoke.isPending}
                onClick={() => revoke.mutate(session.id)}
              >
                {session.current ? "退出此设备" : "撤销"}
              </button>
            </article>
          ))}
        </div>
      </section>
    </AppShell>
  );
}

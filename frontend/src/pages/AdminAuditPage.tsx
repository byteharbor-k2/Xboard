import { useQuery } from "@tanstack/react-query";

import { AppShell } from "../components/AppShell";
import { graphQl } from "../lib/http";
import { useAuthStore } from "../store/auth";
import type { AdminAuditLog } from "../types";

const auditQuery = `
  query AdminAuditLogs {
    adminAuditLogs(limit: 100) {
      id
      actorEmail
      action
      responseStatus
      outcome
      durationMs
      requestId
      ipAddress
      userAgent
      occurredAt
    }
  }
`;

function formatDate(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "medium",
    timeStyle: "medium"
  }).format(new Date(value));
}

export function AdminAuditPage() {
  const accessToken = useAuthStore((state) => state.accessToken)!;
  const auditLogs = useQuery({
    queryKey: ["admin-audit-logs"],
    queryFn: () =>
      graphQl<{ adminAuditLogs: AdminAuditLog[] }>(
        accessToken,
        auditQuery
      ),
    refetchInterval: 30_000
  });

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">Administration</p>
        <h1>管理员审计</h1>
        <p className="muted">
          追踪管理请求的执行人、来源和结果。敏感请求变量不会写入日志。
        </p>
      </header>
      <section className="panel audit-panel">
        {auditLogs.isPending && <p className="muted">正在读取审计记录…</p>}
        {auditLogs.isError && (
          <p className="error-message">无权访问或审计记录读取失败。</p>
        )}
        <div className="audit-list">
          {auditLogs.data?.adminAuditLogs.map((entry) => (
            <article className="audit-row" key={entry.id}>
              <div>
                <div className="session-title">
                  <strong>{entry.action}</strong>
                  <span
                    className={
                      entry.outcome === "SUCCESS"
                        ? "status-pill"
                        : "status-pill status-failure"
                    }
                  >
                    {entry.responseStatus}
                  </span>
                </div>
                <p className="audit-actor">{entry.actorEmail}</p>
              </div>
              <div className="audit-meta">
                <span>{entry.ipAddress ?? "未知来源"}</span>
                <span>{entry.durationMs} ms</span>
                <span>{formatDate(entry.occurredAt)}</span>
              </div>
              <code>{entry.requestId ?? "no-request-id"}</code>
            </article>
          ))}
        </div>
      </section>
    </AppShell>
  );
}

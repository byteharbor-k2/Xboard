import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import {
  listTrafficResets,
  type AdminTrafficReset
} from "../admin/adminTrafficResetsApi";
import { AdminShell } from "../components/AdminShell";
import { ApiError } from "../lib/http";
import { formatBytes, formatDateTime } from "../lib/subscription";
import { useAdminAuthStore } from "../store/adminAuth";
import { useAdminPreferences } from "../store/adminPreferences";

const copy = {
  "zh-CN": {
    eyebrow: "订阅权益",
    title: "流量重置记录",
    description: "按月周期或人工触发的流量清零台账，最新在前。",
    userFilter: "用户 ID 精确筛选",
    filterHint: "留空查看全部账户的记录",
    apply: "查询",
    account: "用户",
    nodeUserId: "节点用户 ID",
    resetAt: "重置时间",
    uploadedBefore: "重置前上行",
    downloadedBefore: "重置前下行",
    loading: "正在加载记录…",
    empty: "没有符合条件的记录。",
    loadFailed: "加载失败"
  },
  "en-US": {
    eyebrow: "Entitlements",
    title: "Traffic resets",
    description:
      "The ledger of zeroed traffic counters - monthly cycles and manual resets, newest first.",
    userFilter: "Exact user ID",
    filterHint: "Leave empty to see every account",
    apply: "Apply",
    account: "Account",
    nodeUserId: "Node user ID",
    resetAt: "Reset at",
    uploadedBefore: "Uploaded before",
    downloadedBefore: "Downloaded before",
    loading: "Loading records…",
    empty: "No resets match this filter.",
    loadFailed: "Load failed"
  }
} as const;

function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback;
}

/** Epoch seconds as the ledger reports them, one row per reset. */
function formatResetAt(value: number, language: "zh-CN" | "en-US") {
  return new Intl.DateTimeFormat(language, {
    dateStyle: "medium",
    timeStyle: "short"
  }).format(new Date(value * 1000));
}

function shortUserId(userId: string) {
  return `${userId.slice(0, 8)}…`;
}

export function AdminTrafficResetsPage() {
  const language = useAdminPreferences((state) => state.language);
  const token = useAdminAuthStore((state) => state.accessToken)!;
  const text = copy[language];

  const [userIdInput, setUserIdInput] = useState("");
  const [userIdFilter, setUserIdFilter] = useState<string | null>(null);

  const recordsQuery = useQuery({
    queryKey: ["admin", "traffic-resets", userIdFilter],
    queryFn: () => listTrafficResets(token, userIdFilter)
  });
  const loadError = recordsQuery.error
    ? errorMessage(recordsQuery.error, text.loadFailed)
    : "";
  const records: AdminTrafficReset[] = recordsQuery.data ?? [];

  return (
    <AdminShell>
      <header className="admin-page-heading">
        <div>
          <p>{text.eyebrow}</p>
          <h1>{text.title}</h1>
          <span>{text.description}</span>
        </div>
      </header>
      {loadError ? <p className="admin-operation-error">{loadError}</p> : null}
      <section className="admin-card" style={{ paddingBottom: 4 }}>
        <form
          style={{ display: "flex", gap: 12, padding: "18px 22px 0" }}
          onSubmit={(event) => {
            event.preventDefault();
            setUserIdFilter(userIdInput.trim() || null);
          }}
        >
          <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <span style={{ color: "#707c93" }}>{text.userFilter}</span>
            <input
              onChange={(event) => setUserIdInput(event.target.value)}
              placeholder={text.filterHint}
              style={{
                padding: "10px 13px",
                border: "1px solid #dfe5ee",
                borderRadius: 9,
                font: "inherit",
                width: 320
              }}
              value={userIdInput}
            />
          </label>
          <button className="primary" type="submit">
            {text.apply}
          </button>
        </form>
        <div className="admin-table-wrap">
          {recordsQuery.isPending ? (
            <p className="admin-table-empty">{text.loading}</p>
          ) : records.length === 0 ? (
            <p className="admin-table-empty">{text.empty}</p>
          ) : (
            <table className="admin-table">
              <thead>
                <tr>
                  <th>{text.account}</th>
                  <th>{text.nodeUserId}</th>
                  <th>{text.resetAt}</th>
                  <th>{text.uploadedBefore}</th>
                  <th>{text.downloadedBefore}</th>
                </tr>
              </thead>
              <tbody>
                {records.map((record, index) => (
                  <tr key={`${record.user_id}-${record.reset_at}-${index}`}>
                    <td>
                      <strong>{record.email ?? "—"}</strong>
                      <span
                        className="order-number"
                        title={record.user_id}
                        style={{ display: "block", color: "#8b97a8" }}
                      >
                        {shortUserId(record.user_id)}
                      </span>
                    </td>
                    <td>{record.node_user_id ?? "—"}</td>
                    <td>{formatResetAt(record.reset_at, language)}</td>
                    <td>{formatBytes(String(record.uploaded_bytes_before))}</td>
                    <td>
                      {formatBytes(String(record.downloaded_bytes_before))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </section>
    </AdminShell>
  );
}

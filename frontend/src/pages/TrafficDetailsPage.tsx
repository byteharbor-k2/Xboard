import { useQuery } from "@tanstack/react-query";

import { fetchViewerTrafficDaily } from "../lib/traffic";
import { AppShell } from "../components/AppShell";
import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";

const copy = {
  "zh-CN": {
    eyebrow: "USAGE",
    title: "流量明细",
    description: "查看近期实际上下行走量与实际扣除流量。",
    retention: "流量明细仅保留近一个月数据以供查询。",
    recordedAt: "记录时间",
    node: "节点",
    uploaded: "实际上行",
    downloaded: "实际下行",
    billed: "扣除流量",
    loading: "加载中…",
    empty: "暂无流量记录",
    emptyDescription: "节点上报产生扣费流量后，近期使用记录会显示在这里。"
  },
  "en-US": {
    eyebrow: "USAGE",
    title: "Traffic details",
    description: "Review recent upload, download, and billed traffic.",
    retention: "Traffic details are retained for the most recent month.",
    recordedAt: "Recorded",
    node: "Node",
    uploaded: "Upload",
    downloaded: "Download",
    billed: "Billed traffic",
    loading: "Loading…",
    empty: "No traffic records",
    emptyDescription:
      "Recent usage will appear here once node reports charge the account."
  }
};

function formatBytes(value: string | number) {
  const normalized = typeof value === "string" ? Number(value) : value;
  if (!Number.isFinite(normalized)) return "—";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let amount = normalized;
  let unit = 0;
  while (amount >= 1024 && unit < units.length - 1) {
    amount /= 1024;
    unit += 1;
  }
  return `${amount.toFixed(unit > 2 ? 2 : 1)} ${units[unit]}`;
}

export function TrafficDetailsPage() {
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const accessToken = useAuthStore((state) => state.accessToken) ?? "";
  const rows = useQuery({
    queryKey: ["viewer-traffic-daily"],
    queryFn: () => fetchViewerTrafficDaily(accessToken)
  });

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">{labels.eyebrow}</p>
        <h1>{labels.title}</h1>
        <p className="muted">{labels.description}</p>
      </header>
      <section className="panel user-record-panel">
        <div className="traffic-retention-notice">
          <span aria-hidden="true">i</span>
          {labels.retention}
        </div>
        <div className="user-table-wrap">
          <table className="user-data-table">
            <thead>
              <tr>
                <th>{labels.recordedAt}</th>
                <th>{labels.node}</th>
                <th>{labels.uploaded}</th>
                <th>{labels.downloaded}</th>
                <th>{labels.billed}</th>
              </tr>
            </thead>
            <tbody>
              {rows.isPending && (
                <tr>
                  <td colSpan={5}>
                    <div className="user-empty-state compact">
                      <span aria-hidden="true">▥</span>
                      <strong>{labels.loading}</strong>
                    </div>
                  </td>
                </tr>
              )}
              {!rows.isPending &&
                (rows.data?.length ?? 0) > 0 &&
                rows.data!.map((row, index) => (
                  <tr
                    key={`${row.day}-${row.nodeName ?? "node"}-${index}`}
                  >
                    <td>{row.day}</td>
                    <td>{row.nodeName ?? "—"}</td>
                    <td>{formatBytes(row.uploadBytes)}</td>
                    <td>{formatBytes(row.downloadBytes)}</td>
                    <td>{formatBytes(row.billedBytes)}</td>
                  </tr>
                ))}
              {!rows.isPending && (rows.data?.length ?? 0) === 0 && (
                <tr>
                  <td colSpan={5}>
                    <div className="user-empty-state compact">
                      <span aria-hidden="true">▥</span>
                      <strong>{labels.empty}</strong>
                      <p>{labels.emptyDescription}</p>
                    </div>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
    </AppShell>
  );
}

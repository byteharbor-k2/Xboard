import { AppShell } from "../components/AppShell";
import { useUserPreferences } from "../store/userPreferences";

const copy = {
  "zh-CN": {
    eyebrow: "USAGE",
    title: "流量明细",
    description: "查看近期上传、下载、倍率与实际扣除流量。",
    retention: "流量明细仅保留近一个月数据以供查询。",
    recordedAt: "记录时间",
    uploaded: "实际上行",
    downloaded: "实际下行",
    rate: "扣费倍率",
    total: "总计",
    empty: "暂无流量记录",
    emptyDescription: "节点流量数据接入后，近期使用记录会显示在这里。"
  },
  "en-US": {
    eyebrow: "USAGE",
    title: "Traffic details",
    description:
      "Review recent upload, download, rate multiplier, and billed traffic.",
    retention: "Traffic details are retained for the most recent month.",
    recordedAt: "Recorded",
    uploaded: "Upload",
    downloaded: "Download",
    rate: "Rate",
    total: "Total",
    empty: "No traffic records",
    emptyDescription:
      "Recent usage will appear here after node traffic data is connected."
  }
};

export function TrafficDetailsPage() {
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];

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
                <th>{labels.uploaded}</th>
                <th>{labels.downloaded}</th>
                <th>{labels.rate}</th>
                <th>{labels.total}</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td colSpan={5}>
                  <div className="user-empty-state compact">
                    <span aria-hidden="true">▥</span>
                    <strong>{labels.empty}</strong>
                    <p>{labels.emptyDescription}</p>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </AppShell>
  );
}

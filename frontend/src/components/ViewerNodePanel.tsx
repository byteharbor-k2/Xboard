import { useEffect, useState } from "react";

import { fetchViewerNodes, type ViewerNode } from "../lib/nodes";
import { formatDateTime } from "../lib/subscription";
import type { UserLanguage } from "../store/userPreferences";

type ViewerNodePanelProps = {
  accessToken: string;
  language: UserLanguage;
};

const copy = {
  "zh-CN": {
    title: "可用节点",
    description: "仅显示当前订阅可用的节点；状态来自节点最近上报。",
    total: "节点总数",
    online: "在线",
    unknown: "未知",
    offline: "离线",
    loading: "正在读取节点…",
    empty: "当前订阅没有可用节点。订阅生效且配置了可用节点后会显示在这里。",
    failed: "节点信息暂时无法加载。",
    lastReport: "最近上报",
    noReport: "尚无上报",
    rate: "流量倍率"
  },
  "en-US": {
    title: "Available nodes",
    description: "Only nodes available to your current subscription are shown. Status comes from node reports.",
    total: "Total nodes",
    online: "Online",
    unknown: "Unknown",
    offline: "Offline",
    loading: "Loading nodes…",
    empty: "No nodes are available to this subscription right now. Nodes will appear here when service is active and configured.",
    failed: "Node information is temporarily unavailable.",
    lastReport: "Last report",
    noReport: "No report yet",
    rate: "Traffic rate"
  }
};

const protocolNames: Record<string, string> = {
  shadowsocks: "Shadowsocks",
  vmess: "VMess",
  vless: "VLESS",
  trojan: "Trojan",
  hysteria: "Hysteria",
  tuic: "TUIC",
  anytls: "AnyTLS",
  socks: "SOCKS",
  naive: "Naive",
  http: "HTTP",
  mieru: "Mieru"
};

function statusLabel(node: ViewerNode, locale: UserLanguage) {
  const labels = copy[locale];
  if (node.onlineStatus === "ONLINE") return labels.online;
  if (node.onlineStatus === "OFFLINE") return labels.offline;
  return labels.unknown;
}

function rateLabel(rate: string) {
  const numericRate = Number(rate);
  return Number.isFinite(numericRate) ? `${numericRate.toLocaleString()}×` : `${rate}×`;
}

export function ViewerNodePanel({ accessToken, language }: ViewerNodePanelProps) {
  const labels = copy[language];
  const [nodes, setNodes] = useState<ViewerNode[]>([]);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    const refresh = () => {
      fetchViewerNodes(accessToken)
        .then((result) => {
          if (!active) return;
          setNodes(result);
          setFailed(false);
        })
        .catch(() => {
          if (active) setFailed(true);
        })
        .finally(() => {
          if (active) setLoading(false);
        });
    };

    refresh();
    const interval = window.setInterval(refresh, 30_000);
    return () => {
      active = false;
      window.clearInterval(interval);
    };
  }, [accessToken]);

  const onlineCount = nodes.filter((node) => node.onlineStatus === "ONLINE").length;
  const unknownCount = nodes.filter((node) => node.onlineStatus === "UNKNOWN").length;
  const offlineCount = nodes.filter((node) => node.onlineStatus === "OFFLINE").length;

  return (
    <section className="viewer-node-panel" aria-labelledby="viewer-nodes-title" aria-live="polite">
      <header className="viewer-node-panel-heading">
        <div>
          <span className="viewer-node-kicker">{labels.total}</span>
          <h2 id="viewer-nodes-title">{labels.title}</h2>
          <p>{labels.description}</p>
        </div>
        <strong className="viewer-node-total">{nodes.length}</strong>
      </header>
      <div className="viewer-node-counts" aria-label={`${labels.online}: ${onlineCount}, ${labels.unknown}: ${unknownCount}, ${labels.offline}: ${offlineCount}`}>
        <div><span>{labels.online}</span><strong>{onlineCount}</strong></div>
        <div><span>{labels.unknown}</span><strong>{unknownCount}</strong></div>
        <div><span>{labels.offline}</span><strong>{offlineCount}</strong></div>
      </div>
      {failed && <p className="viewer-node-error" role="status">{labels.failed}</p>}
      {loading && nodes.length === 0 ? (
        <p className="viewer-node-empty">{labels.loading}</p>
      ) : nodes.length === 0 ? (
        <p className="viewer-node-empty">{labels.empty}</p>
      ) : (
        <ul className="viewer-node-list">
          {nodes.map((node) => (
            <li className="viewer-node-row" key={node.id}>
              <div className="viewer-node-main">
                <div className="viewer-node-name-line">
                  <strong>{node.name}</strong>
                  <span className={`viewer-node-status ${node.onlineStatus.toLowerCase()}`}>
                    {statusLabel(node, language)}
                  </span>
                </div>
                <span className="viewer-node-protocol">
                  {protocolNames[node.protocol.toLowerCase()] ?? node.protocol}
                </span>
                {node.tags.length > 0 && (
                  <div className="viewer-node-tags" aria-label={language === "zh-CN" ? "节点标签" : "Node tags"}>
                    {node.tags.map((tag, index) => <span key={`${tag}-${index}`}>{tag}</span>)}
                  </div>
                )}
              </div>
              <dl className="viewer-node-facts">
                <div>
                  <dt>{labels.rate}</dt>
                  <dd>{rateLabel(node.trafficRate)}</dd>
                </div>
                <div>
                  <dt>{labels.lastReport}</dt>
                  <dd>{node.lastSeenAt ? formatDateTime(node.lastSeenAt, language) : labels.noReport}</dd>
                </div>
              </dl>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

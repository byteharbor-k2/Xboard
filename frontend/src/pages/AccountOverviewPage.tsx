import { useEffect, useState } from "react";

import { AppLink } from "../components/AppLink";
import { AppShell } from "../components/AppShell";
import {
  NetworkGlobe,
  type NetworkMapNode
} from "../components/NetworkGlobe";
import {
  ApiError,
  graphQl
} from "../lib/http";
import {
  entitlementStateLabel,
  formatBytes,
  formatDateTime,
  trafficResetLabel
} from "../lib/subscription";
import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";
import type { SubscriptionEntitlement } from "../types";

const networkPreviewNodes: NetworkMapNode[] = [
  { id: "hkg-01", label: "Hong Kong 01", latitude: 22.3, longitude: 114.2, state: "AVAILABLE" },
  { id: "hkg-02", label: "Hong Kong 02", latitude: 24.2, longitude: 112.2, state: "AVAILABLE" },
  { id: "hkg-03", label: "Hong Kong 03", latitude: 20.7, longitude: 116.1, state: "AVAILABLE" },
  { id: "tyo-01", label: "Tokyo 01", latitude: 35.7, longitude: 139.7, state: "AVAILABLE" },
  { id: "tyo-02", label: "Tokyo 02", latitude: 37.4, longitude: 137.3, state: "AVAILABLE" },
  { id: "osa-01", label: "Osaka 01", latitude: 34.7, longitude: 135.5, state: "AVAILABLE" },
  { id: "kul-01", label: "Kuala Lumpur 01", latitude: 3.1, longitude: 101.7, state: "AVAILABLE" },
  { id: "sin-01", label: "Singapore 01", latitude: 1.4, longitude: 103.8, state: "AVAILABLE" },
  { id: "fra-01", label: "Frankfurt 01", latitude: 50.1, longitude: 8.7, state: "AVAILABLE" },
  { id: "fra-02", label: "Frankfurt 02", latitude: 48.6, longitude: 11.2, state: "AVAILABLE" },
  { id: "lax-01", label: "Los Angeles 01", latitude: 34.1, longitude: -118.2, state: "AVAILABLE" },
  { id: "lax-02", label: "Los Angeles 02", latitude: 36.2, longitude: -116.5, state: "AVAILABLE" },
  { id: "sea-01", label: "Seattle 01", latitude: 47.6, longitude: -122.3, state: "AVAILABLE" },
  { id: "syd-01", label: "Sydney 01", latitude: -33.9, longitude: 151.2, state: "AVAILABLE" }
];

const copy = {
  "zh-CN": {
    kicker: "PRIVATE NETWORK",
    greeting: "欢迎回来",
    description: "订阅状态、用量和全球网络，一眼掌握。",
    entitlementFailed: "订阅权益加载失败",
    subscription: "当前订阅",
    loading: "正在读取权益…",
    noPlan: "暂无套餐",
    viewPlans: "查看套餐",
    used: "已使用",
    remaining: "剩余流量",
    uploadDownload: "上传 / 下载",
    expires: "有效期至",
    nextReset: "下次重置",
    speed: "峰值速率",
    unlimitedSpeed: "不限速",
    devices: "设备数量",
    unlimitedDevices: "不限制",
    deviceUnit: "台",
    empty:
      "当前账户还没有订阅权益，开通套餐后这里会显示流量和有效期。",
    networkTitle: "全球节点网络",
    networkDescription: "从地图查看服务覆盖与节点状态。",
    availableNodes: "可用节点",
    locations: "覆盖区域",
    live: "运行正常",
    preview: "界面预览",
    previewDescription: "实时节点接口接入后，将自动替换当前预览数据。",
    selectedNode: "当前节点"
  },
  "en-US": {
    kicker: "PRIVATE NETWORK",
    greeting: "Welcome back",
    description: "Your subscription, usage, and global network at a glance.",
    entitlementFailed: "Subscription benefits could not be loaded",
    subscription: "Current subscription",
    loading: "Loading benefits…",
    noPlan: "No plan",
    viewPlans: "View plans",
    used: "used",
    remaining: "Remaining data",
    uploadDownload: "Upload / download",
    expires: "Expires",
    nextReset: "Next reset",
    speed: "Peak speed",
    unlimitedSpeed: "Unlimited",
    devices: "Devices",
    unlimitedDevices: "Unlimited",
    deviceUnit: "devices",
    empty:
      "This account has no subscription benefits yet. Data and validity will appear after you activate a plan.",
    networkTitle: "Global node network",
    networkDescription: "Explore service coverage and node availability.",
    availableNodes: "Available nodes",
    locations: "Regions",
    live: "Operational",
    preview: "UI preview",
    previewDescription: "Live node data will replace this preview after the new endpoint is connected.",
    selectedNode: "Selected node"
  }
};

export function AccountOverviewPage() {
  const accessToken = useAuthStore((state) => state.accessToken)!;
  const viewer = useAuthStore((state) => state.viewer)!;
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const [error, setError] = useState("");
  const [entitlement, setEntitlement] =
    useState<SubscriptionEntitlement | null>(null);
  const [entitlementLoading, setEntitlementLoading] = useState(true);
  const [selectedNode, setSelectedNode] = useState<NetworkMapNode>(
    networkPreviewNodes[0]
  );

  useEffect(() => {
    let active = true;
    graphQl<{ viewerEntitlement: SubscriptionEntitlement | null }>(
      accessToken,
      `query AccountSnapshot {
        viewerEntitlement {
          id
          planId
          planName
          state
          transferLimitBytes
          uploadedBytes
          downloadedBytes
          usedBytes
          remainingBytes
          usagePercent
          speedLimitMbps
          deviceLimit
          resetPolicy
          startsAt
          expiresAt
          nextResetAt
        }
      }`
    )
      .then((result) => {
        if (active) {
          setEntitlement(result.viewerEntitlement);
        }
      })
      .catch((caught) => {
        if (active) {
          setError(
            caught instanceof ApiError
              ? caught.message
              : labels.entitlementFailed
          );
        }
      })
      .finally(() => {
        if (active) {
          setEntitlementLoading(false);
        }
      });
    return () => {
      active = false;
    };
  }, [accessToken]);

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">{labels.kicker}</p>
        <h1>{labels.greeting}, {viewer.displayName}</h1>
        <p className="muted">{labels.description}</p>
      </header>
      {error && <p className="error-message">{error}</p>}
      <section className="user-dashboard-grid">
        <section className="subscription-overview dashboard-subscription-card">
          <div className="subscription-heading">
            <div>
              <span>{labels.subscription}</span>
              <h2>
                {entitlementLoading
                  ? labels.loading
                  : entitlement?.planName ?? labels.noPlan}
              </h2>
            </div>
            {entitlement ? (
              <span
                className={`entitlement-state ${entitlement.state.toLowerCase()}`}
              >
                {entitlementStateLabel(entitlement.state, language)}
              </span>
            ) : (
              <AppLink className="secondary-button compact-link" href="/plans">
                {labels.viewPlans}
              </AppLink>
            )}
          </div>
          {entitlement && (
            <>
              <div className="traffic-usage">
                <div>
                  <strong>{formatBytes(entitlement.usedBytes)}</strong>
                  <span>
                    {labels.used} /{" "}
                    {formatBytes(entitlement.transferLimitBytes)}
                  </span>
                </div>
                <strong>{entitlement.usagePercent.toFixed(1)}%</strong>
              </div>
              <div className="traffic-progress" aria-hidden="true">
                <span
                  style={{
                    width: `${Math.min(100, entitlement.usagePercent)}%`
                  }}
                />
              </div>
              <dl className="subscription-facts">
                <div>
                  <dt>{labels.remaining}</dt>
                  <dd>{formatBytes(entitlement.remainingBytes)}</dd>
                </div>
                <div>
                  <dt>{labels.uploadDownload}</dt>
                  <dd>
                    {formatBytes(entitlement.uploadedBytes)} /{" "}
                    {formatBytes(entitlement.downloadedBytes)}
                  </dd>
                </div>
                <div>
                  <dt>{labels.expires}</dt>
                  <dd>{formatDateTime(entitlement.expiresAt, language)}</dd>
                </div>
                <div>
                  <dt>{labels.nextReset}</dt>
                  <dd>
                    {entitlement.nextResetAt
                      ? formatDateTime(entitlement.nextResetAt, language)
                      : trafficResetLabel(
                          entitlement.resetPolicy,
                          language
                        )}
                  </dd>
                </div>
                <div>
                  <dt>{labels.speed}</dt>
                  <dd>
                    {entitlement.speedLimitMbps
                      ? `${entitlement.speedLimitMbps} Mbps`
                      : labels.unlimitedSpeed}
                  </dd>
                </div>
                <div>
                  <dt>{labels.devices}</dt>
                  <dd>
                    {entitlement.deviceLimit
                      ? `${entitlement.deviceLimit} ${labels.deviceUnit}`
                      : labels.unlimitedDevices}
                  </dd>
                </div>
              </dl>
            </>
          )}
          {!entitlementLoading && !entitlement && (
            <p className="subscription-empty-copy">{labels.empty}</p>
          )}
        </section>
        <section className="network-atlas-card">
          <header>
            <div>
              <span>{labels.live}</span>
              <h2>{labels.networkTitle}</h2>
              <p>{labels.networkDescription}</p>
            </div>
            <strong>{networkPreviewNodes.length}</strong>
          </header>
          <NetworkGlobe
            nodes={networkPreviewNodes}
            onSelect={setSelectedNode}
            selectedNodeId={selectedNode.id}
          />
          <div className="network-atlas-stats">
            <div>
              <span>{labels.availableNodes}</span>
              <strong>{networkPreviewNodes.length}</strong>
            </div>
            <div>
              <span>{labels.locations}</span>
              <strong>8</strong>
            </div>
            <div>
              <span>{labels.selectedNode}</span>
              <strong>{selectedNode.label}</strong>
            </div>
          </div>
          <p className="network-preview-note">
            <span>{labels.preview}</span>
            {labels.previewDescription}
          </p>
        </section>
      </section>
    </AppShell>
  );
}

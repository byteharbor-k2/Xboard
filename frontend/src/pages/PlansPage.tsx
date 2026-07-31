import { useEffect, useState } from "react";

import { AppShell } from "../components/AppShell";
import { ApiError, publicGraphQl } from "../lib/http";
import {
  billingPeriodLabel,
  formatBytes,
  formatMoney,
  trafficResetLabel
} from "../lib/subscription";
import { useUserPreferences } from "../store/userPreferences";
import type { PlanOffer } from "../types";

const offerQuery = `
  query OfferCatalog {
    offerCatalog {
      id
      name
      description
      tags
      transferLimitBytes
      speedLimitMbps
      deviceLimit
      resetPolicy
      renewable
      capacityRemaining
      prices {
        period
        amountMinor
        currency
        durationDays
        monthCount
      }
    }
  }
`;

const copy = {
  "zh-CN": {
    title: "选择适合你的套餐",
    description: "套餐价格、流量、限速和设备权益均来自实时商品目录。",
    loading: "正在加载套餐…",
    failed: "套餐加载失败",
    empty: "暂时没有可购买的套餐",
    emptyDescription: "套餐发布后会自动显示在这里。",
    data: "套餐流量",
    speed: "峰值速率",
    unlimitedSpeed: "不限速",
    devices: "设备数量",
    unlimitedDevices: "不限制",
    deviceUnit: "台",
    reset: "流量重置",
    remainingPrefix: "当前剩余",
    remainingSuffix: "个名额"
  },
  "en-US": {
    title: "Choose your plan",
    description:
      "Pricing, data, speed, and device benefits come from the live catalog.",
    loading: "Loading plans…",
    failed: "Plans could not be loaded",
    empty: "No plans are available",
    emptyDescription: "Published plans will appear here automatically.",
    data: "Data allowance",
    speed: "Peak speed",
    unlimitedSpeed: "Unlimited",
    devices: "Devices",
    unlimitedDevices: "Unlimited",
    deviceUnit: "devices",
    reset: "Data reset",
    remainingPrefix: "",
    remainingSuffix: "spots remaining"
  }
};

export function PlansPage() {
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const [offers, setOffers] = useState<PlanOffer[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    publicGraphQl<{ offerCatalog: PlanOffer[] }>(offerQuery)
      .then((result) => {
        if (active) {
          setOffers(result.offerCatalog);
        }
      })
      .catch((caught) => {
        if (active) {
          setError(
            caught instanceof ApiError
              ? caught.message
              : labels.failed
          );
        }
      })
      .finally(() => {
        if (active) {
          setLoading(false);
        }
      });
    return () => {
      active = false;
    };
  }, []);

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">Plans</p>
        <h1>{labels.title}</h1>
        <p className="muted">{labels.description}</p>
      </header>
      {loading && <div className="panel empty-state">{labels.loading}</div>}
      {error && <p className="error-message">{error}</p>}
      {!loading && !error && offers.length === 0 && (
        <div className="panel empty-state">
          <strong>{labels.empty}</strong>
          <span>{labels.emptyDescription}</span>
        </div>
      )}
      <section className="plan-grid">
        {offers.map((offer) => (
          <article className="plan-card" key={offer.id}>
            <header>
              <div className="plan-tags">
                {offer.tags.map((tag) => (
                  <span key={tag}>{tag}</span>
                ))}
              </div>
              <h2>{offer.name}</h2>
              <p>{offer.description}</p>
            </header>
            <dl className="plan-entitlements">
              <div>
                <dt>{labels.data}</dt>
                <dd>{formatBytes(offer.transferLimitBytes)}</dd>
              </div>
              <div>
                <dt>{labels.speed}</dt>
                <dd>
                  {offer.speedLimitMbps
                    ? `${offer.speedLimitMbps} Mbps`
                    : labels.unlimitedSpeed}
                </dd>
              </div>
              <div>
                <dt>{labels.devices}</dt>
                <dd>
                  {offer.deviceLimit
                    ? `${offer.deviceLimit} ${labels.deviceUnit}`
                    : labels.unlimitedDevices}
                </dd>
              </div>
              <div>
                <dt>{labels.reset}</dt>
                <dd>{trafficResetLabel(offer.resetPolicy, language)}</dd>
              </div>
            </dl>
            <div className="plan-prices">
              {offer.prices.map((price) => (
                <div key={price.period}>
                  <span>{billingPeriodLabel(price.period, language)}</span>
                  <strong>
                    {formatMoney(price.amountMinor, price.currency, language)}
                  </strong>
                </div>
              ))}
            </div>
            {offer.capacityRemaining !== null && (
              <small className="plan-capacity">
                {labels.remainingPrefix} {offer.capacityRemaining}{" "}
                {labels.remainingSuffix}
              </small>
            )}
          </article>
        ))}
      </section>
    </AppShell>
  );
}

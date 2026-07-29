import { useEffect, useState } from "react";

import { AppShell } from "../components/AppShell";
import { ApiError, publicGraphQl } from "../lib/http";
import {
  billingPeriodLabel,
  formatBytes,
  formatMoney,
  trafficResetLabel
} from "../lib/subscription";
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

export function PlansPage() {
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
              : "套餐加载失败"
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
        <h1>选择适合你的套餐</h1>
        <p className="muted">
          套餐价格、流量、限速和设备权益均来自实时商品目录。
        </p>
      </header>
      {loading && <div className="panel empty-state">正在加载套餐…</div>}
      {error && <p className="error-message">{error}</p>}
      {!loading && !error && offers.length === 0 && (
        <div className="panel empty-state">
          <strong>暂时没有可购买的套餐</strong>
          <span>套餐发布后会自动显示在这里。</span>
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
                <dt>套餐流量</dt>
                <dd>{formatBytes(offer.transferLimitBytes)}</dd>
              </div>
              <div>
                <dt>峰值速率</dt>
                <dd>
                  {offer.speedLimitMbps
                    ? `${offer.speedLimitMbps} Mbps`
                    : "不限速"}
                </dd>
              </div>
              <div>
                <dt>设备数量</dt>
                <dd>
                  {offer.deviceLimit
                    ? `${offer.deviceLimit} 台`
                    : "不限制"}
                </dd>
              </div>
              <div>
                <dt>流量重置</dt>
                <dd>{trafficResetLabel(offer.resetPolicy)}</dd>
              </div>
            </dl>
            <div className="plan-prices">
              {offer.prices.map((price) => (
                <div key={price.period}>
                  <span>{billingPeriodLabel(price.period)}</span>
                  <strong>
                    {formatMoney(price.amountMinor, price.currency)}
                  </strong>
                </div>
              ))}
            </div>
            {offer.capacityRemaining !== null && (
              <small className="plan-capacity">
                当前剩余 {offer.capacityRemaining} 个名额
              </small>
            )}
          </article>
        ))}
      </section>
    </AppShell>
  );
}

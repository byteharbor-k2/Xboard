import { useQuery } from "@tanstack/react-query";
import { useState, type KeyboardEvent } from "react";

import { AppShell } from "../components/AppShell";
import { ApiError, graphQl, publicGraphQl } from "../lib/http";
import { navigate } from "../lib/navigation";
import { useAuthStore } from "../store/auth";
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
      planType
      transferLimitBytes
      speedLimitMbps
      resetPolicy
      renewable
      resettable
      purchaseLimitPerUser
      capacityRemaining
      newUserOffer
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
    reset: "流量重置",
    trafficPackage: "流量包",
    subscription: "月订阅",
    remainingPrefix: "当前剩余",
    remainingSuffix: "个名额",
    newUserOffer: "新用户专属流量包 · 仅可购买一次",
    categories: {
      all: "全部",
      subscriptions: "订阅套餐",
      trafficPackages: "流量包"
    },
    noCategoryOffers: "此分类暂无可购买的套餐"
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
    reset: "Data reset",
    trafficPackage: "Traffic package",
    subscription: "Monthly subscription",
    remainingPrefix: "",
    remainingSuffix: "spots remaining",
    newUserOffer: "New-user traffic package · One-time purchase",
    categories: {
      all: "All",
      subscriptions: "Subscriptions",
      trafficPackages: "Traffic packages"
    },
    noCategoryOffers: "No plans are available in this category"
  }
};

type OfferCategory = "all" | "subscriptions" | "trafficPackages";

const categories: OfferCategory[] = [
  "all",
  "subscriptions",
  "trafficPackages"
];

export function PlansPage() {
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const accessToken = useAuthStore((state) => state.accessToken);
  const viewerId = useAuthStore((state) => state.viewer?.id ?? null);
  const [category, setCategory] = useState<OfferCategory>("all");
  const catalog = useQuery({
    queryKey: [
      "plan-offers",
      accessToken ? viewerId ?? "authenticated" : "anonymous"
    ],
    queryFn: async () => {
      const result = accessToken
        ? await graphQl<{ offerCatalog: PlanOffer[] }>(accessToken, offerQuery)
        : await publicGraphQl<{ offerCatalog: PlanOffer[] }>(offerQuery);
      return result.offerCatalog;
    },
    retry: false
  });
  const offers = catalog.data ?? [];
  const categoryOffers = offers.filter((offer) => {
    if (category === "subscriptions") return offer.planType === "SUBSCRIPTION";
    if (category === "trafficPackages") return offer.planType === "TRAFFIC_PACKAGE";
    return true;
  });
  const categoryCounts: Record<OfferCategory, number> = {
    all: offers.length,
    subscriptions: offers.filter(
      (offer) => offer.planType === "SUBSCRIPTION"
    ).length,
    trafficPackages: offers.filter(
      (offer) => offer.planType === "TRAFFIC_PACKAGE"
    ).length
  };

  function handleCategoryKeyDown(
    event: KeyboardEvent<HTMLButtonElement>,
    current: OfferCategory
  ) {
    const currentIndex = categories.indexOf(current);
    let nextIndex: number | undefined;
    if (event.key === "ArrowRight") {
      nextIndex = (currentIndex + 1) % categories.length;
    }
    if (event.key === "ArrowLeft") {
      nextIndex = (currentIndex - 1 + categories.length) % categories.length;
    }
    if (event.key === "Home") nextIndex = 0;
    if (event.key === "End") nextIndex = categories.length - 1;
    if (nextIndex === undefined) return;
    event.preventDefault();
    const nextCategory = categories[nextIndex];
    setCategory(nextCategory);
    document.getElementById(`plans-category-${nextCategory}`)?.focus();
  }

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">Plans</p>
        <h1>{labels.title}</h1>
        <p className="muted">{labels.description}</p>
      </header>
      {catalog.isPending && (
        <div className="panel empty-state">{labels.loading}</div>
      )}
      {catalog.isError && (
        <p className="error-message">
          {catalog.error instanceof ApiError
            ? catalog.error.message
            : labels.failed}
        </p>
      )}
      {catalog.isSuccess && (
        <>
          <div
            className="settings-tabs"
            role="tablist"
            aria-label={language === "zh-CN" ? "套餐分类" : "Plan categories"}
          >
            {categories.map((item) => (
              <button
                aria-controls="plans-category-panel"
                aria-selected={category === item}
                id={`plans-category-${item}`}
                key={item}
                onClick={() => setCategory(item)}
                onKeyDown={(event) => handleCategoryKeyDown(event, item)}
                role="tab"
                tabIndex={category === item ? 0 : -1}
                type="button"
              >
                {labels.categories[item]}{" "}
                ({categoryCounts[item]})
              </button>
            ))}
          </div>
          <section
            aria-labelledby={`plans-category-${category}`}
            className="plan-grid"
            id="plans-category-panel"
            role="tabpanel"
            tabIndex={0}
          >
            {categoryOffers.length === 0 && (
              <div className="panel empty-state">
                <strong>
                  {category === "all" ? labels.empty : labels.noCategoryOffers}
                </strong>
                {category === "all" && <span>{labels.emptyDescription}</span>}
              </div>
            )}
            {categoryOffers.map((offer) => (
              <article className="plan-card" key={offer.id}>
                <button
                  aria-label={offer.name}
                  className="plan-card-open"
                  onClick={() => navigate(`/plans/${offer.id}`)}
                  type="button"
                />
                <header>
                  <div className="plan-tags">
                    <span
                      className={`plan-type-badge ${
                        offer.planType === "TRAFFIC_PACKAGE"
                          ? "traffic-package"
                          : "subscription"
                      }`}
                    >
                      {offer.planType === "TRAFFIC_PACKAGE"
                        ? labels.trafficPackage
                        : labels.subscription}
                    </span>
                    {offer.tags.filter((tag) => tag !== (
                      offer.planType === "TRAFFIC_PACKAGE"
                        ? labels.trafficPackage
                        : labels.subscription
                    )).map((tag) => (
                      <span key={tag}>{tag}</span>
                    ))}
                    {offer.newUserOffer && (
                      <span className="new-user-offer-badge">
                        {labels.newUserOffer}
                      </span>
                    )}
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
        </>
      )}
    </AppShell>
  );
}

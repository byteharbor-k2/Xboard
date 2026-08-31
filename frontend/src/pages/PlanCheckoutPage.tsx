import { useCallback, useEffect, useMemo, useState } from "react";

import { AppShell } from "../components/AppShell";
import { ApiError } from "../lib/http";
import { navigate } from "../lib/navigation";
import {
  cancelOrder,
  fetchOrderQuote,
  fetchPlanOffer,
  placeOrder
} from "../lib/orders";
import {
  billingPeriodLabel,
  formatBytes,
  formatMoney,
  trafficResetLabel
} from "../lib/subscription";
import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";
import type { BillingPeriod, OrderQuote, PlanOffer } from "../types";

import "./PlanCheckoutPage.css";

const copy = {
  "zh-CN": {
    back: "← 返回套餐列表",
    loading: "正在加载套餐…",
    notFound: "套餐不存在或已下架",
    planDetail: "套餐详情",
    serviceNotes: "服务说明",
    traffic: "流量",
    speed: "速度限制",
    unlimitedSpeed: "不限速",
    devices: "同时在线设备",
    unlimitedDevices: "不限制",
    deviceUnit: "台",
    reset: "流量重置",
    periodTitle: "付款周期",
    couponPlaceholder: "有优惠券?",
    couponVerify: "验证",
    couponClear: "移除",
    orderTotal: "订单总额",
    subtotal: "小计",
    discount: "优惠券折抵",
    surplus: "套餐升级折抵",
    surplusCredit: "折抵剩余（退回余额）",
    balance: "余额折抵",
    total: "总计",
    submit: "下单",
    submitting: "正在下单…",
    orderTypeNew: "新购",
    orderTypeRenewal: "续费",
    orderTypeUpgrade: "升级",
    orderTypeReset: "流量重置",
    placed: "下单成功",
    placedHint: "订单号 %s，支付功能尚未开放，可在订单记录中取消。",
    viewOrders: "查看订单记录",
    cancelPlaced: "取消该订单",
    quoteFailed: "价格计算失败",
    accountBalance: "账户余额"
  },
  "en-US": {
    back: "← Back to plans",
    loading: "Loading plan…",
    notFound: "This plan does not exist or is no longer on sale",
    planDetail: "Plan details",
    serviceNotes: "What is included",
    traffic: "Traffic",
    speed: "Speed limit",
    unlimitedSpeed: "Unmetered",
    devices: "Concurrent devices",
    unlimitedDevices: "Unlimited",
    deviceUnit: "",
    reset: "Traffic reset",
    periodTitle: "Billing period",
    couponPlaceholder: "Have a coupon?",
    couponVerify: "Apply",
    couponClear: "Remove",
    orderTotal: "Order total",
    subtotal: "Subtotal",
    discount: "Coupon",
    surplus: "Unused plan value",
    surplusCredit: "Credited back to balance",
    balance: "Account balance",
    total: "Total",
    submit: "Place order",
    submitting: "Placing order…",
    orderTypeNew: "New purchase",
    orderTypeRenewal: "Renewal",
    orderTypeUpgrade: "Upgrade",
    orderTypeReset: "Traffic reset",
    placed: "Order placed",
    placedHint: "Order %s. Payment is not available yet; you can cancel it from your orders.",
    viewOrders: "View orders",
    cancelPlaced: "Cancel this order",
    quoteFailed: "Could not price this order",
    accountBalance: "Account balance"
  }
} as const;

type PlanCheckoutPageProps = {
  planId: string;
};

export function PlanCheckoutPage({ planId }: PlanCheckoutPageProps) {
  const language = useUserPreferences((state) => state.language);
  const text = copy[language];
  const accessToken = useAuthStore((state) => state.accessToken);

  const [offer, setOffer] = useState<PlanOffer | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [period, setPeriod] = useState<BillingPeriod | null>(null);
  const [couponDraft, setCouponDraft] = useState("");
  const [appliedCoupon, setAppliedCoupon] = useState("");
  const [quote, setQuote] = useState<OrderQuote | null>(null);
  const [quoteError, setQuoteError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [placedTradeNo, setPlacedTradeNo] = useState("");

  useEffect(() => {
    let active = true;
    setLoading(true);
    fetchPlanOffer(planId)
      .then((result) => {
        if (!active) return;
        setOffer(result);
        // Default to the cheapest period so the total is never blank.
        const cheapest = [...(result?.prices ?? [])].sort(
          (left, right) => Number(left.amountMinor) - Number(right.amountMinor)
        )[0];
        setPeriod(cheapest ? cheapest.period : null);
      })
      .catch((error) =>
        active && setLoadError(errorMessage(error, text.notFound))
      )
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [planId, text.notFound]);

  const refreshQuote = useCallback(
    async (chosen: BillingPeriod, coupon: string) => {
      if (!accessToken) return;
      setQuoteError("");
      try {
        setQuote(
          await fetchOrderQuote(accessToken, planId, chosen, coupon || undefined)
        );
      } catch (error) {
        // Keep the previous total on screen rather than flashing an empty panel.
        setQuoteError(errorMessage(error, text.quoteFailed));
      }
    },
    [accessToken, planId, text.quoteFailed]
  );

  useEffect(() => {
    if (period) void refreshQuote(period, appliedCoupon);
  }, [period, appliedCoupon, refreshQuote]);

  const orderTypeLabel = useMemo(() => {
    switch (quote?.orderType) {
      case "RENEWAL":
        return text.orderTypeRenewal;
      case "UPGRADE":
        return text.orderTypeUpgrade;
      case "RESET_TRAFFIC":
        return text.orderTypeReset;
      case "NEW_PURCHASE":
        return text.orderTypeNew;
      default:
        return "";
    }
  }, [quote?.orderType, text]);

  async function submit() {
    if (!accessToken || !period || submitting) return;
    setSubmitting(true);
    setQuoteError("");
    try {
      const order = await placeOrder(
        accessToken,
        planId,
        period,
        appliedCoupon || undefined
      );
      setPlacedTradeNo(order.tradeNo);
    } catch (error) {
      setQuoteError(errorMessage(error, text.quoteFailed));
    } finally {
      setSubmitting(false);
    }
  }

  async function undoPlacedOrder() {
    if (!accessToken || !placedTradeNo) return;
    try {
      await cancelOrder(accessToken, placedTradeNo);
      setPlacedTradeNo("");
      if (period) void refreshQuote(period, appliedCoupon);
    } catch (error) {
      setQuoteError(errorMessage(error, text.quoteFailed));
    }
  }

  if (loading) {
    return (
      <AppShell>
        <p className="checkout-status">{text.loading}</p>
      </AppShell>
    );
  }

  if (!offer) {
    return (
      <AppShell>
        <p className="checkout-status">{loadError || text.notFound}</p>
        <button
          className="text-button"
          onClick={() => navigate("/plans")}
          type="button"
        >
          {text.back}
        </button>
      </AppShell>
    );
  }

  const currency = quote?.currency ?? offer.prices[0]?.currency ?? "CNY";

  return (
    <AppShell>
      <button
        className="checkout-back"
        onClick={() => navigate("/plans")}
        type="button"
      >
        {text.back}
      </button>

      <div className="checkout-grid">
        <section className="checkout-card checkout-detail">
          <p className="checkout-plan-name">{offer.name}</p>
          <h2>{text.planDetail}</h2>
          <ul className="checkout-facts">
            <li>
              <span>{text.traffic}</span>
              <strong>{formatBytes(offer.transferLimitBytes)}</strong>
            </li>
            <li>
              <span>{text.speed}</span>
              <strong>
                {offer.speedLimitMbps
                  ? `${offer.speedLimitMbps} Mbps`
                  : text.unlimitedSpeed}
              </strong>
            </li>
            <li>
              <span>{text.devices}</span>
              <strong>
                {offer.deviceLimit
                  ? `${offer.deviceLimit} ${text.deviceUnit}`.trim()
                  : text.unlimitedDevices}
              </strong>
            </li>
            <li>
              <span>{text.reset}</span>
              <strong>{trafficResetLabel(offer.resetPolicy, language)}</strong>
            </li>
          </ul>

          {offer.description && (
            <>
              <h2>{text.serviceNotes}</h2>
              <div className="checkout-notes">
                {offer.description
                  .split("\n")
                  .map((line) => line.trim())
                  .filter(Boolean)
                  .map((line, index) => (
                    <p key={`${index}-${line.slice(0, 12)}`}>{line}</p>
                  ))}
              </div>
            </>
          )}
        </section>

        <div className="checkout-side">
          <section className="checkout-card">
            <h2>{text.periodTitle}</h2>
            <ul className="checkout-periods">
              {offer.prices.map((price) => (
                <li key={price.period}>
                  <button
                    aria-pressed={period === price.period}
                    className={
                      period === price.period
                        ? "checkout-period is-selected"
                        : "checkout-period"
                    }
                    onClick={() => setPeriod(price.period)}
                    type="button"
                  >
                    <span>{billingPeriodLabel(price.period, language)}</span>
                    <strong>
                      {formatMoney(price.amountMinor, price.currency, language)}
                    </strong>
                  </button>
                </li>
              ))}
            </ul>
          </section>

          <section className="checkout-card checkout-coupon">
            <input
              aria-label={text.couponPlaceholder}
              disabled={Boolean(placedTradeNo)}
              onChange={(event) => setCouponDraft(event.target.value)}
              placeholder={text.couponPlaceholder}
              value={couponDraft}
            />
            {appliedCoupon ? (
              <button
                onClick={() => {
                  setAppliedCoupon("");
                  setCouponDraft("");
                }}
                type="button"
              >
                {text.couponClear}
              </button>
            ) : (
              <button
                disabled={!couponDraft.trim() || Boolean(placedTradeNo)}
                onClick={() => setAppliedCoupon(couponDraft.trim())}
                type="button"
              >
                {text.couponVerify}
              </button>
            )}
          </section>

          <section className="checkout-card checkout-summary">
            <h2>{text.orderTotal}</h2>
            {quote ? (
              <>
                <dl>
                  <div>
                    <dt>
                      {quote.planName}
                      {orderTypeLabel && (
                        <span className="checkout-tag">{orderTypeLabel}</span>
                      )}
                    </dt>
                    <dd>
                      {formatMoney(quote.originalAmount, currency, language)}
                    </dd>
                  </div>
                  {Number(quote.discountAmount) > 0 && (
                    <div className="is-deduction">
                      <dt>
                        {text.discount}
                        {quote.couponName && ` · ${quote.couponName}`}
                      </dt>
                      <dd>
                        −{formatMoney(quote.discountAmount, currency, language)}
                      </dd>
                    </div>
                  )}
                  {Number(quote.surplusAmount) > 0 && (
                    <div className="is-deduction">
                      <dt>{text.surplus}</dt>
                      <dd>
                        −{formatMoney(quote.surplusAmount, currency, language)}
                      </dd>
                    </div>
                  )}
                  {Number(quote.balanceAmount) > 0 && (
                    <div className="is-deduction">
                      <dt>{text.balance}</dt>
                      <dd>
                        −{formatMoney(quote.balanceAmount, currency, language)}
                      </dd>
                    </div>
                  )}
                  {Number(quote.surplusCredit) > 0 && (
                    <div className="is-note">
                      <dt>{text.surplusCredit}</dt>
                      <dd>
                        {formatMoney(quote.surplusCredit, currency, language)}
                      </dd>
                    </div>
                  )}
                </dl>
                <p className="checkout-total-label">{text.total}</p>
                <p className="checkout-total">
                  {formatMoney(quote.totalAmount, currency, language)}{" "}
                  <span>{currency}</span>
                </p>
                <p className="checkout-balance-note">
                  {text.accountBalance}:{" "}
                  {formatMoney(quote.accountBalanceMinor, currency, language)}
                </p>
              </>
            ) : (
              <p className="checkout-status">{text.loading}</p>
            )}

            {quoteError && <p className="checkout-error">{quoteError}</p>}

            {placedTradeNo ? (
              <div className="checkout-placed">
                <strong>{text.placed}</strong>
                <p>{text.placedHint.replace("%s", placedTradeNo)}</p>
                <button
                  className="checkout-submit"
                  onClick={() => navigate("/orders")}
                  type="button"
                >
                  {text.viewOrders}
                </button>
                <button
                  className="text-button"
                  onClick={() => void undoPlacedOrder()}
                  type="button"
                >
                  {text.cancelPlaced}
                </button>
              </div>
            ) : (
              <button
                className="checkout-submit"
                disabled={!quote || submitting}
                onClick={() => void submit()}
                type="button"
              >
                {submitting ? text.submitting : text.submit}
              </button>
            )}
          </section>
        </div>
      </div>
    </AppShell>
  );
}

function errorMessage(error: unknown, fallback: string): string {
  // ApiError already carries the server's detail as its message.
  if (error instanceof ApiError) {
    return error.message || fallback;
  }
  return error instanceof Error ? error.message : fallback;
}

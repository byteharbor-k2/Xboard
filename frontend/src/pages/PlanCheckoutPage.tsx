import {
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore
} from "react";

import { AppShell } from "../components/AppShell";
import { ApiError } from "../lib/http";
import { navigate } from "../lib/navigation";
import {
  canonicalCoupon,
  CheckoutQuoteState,
  checkoutQuoteInputKey,
  orderInputForQuote,
  type CheckoutQuoteInput
} from "../lib/checkoutQuoteState";
import {
  cancelOrder,
  fetchOrderQuote,
  fetchPlanOffer,
  placeOrder
} from "../lib/orders";
import {
  billingPeriodLabel,
  formatBytes,
  formatMinorMoney,
  trafficResetLabel
} from "../lib/subscription";
import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";
import type { BillingPeriod, OrderDeductionMode, OrderQuote, PlanOffer, ServiceOrder } from "../types";

import "./PlanCheckoutPage.css";

const copy = {
  "zh-CN": {
    back: "← 返回套餐列表",
    loading: "正在加载套餐…",
    notFound: "套餐不存在或已下架",
    planDetail: "套餐详情",
    newUserOffer: "新用户专属流量包 · 仅可购买一次",
    serviceNotes: "服务说明",
    traffic: "流量",
    speed: "速度限制",
    unlimitedSpeed: "不限速",
    reset: "流量重置",
    periodTitle: "付款周期",
    couponPlaceholder: "有优惠券?",
    couponVerify: "验证",
    couponClear: "移除",
    orderTotal: "订单总额",
    subtotal: "小计",
    discount: "优惠券折抵",
    surplus: "套餐升级折抵",
    balance: "余额折抵",
    total: "总计",
    submit: "下单",
    submitting: "正在下单…",
    orderTypeNew: "新购",
    orderTypeRenewal: "续费",
    orderTypeUpgrade: "升级",
    orderTypeReset: "流量重置",
    placed: "下单成功",
    placedHint: "订单号 %s，请前往订单详情完成支付。",
    placedBlockedHint: "订单号 %s；此订单不能线上支付，请联系管理员线下人工结算。",
    viewOrders: "查看订单",
    cancelPlaced: "取消该订单",
    quoteFailed: "价格计算失败",
    retryQuote: "重试报价",
    accountBalance: "账户余额",
    deferredCredit: "开通后预计退回余额",
    surplusReserved: "换购订单待付款期间，当前套餐暂停使用；取消订单即可恢复。",
    minimumPayment: "在线支付金额需至少 ¥10。请选择其他套餐，或联系客服。",
    blockedOffline: "如需保留此订单，请联系客服协助付款。",
    zeroAuto: "应付为 ¥0 的订单将自动开通，不需要支付方式。",
    autoSettled: "该订单已自动开通，无需支付。",
    balanceReturned: "该订单未重置流量，已返还站内余额%s（可用于后续订单，非银行退款）"
  },
  "en-US": {
    back: "← Back to plans",
    loading: "Loading plan…",
    notFound: "This plan does not exist or is no longer on sale",
    planDetail: "Plan details",
    newUserOffer: "New-user traffic package · One-time purchase",
    serviceNotes: "What is included",
    traffic: "Traffic",
    speed: "Speed limit",
    unlimitedSpeed: "Unmetered",
    reset: "Traffic reset",
    periodTitle: "Billing period",
    couponPlaceholder: "Have a coupon?",
    couponVerify: "Apply",
    couponClear: "Remove",
    orderTotal: "Order total",
    subtotal: "Subtotal",
    discount: "Coupon",
    surplus: "Unused plan value",
    balance: "Account balance",
    total: "Total",
    submit: "Place order",
    submitting: "Placing order…",
    orderTypeNew: "New purchase",
    orderTypeRenewal: "Renewal",
    orderTypeUpgrade: "Upgrade",
    orderTypeReset: "Traffic reset",
    placed: "Order placed",
    placedHint: "Order %s. Open it to pay.",
    placedBlockedHint: "Order %s cannot be paid online; contact an administrator for manual offline settlement.",
    viewOrders: "View order",
    cancelPlaced: "Cancel this order",
    quoteFailed: "Could not price this order",
    retryQuote: "Retry quote",
    accountBalance: "Account balance",
    deferredCredit: "Estimated balance credit after activation",
    surplusReserved: "Your current plan is paused while the switch order awaits payment. Cancel the order to resume it.",
    minimumPayment: "Online payments must be at least ¥10. Choose another plan or contact support.",
    blockedOffline: "Contact support for help paying this order.",
    zeroAuto: "Orders with ¥0 due are fulfilled automatically without a payment method.",
    autoSettled: "This order was fulfilled automatically; no payment is due.",
    balanceReturned: "Traffic was not reset; %s was returned to your site balance for future orders (not a bank refund)."
  }
} as const;

type PlanCheckoutPageProps = {
  planId: string;
};

export function PlanCheckoutPage({ planId }: PlanCheckoutPageProps) {
  const language = useUserPreferences((state) => state.language);
  const text = copy[language];
  const accessToken = useAuthStore((state) => state.accessToken);
  const viewerId = useAuthStore((state) => state.viewer?.id ?? "");

  const [offer, setOffer] = useState<PlanOffer | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [period, setPeriod] = useState<BillingPeriod | null>(null);
  const [deductionMode] = useState<OrderDeductionMode>(
    new URLSearchParams(window.location.search).get("deductionMode") === "FULL_PAYMENT"
      ? "FULL_PAYMENT"
      : "STANDARD"
  );
  const initialCoupon = canonicalCoupon(new URLSearchParams(window.location.search).get("couponCode") ?? "") ?? "";
  const [couponDraft, setCouponDraft] = useState(initialCoupon);
  const [appliedCoupon, setAppliedCoupon] = useState(initialCoupon);
  const [quoteState] = useState(() => new CheckoutQuoteState<OrderQuote>());
  const quoteSnapshot = useSyncExternalStore(
    quoteState.subscribe,
    quoteState.getSnapshot,
    quoteState.getSnapshot
  );
  const [refreshNonce, setRefreshNonce] = useState(0);
  const [actionError, setActionError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const submittingRef = useRef(false);
  const [placedTradeNo, setPlacedTradeNo] = useState("");
  const [placedStatus, setPlacedStatus] = useState<string | null>(null);
  const [placedSettlement, setPlacedSettlement] = useState<{
    outcome: ServiceOrder["settlementOutcome"];
    returnedBalanceMinor: string | null;
  } | null>(null);
  const [placedInput, setPlacedInput] = useState<CheckoutQuoteInput | null>(null);

  const normalizedCoupon = canonicalCoupon(appliedCoupon);
  const quoteInput = useMemo<CheckoutQuoteInput | null>(
    () => period === null
      ? null
      : {
          ownerId: viewerId,
          planId,
          period,
          couponCode: normalizedCoupon,
          deductionMode
        },
    [deductionMode, normalizedCoupon, period, planId, viewerId]
  );
  const quoteInputKey = quoteInput ? checkoutQuoteInputKey(quoteInput) : "<no-selection>";
  const snapshotMatchesInputs = Boolean(
    quoteInput && quoteSnapshot.input &&
      checkoutQuoteInputKey(quoteSnapshot.input) === quoteInputKey
  );
  const quote = snapshotMatchesInputs ? quoteSnapshot.quote : null;
  const quoteError = snapshotMatchesInputs ? quoteSnapshot.error ?? "" : "";
  const confirmedQuote = quoteInput ? quoteState.confirmed(quoteInput) : null;
  const quotePending = Boolean(
    quoteInput && viewerId && accessToken &&
      (!snapshotMatchesInputs || quoteSnapshot.pending)
  );

  function selectPeriod(nextPeriod: BillingPeriod) {
    if (viewerId) {
      quoteState.select({
        ownerId: viewerId,
        planId,
        period: nextPeriod,
        couponCode: normalizedCoupon,
        deductionMode
      });
    }
    setActionError("");
    setPeriod(nextPeriod);
  }

  function applyCoupon(value: string) {
    const nextCoupon = canonicalCoupon(value);
    if (viewerId && period) {
      quoteState.select({
        ownerId: viewerId,
        planId,
        period,
        couponCode: nextCoupon,
        deductionMode
      });
    }
    setActionError("");
    setAppliedCoupon(nextCoupon ?? "");
    setCouponDraft(nextCoupon ?? "");
  }

  function retryQuote() {
    if (!quoteInput || !accessToken || !viewerId) return;
    quoteState.invalidate(quoteInput);
    setActionError("");
    setRefreshNonce((nonce) => nonce + 1);
  }

  useEffect(() => {
    let active = true;
    setLoading(true);
      fetchPlanOffer(planId, accessToken)
      .then((result) => {
        if (!active) return;
        setOffer(result);
        // Default to the cheapest period so the total is never blank.
        const requestedPeriod = new URLSearchParams(window.location.search).get("period") as BillingPeriod | null;
        const requestedPrice = result?.prices.find((price) => price.period === requestedPeriod);
        const cheapest = [...(result?.prices ?? [])].sort(
          (left, right) => BigInt(left.amountMinor) < BigInt(right.amountMinor) ? -1 : BigInt(left.amountMinor) > BigInt(right.amountMinor) ? 1 : 0
        )[0];
        setPeriod(requestedPrice?.period ?? (cheapest ? cheapest.period : null));
      })
      .catch((error) =>
        active && setLoadError(errorMessage(error, text.notFound))
      )
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [accessToken, planId, text.notFound]);

  useLayoutEffect(() => {
    if (quoteInput) quoteState.select(quoteInput);
    else quoteState.clear();
    setActionError("");
  }, [quoteInputKey, quoteState]);

  useEffect(() => {
    if (!quoteInput || !quoteInput.ownerId || !accessToken) return;
    void quoteState.request(
      quoteInput,
      (input) => fetchOrderQuote(
        accessToken,
        input.planId,
        input.period,
        input.couponCode ?? undefined,
        input.deductionMode
      ),
      (error) => errorMessage(error, text.quoteFailed)
    );
  }, [accessToken, quoteInput, quoteInputKey, quoteState, refreshNonce, text.quoteFailed]);

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
    if (
      !accessToken || !viewerId || !confirmedQuote || submittingRef.current ||
      placedTradeNo
    ) return;
    const quoteInputSnapshot = { ...confirmedQuote.input };
    const orderInput = orderInputForQuote(quoteInputSnapshot);
    const requestAccessToken = accessToken;
    submittingRef.current = true;
    setSubmitting(true);
    setActionError("");
    try {
      const order = await placeOrder(
        requestAccessToken,
        orderInput.planId,
        orderInput.period,
        orderInput.couponCode ?? undefined,
        orderInput.deductionMode
      );
      if (useAuthStore.getState().viewer?.id !== quoteInputSnapshot.ownerId) return;
      setPlacedTradeNo(order.tradeNo);
      setPlacedStatus(order.status);
      setPlacedSettlement({
        outcome: order.settlementOutcome,
        returnedBalanceMinor: order.returnedBalanceMinor ?? null
      });
      setPlacedInput(quoteInputSnapshot);
    } catch (error) {
      if (useAuthStore.getState().viewer?.id === quoteInputSnapshot.ownerId) {
        setActionError(errorMessage(error, text.quoteFailed));
      }
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  }

  async function undoPlacedOrder() {
    if (!accessToken || !placedTradeNo) return;
    try {
      await cancelOrder(accessToken, placedTradeNo);
      setPlacedTradeNo("");
      setPlacedStatus(null);
      setPlacedSettlement(null);
      setPlacedInput(null);
      setActionError("");
      if (placedInput) {
        quoteState.invalidate(placedInput);
        setRefreshNonce((nonce) => nonce + 1);
      }
    } catch (error) {
      setActionError(errorMessage(error, text.quoteFailed));
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
      <button className="checkout-back" onClick={() => navigate("/plans")} type="button">
        {text.back}
      </button>

      <div className="checkout-grid">
        <section className="checkout-card checkout-detail">
          <p className="checkout-plan-name">{offer.name}</p>
          {offer.newUserOffer && (
            <p className="new-user-offer-badge checkout-offer-badge">
              {text.newUserOffer}
            </p>
          )}
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
                    disabled={Boolean(placedTradeNo) || submitting}
                    onClick={() => selectPeriod(price.period)}
                    type="button"
                  >
                    <span>{billingPeriodLabel(price.period, language)}</span>
                    <strong>
                      {formatMinorMoney(price.amountMinor, price.currency, language)}
                    </strong>
                  </button>
                </li>
              ))}
            </ul>
          </section>

          <section className="checkout-card checkout-coupon">
            <input
              aria-label={text.couponPlaceholder}
              disabled={Boolean(placedTradeNo) || submitting}
              onChange={(event) => setCouponDraft(event.target.value)}
              placeholder={text.couponPlaceholder}
              value={couponDraft}
            />
            {appliedCoupon ? (
              <button
                disabled={Boolean(placedTradeNo) || submitting}
                onClick={() => applyCoupon("")}
                type="button"
              >
                {text.couponClear}
              </button>
            ) : (
              <button
                disabled={!couponDraft.trim() || Boolean(placedTradeNo) || submitting}
                onClick={() => applyCoupon(couponDraft)}
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
                      {formatMinorMoney(quote.originalAmount, currency, language)}
                    </dd>
                  </div>
                  {BigInt(quote.discountAmount) > 0n && (
                    <div className="is-deduction">
                      <dt>
                        {text.discount}
                        {quote.couponName && ` · ${quote.couponName}`}
                      </dt>
                      <dd>
                        −{formatMinorMoney(quote.discountAmount, currency, language)}
                      </dd>
                    </div>
                  )}
                  {BigInt(quote.surplusAmount) > 0n && (
                    <div className="is-deduction">
                      <dt>{text.surplus}</dt>
                      <dd>
                        −{formatMinorMoney(quote.surplusAmount, currency, language)}
                      </dd>
                    </div>
                  )}
                  {BigInt(quote.balanceAmount) > 0n && (
                    <div className="is-deduction">
                      <dt>{text.balance}</dt>
                      <dd>
                        −{formatMinorMoney(quote.balanceAmount, currency, language)}
                      </dd>
                    </div>
                  )}
                  {BigInt(quote.deferredSurplusCreditMinor) > 0n && (
                    <div className="is-note">
                      <dt>{text.deferredCredit}</dt>
                      <dd>
                        {formatMinorMoney(quote.deferredSurplusCreditMinor, currency, language)}
                      </dd>
                    </div>
                  )}
                </dl>
                <p className="checkout-total-label">{text.total}</p>
                <p className="checkout-total">
                  {formatMinorMoney(quote.totalAmount, currency, language)}{" "}
                  <span>{currency}</span>
                </p>
                <p className="checkout-balance-note">
                  {text.accountBalance}:{" "}
                  {formatMinorMoney(quote.accountBalanceMinor, currency, language)}
                </p>
                {quote.minimumOnlinePaymentBlocked && (
                  <div className="checkout-error" role="alert">
                    <p>{text.minimumPayment}</p>
                    <p>{text.blockedOffline}</p>
                  </div>
                )}
                {BigInt(quote.totalAmount) === 0n && <p className="checkout-status">{text.zeroAuto}</p>}
                {BigInt(quote.surplusAmount) > 0n && <p className="checkout-balance-note">{text.surplusReserved}</p>}
              </>
            ) : (
              <p className="checkout-status">{text.loading}</p>
            )}

            {quoteError && <p className="checkout-error">{quoteError}</p>}
            {quoteError && <button className="text-button" disabled={quotePending} onClick={retryQuote} type="button">{text.retryQuote}</button>}
            {actionError && <p className="checkout-error">{actionError}</p>}

            {placedTradeNo ? (
              <div className="checkout-placed">
                <strong>{text.placed}</strong>
                <p>{placedSettlement?.outcome === "BALANCE_RETURNED"
                  ? text.balanceReturned.replace(
                    "%s",
                    formatMinorMoney(placedSettlement.returnedBalanceMinor ?? "0", currency, language)
                  )
                  : placedStatus === "COMPLETED"
                    ? text.autoSettled
                    : quote?.minimumOnlinePaymentBlocked
                      ? text.placedBlockedHint.replace("%s", placedTradeNo)
                      : text.placedHint.replace("%s", placedTradeNo)}</p>
                <button
                  className="checkout-submit"
                  onClick={() =>
                    navigate(`/account/orders/${encodeURIComponent(placedTradeNo)}?planId=${encodeURIComponent(placedInput?.planId ?? planId)}&period=${placedInput?.period ?? period}&deductionMode=${placedInput?.deductionMode ?? deductionMode}${placedInput?.couponCode ? `&couponCode=${encodeURIComponent(placedInput.couponCode)}` : ""}`)
                  }
                  type="button"
                >
                  {text.viewOrders}
                </button>
                {placedStatus === "PENDING" && <button
                  disabled={submitting}
                  className="text-button"
                  onClick={() => void undoPlacedOrder()}
                  type="button"
                >
                  {text.cancelPlaced}
                </button>}
              </div>
            ) : (
              <button
                className="checkout-submit"
                disabled={!confirmedQuote || quotePending || submitting}
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

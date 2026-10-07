import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { AppShell } from "../components/AppShell";
import { AppLink } from "../components/AppLink";
import { ApiError } from "../lib/http";
import { navigate } from "../lib/navigation";
import {
  cancelOrder,
  checkoutOrder,
  fetchPaymentOptions,
  fetchViewerOrders
} from "../lib/orders";
import {
  billingPeriodLabel,
  formatDateTime,
  formatMinorMoney,
  orderStatusLabel,
  orderTypeLabel
} from "../lib/subscription";
import { useAuthStore } from "../store/auth";
import { useUserPreferences, type UserLanguage } from "../store/userPreferences";
import type { PaymentOption, ServiceOrder } from "../types";

import "./PlanCheckoutPage.css";

/**
 * How often the page asks whether the order has moved on. A gateway callback
 * arrives out of band, so the only way the customer learns their order opened
 * is by asking again. Fast enough to feel immediate, slow enough not to hammer
 * the API for the two hours an order may sit unpaid.
 */
const POLL_INTERVAL_MS = 1500;

const copy = {
  "zh-CN": {
    eyebrow: "BILLING",
    title: "订单详情",
    back: "返回订单记录",
    loading: "正在读取订单…",
    notFound: "找不到该订单。",
    failed: "订单读取失败。",
    orderNumber: "订单号",
    plan: "套餐",
    period: "周期",
    deductionMode: "折抵方式",
    fullPaymentMode: "不使用余额或套餐剩余价值折抵",
    standardPaymentMode: "使用可用余额及套餐剩余价值折抵",
    createdAt: "创建时间",
    paidAt: "支付时间",
    original: "套餐原价",
    discount: "优惠",
    surplus: "折抵",
    balance: "余额抵扣",
    total: "订单金额",
    handling: "支付手续费",
    feePartsPrefix: "手续费：",
    noFee: "免手续费",
    payable: "应付总额",
    method: "支付方式",
    methodEmpty: "暂无可用支付方式，请联系管理员。",
    minimumBlockedOffline: "此订单可等待管理员线下人工结算；线上支付不可用。若要使用全额支付方式，必须先取消此订单，再重新下单，系统不会在当前订单上静默改价。",
    fullPaymentRetry: "取消订单并重新选择全额支付",
    minimumPayment: "受支付系统限制，最小付款金额不得小于10CNY，此笔支付无法使用剩余价值或余额折抵，请选择折抵后大于10CNY的套餐或不使用折抵全额支付，折抵金额会进入您的余额，下次可以使用",
    deferredCredit: "开通成功后才会退回的剩余价值",
    completedCredit: "已开通，剩余价值已退回余额",
    balanceReturned: "该订单未重置流量，已返还站内余额%s（可用于后续订单，非银行退款）",
    viewBalance: "查看余额明细",
    methodCoveredByBalance: "该订单已由账户余额全额抵扣，无需再支付，正在为你开通。",
    methodCoveredBySurplus: "该订单已由套餐升级折抵全额覆盖，无需再支付，正在为你开通。",
    methodCoveredByCoupon: "该订单已由优惠券全额抵扣，无需再支付，正在为你开通。",
    methodLoading: "正在读取支付方式…",
    pay: "立即支付",
    paying: "正在跳转…",
    payFailed: "无法发起支付。",
    close: "关闭订单",
    closing: "正在关闭…",
    closeFailed: "订单关闭失败。",
    closeConfirmTitle: "关闭订单",
    closeConfirm:
      "如果您已经付款，取消订单可能会导致支付失败，确定要取消订单吗？",
    confirm: "确定",
    dismiss: "再想想",
    statusHint: {
      PENDING: "订单已创建，请选择支付方式完成支付。超时未支付将自动关闭。",
      PROCESSING: "订单系统正在进行处理，请稍等 1-3 分钟。",
      CANCELLED: "订单由于超时支付已被取消。",
      COMPLETED: "订单已支付并开通。",
      DISCOUNTED: "该订单的金额已被后续订单折抵。"
    }
  },
  "en-US": {
    eyebrow: "BILLING",
    title: "Order",
    back: "Back to orders",
    loading: "Loading the order…",
    notFound: "That order could not be found.",
    failed: "The order could not be loaded.",
    orderNumber: "Order",
    plan: "Plan",
    period: "Period",
    deductionMode: "Deduction mode",
    fullPaymentMode: "No balance or unused-plan-value deductions",
    standardPaymentMode: "Use available balance and unused plan value",
    createdAt: "Created",
    paidAt: "Paid",
    original: "Plan price",
    discount: "Discount",
    surplus: "Trade-in",
    balance: "Balance used",
    total: "Order total",
    handling: "Payment fee",
    feePartsPrefix: "Fee: ",
    noFee: "No fee",
    payable: "Total to pay",
    method: "Payment method",
    methodEmpty: "No payment method is available; please contact an administrator.",
    minimumBlockedOffline: "This order may be settled manually by an administrator; online payment is unavailable. To choose full payment, cancel this order and place a new one. The current order will not be silently repriced.",
    fullPaymentRetry: "Cancel and choose full payment in a new order",
    minimumPayment: "受支付系统限制，最小付款金额不得小于10CNY，此笔支付无法使用剩余价值或余额折抵，请选择折抵后大于10CNY的套餐或不使用折抵全额支付，折抵金额会进入您的余额，下次可以使用",
    deferredCredit: "Unused value credited only after successful activation",
    completedCredit: "Activated; the unused value was credited to the balance",
    balanceReturned: "Traffic was not reset; %s was returned to your site balance for future orders (not a bank refund).",
    viewBalance: "View balance history",
    methodCoveredByBalance:
      "This order was covered in full by your account balance, so there is nothing left to pay. It is being opened for you.",
    methodCoveredBySurplus:
      "This order was covered in full by the credit from your previous plan, so there is nothing left to pay. It is being opened for you.",
    methodCoveredByCoupon:
      "This order was covered in full by a coupon, so there is nothing left to pay. It is being opened for you.",
    methodLoading: "Loading payment methods…",
    pay: "Pay now",
    paying: "Redirecting…",
    payFailed: "The payment could not be started.",
    close: "Close order",
    closing: "Closing…",
    closeFailed: "The order could not be closed.",
    closeConfirmTitle: "Close this order",
    closeConfirm:
      "If you have already paid, cancelling now may cause the payment to fail. Close the order anyway?",
    confirm: "Close it",
    dismiss: "Keep it",
    statusHint: {
      PENDING:
        "The order has been placed. Choose how to pay; it closes itself if left unpaid.",
      PROCESSING: "The order is being processed. Please allow 1-3 minutes.",
      CANCELLED: "The order was cancelled because it went unpaid.",
      COMPLETED: "The order has been paid and opened.",
      DISCOUNTED: "This order's value was applied to a later order."
    }
  }
};

type CloseTarget = { tradeNo: string };

type Labels = (typeof copy)["zh-CN"];

/**
 * Which deduction emptied the order.
 *
 * The three are not interchangeable to a customer - one is money on their
 * account, one is credit carried over from the plan they are leaving, one is a
 * coupon - so the message names whichever actually applied rather than always
 * claiming the balance.
 */
function coveredByLabel(order: ServiceOrder, labels: Labels) {
  if (BigInt(order.balanceAmount) > 0n) {
    return labels.methodCoveredByBalance;
  }
  if (BigInt(order.surplusAmount) > 0n) {
    return labels.methodCoveredBySurplus;
  }
  if (BigInt(order.discountAmount) > 0n) {
    return labels.methodCoveredByCoupon;
  }
  return labels.methodCoveredByBalance;
}

/**
 * How this method's fee is made up, in words: a percentage, a fixed amount,
 * or both.
 *
 * Shown next to the result (the method's quoted fee) so the customer sees
 * where the surcharge comes from - and so a zero quote reads as "no fee"
 * rather than as an amount to pay.
 */
function feeParts(method: PaymentOption, language: UserLanguage): string | undefined {
  const parts: string[] = [];
  if (method.handlingFeePercent != null) {
    parts.push(`${stripTrailingZeros(Number(method.handlingFeePercent).toFixed(2))}%`);
  }
  if (method.handlingFeeFixed != null) {
    parts.push(formatMinorMoney(method.handlingFeeFixed, method.currency, language));
  }
  return parts.length > 0 ? parts.join(" + ") : undefined;
}

function stripTrailingZeros(value: string): string {
  return value.replace(/\.?0+$/, "");
}

export function OrderDetailPage({ tradeNo }: { tradeNo: string }) {
  const accessToken = useAuthStore((state) => state.accessToken)!;
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const queryClient = useQueryClient();
  const [selectedMethod, setSelectedMethod] = useState("");
  const [closing, setClosing] = useState<CloseTarget | null>(null);

  const orders = useQuery({
    queryKey: ["viewer-orders"],
    queryFn: () => fetchViewerOrders(accessToken)
  });
  const order: ServiceOrder | undefined = orders.data?.find(
    (candidate) => candidate.tradeNo === tradeNo
  );

  // Only an order that is still moving is worth asking about.
  const unsettled =
    order?.status === "PENDING" || order?.status === "PROCESSING";
  useEffect(() => {
    if (!unsettled) {
      return;
    }
    const timer = window.setInterval(() => {
      void queryClient.invalidateQueries({ queryKey: ["viewer-orders"] });
    }, POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [unsettled, queryClient]);

  const methods = useQuery({
    queryKey: ["payment-options", tradeNo],
    queryFn: () => fetchPaymentOptions(accessToken, tradeNo),
    // Asked for even when the balance already covers the order: that call is
    // what settles such an order, and the empty list it answers with is how the
    // customer learns there is nothing left to pay.
    enabled: order?.status === "PENDING"
  });

  // The surcharge the customer will actually be charged.
  //
  // Before checkout that is whatever the server quotes for the method they
  // picked; afterwards the order carries it. Taking the quote into the total
  // matters: the gateway is asked for the total *plus* the fee, so leaving the
  // fee out of the headline figure shows the customer one price and charges
  // them another.
  const selectedOption = methods.data?.find(
    (method) => method.id === selectedMethod
  );
  const handling = BigInt(order?.handlingAmount ?? "0") > 0n
    ? BigInt(order!.handlingAmount)
    : BigInt(selectedOption?.handlingFee ?? "0");

  // How the fee the summary line is quoting is made up, when the method the
  // customer picked charges one. After checkout the order only carries the
  // amount, so the line then names the fee without its recipe.
  const selectedParts = selectedOption
    ? feeParts(selectedOption, language)
    : undefined;
  const handlingLabel = selectedParts
    ? language === "zh-CN"
      ? `${labels.handling}（${selectedParts}）`
      : `${labels.handling} (${selectedParts})`
    : labels.handling;

  const checkout = useMutation({
    mutationFn: (paymentMethodId: string) =>
      checkoutOrder(accessToken, tradeNo, paymentMethodId),
    onSuccess: (redirect) => {
      // type 1 is a cashier page the customer's own browser has to open; a QR
      // payload (type 0) is not produced by any gateway this build speaks.
      if (redirect.type === 1) {
        window.location.href = redirect.data;
        return;
      }
      void queryClient.invalidateQueries({ queryKey: ["viewer-orders"] });
    }
  });

  const cancel = useMutation({
    mutationFn: (target: string) => cancelOrder(accessToken, target),
    onSuccess: () => {
      setClosing(null);
      void queryClient.invalidateQueries({ queryKey: ["viewer-orders"] });
      if (order?.minimumOnlinePaymentBlocked) {
        const params = new URLSearchParams({ period: order.period, deductionMode: "FULL_PAYMENT" });
        const coupon = new URLSearchParams(window.location.search).get("couponCode");
        if (coupon) params.set("couponCode", coupon);
        navigate(`/plans/${encodeURIComponent(order.planId)}?${params.toString()}`);
      }
    }
  });

  const chosen = methods.data?.find((method) => method.id === selectedMethod);

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">{labels.eyebrow}</p>
        <h1>{labels.title}</h1>
        <button
          className="text-button"
          onClick={() => navigate("/account/orders")}
          type="button"
        >
          {labels.back}
        </button>
      </header>

      {orders.isPending && <p className="checkout-status">{labels.loading}</p>}
      {orders.isError && (
        <p className="error-message">
          {orders.error instanceof ApiError
            ? orders.error.message
            : labels.failed}
        </p>
      )}
      {orders.isSuccess && !order && (
        <p className="error-message">{labels.notFound}</p>
      )}

      {order && (
        <div className="checkout-grid">
          <section className="checkout-card checkout-detail">
            <p className="checkout-plan-name">{order.planName}</p>
            <ul className="checkout-facts">
              <li>
                <span>{labels.orderNumber}</span>
                <strong className="order-number">{order.tradeNo}</strong>
              </li>
              <li>
                <span>{labels.period}</span>
                <strong>{billingPeriodLabel(order.period, language)}</strong>
              </li>
              <li>
                <span>{labels.deductionMode}</span>
                <strong>{order.deductionMode === "FULL_PAYMENT" ? labels.fullPaymentMode : labels.standardPaymentMode}</strong>
              </li>
              <li>
                <span>{labels.createdAt}</span>
                <strong>{formatDateTime(order.createdAt, language)}</strong>
              </li>
              {order.paidAt && (
                <li>
                  <span>{labels.paidAt}</span>
                  <strong>{formatDateTime(order.paidAt, language)}</strong>
                </li>
              )}
            </ul>
            <p className="muted">
              <span
                className={`status-pill order-status-${order.status.toLowerCase()}`}
              >
                {orderStatusLabel(order.status, language)}
              </span>{" "}
              {order.settlementOutcome === "BALANCE_RETURNED" ? (
                <>
                  {labels.balanceReturned.replace(
                    "%s",
                    formatMinorMoney(order.returnedBalanceMinor ?? "0", order.currency, language)
                  )}{" "}
                  <AppLink href="/account/balance">{labels.viewBalance}</AppLink>
                </>
              ) : labels.statusHint[order.status]}
            </p>
          </section>

          <div className="checkout-side">
            <section className="checkout-card checkout-summary">
              <h2>
                {labels.total}
                <span className="checkout-tag">
                  {orderTypeLabel(order.orderType, language)}
                </span>
              </h2>
              <dl>
                <div>
                  <dt>{order.planName}</dt>
                  <dd>
                    {formatMinorMoney(order.originalAmount, order.currency, language)}
                  </dd>
                </div>
                {BigInt(order.discountAmount) > 0n && (
                  <div className="is-deduction">
                    <dt>{labels.discount}</dt>
                    <dd>
                      −
                      {formatMinorMoney(
                        order.discountAmount,
                        order.currency,
                        language
                      )}
                    </dd>
                  </div>
                )}
                {BigInt(order.surplusAmount) > 0n && (
                  <div className="is-deduction">
                    <dt>{labels.surplus}</dt>
                    <dd>
                      −
                      {formatMinorMoney(
                        order.surplusAmount,
                        order.currency,
                        language
                      )}
                    </dd>
                  </div>
                )}
                {BigInt(order.balanceAmount) > 0n && (
                  <div className="is-deduction">
                    <dt>{labels.balance}</dt>
                    <dd>
                      −
                      {formatMinorMoney(
                        order.balanceAmount,
                        order.currency,
                        language
                      )}
                    </dd>
                  </div>
                )}
                {order.status !== "CANCELLED" && order.settlementOutcome !== "BALANCE_RETURNED" && BigInt(order.deferredSurplusCreditMinor) > 0n && (
                  <div className="is-note">
                    <dt>{order.status === "COMPLETED" ? labels.completedCredit : labels.deferredCredit}</dt>
                    <dd>
                      {formatMinorMoney(
                        order.deferredSurplusCreditMinor,
                        order.currency,
                        language
                      )}
                    </dd>
                  </div>
                )}
                {handling > 0n && (
                  <div>
                    <dt>{handlingLabel}</dt>
                    <dd>
                      +
                      {formatMinorMoney(
                        handling.toString(),
                        order.currency,
                        language
                      )}
                    </dd>
                  </div>
                )}
              </dl>
              <p className="checkout-total-label">{labels.payable}</p>
              <p className="checkout-total">
                {formatMinorMoney(
                  (BigInt(order.totalAmount) + handling).toString(),
                  order.currency,
                  language
                )}{" "}
                <span>{order.currency}</span>
              </p>
              {order.status === "PENDING" && order.minimumOnlinePaymentBlocked && (
                <div className="checkout-error" role="alert">
                  <p>{order.minimumPaymentMessage ?? labels.minimumPayment}</p>
                  <p>{labels.minimumBlockedOffline}</p>
                </div>
              )}
            </section>

            {order.status === "PENDING" && (
              <section className="checkout-card">
                <h2>{labels.method}</h2>
                {methods.isPending && (
                  <p className="checkout-status">{labels.methodLoading}</p>
                )}
                {methods.isError && (
                  <p className="checkout-error">
                    {methods.error instanceof ApiError
                      ? methods.error.message
                      : labels.failed}
                  </p>
                )}
                {methods.data?.length === 0 && (
                  <p className="muted">
                    {BigInt(order.totalAmount) <= 0n
                      ? coveredByLabel(order, labels)
                      : order.minimumOnlinePaymentBlocked
                        ? labels.minimumBlockedOffline
                        : labels.methodEmpty}
                  </p>
                )}
                <ul className="checkout-periods">
                  {methods.data?.map((method) => {
                    const parts = feeParts(method, language);
                    return (
                      <li key={method.id}>
                        <button
                          aria-pressed={method.id === selectedMethod}
                          className={
                            method.id === selectedMethod
                              ? "checkout-period is-selected"
                              : "checkout-period"
                          }
                          disabled={checkout.isPending || order.minimumOnlinePaymentBlocked}
                          onClick={() => setSelectedMethod(method.id)}
                          type="button"
                        >
                          <span className="checkout-period-info">
                            <span>
                              {method.icon && (
                                <span aria-hidden="true">
                                  {method.icon}{" "}
                                </span>
                              )}
                              {method.name}
                            </span>
                            {parts && (
                              <small className="checkout-period-fee">
                                {labels.feePartsPrefix}
                                {parts}
                              </small>
                            )}
                          </span>
                          <strong>
                            {BigInt(method.handlingFee) > 0n ? (
                               `+${formatMinorMoney(
                                method.handlingFee,
                                method.currency,
                                language
                              )}`
                            ) : (
                              labels.noFee
                            )}
                          </strong>
                        </button>
                      </li>
                    );
                  })}
                </ul>

                {checkout.isError && (
                  <p className="checkout-error">
                    {checkout.error instanceof ApiError
                      ? checkout.error.message
                      : labels.payFailed}
                  </p>
                )}

                <button
                  className="checkout-submit"
                  disabled={!chosen || checkout.isPending || order.minimumOnlinePaymentBlocked}
                  onClick={() => void checkout.mutate(selectedMethod)}
                  type="button"
                >
                  {checkout.isPending ? labels.paying : labels.pay}
                </button>
                <button
                  className="text-button"
                  disabled={cancel.isPending}
                  onClick={() => {
                    cancel.reset();
                    setClosing({ tradeNo: order.tradeNo });
                  }}
                  type="button"
                >
                  {labels.close}
                </button>
              </section>
            )}
          </div>
        </div>
      )}

      {closing && (
        <div className="plan-editor-backdrop" role="presentation">
          <div
            aria-modal="true"
            className="plan-editor"
            role="dialog"
          >
            <h2>{labels.closeConfirmTitle}</h2>
            <p>{order?.minimumOnlinePaymentBlocked ? labels.minimumBlockedOffline : labels.closeConfirm}</p>
            {cancel.isError && (
              <p className="admin-operation-error">
                {cancel.error instanceof ApiError
                  ? cancel.error.message
                  : labels.closeFailed}
              </p>
            )}
            <div className="plan-row-actions">
              <button
                className="danger-button"
                disabled={cancel.isPending}
                onClick={() => void cancel.mutate(closing.tradeNo)}
                type="button"
              >
                {cancel.isPending ? labels.closing : order?.minimumOnlinePaymentBlocked ? labels.fullPaymentRetry : labels.confirm}
              </button>
              <button
                className="text-button"
                disabled={cancel.isPending}
                onClick={() => setClosing(null)}
                type="button"
              >
                {labels.dismiss}
              </button>
            </div>
          </div>
        </div>
      )}
    </AppShell>
  );
}

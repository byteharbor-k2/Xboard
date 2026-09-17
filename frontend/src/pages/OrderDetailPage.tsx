import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { AppShell } from "../components/AppShell";
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
  formatMoney,
  orderStatusLabel,
  orderTypeLabel
} from "../lib/subscription";
import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";
import type { ServiceOrder } from "../types";

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
    createdAt: "创建时间",
    paidAt: "支付时间",
    original: "套餐原价",
    discount: "优惠",
    surplus: "折抵",
    balance: "余额抵扣",
    surplusCredit: "返还余额",
    total: "订单金额",
    handling: "支付手续费",
    payable: "应付总额",
    method: "支付方式",
    methodEmpty: "暂无可用支付方式，请联系管理员。",
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
    createdAt: "Created",
    paidAt: "Paid",
    original: "Plan price",
    discount: "Discount",
    surplus: "Trade-in",
    balance: "Balance used",
    surplusCredit: "Balance returned",
    total: "Order total",
    handling: "Payment fee",
    payable: "Total to pay",
    method: "Payment method",
    methodEmpty: "No payment method is available; please contact an administrator.",
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
    enabled: order?.status === "PENDING" && Number(order.totalAmount) > 0
  });

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
              {labels.statusHint[order.status]}
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
                    {formatMoney(order.originalAmount, order.currency, language)}
                  </dd>
                </div>
                {Number(order.discountAmount) > 0 && (
                  <div className="is-deduction">
                    <dt>{labels.discount}</dt>
                    <dd>
                      −
                      {formatMoney(
                        order.discountAmount,
                        order.currency,
                        language
                      )}
                    </dd>
                  </div>
                )}
                {Number(order.surplusAmount) > 0 && (
                  <div className="is-deduction">
                    <dt>{labels.surplus}</dt>
                    <dd>
                      −
                      {formatMoney(
                        order.surplusAmount,
                        order.currency,
                        language
                      )}
                    </dd>
                  </div>
                )}
                {Number(order.balanceAmount) > 0 && (
                  <div className="is-deduction">
                    <dt>{labels.balance}</dt>
                    <dd>
                      −
                      {formatMoney(
                        order.balanceAmount,
                        order.currency,
                        language
                      )}
                    </dd>
                  </div>
                )}
                {Number(order.surplusCredit) > 0 && (
                  <div className="is-note">
                    <dt>{labels.surplusCredit}</dt>
                    <dd>
                      {formatMoney(
                        order.surplusCredit,
                        order.currency,
                        language
                      )}
                    </dd>
                  </div>
                )}
                {Number(order.handlingAmount) > 0 && (
                  <div>
                    <dt>{labels.handling}</dt>
                    <dd>
                      +
                      {formatMoney(
                        order.handlingAmount,
                        order.currency,
                        language
                      )}
                    </dd>
                  </div>
                )}
              </dl>
              <p className="checkout-total-label">{labels.payable}</p>
              <p className="checkout-total">
                {formatMoney(
                  String(
                    Number(order.totalAmount) + Number(order.handlingAmount)
                  ),
                  order.currency,
                  language
                )}{" "}
                <span>{order.currency}</span>
              </p>
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
                  <p className="muted">{labels.methodEmpty}</p>
                )}
                <ul className="checkout-periods">
                  {methods.data?.map((method) => (
                    <li key={method.id}>
                      <button
                        aria-pressed={method.id === selectedMethod}
                        className={
                          method.id === selectedMethod
                            ? "checkout-period is-selected"
                            : "checkout-period"
                        }
                        disabled={checkout.isPending}
                        onClick={() => setSelectedMethod(method.id)}
                        type="button"
                      >
                        <span>
                          {method.icon && (
                            <span aria-hidden="true">{method.icon} </span>
                          )}
                          {method.name}
                        </span>
                        <strong>
                          {Number(method.handlingFee) > 0 ? "+" : ""}
                          {formatMoney(
                            method.handlingFee,
                            method.currency,
                            language
                          )}
                        </strong>
                      </button>
                    </li>
                  ))}
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
                  disabled={!chosen || checkout.isPending}
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
            <p>{labels.closeConfirm}</p>
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
                {cancel.isPending ? labels.closing : labels.confirm}
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

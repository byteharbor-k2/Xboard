import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import {
  cancelOrder,
  listOrders,
  settleOrder,
  type AdminOrder
} from "../admin/orderManagementApi";
import { AdminShell } from "../components/AdminShell";
import { ApiError } from "../lib/http";
import { formatMoney, orderStatusLabel, orderTypeLabel } from "../lib/subscription";
import { useAdminAuthStore } from "../store/adminAuth";
import { useAdminPreferences } from "../store/adminPreferences";
import type { OrderStatus } from "../types";

const STATUS_FILTERS: Array<OrderStatus | "ALL"> = [
  "ALL",
  "PENDING",
  "PROCESSING",
  "COMPLETED",
  "CANCELLED",
  "DISCOUNTED"
];

const copy = {
  "zh-CN": {
    eyebrow: "订阅与交易",
    title: "订单管理",
    description: "查询订单，并手动开通或取消。",
    orderNumber: "订单号",
    account: "用户",
    plan: "套餐",
    period: "周期",
    amount: "金额",
    status: "状态",
    createdAt: "创建时间",
    actions: "操作",
    filter: "状态筛选",
    all: "全部状态",
    loading: "正在加载订单…",
    empty: "没有符合条件的订单。",
    operationFailed: "操作失败",
    open: "开通",
    opening: "开通中…",
    cancel: "取消",
    confirmOpenTitle: "手动开通订单",
    confirmOpenBody:
      "这会给用户发放套餐权益，且不经过任何支付。仅用于线下转账或人工补单。",
    confirmCancelTitle: "取消订单",
    confirmCancelBody: "取消后订单不可恢复，已占用的余额和优惠券会退回。",
    confirm: "确认",
    close: "关闭"
  },
  "en-US": {
    eyebrow: "Subscriptions & finance",
    title: "Orders",
    description: "Review orders and open or cancel them by hand.",
    orderNumber: "Order",
    account: "Account",
    plan: "Plan",
    period: "Period",
    amount: "Amount",
    status: "Status",
    createdAt: "Created",
    actions: "Actions",
    filter: "Status",
    all: "All statuses",
    loading: "Loading orders…",
    empty: "No orders match this filter.",
    operationFailed: "Operation failed",
    open: "Open",
    opening: "Opening…",
    cancel: "Cancel",
    confirmOpenTitle: "Open order by hand",
    confirmOpenBody:
      "This grants the plan to the customer without any payment. Use it for bank transfers and manual corrections only.",
    confirmCancelTitle: "Cancel order",
    confirmCancelBody:
      "A cancelled order cannot be reopened. Any balance and coupon it held are released.",
    confirm: "Confirm",
    close: "Close"
  }
};

function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback;
}

function formatEpoch(value: number | null, language: "zh-CN" | "en-US") {
  if (!value) {
    return "—";
  }
  return new Intl.DateTimeFormat(language, {
    dateStyle: "medium",
    timeStyle: "short"
  }).format(new Date(value * 1000));
}

export function AdminOrdersPage() {
  const language = useAdminPreferences((state) => state.language);
  const token = useAdminAuthStore((state) => state.accessToken)!;
  const text = copy[language];
  const client = useQueryClient();
  const [status, setStatus] = useState<OrderStatus | "ALL">("PENDING");
  const [error, setError] = useState("");
  const [pendingAction, setPendingAction] = useState<{
    order: AdminOrder;
    kind: "open" | "cancel";
  } | null>(null);

  const ordersQuery = useQuery({
    queryKey: ["admin", "orders", status],
    queryFn: () => listOrders(token, status === "ALL" ? null : status)
  });

  const settleMutation = useMutation({
    mutationFn: (tradeNo: string) => settleOrder(token, tradeNo),
    onSuccess: () => client.invalidateQueries({ queryKey: ["admin", "orders"] })
  });
  const cancelMutation = useMutation({
    mutationFn: (tradeNo: string) => cancelOrder(token, tradeNo),
    onSuccess: () => client.invalidateQueries({ queryKey: ["admin", "orders"] })
  });
  const busy = settleMutation.isPending || cancelMutation.isPending;

  async function run() {
    if (!pendingAction) {
      return;
    }
    setError("");
    try {
      if (pendingAction.kind === "open") {
        await settleMutation.mutateAsync(pendingAction.order.trade_no);
      } else {
        await cancelMutation.mutateAsync(pendingAction.order.trade_no);
      }
      setPendingAction(null);
    } catch (caught) {
      setError(errorMessage(caught, text.operationFailed));
    }
  }

  const loadError = ordersQuery.error
    ? errorMessage(ordersQuery.error, text.operationFailed)
    : "";
  const orders = ordersQuery.data ?? [];

  return (
    <AdminShell>
      <header className="admin-page-heading">
        <div>
          <p>{text.eyebrow}</p>
          <h1>{text.title}</h1>
          <span>{text.description}</span>
        </div>
      </header>
      {error || loadError ? (
        <p className="admin-operation-error">{error || loadError}</p>
      ) : null}
      <section className="admin-card" style={{ paddingBottom: 4 }}>
        <div style={{ display: "flex", gap: 12, padding: "18px 22px 0" }}>
          <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <span style={{ color: "#707c93" }}>{text.filter}</span>
            <select
              onChange={(event) => {
                setStatus(event.target.value as OrderStatus | "ALL");
                setError("");
              }}
              style={{
                padding: "10px 13px",
                border: "1px solid #dfe5ee",
                borderRadius: 9,
                font: "inherit"
              }}
              value={status}
            >
              {STATUS_FILTERS.map((option) => (
                <option key={option} value={option}>
                  {option === "ALL"
                    ? text.all
                    : orderStatusLabel(option, language)}
                </option>
              ))}
            </select>
          </label>
        </div>
        <div className="admin-table-wrap">
          {ordersQuery.isPending ? (
            <p className="admin-table-empty">{text.loading}</p>
          ) : orders.length === 0 ? (
            <p className="admin-table-empty">{text.empty}</p>
          ) : (
            <table className="admin-table">
              <thead>
                <tr>
                  <th>{text.orderNumber}</th>
                  <th>{text.account}</th>
                  <th>{text.plan}</th>
                  <th>{text.period}</th>
                  <th>{text.amount}</th>
                  <th>{text.status}</th>
                  <th>{text.createdAt}</th>
                  <th>{text.actions}</th>
                </tr>
              </thead>
              <tbody>
                {orders.map((order) => (
                  <tr key={order.trade_no}>
                    <td className="order-number">{order.trade_no}</td>
                    <td>{order.email}</td>
                    <td>
                      <strong>{order.plan_name}</strong>
                      <span className="order-type-tag">
                        {orderTypeLabel(order.order_type, language)}
                      </span>
                    </td>
                    <td>{order.period}</td>
                    <td>
                      {formatMoney(
                        String(order.total_amount),
                        order.currency,
                        language
                      )}
                    </td>
                    <td>
                      <span
                        className={`status-pill order-status-${order.status.toLowerCase()}`}
                      >
                        {orderStatusLabel(order.status, language)}
                      </span>
                    </td>
                    <td>{formatEpoch(order.created_at, language)}</td>
                    <td>
                      <div className="machine-actions">
                        {order.status === "PENDING" ? (
                          <>
                            <button
                              disabled={busy}
                              onClick={() => {
                                setError("");
                                setPendingAction({ order, kind: "open" });
                              }}
                              type="button"
                            >
                              {text.open}
                            </button>
                            <button
                              className="danger"
                              disabled={busy}
                              onClick={() => {
                                setError("");
                                setPendingAction({ order, kind: "cancel" });
                              }}
                              type="button"
                            >
                              {text.cancel}
                            </button>
                          </>
                        ) : (
                          <span style={{ color: "#8b97a8" }}>—</span>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </section>
      {pendingAction ? (
        <div className="admin-modal-backdrop" role="presentation">
          <section
            aria-label={
              pendingAction.kind === "open"
                ? text.confirmOpenTitle
                : text.confirmCancelTitle
            }
            className="admin-modal machine-modal"
          >
            <header>
              <h2>
                {pendingAction.kind === "open"
                  ? text.confirmOpenTitle
                  : text.confirmCancelTitle}
              </h2>
              <button
                aria-label={text.close}
                disabled={busy}
                onClick={() => setPendingAction(null)}
                type="button"
              >
                ×
              </button>
            </header>
            <div className="machine-form">
              <p
                style={{
                  margin: 0,
                  color:
                    pendingAction.kind === "open" ? "#4c5a72" : "#6d3740"
                }}
              >
                {pendingAction.kind === "open"
                  ? text.confirmOpenBody
                  : text.confirmCancelBody}
              </p>
              <strong>
                {pendingAction.order.trade_no} ·{" "}
                {pendingAction.order.plan_name} ·{" "}
                {formatMoney(
                  String(pendingAction.order.total_amount),
                  pendingAction.order.currency,
                  language
                )}
              </strong>
              {error ? (
                <p className="admin-operation-error">{error}</p>
              ) : null}
            </div>
            <footer>
              <button
                disabled={busy}
                onClick={() => setPendingAction(null)}
                type="button"
              >
                {text.close}
              </button>
              <button
                className="primary"
                disabled={busy}
                onClick={() => void run()}
                style={
                  pendingAction.kind === "cancel"
                    ? { background: "#d94f5e", borderColor: "#d94f5e" }
                    : undefined
                }
                type="button"
              >
                {pendingAction.kind === "open" && settleMutation.isPending
                  ? text.opening
                  : text.confirm}
              </button>
            </footer>
          </section>
        </div>
      ) : null}
    </AdminShell>
  );
}

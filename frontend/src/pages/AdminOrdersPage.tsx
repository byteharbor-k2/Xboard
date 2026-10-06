import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import {
  cancelOrder,
  getOrderDetail,
  listOrders,
  settleOrder,
  updateCommissionStatus,
  type AdminOrderDetail,
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
    commission: "佣金",
    commissionFilter: "仅佣金订单",
    commissionStatusFilter: "佣金状态",
    commissionAll: "全部佣金状态",
    commissionPending: "待确认",
    commissionQueued: "已确认待入账",
    commissionCredited: "已入账",
    commissionInvalid: "无效",
    commissionLegacy: "历史订单：无佣金记录",
    noInviter: "无邀请人，不产生佣金",
    commissionBase: "返佣基数",
    commissionExpected: "佣金金额",
    commissionActual: "实际入账",
    detail: "佣金详情",
    detailTitle: "订单佣金详情",
    loadingDetail: "正在加载佣金详情…",
    detailFailed: "佣金详情加载失败",
    payoutLog: "实际入账记录",
    recipient: "收款用户 ID",
    buyer: "下单用户 ID",
    creditedAmount: "入账金额",
    level: "层级",
    createdAtLog: "入账时间",
    noPayouts: "暂无实际入账记录。",
    setPending: "设为待确认",
    confirmQueue: "确认并排队入账",
    markInvalid: "标记无效",
    stateSaved: "佣金状态已更新。入账仍由自动结算任务执行。",
    paidReadOnly: "此佣金已入账，状态不可更改。",
    close: "关闭",
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
    confirm: "确认"
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
    commission: "Commission",
    commissionFilter: "Commission orders only",
    commissionStatusFilter: "Commission status",
    commissionAll: "All commission statuses",
    commissionPending: "Pending confirmation",
    commissionQueued: "Confirmed, awaiting credit",
    commissionCredited: "Credited",
    commissionInvalid: "Invalid",
    commissionLegacy: "Legacy order: no commission record",
    noInviter: "No inviter; no commission",
    commissionBase: "Commission base",
    commissionExpected: "Commission amount",
    commissionActual: "Actually credited",
    detail: "Commission details",
    detailTitle: "Order commission details",
    loadingDetail: "Loading commission details…",
    detailFailed: "Failed to load commission details",
    payoutLog: "Actual payout records",
    recipient: "Recipient user ID",
    buyer: "Buyer user ID",
    creditedAmount: "Credited amount",
    level: "Level",
    createdAtLog: "Credited at",
    noPayouts: "No actual commission credits.",
    setPending: "Mark pending",
    confirmQueue: "Confirm and queue credit",
    markInvalid: "Mark invalid",
    stateSaved: "Commission state updated. Credit is still handled by the automatic settlement job.",
    paidReadOnly: "This commission has been credited; its state cannot be changed.",
    close: "Close",
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
    confirm: "Confirm"
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

function commissionStatusLabel(status: number, text: (typeof copy)["zh-CN"]) {
  switch (status) {
    case 0: return text.commissionPending;
    case 1: return text.commissionQueued;
    case 2: return text.commissionCredited;
    case 3: return text.commissionInvalid;
    default: return "—";
  }
}

export function AdminOrdersPage() {
  const language = useAdminPreferences((state) => state.language);
  const token = useAdminAuthStore((state) => state.accessToken)!;
  const text = copy[language];
  const client = useQueryClient();
  const [status, setStatus] = useState<OrderStatus | "ALL">("PENDING");
  const [commissionOnly, setCommissionOnly] = useState(false);
  const [commissionStatus, setCommissionStatus] = useState<number | "ALL">("ALL");
  const [error, setError] = useState("");
  const [selectedOrder, setSelectedOrder] = useState<AdminOrder | null>(null);
  const [pendingAction, setPendingAction] = useState<{
    order: AdminOrder;
    kind: "open" | "cancel";
  } | null>(null);

  const ordersQuery = useQuery({
    queryKey: ["admin", "orders", status, commissionOnly, commissionStatus],
    queryFn: () => listOrders(token, status === "ALL" ? null : status, 100, {
      isCommission: commissionOnly,
      commissionStatus: commissionStatus === "ALL" ? null : commissionStatus
    })
  });
  const detailQuery = useQuery({
    queryKey: ["admin", "order-detail", selectedOrder?.trade_no],
    queryFn: () => getOrderDetail(token, selectedOrder!.trade_no),
    enabled: Boolean(selectedOrder),
    retry: false
  });

  const settleMutation = useMutation({
    mutationFn: (tradeNo: string) => settleOrder(token, tradeNo),
    onSuccess: () => client.invalidateQueries({ queryKey: ["admin", "orders"] })
  });
  const cancelMutation = useMutation({
    mutationFn: (tradeNo: string) => cancelOrder(token, tradeNo),
    onSuccess: () => client.invalidateQueries({ queryKey: ["admin", "orders"] })
  });
  const commissionMutation = useMutation({
    mutationFn: ({ tradeNo, commissionStatus: nextStatus }: { tradeNo: string; commissionStatus: 0 | 1 | 3 }) =>
      updateCommissionStatus(token, tradeNo, nextStatus),
    onSuccess: async () => {
      setError(text.stateSaved);
      await Promise.all([
        client.invalidateQueries({ queryKey: ["admin", "orders"] }),
        client.invalidateQueries({ queryKey: ["admin", "order-detail", selectedOrder?.trade_no] })
      ]);
    },
    onError: (caught) => setError(errorMessage(caught, text.operationFailed))
  });
  const busy = settleMutation.isPending || cancelMutation.isPending || commissionMutation.isPending;

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
  const detail: AdminOrderDetail | undefined = detailQuery.data;

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
        <div style={{ display: "flex", gap: 12, padding: "18px 22px 0", flexWrap: "wrap", alignItems: "center" }}>
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
          <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <input
              checked={commissionOnly}
              onChange={(event) => setCommissionOnly(event.target.checked)}
              type="checkbox"
            />
            <span style={{ color: "#707c93" }}>{text.commissionFilter}</span>
          </label>
          <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <span style={{ color: "#707c93" }}>{text.commissionStatusFilter}</span>
            <select
              onChange={(event) => setCommissionStatus(event.target.value === "ALL" ? "ALL" : Number(event.target.value))}
              style={{ padding: "10px 13px", border: "1px solid #dfe5ee", borderRadius: 9, font: "inherit" }}
              value={commissionStatus}
            >
              <option value="ALL">{text.commissionAll}</option>
              <option value={0}>{text.commissionPending}</option>
              <option value={1}>{text.commissionQueued}</option>
              <option value={2}>{text.commissionCredited}</option>
              <option value={3}>{text.commissionInvalid}</option>
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
                  <th>{text.commission}</th>
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
                      {order.commission_status === null
                        ? order.invite_user_id
                          ? text.commissionLegacy
                          : text.noInviter
                        : <>
                            <strong>{commissionStatusLabel(order.commission_status, text)}</strong>
                            <small style={{ display: "block", color: "#707c93" }}>
                              {text.commissionBase}: {formatMoney(String(order.commission_base), order.currency, language)}<br />
                              {text.commissionExpected}: {formatMoney(String(order.commission_balance), order.currency, language)}<br />
                              {text.commissionActual}: {formatMoney(String(order.actual_commission_balance), order.currency, language)}
                            </small>
                          </>}
                    </td>
                    <td>
                      <div className="machine-actions">
                        <button
                          onClick={() => {
                            setError("");
                            setSelectedOrder(order);
                          }}
                          type="button"
                        >
                          {text.detail}
                        </button>
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
      {selectedOrder ? (
        <div className="admin-modal-backdrop" role="presentation">
          <section aria-label={text.detailTitle} className="admin-modal machine-modal">
            <header>
              <h2>{text.detailTitle}</h2>
              <button aria-label={text.close} onClick={() => setSelectedOrder(null)} type="button">×</button>
            </header>
            <div className="machine-form">
              <strong>{selectedOrder.trade_no}</strong>
              {detailQuery.isPending ? <p>{text.loadingDetail}</p> : null}
              {detailQuery.isError ? (
                <p className="admin-operation-error">{errorMessage(detailQuery.error, text.detailFailed)}</p>
              ) : null}
              {detail ? (
                <>
                  <p>
                    {detail.commission_status === null
                      ? detail.invite_user_id ? text.commissionLegacy : text.noInviter
                      : commissionStatusLabel(detail.commission_status, text)}
                  </p>
                  {detail.commission_status === null ? null : (
                    <p>
                      {text.commissionBase}: {formatMoney(String(detail.commission_base), detail.currency, language)} · {text.commissionExpected}: {formatMoney(String(detail.commission_balance), detail.currency, language)} · {text.commissionActual}: {formatMoney(String(detail.actual_commission_balance), detail.currency, language)}
                    </p>
                  )}
                  <h3>{text.payoutLog}</h3>
                  {detail.commission_log.length === 0 ? <p>{text.noPayouts}</p> : (
                    <div className="admin-table-wrap">
                      <table className="admin-table">
                        <thead><tr><th>{text.recipient}</th><th>{text.buyer}</th><th>{text.creditedAmount}</th><th>{text.level}</th><th>{text.createdAtLog}</th></tr></thead>
                        <tbody>
                          {detail.commission_log.map((log) => (
                            <tr key={log.id}>
                              <td>{log.invite_user_id}</td>
                              <td>{log.user_id}</td>
                              <td>{formatMoney(String(log.get_amount), detail.currency, language)}</td>
                              <td>{log.level}</td>
                              <td>{formatEpoch(log.created_at, language)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                  {detail.commission_status === 2 ? <p>{text.paidReadOnly}</p> : null}
                  {detail.commission_status !== null && detail.commission_status !== 2 && detail.invite_user_id !== null && detail.commission_balance > 0 ? (
                    <div className="machine-actions">
                      <button disabled={commissionMutation.isPending} onClick={() => commissionMutation.mutate({ tradeNo: detail.trade_no, commissionStatus: 0 })} type="button">{text.setPending}</button>
                      <button disabled={commissionMutation.isPending} onClick={() => commissionMutation.mutate({ tradeNo: detail.trade_no, commissionStatus: 1 })} type="button">{text.confirmQueue}</button>
                      <button className="danger" disabled={commissionMutation.isPending} onClick={() => commissionMutation.mutate({ tradeNo: detail.trade_no, commissionStatus: 3 })} type="button">{text.markInvalid}</button>
                    </div>
                  ) : null}
                  {error ? <p className="admin-operation-error">{error}</p> : null}
                </>
              ) : null}
            </div>
            <footer><button onClick={() => setSelectedOrder(null)} type="button">{text.close}</button></footer>
          </section>
        </div>
      ) : null}
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

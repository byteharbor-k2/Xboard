import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { AppShell } from "../components/AppShell";
import { cancelOrder, fetchViewerOrders } from "../lib/orders";
import { ApiError } from "../lib/http";
import {
  billingPeriodLabel,
  formatDateTime,
  formatMoney,
  orderStatusLabel,
  orderTypeLabel
} from "../lib/subscription";
import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";

const copy = {
  "zh-CN": {
    eyebrow: "BILLING",
    title: "我的订单",
    description: "查看套餐订单、支付状态和创建时间。",
    orderNumber: "订单号",
    plan: "套餐",
    period: "周期",
    amount: "订单金额",
    status: "订单状态",
    createdAt: "创建时间",
    action: "操作",
    empty: "暂无订单",
    emptyDescription: "购买套餐后，订单记录会显示在这里。",
    loading: "正在读取订单…",
    failed: "订单读取失败。",
    cancel: "取消订单",
    cancelling: "正在取消…",
    cancelFailed: "订单取消失败。",
    notCancellable: "—",
    pendingHint: "订单已创建，等待开通。"
  },
  "en-US": {
    eyebrow: "BILLING",
    title: "My orders",
    description: "Review plan orders, payment status, and creation time.",
    orderNumber: "Order",
    plan: "Plan",
    period: "Period",
    amount: "Amount",
    status: "Status",
    createdAt: "Created",
    action: "Action",
    empty: "No orders",
    emptyDescription: "Orders will appear here after you purchase a plan.",
    loading: "Loading orders…",
    failed: "Orders could not be loaded.",
    cancel: "Cancel order",
    cancelling: "Cancelling…",
    cancelFailed: "The order could not be cancelled.",
    notCancellable: "—",
    pendingHint: "Placed, waiting to be opened."
  }
};

export function OrdersPage() {
  const accessToken = useAuthStore((state) => state.accessToken)!;
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const queryClient = useQueryClient();
  const orders = useQuery({
    queryKey: ["viewer-orders"],
    queryFn: () => fetchViewerOrders(accessToken)
  });
  const cancel = useMutation({
    mutationFn: (tradeNo: string) => cancelOrder(accessToken, tradeNo),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ["viewer-orders"] })
  });

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">{labels.eyebrow}</p>
        <h1>{labels.title}</h1>
        <p className="muted">{labels.description}</p>
      </header>
      <section className="panel user-record-panel">
        {orders.isPending && <p className="muted">{labels.loading}</p>}
        {orders.isError && (
          <p className="error-message">
            {orders.error instanceof ApiError
              ? orders.error.message
              : labels.failed}
          </p>
        )}
        {cancel.isError && (
          <p className="error-message">
            {cancel.error instanceof ApiError
              ? cancel.error.message
              : labels.cancelFailed}
          </p>
        )}
        <div className="user-table-wrap">
          <table className="user-data-table">
            <thead>
              <tr>
                <th>{labels.orderNumber}</th>
                <th>{labels.plan}</th>
                <th>{labels.period}</th>
                <th>{labels.amount}</th>
                <th>{labels.status}</th>
                <th>{labels.createdAt}</th>
                <th>{labels.action}</th>
              </tr>
            </thead>
            <tbody>
              {orders.data?.length === 0 && (
                <tr>
                  <td colSpan={7}>
                    <div className="user-empty-state compact">
                      <span aria-hidden="true">▤</span>
                      <strong>{labels.empty}</strong>
                      <p>{labels.emptyDescription}</p>
                    </div>
                  </td>
                </tr>
              )}
              {orders.data?.map((order) => (
                <tr key={order.id}>
                  <td className="order-number">{order.tradeNo}</td>
                  <td>
                    {order.planName}
                    <span className="order-type-tag">
                      {orderTypeLabel(order.orderType, language)}
                    </span>
                  </td>
                  <td>{billingPeriodLabel(order.period, language)}</td>
                  <td>
                    {formatMoney(order.totalAmount, order.currency, language)}
                  </td>
                  <td>
                    <span
                      className={`status-pill order-status-${order.status.toLowerCase()}`}
                      title={
                        order.status === "PENDING"
                          ? labels.pendingHint
                          : undefined
                      }
                    >
                      {orderStatusLabel(order.status, language)}
                    </span>
                  </td>
                  <td>
                    {formatDateTime(order.createdAt, language)}
                  </td>
                  <td>
                    {order.status === "PENDING" ? (
                      <button
                        className="danger-button"
                        disabled={cancel.isPending}
                        onClick={() => cancel.mutate(order.tradeNo)}
                        type="button"
                      >
                        {cancel.isPending
                          ? labels.cancelling
                          : labels.cancel}
                      </button>
                    ) : (
                      <span className="muted">{labels.notCancellable}</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </AppShell>
  );
}

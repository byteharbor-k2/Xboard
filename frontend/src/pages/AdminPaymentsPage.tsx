import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";

import {
  deletePaymentMethod,
  fetchPaymentForm,
  listPaymentGateways,
  listPaymentMethods,
  savePaymentMethod,
  sortPaymentMethods,
  togglePaymentMethod,
  type AdminPaymentMethod,
  type GatewayField
} from "../admin/paymentManagementApi";
import { ConfirmBar, type ConfirmRequest } from "../components/ConfirmBar";
import { AdminShell } from "../components/AdminShell";
import { ApiError } from "../lib/http";
import { useAdminAuthStore } from "../store/adminAuth";
import { useAdminPreferences } from "../store/adminPreferences";

const copy = {
  "zh-CN": {
    eyebrow: "订阅与交易",
    title: "支付配置",
    description: "管理支付方式、手续费和启用状态。",
    create: "新建支付方式",
    loading: "正在加载支付方式…",
    loadFailed: "支付方式加载失败",
    empty: "还没有配置支付方式，配置并启用后用户才能下单支付。",
    name: "显示名称",
    gateway: "支付接口",
    fee: "手续费",
    noFee: "无",
    notifyUrl: "通知地址",
    notifyHint:
      "支付网关将会把数据通知到本地址，请通过防火墙放行本地址。",
    copy: "复制",
    copied: "已复制",
    enabled: "已启用",
    disabled: "已停用",
    actions: "操作",
    edit: "编辑",
    moveUp: "上移",
    moveDown: "下移",
    enable: "启用",
    disable: "停用",
    remove: "删除",
    removeConfirm: "确定删除这个支付方式吗？已被订单使用过的支付方式无法删除。",
    editTitle: "编辑支付方式",
    createTitle: "新建支付方式",
    baseInfo: "基本设置",
    gatewayLabel: "支付接口",
    gatewayHint: "本版本支持易支付（EPay）协议，法币与加密货币均通过该协议接入。",
    nameLabel: "显示名称",
    iconLabel: "图标",
    iconHint: "可填写 emoji 或图片地址，显示在用户的支付方式列表中。",
    notifyDomain: "通知域名",
    notifyDomainHint:
      "留空则使用站点地址；站点位于代理之后且网关无法直接访问时填写。",
    fixedFee: "固定手续费",
    fixedFeeHint: "按元填写，将加在用户实付金额上。留空表示不收。",
    percentFee: "百分比手续费",
    percentFeeHint: "按百分比填写，例如 2.5。",
    credentials: "接口参数",
    credentialsHint: "由支付接口提供，用于发起支付和校验回调签名。",
    secret: "该字段为敏感信息，不会显示在页面之外。",
    required: "必填",
    cancel: "取消",
    save: "保存",
    saving: "保存中…",
    operationFailed: "操作失败"
  },
  "en-US": {
    eyebrow: "Subscriptions & finance",
    title: "Payment methods",
    description: "Manage payment methods, handling fees, and availability.",
    create: "New payment method",
    loading: "Loading payment methods…",
    loadFailed: "Failed to load payment methods",
    empty:
      "No payment methods yet. Customers cannot pay until one is configured and enabled.",
    name: "Name",
    gateway: "Interface",
    fee: "Handling fee",
    noFee: "None",
    notifyUrl: "Notify URL",
    notifyHint:
      "The payment gateway will report payments to this address; allow it through your firewall.",
    copy: "Copy",
    copied: "Copied",
    enabled: "Enabled",
    disabled: "Disabled",
    actions: "Actions",
    edit: "Edit",
    moveUp: "Move up",
    moveDown: "Move down",
    enable: "Enable",
    disable: "Disable",
    remove: "Delete",
    removeConfirm:
      "Delete this payment method? One that an order has already used cannot be deleted.",
    editTitle: "Edit payment method",
    createTitle: "New payment method",
    baseInfo: "Settings",
    gatewayLabel: "Interface",
    gatewayHint:
      "This build speaks the Epay protocol, for fiat channels and crypto upstreams alike.",
    nameLabel: "Name",
    iconLabel: "Icon",
    iconHint:
      "An emoji or an image URL, shown in the customer's list of payment methods.",
    notifyDomain: "Notify domain",
    notifyDomainHint:
      "Leave blank to use the site address. Set it when the site sits behind a proxy the gateway cannot reach directly.",
    fixedFee: "Fixed fee",
    fixedFeeHint:
      "In major currency units, added to what the customer pays. Leave blank for none.",
    percentFee: "Percentage fee",
    percentFeeHint: "A whole percent, e.g. 2.5.",
    credentials: "Interface parameters",
    credentialsHint:
      "Issued by the payment provider, used to start a payment and to check the signature on a callback.",
    secret: "This field is sensitive and is not shown anywhere outside this page.",
    required: "Required",
    cancel: "Cancel",
    save: "Save",
    saving: "Saving…",
    operationFailed: "The operation failed"
  }
};

type FormState = {
  id?: string;
  payment: string;
  name: string;
  icon: string;
  notifyDomain: string;
  /** Major units in the form; converted to minor units on save. */
  fixedFee: string;
  percentFee: string;
  config: Record<string, string>;
};

function emptyForm(gateway: string): FormState {
  return {
    payment: gateway,
    name: "",
    icon: "",
    notifyDomain: "",
    fixedFee: "",
    percentFee: "",
    config: {}
  };
}

/** What the customer is charged on top, in words. */
function feeSummary(method: AdminPaymentMethod, language: "zh-CN" | "en-US") {
  const parts: string[] = [];
  if (method.handling_fee_fixed) {
    parts.push((method.handling_fee_fixed / 100).toFixed(2));
  }
  if (method.handling_fee_percent) {
    parts.push(`${method.handling_fee_percent}%`);
  }
  return parts.length
    ? parts.join(" + ")
    : language === "zh-CN"
      ? copy["zh-CN"].noFee
      : copy["en-US"].noFee;
}

export function AdminPaymentsPage() {
  const accessToken = useAdminAuthStore((state) => state.accessToken)!;
  const language = useAdminPreferences((state) => state.language);
  const text = copy[language];
  const queryClient = useQueryClient();

  const [form, setForm] = useState<FormState | undefined>(undefined);
  const [formError, setFormError] = useState("");
  const [pageError, setPageError] = useState("");
  const [copiedId, setCopiedId] = useState("");
  const [pendingConfirmation, setPendingConfirmation] =
    useState<ConfirmRequest | null>(null);
  const [confirming, setConfirming] = useState(false);

  const methodsQuery = useQuery({
    queryKey: ["admin", "payment-methods"],
    queryFn: () => listPaymentMethods(accessToken)
  });
  const gatewaysQuery = useQuery({
    queryKey: ["admin", "payment-gateways"],
    queryFn: () => listPaymentGateways(accessToken)
  });
  const formQuery = useQuery({
    queryKey: ["admin", "payment-form", form?.payment, form?.id],
    queryFn: () => fetchPaymentForm(accessToken, form!.payment, form!.id),
    enabled: Boolean(form)
  });

  function refresh() {
    void queryClient.invalidateQueries({
      queryKey: ["admin", "payment-methods"]
    });
  }

  const saveMutation = useMutation({
    mutationFn: (draft: FormState) =>
      savePaymentMethod(accessToken, {
        id: draft.id,
        payment: draft.payment,
        name: draft.name,
        icon: draft.icon,
        notify_domain: draft.notifyDomain,
        handling_fee_fixed: draft.fixedFee.trim()
          ? Math.round(Number.parseFloat(draft.fixedFee) * 100)
          : null,
        handling_fee_percent: draft.percentFee.trim()
          ? Number.parseFloat(draft.percentFee)
          : null,
        config: draft.config
      }),
    onSuccess: () => {
      setForm(undefined);
      refresh();
    }
  });

  const toggleMutation = useMutation({
    mutationFn: (id: string) => togglePaymentMethod(accessToken, id),
    onSuccess: refresh
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deletePaymentMethod(accessToken, id),
    onSuccess: refresh
  });

  /**
   * The order here is the order a customer sees their choices in, so it is
   * worth adjusting without having to re-create the methods.
   */
  const sortMutation = useMutation({
    mutationFn: (ids: string[]) => sortPaymentMethods(accessToken, ids),
    onSuccess: refresh
  });

  const methods = methodsQuery.data ?? [];
  const gateways = Object.entries(gatewaysQuery.data ?? {});
  const fields: Record<string, GatewayField> = formQuery.data ?? {};

  function openCreate() {
    setFormError("");
    setForm(emptyForm(gateways[0]?.[0] ?? "EPay"));
  }

  function openEdit(method: AdminPaymentMethod) {
    setFormError("");
    setForm({
      id: method.id,
      payment: method.payment,
      name: method.name,
      icon: method.icon ?? "",
      notifyDomain: method.notify_domain ?? "",
      fixedFee: method.handling_fee_fixed
        ? (method.handling_fee_fixed / 100).toFixed(2)
        : "",
      percentFee: method.handling_fee_percent?.toString() ?? "",
      config: method.config
    });
  }

  function update<K extends keyof FormState>(field: K, value: FormState[K]) {
    setForm((current) => (current ? { ...current, [field]: value } : current));
  }

  function updateConfig(key: string, value: string) {
    setForm((current) =>
      current
        ? { ...current, config: { ...current.config, [key]: value } }
        : current
    );
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!form) return;
    setFormError("");
    saveMutation.mutate(form, {
      onError: (error) =>
        setFormError(errorMessage(error, text.operationFailed))
    });
  }

  function remove(method: AdminPaymentMethod) {
    setPendingConfirmation({
      message: `${method.name} — ${text.removeConfirm}`,
      confirmLabel: text.remove,
      danger: true,
      run: () => deleteMutation.mutateAsync(method.id)
    });
  }

  async function confirmPendingAction() {
    if (!pendingConfirmation || confirming) return;
    setConfirming(true);
    setPageError("");
    try {
      await pendingConfirmation.run();
      setPendingConfirmation(null);
    } catch (error) {
      setPageError(errorMessage(error, text.operationFailed));
    } finally {
      setConfirming(false);
    }
  }

  function move(index: number, by: number) {
    const target = index + by;
    if (target < 0 || target >= methods.length) return;
    const ids = methods.map((method) => method.id);
    [ids[index], ids[target]] = [ids[target], ids[index]];
    sortMutation.mutate(ids);
  }

  async function copyNotifyUrl(method: AdminPaymentMethod) {
    try {
      await navigator.clipboard.writeText(method.notify_url);
      setCopiedId(method.id);
      window.setTimeout(() => setCopiedId(""), 2000);
    } catch {
      // A browser that refuses clipboard access still shows the address.
      setCopiedId("");
    }
  }

  return (
    <AdminShell>
      <header className="admin-page-heading">
        <div>
          <p>{text.eyebrow}</p>
          <h1>{text.title}</h1>
          <span>{text.description}</span>
        </div>
        <button
          className="plan-primary-button"
          disabled={!gatewaysQuery.isSuccess}
          onClick={openCreate}
          type="button"
        >
          ＋ {text.create}
        </button>
      </header>

      {pendingConfirmation && (
        <ConfirmBar
          busy={confirming}
          language={language}
          onCancel={() => setPendingConfirmation(null)}
          onConfirm={() => void confirmPendingAction()}
          request={pendingConfirmation}
        />
      )}
      {pageError && <p className="admin-operation-error">{pageError}</p>}
      {(toggleMutation.isError || sortMutation.isError) && (
        <p className="admin-operation-error">
          {errorMessage(
            toggleMutation.isError ? toggleMutation.error : sortMutation.error,
            text.operationFailed
          )}
        </p>
      )}

      <section className="admin-card plan-management-card">
        {methodsQuery.isLoading ? (
          <div className="plan-empty-state">{text.loading}</div>
        ) : methodsQuery.isError ? (
          <div className="plan-empty-state error">{text.loadFailed}</div>
        ) : methods.length === 0 ? (
          <div className="plan-empty-state">{text.empty}</div>
        ) : (
          <div className="plan-table-scroll">
            <table className="plan-management-table">
              <thead>
                <tr>
                  <th>{text.name}</th>
                  <th>{text.gateway}</th>
                  <th>{text.fee}</th>
                  <th>{text.notifyUrl}</th>
                  <th>{text.enabled}</th>
                  <th>{text.actions}</th>
                </tr>
              </thead>
              <tbody>
                {methods.map((method, index) => (
                  <tr key={method.id}>
                    <td>
                      <strong>
                        {method.icon ? `${method.icon} ` : ""}
                        {method.name}
                      </strong>
                    </td>
                    <td>{method.payment}</td>
                    <td>{feeSummary(method, language)}</td>
                    <td>
                      <div className="payment-notify-cell">
                        <span className="order-number" title={text.notifyHint}>
                          {method.notify_url}
                        </span>
                        <button
                          className="text-button"
                          onClick={() => void copyNotifyUrl(method)}
                          type="button"
                        >
                          {copiedId === method.id ? text.copied : text.copy}
                        </button>
                      </div>
                    </td>
                    <td>
                      <div className="plan-status-stack">
                        <span className={method.enable ? "positive" : "neutral"}>
                          {method.enable ? text.enabled : text.disabled}
                        </span>
                      </div>
                    </td>
                    <td>
                      <div className="plan-row-actions">
                        <button onClick={() => openEdit(method)} type="button">
                          {text.edit}
                        </button>
                        <button
                          disabled={index === 0 || sortMutation.isPending}
                          onClick={() => move(index, -1)}
                          title={text.moveUp}
                          type="button"
                        >
                          ↑
                        </button>
                        <button
                          disabled={
                            index === methods.length - 1 ||
                            sortMutation.isPending
                          }
                          onClick={() => move(index, 1)}
                          title={text.moveDown}
                          type="button"
                        >
                          ↓
                        </button>
                        <button
                          disabled={toggleMutation.isPending}
                          onClick={() => toggleMutation.mutate(method.id)}
                          type="button"
                        >
                          {method.enable ? text.disable : text.enable}
                        </button>
                        <button
                          className="danger"
                          disabled={deleteMutation.isPending}
                          onClick={() => remove(method)}
                          type="button"
                        >
                          {text.remove}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {form && (
        <div className="plan-editor-backdrop">
          <form className="plan-editor" onSubmit={submit}>
            <header>
              <div>
                <p>{text.eyebrow}</p>
                <h2>{form.id ? text.editTitle : text.createTitle}</h2>
              </div>
              <button onClick={() => setForm(undefined)} type="button">
                ×
              </button>
            </header>
            <div className="plan-editor-body">
              <section>
                <h3>{text.baseInfo}</h3>
                <div className="plan-form-grid">
                  <label className="wide">
                    <span>{text.nameLabel}</span>
                    <input
                      maxLength={120}
                      required
                      value={form.name}
                      onChange={(event) =>
                        update("name", event.target.value)
                      }
                    />
                  </label>
                  <label className="wide">
                    <span>{text.gatewayLabel}</span>
                    <select
                      disabled={Boolean(form.id)}
                      value={form.payment}
                      onChange={(event) => {
                        setFormError("");
                        update("payment", event.target.value);
                      }}
                    >
                      {gateways.map(([code, label]) => (
                        <option key={code} value={code}>
                          {label}
                        </option>
                      ))}
                    </select>
                    <small>{text.gatewayHint}</small>
                  </label>
                  <label>
                    <span>{text.iconLabel}</span>
                    <input
                      placeholder="💳"
                      value={form.icon}
                      onChange={(event) =>
                        update("icon", event.target.value)
                      }
                    />
                  </label>
                  <label>
                    <span>{text.notifyDomain}</span>
                    <input
                      placeholder={text.notifyDomainHint}
                      value={form.notifyDomain}
                      onChange={(event) =>
                        update("notifyDomain", event.target.value)
                      }
                    />
                  </label>
                  <label>
                    <span>{text.fixedFee}</span>
                    <input
                      min="0"
                      placeholder={text.fixedFeeHint}
                      step="0.01"
                      type="number"
                      value={form.fixedFee}
                      onChange={(event) =>
                        update("fixedFee", event.target.value)
                      }
                    />
                  </label>
                  <label>
                    <span>{text.percentFee}</span>
                    <input
                      max="100"
                      min="0"
                      placeholder={text.percentFeeHint}
                      step="0.01"
                      type="number"
                      value={form.percentFee}
                      onChange={(event) =>
                        update("percentFee", event.target.value)
                      }
                    />
                  </label>
                </div>
              </section>

              <section>
                <h3>{text.credentials}</h3>
                <p className="muted">{text.credentialsHint}</p>
                {formQuery.isLoading ? (
                  <div className="plan-empty-state">{text.loading}</div>
                ) : (
                  <div className="plan-form-grid">
                    {Object.entries(fields).map(([key, field]) => (
                      <label className="wide" key={key}>
                        <span>
                          {field.label[language]}
                          {field.required && (
                            <em className="payment-field-required">
                              {" "}
                              {text.required}
                            </em>
                          )}
                        </span>
                        <input
                          required={field.required}
                          type={field.secret ? "password" : "text"}
                          placeholder={field.placeholder[language]}
                          value={form.config[key] ?? field.value ?? ""}
                          onChange={(event) =>
                            updateConfig(key, event.target.value)
                          }
                        />
                        <small>{field.description[language]}</small>
                      </label>
                    ))}
                  </div>
                )}
              </section>
            </div>
            <footer>
              {formError && <p className="admin-operation-error">{formError}</p>}
              <div className="plan-row-actions">
                <button
                  className="plan-primary-button"
                  disabled={saveMutation.isPending}
                  type="submit"
                >
                  {saveMutation.isPending ? text.saving : text.save}
                </button>
                <button
                  className="text-button"
                  onClick={() => setForm(undefined)}
                  type="button"
                >
                  {text.cancel}
                </button>
              </div>
            </footer>
          </form>
        </div>
      )}
    </AdminShell>
  );
}

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  return error instanceof Error ? error.message : fallback;
}

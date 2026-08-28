import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState, type FormEvent } from "react";

import {
  createManagedPlan,
  deleteManagedPlan,
  listManagedPlans,
  updateManagedPlan
} from "../admin/planManagementApi";
import { listNodeGroups } from "../admin/groupManagementApi";
import { AdminShell } from "../components/AdminShell";
import { ApiError } from "../lib/http";
import { useAdminAuthStore } from "../store/adminAuth";
import { ConfirmBar, type ConfirmRequest } from "../components/ConfirmBar";
import { useAdminPreferences } from "../store/adminPreferences";
import type {
  BillingPeriod,
  ManagedPlan,
  PlanDraft,
  PlanType,
  TrafficResetPolicy
} from "../types";

const GIB = 1_073_741_824n;
const subscriptionPeriods: BillingPeriod[] = [
  "MONTHLY",
  "QUARTERLY",
  "HALF_YEARLY",
  "YEARLY",
  "TWO_YEARLY",
  "THREE_YEARLY"
];
const packagePeriods: BillingPeriod[] = ["ONETIME", "RESET_TRAFFIC"];
const periods: BillingPeriod[] = [...subscriptionPeriods, ...packagePeriods];

const copy = {
  "zh-CN": {
    eyebrow: "订阅与交易",
    title: "套餐管理",
    description: "管理套餐权益、价格周期、容量及用户可见状态。",
    create: "新建套餐",
    loading: "正在加载套餐…",
    empty: "还没有套餐，创建后才会出现在用户订阅方案中。",
    loadFailed: "套餐数据加载失败",
    name: "套餐",
    quota: "流量",
    limits: "限速 / 设备",
    prices: "价格周期",
    subscribers: "订阅用户",
    state: "发布状态",
    actions: "操作",
    unlimited: "不限",
    active: "有效",
    total: "累计",
    published: "已发布",
    draft: "未发布",
    sellable: "可购买",
    unavailable: "停止销售",
    edit: "编辑",
    remove: "删除",
    removeConfirm: "确定删除这个套餐吗？已有订阅记录的套餐不会被删除。",
    editTitle: "编辑套餐",
    createTitle: "创建套餐",
    baseInfo: "基本信息",
    planType: "套餐类型",
    subscriptionType: "月订阅",
    subscriptionTypeHint: "按购买周期到期，并按所选规则重置流量",
    packageType: "流量包",
    packageTypeHint: "无时间限制，流量用完即止",
    planName: "套餐名称",
    descriptionLabel: "套餐说明",
    tags: "标签",
    tagsHint: "使用英文逗号分隔，最多 12 个",
    transferGb: "每周期流量（GB）",
    packageTransferGb: "流量包额度（GB）",
    speed: "限速（Mbps）",
    devices: "设备数",
    serverGroup: "服务器分组",
    serverGroupPlaceholder: "选择该套餐可使用的节点权限组",
    capacity: "可售容量",
    emptyUnlimited: "留空表示不限",
    resetPolicy: "流量重置规则",
    resettable: "允许购买流量重置",
    resettableHint: "开启后用户可以按重置价格恢复当前套餐的全部流量",
    purchaseLimit: "每位用户限购次数",
    purchaseLimitHint: "留空表示不限购",
    sortOrder: "排序值",
    pricing: "价格周期",
    pricingHint: "留空表示不提供该周期；金额使用主要货币单位。",
    packagePricingHint: "流量包价格必填；允许重置时，重置包价格也必须填写。",
    currency: "币种",
    availability: "发布与销售",
    publishToggle: "在用户目录发布",
    sellToggle: "允许新用户购买",
    renewToggle: "允许现有用户续费",
    cancel: "取消",
    save: "保存",
    saving: "保存中…",
    operationFailed: "操作失败",
    noPrices: "未设置价格"
  },
  "en-US": {
    eyebrow: "Subscriptions & finance",
    title: "Plans",
    description: "Manage plan benefits, billing periods, capacity, and catalog visibility.",
    create: "New plan",
    loading: "Loading plans…",
    empty: "No plans yet. Published plans will appear in the customer catalog.",
    loadFailed: "Failed to load plans",
    name: "Plan",
    quota: "Traffic",
    limits: "Speed / devices",
    prices: "Billing",
    subscribers: "Subscribers",
    state: "Availability",
    actions: "Actions",
    unlimited: "Unlimited",
    active: "Active",
    total: "Lifetime",
    published: "Published",
    draft: "Draft",
    sellable: "For sale",
    unavailable: "Not for sale",
    edit: "Edit",
    remove: "Delete",
    removeConfirm: "Delete this plan? Plans with subscription history cannot be deleted.",
    editTitle: "Edit plan",
    createTitle: "Create plan",
    baseInfo: "Plan details",
    planType: "Plan type",
    subscriptionType: "Monthly subscription",
    subscriptionTypeHint: "Expires by billing period and resets data by policy",
    packageType: "Traffic package",
    packageTypeHint: "No expiry; ends when its data is exhausted",
    planName: "Plan name",
    descriptionLabel: "Description",
    tags: "Tags",
    tagsHint: "Comma-separated, up to 12",
    transferGb: "Traffic per cycle (GB)",
    packageTransferGb: "Package data (GB)",
    speed: "Speed limit (Mbps)",
    devices: "Device limit",
    serverGroup: "Server group",
    serverGroupPlaceholder: "Choose the node group available to this plan",
    capacity: "Sales capacity",
    emptyUnlimited: "Leave blank for unlimited",
    resetPolicy: "Traffic reset policy",
    resettable: "Allow traffic reset purchase",
    resettableHint: "Users can restore the full plan allowance at the reset price",
    purchaseLimit: "Purchases per user",
    purchaseLimitHint: "Leave blank for unlimited purchases",
    sortOrder: "Sort order",
    pricing: "Billing periods",
    pricingHint: "Leave blank to disable a period. Enter major currency units.",
    packagePricingHint: "Package price is required; reset price is required when resets are enabled.",
    currency: "Currency",
    availability: "Publishing and sales",
    publishToggle: "Publish in customer catalog",
    sellToggle: "Allow new purchases",
    renewToggle: "Allow existing subscribers to renew",
    cancel: "Cancel",
    save: "Save",
    saving: "Saving…",
    operationFailed: "Operation failed",
    noPrices: "No prices"
  }
};

const periodLabels = {
  "zh-CN": {
    MONTHLY: "月付",
    QUARTERLY: "季付",
    HALF_YEARLY: "半年付",
    YEARLY: "年付",
    TWO_YEARLY: "两年付",
    THREE_YEARLY: "三年付",
    ONETIME: "流量包",
    RESET_TRAFFIC: "重置包"
  },
  "en-US": {
    MONTHLY: "Monthly",
    QUARTERLY: "Quarterly",
    HALF_YEARLY: "Half-year",
    YEARLY: "Yearly",
    TWO_YEARLY: "Two years",
    THREE_YEARLY: "Three years",
    ONETIME: "Traffic package",
    RESET_TRAFFIC: "Reset package"
  }
} satisfies Record<string, Record<BillingPeriod, string>>;

const resetLabels = {
  "zh-CN": {
    FIRST_DAY_OF_MONTH: "每月 1 日",
    MONTHLY_FROM_ACTIVATION: "按开通日每月",
    NEVER: "不重置",
    FIRST_DAY_OF_YEAR: "每年 1 月 1 日",
    YEARLY_FROM_ACTIVATION: "按开通日每年"
  },
  "en-US": {
    FIRST_DAY_OF_MONTH: "First day of month",
    MONTHLY_FROM_ACTIVATION: "Monthly from activation",
    NEVER: "Never",
    FIRST_DAY_OF_YEAR: "First day of year",
    YEARLY_FROM_ACTIVATION: "Yearly from activation"
  }
} satisfies Record<string, Record<TrafficResetPolicy, string>>;

type FormState = {
  planType: PlanType;
  name: string;
  description: string;
  tags: string;
  transferGb: string;
  speedLimitMbps: string;
  deviceLimit: string;
  serverGroupId: string;
  capacityLimit: string;
  resettable: boolean;
  purchaseLimitPerUser: string;
  resetPolicy: TrafficResetPolicy;
  sortOrder: string;
  currency: string;
  priceValues: Record<BillingPeriod, string>;
  published: boolean;
  sellable: boolean;
  renewable: boolean;
};

function blankPrices(): Record<BillingPeriod, string> {
  return Object.fromEntries(periods.map((period) => [period, ""])) as
    Record<BillingPeriod, string>;
}

function emptyForm(): FormState {
  return {
    planType: "SUBSCRIPTION",
    name: "",
    description: "",
    tags: "",
    transferGb: "100",
    speedLimitMbps: "",
    deviceLimit: "",
    serverGroupId: "",
    capacityLimit: "",
    resettable: false,
    purchaseLimitPerUser: "",
    resetPolicy: "MONTHLY_FROM_ACTIVATION",
    sortOrder: "0",
    currency: "CNY",
    priceValues: blankPrices(),
    published: false,
    sellable: false,
    renewable: true
  };
}

function formFromPlan(plan: ManagedPlan): FormState {
  const prices = blankPrices();
  for (const price of plan.prices) {
    prices[price.period] = (price.amountMinor / 100).toFixed(2);
  }
  return {
    planType: plan.planType,
    name: plan.name,
    description: plan.description,
    tags: plan.tags.join(", "),
    transferGb: (BigInt(plan.transferLimitBytes) / GIB).toString(),
    speedLimitMbps: plan.speedLimitMbps?.toString() ?? "",
    deviceLimit: plan.deviceLimit?.toString() ?? "",
    serverGroupId: plan.serverGroupId?.toString() ?? "",
    capacityLimit: plan.capacityLimit?.toString() ?? "",
    resettable: plan.resettable,
    purchaseLimitPerUser: plan.purchaseLimitPerUser?.toString() ?? "",
    resetPolicy: plan.resetPolicy,
    sortOrder: plan.sortOrder.toString(),
    currency: plan.prices[0]?.currency ?? "CNY",
    priceValues: prices,
    published: plan.published,
    sellable: plan.sellable,
    renewable: plan.renewable
  };
}

function optionalInteger(value: string) {
  return value.trim() ? Number.parseInt(value, 10) : null;
}

function toDraft(form: FormState): PlanDraft {
  const activePeriods = form.planType === "TRAFFIC_PACKAGE"
    ? packagePeriods.filter(
        (period) => period === "ONETIME" || form.resettable
      )
    : [
        ...subscriptionPeriods,
        ...(form.resettable ? ["RESET_TRAFFIC" as const] : [])
      ];
  const prices = activePeriods.flatMap((period) => {
    const value = form.priceValues[period].trim();
    return value
      ? [{
          period,
          amountMinor: Math.round(Number.parseFloat(value) * 100),
          currency: form.currency.trim().toUpperCase()
        }]
      : [];
  });
  return {
    name: form.name,
    description: form.description,
    tags: form.tags.split(",").map((tag) => tag.trim()).filter(Boolean),
    planType: form.planType,
    transferLimitBytes: (
      BigInt(form.transferGb.trim() || "0") * GIB
    ).toString(),
    speedLimitMbps: optionalInteger(form.speedLimitMbps),
    deviceLimit: optionalInteger(form.deviceLimit),
    serverGroupId: optionalInteger(form.serverGroupId),
    capacityLimit: optionalInteger(form.capacityLimit),
    resetPolicy: form.planType === "TRAFFIC_PACKAGE"
      ? "NEVER"
      : form.resetPolicy,
    resettable: form.resettable,
    purchaseLimitPerUser: form.planType === "TRAFFIC_PACKAGE"
      ? optionalInteger(form.purchaseLimitPerUser)
      : null,
    sortOrder: Number.parseInt(form.sortOrder || "0", 10),
    published: form.published,
    sellable: form.sellable,
    renewable: form.planType === "SUBSCRIPTION" && form.renewable,
    prices
  };
}

function displayTraffic(bytes: string) {
  return `${BigInt(bytes) / GIB} GB`;
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback;
}

export function AdminPlansPage() {
  const language = useAdminPreferences((state) => state.language);
  const accessToken = useAdminAuthStore((state) => state.accessToken);
  const queryClient = useQueryClient();
  const text = copy[language];
  const [pendingConfirmation, setPendingConfirmation] = useState<ConfirmRequest | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [pageError, setPageError] = useState("");
  const [editing, setEditing] = useState<ManagedPlan | null | undefined>();
  const [form, setForm] = useState<FormState>(emptyForm);
  const [formError, setFormError] = useState("");

  const plansQuery = useQuery({
    queryKey: ["admin", "plans"],
    queryFn: () => listManagedPlans(accessToken!),
    enabled: Boolean(accessToken)
  });

  const groupsQuery = useQuery({
    queryKey: ["admin", "node-groups"],
    queryFn: () => listNodeGroups(accessToken!),
    enabled: Boolean(accessToken)
  });

  const saveMutation = useMutation({
    mutationFn: (draft: PlanDraft) =>
      editing
        ? updateManagedPlan(accessToken!, editing.id, draft)
        : createManagedPlan(accessToken!, draft),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin", "plans"] });
      await queryClient.invalidateQueries({ queryKey: ["plan-offers"] });
      setEditing(undefined);
    }
  });

  const deleteMutation = useMutation({
    mutationFn: (planId: string) => deleteManagedPlan(accessToken!, planId),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ["admin", "plans"] })
  });

  const plans = useMemo(() => plansQuery.data ?? [], [plansQuery.data]);

  function openCreate() {
    setForm(emptyForm());
    setFormError("");
    setEditing(null);
  }

  function openEdit(plan: ManagedPlan) {
    setForm(formFromPlan(plan));
    setFormError("");
    setEditing(plan);
  }

  function updateForm<K extends keyof FormState>(
    field: K,
    value: FormState[K]
  ) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  function selectPlanType(planType: PlanType) {
    setForm((current) => ({
      ...current,
      planType,
      resetPolicy: planType === "TRAFFIC_PACKAGE"
        ? "NEVER"
        : current.resetPolicy === "NEVER"
          ? "MONTHLY_FROM_ACTIVATION"
          : current.resetPolicy,
      resettable: current.resettable,
      purchaseLimitPerUser: planType === "TRAFFIC_PACKAGE"
        ? current.purchaseLimitPerUser
        : "",
      renewable: planType === "SUBSCRIPTION"
    }));
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setFormError("");
    try {
      saveMutation.mutate(toDraft(form), {
        onError: (error) =>
          setFormError(errorMessage(error, text.operationFailed))
      });
    } catch (error) {
      setFormError(error instanceof Error ? error.message : text.operationFailed);
    }
  }

  function remove(plan: ManagedPlan) {
    setPendingConfirmation({
      message: `${plan.name} — ${text.removeConfirm}`,
      confirmLabel: text.remove,
      danger: true,
      run: () => deleteMutation.mutateAsync(plan.id)
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

  return (
    <AdminShell>
      <header className="admin-page-heading">
        <div>
          <p>{text.eyebrow}</p>
          <h1>{text.title}</h1>
          <span>{text.description}</span>
        </div>
        <button className="plan-primary-button" onClick={openCreate} type="button">
          ＋ {text.create}
        </button>
      </header>

      {pendingConfirmation && <ConfirmBar busy={confirming} language={language} onCancel={() => setPendingConfirmation(null)}
        onConfirm={() => void confirmPendingAction()} request={pendingConfirmation} />}
      {pageError && <p className="admin-operation-error">{pageError}</p>}

      <section className="admin-card plan-management-card">
        {plansQuery.isLoading ? (
          <div className="plan-empty-state">{text.loading}</div>
        ) : plansQuery.isError ? (
          <div className="plan-empty-state error">{text.loadFailed}</div>
        ) : plans.length === 0 ? (
          <div className="plan-empty-state">{text.empty}</div>
        ) : (
          <div className="plan-table-scroll">
            <table className="plan-management-table">
              <thead>
                <tr>
                  <th>{text.name}</th>
                  <th>{text.quota}</th>
                  <th>{text.limits}</th>
                  <th>{text.prices}</th>
                  <th>{text.subscribers}</th>
                  <th>{text.state}</th>
                  <th>{text.actions}</th>
                </tr>
              </thead>
              <tbody>
                {plans.map((plan) => (
                  <tr key={plan.id}>
                    <td>
                      <strong>{plan.name}</strong>
                      <small>{plan.description || "—"}</small>
                      <div className="plan-tags">
                        <span
                          className={`plan-type-badge ${
                            plan.planType === "TRAFFIC_PACKAGE"
                              ? "traffic-package"
                              : "subscription"
                          }`}
                        >
                          {plan.planType === "TRAFFIC_PACKAGE"
                            ? text.packageType
                            : text.subscriptionType}
                        </span>
                        {plan.tags
                          .filter((tag) => tag !== (
                            plan.planType === "TRAFFIC_PACKAGE"
                              ? text.packageType
                              : text.subscriptionType
                          ))
                          .map((tag) => <span key={tag}>{tag}</span>)}
                      </div>
                    </td>
                    <td>{displayTraffic(plan.transferLimitBytes)}</td>
                    <td>
                      <span>
                        {plan.speedLimitMbps
                          ? `${plan.speedLimitMbps} Mbps`
                          : text.unlimited}
                      </span>
                      <small>
                        {plan.deviceLimit
                          ? `${plan.deviceLimit} devices`
                          : text.unlimited}
                      </small>
                    </td>
                    <td>
                      <div className="plan-price-list">
                        {plan.prices.length ? plan.prices.map((price) => (
                          <span key={price.period}>
                            {periodLabels[language][price.period]} ·{" "}
                            {(price.amountMinor / 100).toFixed(2)}{" "}
                            {price.currency}
                          </span>
                        )) : <small>{text.noPrices}</small>}
                      </div>
                    </td>
                    <td>
                      <span>{text.active} {plan.activeSubscriberCount}</span>
                      <small>{text.total} {plan.subscriberCount}</small>
                    </td>
                    <td>
                      <div className="plan-status-stack">
                        <span className={plan.published ? "positive" : "neutral"}>
                          {plan.published ? text.published : text.draft}
                        </span>
                        <span className={plan.sellable ? "positive" : "warning"}>
                          {plan.sellable ? text.sellable : text.unavailable}
                        </span>
                      </div>
                    </td>
                    <td>
                      <div className="plan-row-actions">
                        <button onClick={() => openEdit(plan)} type="button">
                          {text.edit}
                        </button>
                        <button
                          className="danger"
                          disabled={deleteMutation.isPending}
                          onClick={() => remove(plan)}
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

      {editing !== undefined && (
        <div className="plan-editor-backdrop">
          <form className="plan-editor" onSubmit={submit}>
            <header>
              <div>
                <p>{text.eyebrow}</p>
                <h2>{editing ? text.editTitle : text.createTitle}</h2>
              </div>
              <button onClick={() => setEditing(undefined)} type="button">×</button>
            </header>
            <div className="plan-editor-body">
              <section>
                <h3>{text.baseInfo}</h3>
                <div className="plan-type-selector" aria-label={text.planType}>
                  <button
                    aria-pressed={form.planType === "SUBSCRIPTION"}
                    onClick={() => selectPlanType("SUBSCRIPTION")}
                    type="button"
                  >
                    <strong>{text.subscriptionType}</strong>
                    <span>{text.subscriptionTypeHint}</span>
                  </button>
                  <button
                    aria-pressed={form.planType === "TRAFFIC_PACKAGE"}
                    onClick={() => selectPlanType("TRAFFIC_PACKAGE")}
                    type="button"
                  >
                    <strong>{text.packageType}</strong>
                    <span>{text.packageTypeHint}</span>
                  </button>
                </div>
                <div className="plan-form-grid">
                  <label className="wide">
                    <span>{text.planName}</span>
                    <input
                      maxLength={120}
                      required
                      value={form.name}
                      onChange={(event) => updateForm("name", event.target.value)}
                    />
                  </label>
                  <label className="wide">
                    <span>{text.descriptionLabel}</span>
                    <textarea
                      rows={3}
                      value={form.description}
                      onChange={(event) =>
                        updateForm("description", event.target.value)
                      }
                    />
                  </label>
                  <label className="wide">
                    <span>{text.tags}</span>
                    <input
                      placeholder={text.tagsHint}
                      value={form.tags}
                      onChange={(event) => updateForm("tags", event.target.value)}
                    />
                  </label>
                  <label>
                    <span>
                      {form.planType === "TRAFFIC_PACKAGE"
                        ? text.packageTransferGb
                        : text.transferGb}
                    </span>
                    <input
                      min="1"
                      required
                      type="number"
                      value={form.transferGb}
                      onChange={(event) =>
                        updateForm("transferGb", event.target.value)
                      }
                    />
                  </label>
                  <label>
                    <span>{text.speed}</span>
                    <input
                      min="1"
                      placeholder={text.emptyUnlimited}
                      type="number"
                      value={form.speedLimitMbps}
                      onChange={(event) =>
                        updateForm("speedLimitMbps", event.target.value)
                      }
                    />
                  </label>
                  <label>
                    <span>{text.devices}</span>
                    <input
                      min="1"
                      placeholder={text.emptyUnlimited}
                      type="number"
                      value={form.deviceLimit}
                      onChange={(event) =>
                        updateForm("deviceLimit", event.target.value)
                      }
                    />
                  </label>
                  <label>
                    <span>{text.serverGroup}</span>
                    <select
                      value={form.serverGroupId}
                      onChange={(event) =>
                        updateForm("serverGroupId", event.target.value)
                      }
                    >
                      <option value="">{text.serverGroupPlaceholder}</option>
                      {(groupsQuery.data ?? []).map((group) => (
                        <option key={group.id} value={group.id}>{group.name}</option>
                      ))}
                    </select>
                  </label>
                  <label>
                    <span>{text.capacity}</span>
                    <input
                      min="1"
                      placeholder={text.emptyUnlimited}
                      type="number"
                      value={form.capacityLimit}
                      onChange={(event) =>
                        updateForm("capacityLimit", event.target.value)
                      }
                    />
                  </label>
                  {form.planType === "SUBSCRIPTION" ? (
                    <label>
                      <span>{text.resetPolicy}</span>
                      <select
                        value={form.resetPolicy}
                        onChange={(event) =>
                          updateForm(
                            "resetPolicy",
                            event.target.value as TrafficResetPolicy
                          )
                        }
                      >
                        {Object.entries(resetLabels[language])
                          .filter(([value]) => value !== "NEVER")
                          .map(([value, label]) => (
                            <option key={value} value={value}>{label}</option>
                          ))}
                      </select>
                    </label>
                  ) : (
                    <label>
                      <span>{text.purchaseLimit}</span>
                      <input
                        min="1"
                        placeholder={text.purchaseLimitHint}
                        type="number"
                        value={form.purchaseLimitPerUser}
                        onChange={(event) =>
                          updateForm("purchaseLimitPerUser", event.target.value)
                        }
                      />
                    </label>
                  )}
                  <label>
                    <span>{text.sortOrder}</span>
                    <input
                      type="number"
                      value={form.sortOrder}
                      onChange={(event) =>
                        updateForm("sortOrder", event.target.value)
                      }
                    />
                  </label>
                </div>
              </section>

              <section>
                <h3>{text.pricing}</h3>
                <p className="plan-section-hint">
                  {form.planType === "TRAFFIC_PACKAGE"
                    ? text.packagePricingHint
                    : text.pricingHint}
                </p>
                <div className="plan-currency-field">
                  <label>
                    <span>{text.currency}</span>
                    <input
                      maxLength={3}
                      required
                      value={form.currency}
                      onChange={(event) =>
                        updateForm("currency", event.target.value.toUpperCase())
                      }
                    />
                  </label>
                </div>
                <div className="plan-price-grid">
                  {(form.planType === "TRAFFIC_PACKAGE"
                    ? packagePeriods.filter(
                        (period) => period === "ONETIME" || form.resettable
                      )
                    : [
                        ...subscriptionPeriods,
                        ...(form.resettable ? ["RESET_TRAFFIC" as const] : [])
                      ]
                  ).map((period) => (
                    <label key={period}>
                      <span>{periodLabels[language][period]}</span>
                      <input
                        min="0.01"
                        placeholder="0.00"
                        step="0.01"
                        type="number"
                        required={
                          form.planType === "TRAFFIC_PACKAGE"
                          || period === "RESET_TRAFFIC"
                        }
                        value={form.priceValues[period]}
                        onChange={(event) =>
                          updateForm("priceValues", {
                            ...form.priceValues,
                            [period]: event.target.value
                          })
                        }
                      />
                    </label>
                  ))}
                </div>
                <label className="plan-inline-toggle">
                  <input
                    checked={form.resettable}
                    type="checkbox"
                    onChange={(event) =>
                      updateForm("resettable", event.target.checked)
                    }
                  />
                  <span>
                    <strong>{text.resettable}</strong>
                    <small>{text.resettableHint}</small>
                  </span>
                </label>
              </section>

              <section>
                <h3>{text.availability}</h3>
                <div className="plan-toggle-grid">
                  {([
                    ["published", text.publishToggle],
                    ["sellable", text.sellToggle],
                    ...(form.planType === "SUBSCRIPTION"
                      ? [["renewable", text.renewToggle] as const]
                      : [])
                  ] as const).map(([field, label]) => (
                    <label key={field}>
                      <input
                        checked={form[field]}
                        type="checkbox"
                        onChange={(event) =>
                          updateForm(field, event.target.checked)
                        }
                      />
                      <span>{label}</span>
                    </label>
                  ))}
                </div>
              </section>
              {(formError || saveMutation.isError) && (
                <p className="plan-form-error">
                  {formError ||
                    errorMessage(saveMutation.error, text.operationFailed)}
                </p>
              )}
            </div>
            <footer>
              <button onClick={() => setEditing(undefined)} type="button">
                {text.cancel}
              </button>
              <button
                className="primary"
                disabled={saveMutation.isPending}
                type="submit"
              >
                {saveMutation.isPending ? text.saving : text.save}
              </button>
            </footer>
          </form>
        </div>
      )}
    </AdminShell>
  );
}

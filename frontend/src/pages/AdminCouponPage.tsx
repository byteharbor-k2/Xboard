import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState, type FormEvent } from "react";

import {
  createCoupon,
  deleteCoupon,
  listCoupons,
  updateCoupon,
  type AdminCoupon,
  type CouponDiscountType,
  type CouponDraft
} from "../admin/couponManagementApi";
import { listManagedPlans } from "../admin/planManagementApi";
import { AdminShell } from "../components/AdminShell";
import { ConfirmBar, type ConfirmRequest } from "../components/ConfirmBar";
import { ApiError } from "../lib/http";
import { billingPeriodLabel, formatMoney } from "../lib/subscription";
import { useAdminAuthStore } from "../store/adminAuth";
import { useAdminPreferences } from "../store/adminPreferences";
import type { BillingPeriod } from "../types";

const discountTypes: CouponDiscountType[] = ["FIXED_AMOUNT", "PERCENTAGE"];
const periods: BillingPeriod[] = [
  "MONTHLY",
  "QUARTERLY",
  "HALF_YEARLY",
  "YEARLY",
  "TWO_YEARLY",
  "THREE_YEARLY",
  "ONETIME",
  "RESET_TRAFFIC"
];

const copy = {
  "zh-CN": {
    eyebrow: "订阅与交易",
    title: "优惠券管理",
    description: "管理折扣类型、适用范围、有效期与每人限用次数。",
    create: "新建优惠券",
    loading: "正在加载优惠券…",
    empty: "还没有优惠券，创建后用户即可在结算时使用。",
    loadFailed: "优惠券数据加载失败",
    code: "券码",
    name: "名称",
    discount: "折扣",
    usage: "已用/上限",
    perUser: "每人限用",
    window: "有效期",
    limits: "限用套餐/周期",
    state: "状态",
    actions: "操作",
    limited: "不限",
    forever: "长期有效",
    enabled: "启用中",
    disabled: "已停用",
    edit: "编辑",
    remove: "删除",
    removeConfirm: "删除后引用它的订单仍保留折抵金额，历史使用记录清除。",
    editTitle: "编辑优惠券",
    createTitle: "创建优惠券",
    codeLabel: "券码",
    nameLabel: "名称",
    discountType: "折扣类型",
    fixedAmount: "固定减免",
    fixedAmountValue: "减免金额（元）",
    percentage: "百分比",
    percentageValue: "减免百分比（%）",
    percentageInvalid: "百分比折扣必须是整数。",
    startsAt: "生效时间",
    endsAt: "失效时间",
    emptyForever: "留空表示该侧不限",
    maxRedemptions: "总限用次数",
    maxRedemptionsPerUser: "每人限用次数",
    limitsHint: "留空表示不限",
    limitedPlans: "限用套餐",
    limitedPlansHint: "不选表示全部套餐可用",
    limitedPeriods: "限用付费周期",
    limitedPeriodsHint: "不选表示全部周期可用",
    enabledToggle: "启用该优惠券",
    cancel: "取消",
    save: "保存",
    saving: "保存中…",
    operationFailed: "操作失败"
  },
  "en-US": {
    eyebrow: "Subscriptions & finance",
    title: "Coupons",
    description:
      "Manage discount types, eligibility, validity, and per-user limits.",
    create: "New coupon",
    loading: "Loading coupons…",
    empty: "No coupons yet. Created ones await customers at checkout.",
    loadFailed: "Failed to load coupons",
    code: "Code",
    name: "Name",
    discount: "Discount",
    usage: "Used / cap",
    perUser: "Per user",
    window: "Validity",
    limits: "Plan / period limits",
    state: "State",
    actions: "Actions",
    limited: "All",
    forever: "No expiry",
    enabled: "Enabled",
    disabled: "Disabled",
    edit: "Edit",
    remove: "Delete",
    removeConfirm:
      "Orders that used it keep their priced discounts; redemption history is removed.",
    editTitle: "Edit coupon",
    createTitle: "Create coupon",
    codeLabel: "Code",
    nameLabel: "Name",
    discountType: "Discount type",
    fixedAmount: "Fixed amount",
    fixedAmountValue: "Amount off (CNY)",
    percentage: "Percentage",
    percentageValue: "Percent off",
    percentageInvalid: "Percentage discount must be a whole number.",
    startsAt: "Starts at",
    endsAt: "Ends at",
    emptyForever: "Leave blank for no bound on that side",
    maxRedemptions: "Redemption cap",
    maxRedemptionsPerUser: "Per-user cap",
    limitsHint: "Leave blank for unlimited",
    limitedPlans: "Applicable plans",
    limitedPlansHint: "Select none to allow every plan",
    limitedPeriods: "Applicable billing periods",
    limitedPeriodsHint: "Select none to allow every period",
    enabledToggle: "Coupon enabled",
    cancel: "Cancel",
    save: "Save",
    saving: "Saving…",
    operationFailed: "Operation failed"
  }
};

type FormState = {
  code: string;
  name: string;
  discountType: CouponDiscountType;
  discountValue: string;
  startsAt: string;
  endsAt: string;
  maxRedemptions: string;
  maxRedemptionsPerUser: string;
  limitedPlanIds: string[];
  limitedPeriods: BillingPeriod[];
  enabled: boolean;
};

function emptyForm(): FormState {
  return {
    code: "",
    name: "",
    discountType: "PERCENTAGE",
    discountValue: "",
    startsAt: "",
    endsAt: "",
    maxRedemptions: "",
    maxRedemptionsPerUser: "",
    limitedPlanIds: [],
    limitedPeriods: [],
    enabled: true
  };
}

function epochToLocalInput(epoch: number | null): string {
  if (!epoch) {
    return "";
  }
  const date = new Date(epoch * 1000);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(
    date.getDate()
  )}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function localInputToEpoch(value: string): number | null {
  if (!value.trim()) {
    return null;
  }
  const milliseconds = new Date(value).getTime();
  return Number.isFinite(milliseconds) ? Math.floor(milliseconds / 1000) : null;
}

function formFromCoupon(coupon: AdminCoupon): FormState {
  return {
    code: coupon.code,
    name: coupon.name,
    discountType: coupon.discount_type,
    discountValue: coupon.discount_type === "PERCENTAGE"
      ? String(coupon.discount_value)
      : (coupon.discount_value / 100).toFixed(2),
    startsAt: epochToLocalInput(coupon.starts_at),
    endsAt: epochToLocalInput(coupon.ends_at),
    maxRedemptions: coupon.max_redemptions?.toString() ?? "",
    maxRedemptionsPerUser: coupon.max_redemptions_per_user?.toString() ?? "",
    limitedPlanIds: [...coupon.limited_plan_ids],
    limitedPeriods: [...coupon.limited_periods],
    enabled: coupon.enabled
  };
}

function toDraft(form: FormState, id?: string): CouponDraft {
  const percentage = form.discountType === "PERCENTAGE";
  const rawValue = form.discountValue.trim();
  return {
    ...(id ? { id } : {}),
    code: form.code.trim(),
    name: form.name.trim(),
    discount_type: form.discountType,
    discount_value: percentage
      ? Number.parseInt(rawValue || "0", 10)
      : Math.round(Number.parseFloat(rawValue || "0") * 100),
    starts_at: localInputToEpoch(form.startsAt),
    ends_at: localInputToEpoch(form.endsAt),
    max_redemptions: form.maxRedemptions.trim()
      ? Number.parseInt(form.maxRedemptions, 10)
      : null,
    max_redemptions_per_user: form.maxRedemptionsPerUser.trim()
      ? Number.parseInt(form.maxRedemptionsPerUser, 10)
      : null,
    limited_plan_ids: form.limitedPlanIds,
    limited_periods: form.limitedPeriods,
    enabled: form.enabled
  };
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback;
}

export function AdminCouponPage() {
  const language = useAdminPreferences((state) => state.language);
  const accessToken = useAdminAuthStore((state) => state.accessToken);
  const queryClient = useQueryClient();
  const text = copy[language];
  const [pendingConfirmation, setPendingConfirmation] =
    useState<ConfirmRequest | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [pageError, setPageError] = useState("");
  const [editing, setEditing] = useState<AdminCoupon | null | undefined>();
  const [form, setForm] = useState<FormState>(emptyForm);
  const [formError, setFormError] = useState("");

  const couponsQuery = useQuery({
    queryKey: ["admin", "coupons"],
    queryFn: () => listCoupons(accessToken!),
    enabled: Boolean(accessToken)
  });

  const plansQuery = useQuery({
    queryKey: ["admin", "plans"],
    queryFn: () => listManagedPlans(accessToken!),
    enabled: Boolean(accessToken)
  });

  const planNames = useMemo(() => {
    const names = new Map<string, string>();
    for (const plan of plansQuery.data ?? []) {
      names.set(plan.id, plan.name);
    }
    return names;
  }, [plansQuery.data]);

  const saveMutation = useMutation({
    mutationFn: (draft: CouponDraft) =>
      editing
        ? updateCoupon(accessToken!, draft)
        : createCoupon(accessToken!, draft),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin", "coupons"] });
      setEditing(undefined);
    }
  });

  const deleteMutation = useMutation({
    mutationFn: (couponId: string) => deleteCoupon(accessToken!, couponId),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ["admin", "coupons"] })
  });

  const coupons = useMemo(() => couponsQuery.data ?? [], [couponsQuery.data]);

  function openCreate() {
    setForm(emptyForm());
    setFormError("");
    setEditing(null);
  }

  function openEdit(coupon: AdminCoupon) {
    setForm(formFromCoupon(coupon));
    setFormError("");
    setEditing(coupon);
  }

  function updateForm<K extends keyof FormState>(
    field: K,
    value: FormState[K]
  ) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  function toggleLimitedPlan(planId: string) {
    setForm((current) => ({
      ...current,
      limitedPlanIds: current.limitedPlanIds.includes(planId)
        ? current.limitedPlanIds.filter((id) => id !== planId)
        : [...current.limitedPlanIds, planId]
    }));
  }

  function toggleLimitedPeriod(period: BillingPeriod) {
    setForm((current) => ({
      ...current,
      limitedPeriods: current.limitedPeriods.includes(period)
        ? current.limitedPeriods.filter((value) => value !== period)
        : [...current.limitedPeriods, period]
    }));
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setFormError("");
    // A percentage that is not a whole number would be silently truncated
    // by the integer parse below, so it is refused before anything is sent.
    const rawValue = form.discountValue.trim();
    if (
      form.discountType === "PERCENTAGE" &&
      rawValue !== "" &&
      !Number.isInteger(Number(rawValue))
    ) {
      setFormError(text.percentageInvalid);
      return;
    }
    saveMutation.mutate(toDraft(form, editing?.id), {
      onError: (error) =>
        setFormError(errorMessage(error, text.operationFailed))
    });
  }

  function remove(coupon: AdminCoupon) {
    setPendingConfirmation({
      message: `${coupon.code} — ${text.removeConfirm}`,
      confirmLabel: text.remove,
      danger: true,
      run: () => deleteMutation.mutateAsync(coupon.id)
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

  function displayDiscount(coupon: AdminCoupon) {
    return coupon.discount_type === "PERCENTAGE"
      ? `-${coupon.discount_value}%`
      : formatMoney(String(coupon.discount_value), "CNY", language);
  }

  function displayWindow(coupon: AdminCoupon) {
    const format = (epoch: number) =>
      new Intl.DateTimeFormat(language, { dateStyle: "short" }).format(
        new Date(epoch * 1000)
      );
    if (!coupon.starts_at && !coupon.ends_at) {
      return text.forever;
    }
    const start = coupon.starts_at ? format(coupon.starts_at) : "—";
    const end = coupon.ends_at ? format(coupon.ends_at) : "—";
    return `${start} ~ ${end}`;
  }

  function displayUsage(coupon: AdminCoupon) {
    return coupon.max_redemptions === null
      ? `${coupon.redemptions_used} / ${text.limited}`
      : `${coupon.redemptions_used} / ${coupon.max_redemptions}`;
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

      <section className="admin-card" style={{ paddingBottom: 4 }}>
        <div className="admin-table-wrap">
          {couponsQuery.isPending ? (
            <p className="admin-table-empty">{text.loading}</p>
          ) : couponsQuery.isError ? (
            <p className="admin-table-empty">{text.loadFailed}</p>
          ) : coupons.length === 0 ? (
            <p className="admin-table-empty">{text.empty}</p>
          ) : (
            <table className="admin-table">
              <thead>
                <tr>
                  <th>{text.code}</th>
                  <th>{text.name}</th>
                  <th>{text.discount}</th>
                  <th>{text.usage}</th>
                  <th>{text.perUser}</th>
                  <th>{text.window}</th>
                  <th>{text.limits}</th>
                  <th>{text.state}</th>
                  <th>{text.actions}</th>
                </tr>
              </thead>
              <tbody>
                {coupons.map((coupon) => (
                  <tr key={coupon.id}>
                    <td className="order-number">{coupon.code}</td>
                    <td>
                      <strong>{coupon.name}</strong>
                    </td>
                    <td>{displayDiscount(coupon)}</td>
                    <td>{displayUsage(coupon)}</td>
                    <td>
                      {coupon.max_redemptions_per_user ?? text.limited}
                    </td>
                    <td>{displayWindow(coupon)}</td>
                    <td>
                      <div>
                        {coupon.limited_plan_ids.length === 0
                          ? text.limited
                          : coupon.limited_plan_ids
                              .map((id) => planNames.get(id) ?? id)
                              .join("、")}
                        <span
                          style={{
                            display: "block",
                            color: "#8b97a8",
                            fontSize: 11
                          }}
                        >
                          {coupon.limited_periods.length
                            ? coupon.limited_periods
                                .map((period) =>
                                  billingPeriodLabel(period, language)
                                )
                                .join(language === "zh-CN" ? "、" : ", ")
                            : text.limited}
                        </span>
                      </div>
                    </td>
                    <td>
                      <span
                        className={`status-pill ${coupon.enabled ? "" : "order-status-cancelled"}`}
                      >
                        {coupon.enabled ? text.enabled : text.disabled}
                      </span>
                    </td>
                    <td>
                      <div className="machine-actions">
                        <button
                          onClick={() => openEdit(coupon)}
                          type="button"
                        >
                          {text.edit}
                        </button>
                        <button
                          className="danger"
                          disabled={deleteMutation.isPending}
                          onClick={() => remove(coupon)}
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
          )}
        </div>
      </section>

      {editing !== undefined && (
        <div className="admin-modal-backdrop" role="presentation">
          <form
            aria-label={editing ? text.editTitle : text.createTitle}
            className="admin-modal machine-modal"
            onSubmit={submit}
          >
            <header>
              <h2>{editing ? text.editTitle : text.createTitle}</h2>
              <button
                aria-label={text.cancel}
                onClick={() => setEditing(undefined)}
                type="button"
              >
                ×
              </button>
            </header>
            <div className="machine-form">
              <label>
                {text.codeLabel}
                <input
                  autoFocus
                  maxLength={64}
                  required
                  value={form.code}
                  onChange={(event) => updateForm("code", event.target.value)}
                />
              </label>
              <label>
                {text.nameLabel}
                <input
                  maxLength={120}
                  required
                  value={form.name}
                  onChange={(event) => updateForm("name", event.target.value)}
                />
              </label>
              <label>
                {text.discountType}
                <select
                  value={form.discountType}
                  onChange={(event) =>
                    updateForm(
                      "discountType",
                      event.target.value as CouponDiscountType
                    )
                  }
                >
                  {discountTypes.map((type) => (
                    <option key={type} value={type}>
                      {type === "FIXED_AMOUNT"
                        ? text.fixedAmount
                        : text.percentage}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                {form.discountType === "FIXED_AMOUNT"
                  ? text.fixedAmountValue
                  : text.percentageValue}
                <input
                  min={form.discountType === "PERCENTAGE" ? 1 : 0.01}
                  max={form.discountType === "PERCENTAGE" ? 100 : undefined}
                  required
                  step={form.discountType === "PERCENTAGE" ? 1 : 0.01}
                  type="number"
                  value={form.discountValue}
                  onChange={(event) =>
                    updateForm("discountValue", event.target.value)
                  }
                />
              </label>
              <label>
                {text.startsAt}
                <input
                  type="datetime-local"
                  value={form.startsAt}
                  onChange={(event) =>
                    updateForm("startsAt", event.target.value)
                  }
                />
              </label>
              <label>
                {text.endsAt}
                <input
                  type="datetime-local"
                  value={form.endsAt}
                  onChange={(event) => updateForm("endsAt", event.target.value)}
                />
              </label>
              <label>
                {text.maxRedemptions}
                <input
                  min={1}
                  placeholder={text.limitsHint}
                  type="number"
                  value={form.maxRedemptions}
                  onChange={(event) =>
                    updateForm("maxRedemptions", event.target.value)
                  }
                />
              </label>
              <label>
                {text.maxRedemptionsPerUser}
                <input
                  min={1}
                  placeholder={text.limitsHint}
                  type="number"
                  value={form.maxRedemptionsPerUser}
                  onChange={(event) =>
                    updateForm("maxRedemptionsPerUser", event.target.value)
                  }
                />
              </label>
              <div>
                <span>{text.limitedPlans}</span>
                <small>{text.limitedPlansHint}</small>
                <div
                  style={{
                    display: "flex",
                    flexWrap: "wrap",
                    gap: 10,
                    marginTop: 8
                  }}
                >
                  {(plansQuery.data ?? []).map((plan) => (
                    <label
                      key={plan.id}
                      style={{
                        display: "flex",
                        gap: 6,
                        alignItems: "center",
                        fontSize: 13
                      }}
                    >
                      <input
                        checked={form.limitedPlanIds.includes(plan.id)}
                        type="checkbox"
                        onChange={() => toggleLimitedPlan(plan.id)}
                      />
                      {plan.name}
                    </label>
                  ))}
                </div>
              </div>
              <div>
                <span>{text.limitedPeriods}</span>
                <small>{text.limitedPeriodsHint}</small>
                <div
                  style={{
                    display: "flex",
                    flexWrap: "wrap",
                    gap: 10,
                    marginTop: 8
                  }}
                >
                  {periods.map((period) => (
                    <label
                      key={period}
                      style={{
                        display: "flex",
                        gap: 6,
                        alignItems: "center",
                        fontSize: 13
                      }}
                    >
                      <input
                        checked={form.limitedPeriods.includes(period)}
                        type="checkbox"
                        onChange={() => toggleLimitedPeriod(period)}
                      />
                      {billingPeriodLabel(period, language)}
                    </label>
                  ))}
                </div>
              </div>
              <label className="machine-check">
                <input
                  checked={form.enabled}
                  type="checkbox"
                  onChange={(event) =>
                    updateForm("enabled", event.target.checked)
                  }
                />
                <span>{text.enabledToggle}</span>
              </label>
              {(formError || saveMutation.isError) && (
                <p className="admin-operation-error">
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

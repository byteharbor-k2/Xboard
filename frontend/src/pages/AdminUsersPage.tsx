import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import {
  assignUserInviter,
  assignUserOrder,
  deleteUser,
  exportUserCsv,
  listUsers,
  resetUserSecret,
  resetUserTraffic,
  sendUserMail,
  setUserBanned,
  updateUser,
  type AdminUser,
  type AdminUserUpdate
} from "../admin/adminUsersApi";
import { AdminShell } from "../components/AdminShell";
import { ApiError } from "../lib/http";
import { formatBytes } from "../lib/subscription";
import { useAdminAuthStore } from "../store/adminAuth";
import { useAdminPreferences } from "../store/adminPreferences";

const STATUS_FILTERS = ["ALL", "ACTIVE", "SUSPENDED"] as const;
type StatusFilter = (typeof STATUS_FILTERS)[number];

const copy = {
  "zh-CN": {
    eyebrow: "用户与支持",
    title: "用户管理",
    description: "查询账号，并调整订阅、流量、有效期与访问权限。",
    search: "搜索用户邮箱…",
    filter: "状态筛选",
    all: "全部状态",
    active: "正常",
    suspended: "已封禁",
    id: "ID",
    account: "邮箱",
    devices: "在线设备",
    plan: "订阅",
    usage: "已用流量",
    expiresAt: "到期时间",
    actions: "操作",
    none: "—",
    never: "长期有效",
    unlimited: "不限",
    loading: "加载中…",
    empty: "没有匹配的用户。",
    loadFailed: "用户列表加载失败",
    operationFailed: "操作失败",
    previous: "上一页",
    next: "下一页",
    pageOf: "第 {page} / {pages} 页，共 {total} 个账号",
    edit: "编辑",
    ban: "封禁",
    unban: "解封",
    resetSecret: "重置订阅链接",
    resetTraffic: "重置流量",
    remove: "删除",
    confirm: "确认",
    cancel: "取消",
    editTitle: "编辑用户",
    email: "邮箱",
    password: "新密码",
    passwordHint: "留空则不修改",
    remarks: "备注",
    remarksHint: "仅管理员可见",
    speedLimit: "限速（Mbps）",
    speedLimitHint: "留空表示跟随套餐",
    transferLimit: "流量额度（GB）",
    expiresHint: "留空表示长期有效",
    save: "保存",
    saved: "已保存",
    banConfirm: "封禁后该账号将无法登录，订阅链接也会停止下发配置。确定吗？",
    resetSecretConfirm:
      "重置后旧订阅地址立即失效，用户需要重新导入。确定吗？",
    resetTrafficConfirm: "将已用流量清零，额度与有效期不变。确定吗？",
    removeConfirm:
      "删除账号不可恢复。若该账号有订单记录，删除会被拒绝，请改用封禁。",
    secretTitle: "新的订阅链接",
    secretHint: "请复制并发给用户，关闭后无法再次查看。",
    copy: "复制",
    copied: "已复制",
    exportCsv: "导出 CSV",
    exportFailed: "CSV 导出失败",
    sendMail: "发信",
    mailTitle: "发送邮件",
    mailTo: "收件人",
    mailSubject: "主题",
    mailSubjectHint: "邮件主题，不能为空",
    mailBody: "正文",
    mailBodyHint: "支持 HTML",
    mailSent: "邮件已发送",
    send: "发送",
    inviter: "指定邀请人",
    inviterTitle: "指定邀请人",
    inviterLabel: "邀请人用户 ID（UUID）",
    inviterHint:
      "可为账号指定或更换邀请人；清除后该账号将不再有邀请人。",
    clearInviter: "清除邀请人",
    inviterCleared: "已清除邀请人",
    inviterSaved: "已指定邀请人",
    assignOrder: "分配订单",
    assignOrderTitle: "分配订单",
    tradeNoLabel: "订单号",
    tradeNoHint: "将被转移订单的编号",
    assign: "确认分配",
    assigned: "订单已分配"
  },
  "en-US": {
    eyebrow: "Users & support",
    title: "Users",
    description:
      "Look up accounts and adjust subscriptions, traffic, expiry and access.",
    search: "Search by email…",
    filter: "Status",
    all: "All statuses",
    active: "Active",
    suspended: "Suspended",
    id: "ID",
    account: "Email",
    devices: "Devices",
    plan: "Subscription",
    usage: "Used",
    expiresAt: "Expires",
    actions: "Actions",
    none: "—",
    never: "No expiry",
    unlimited: "Unlimited",
    loading: "Loading…",
    empty: "No accounts match.",
    loadFailed: "Failed to load users",
    operationFailed: "Operation failed",
    previous: "Previous",
    next: "Next",
    pageOf: "Page {page} of {pages}, {total} accounts",
    edit: "Edit",
    ban: "Suspend",
    unban: "Restore",
    resetSecret: "Reset subscription link",
    resetTraffic: "Reset traffic",
    remove: "Delete",
    confirm: "Confirm",
    cancel: "Cancel",
    editTitle: "Edit user",
    email: "Email",
    password: "New password",
    passwordHint: "Leave blank to keep the current one",
    remarks: "Notes",
    remarksHint: "Visible to administrators only",
    speedLimit: "Speed limit (Mbps)",
    speedLimitHint: "Leave blank to follow the plan",
    transferLimit: "Traffic allowance (GB)",
    expiresHint: "Leave blank for no expiry",
    save: "Save",
    saved: "Saved",
    banConfirm:
      "A suspended account cannot sign in, and its subscription link stops serving configs. Continue?",
    resetSecretConfirm:
      "The old subscription address stops working immediately and the customer has to import again. Continue?",
    resetTrafficConfirm:
      "Usage counters return to zero; the allowance and expiry are untouched. Continue?",
    removeConfirm:
      "Deleting an account cannot be undone. An account with order history will be refused - suspend it instead.",
    secretTitle: "New subscription link",
    secretHint:
      "Copy it to the customer now; it cannot be shown again after closing.",
    copy: "Copy",
    copied: "Copied",
    exportCsv: "Export CSV",
    exportFailed: "CSV export failed",
    sendMail: "Send mail",
    mailTitle: "Send mail",
    mailTo: "Recipient",
    mailSubject: "Subject",
    mailSubjectHint: "Required",
    mailBody: "Body",
    mailBodyHint: "HTML allowed",
    mailSent: "Mail sent",
    send: "Send",
    inviter: "Assign inviter",
    inviterTitle: "Assign inviter",
    inviterLabel: "Inviter user ID (UUID)",
    inviterHint:
      "Point the account at an inviter, or clear it entirely.",
    clearInviter: "Clear inviter",
    inviterCleared: "Inviter cleared",
    inviterSaved: "Inviter assigned",
    assignOrder: "Assign order",
    assignOrderTitle: "Assign order",
    tradeNoLabel: "Trade number",
    tradeNoHint: "The order to hand over",
    assign: "Assign",
    assigned: "Order assigned"
  }
};

const GIB = 1024n * 1024n * 1024n;

function formatEpoch(seconds: number | null, never: string) {
  if (seconds === null) {
    return never;
  }
  return new Date(seconds * 1000).toLocaleString();
}

function toGib(bytes: string) {
  const value = BigInt(bytes || "0");
  return (Number(value) / Number(GIB)).toFixed(2);
}

export function AdminUsersPage() {
  const accessToken = useAdminAuthStore((state) => state.accessToken)!;
  const language = useAdminPreferences((state) => state.language);
  const text = copy[language];
  const queryClient = useQueryClient();

  const [search, setSearch] = useState("");
  const [status, setStatus] = useState<StatusFilter>("ALL");
  const [page, setPage] = useState(0);
  const [error, setError] = useState("");
  const [editing, setEditing] = useState<AdminUser | null>(null);
  const [newSecret, setNewSecret] = useState<string | null>(null);
  const [mailing, setMailing] = useState<AdminUser | null>(null);
  const [assigningInviter, setAssigningInviter] = useState<AdminUser | null>(
    null
  );
  const [assigningOrder, setAssigningOrder] = useState<AdminUser | null>(null);
  const [csvBusy, setCsvBusy] = useState(false);

  const usersQuery = useQuery({
    queryKey: ["admin-users", search, status, page],
    queryFn: () =>
      listUsers(accessToken, {
        search,
        status: status === "ALL" ? null : status,
        page,
        limit: 20
      }),
    retry: false
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ["admin-users"] });
  }

  function reportFailure(cause: unknown) {
    setError(cause instanceof ApiError ? cause.message : text.operationFailed);
  }

  const ban = useMutation({
    mutationFn: (target: AdminUser) =>
      setUserBanned(accessToken, target.id, !target.banned),
    onSuccess: refresh,
    onError: reportFailure
  });

  const resetTraffic = useMutation({
    mutationFn: (target: AdminUser) =>
      resetUserTraffic(accessToken, target.id),
    onSuccess: refresh,
    onError: reportFailure
  });

  const resetSecret = useMutation({
    mutationFn: (target: AdminUser) => resetUserSecret(accessToken, target.id),
    onSuccess: (secret) => setNewSecret(secret),
    onError: reportFailure
  });

  const remove = useMutation({
    mutationFn: (target: AdminUser) => deleteUser(accessToken, target.id),
    onSuccess: refresh,
    onError: reportFailure
  });

  async function exportCsv() {
    setError("");
    setCsvBusy(true);
    try {
      const blob = await exportUserCsv(accessToken);
      const link = document.createElement("a");
      link.href = URL.createObjectURL(blob);
      link.download = "users.csv";
      link.click();
      URL.revokeObjectURL(link.href);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : text.exportFailed);
    } finally {
      setCsvBusy(false);
    }
  }

  const data = usersQuery.data;
  const users = data?.data ?? [];
  const pages = data ? Math.max(Math.ceil(data.total / data.limit), 1) : 1;

  return (
    <AdminShell>
      <header className="admin-page-heading">
        <div>
          <p>{text.eyebrow}</p>
          <h1>{text.title}</h1>
          <span>{text.description}</span>
        </div>
      </header>

      {error ? <p className="admin-operation-error">{error}</p> : null}
      {usersQuery.isError ? (
        <p className="admin-operation-error">{text.loadFailed}</p>
      ) : null}

      <section className="admin-card" style={{ paddingBottom: 4 }}>
        <div
          style={{
            display: "flex",
            gap: 12,
            padding: "18px 22px 0",
            flexWrap: "wrap"
          }}
        >
          <input
            onChange={(event) => {
              setSearch(event.target.value);
              setPage(0);
              setError("");
            }}
            placeholder={text.search}
            style={{
              padding: "10px 13px",
              border: "1px solid #dfe5ee",
              borderRadius: 9,
              font: "inherit",
              minWidth: 260
            }}
            value={search}
          />
          <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <span style={{ color: "#707c93" }}>{text.filter}</span>
            <select
              onChange={(event) => {
                setStatus(event.target.value as StatusFilter);
                setPage(0);
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
                    : option === "ACTIVE"
                      ? text.active
                      : text.suspended}
                </option>
              ))}
            </select>
          </label>
          <button
            disabled={csvBusy}
            onClick={() => void exportCsv()}
            type="button"
          >
            {text.exportCsv}
          </button>
        </div>

        <div className="admin-table-wrap">
          {usersQuery.isPending ? (
            <p className="admin-table-empty">{text.loading}</p>
          ) : users.length === 0 ? (
            <p className="admin-table-empty">{text.empty}</p>
          ) : (
            <table className="admin-table">
              <thead>
                <tr>
                  <th>{text.id}</th>
                  <th>{text.account}</th>
                  <th>{text.devices}</th>
                  <th>{text.plan}</th>
                  <th>{text.usage}</th>
                  <th>{text.expiresAt}</th>
                  <th>{text.actions}</th>
                </tr>
              </thead>
              <tbody>
                {users.map((user) => (
                  <tr key={user.id}>
                    <td>{user.node_user_id}</td>
                    <td>
                      <strong>{user.email}</strong>
                      {user.banned ? (
                        <span className="order-type-tag">{text.suspended}</span>
                      ) : null}
                    </td>
                    <td>{user.online_devices}</td>
                    <td>{user.plan_name ?? text.none}</td>
                    <td>
                      {formatBytes(user.used_bytes)} /{" "}
                      {formatBytes(user.transfer_limit_bytes)}
                    </td>
                    <td>{formatEpoch(user.expires_at, text.never)}</td>
                    <td>
                      <div className="admin-row-actions">
                        <button
                          onClick={() => setEditing(user)}
                          type="button"
                        >
                          {text.edit}
                        </button>
                        <button
                          onClick={() => {
                            if (window.confirm(text.banConfirm)) {
                              ban.mutate(user);
                            }
                          }}
                          type="button"
                        >
                          {user.banned ? text.unban : text.ban}
                        </button>
                        <button
                          onClick={() => {
                            if (window.confirm(text.resetSecretConfirm)) {
                              resetSecret.mutate(user);
                            }
                          }}
                          type="button"
                        >
                          {text.resetSecret}
                        </button>
                        <button
                          onClick={() => {
                            if (window.confirm(text.resetTrafficConfirm)) {
                              resetTraffic.mutate(user);
                            }
                          }}
                          type="button"
                        >
                          {text.resetTraffic}
                        </button>
                        <button
                          onClick={() => setMailing(user)}
                          type="button"
                        >
                          {text.sendMail}
                        </button>
                        <button
                          onClick={() => setAssigningInviter(user)}
                          type="button"
                        >
                          {text.inviter}
                        </button>
                        <button
                          onClick={() => setAssigningOrder(user)}
                          type="button"
                        >
                          {text.assignOrder}
                        </button>
                        <button
                          onClick={() => {
                            if (window.confirm(text.removeConfirm)) {
                              remove.mutate(user);
                            }
                          }}
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

        <div
          style={{
            display: "flex",
            gap: 12,
            alignItems: "center",
            padding: "14px 22px"
          }}
        >
          <button
            disabled={page <= 0}
            onClick={() => setPage((current) => Math.max(current - 1, 0))}
            type="button"
          >
            {text.previous}
          </button>
          <button
            disabled={page + 1 >= pages}
            onClick={() => setPage((current) => current + 1)}
            type="button"
          >
            {text.next}
          </button>
          <span style={{ color: "#707c93" }}>
            {text.pageOf
              .replace("{page}", String(page + 1))
              .replace("{pages}", String(pages))
              .replace("{total}", String(data?.total ?? 0))}
          </span>
        </div>
      </section>

      {editing ? (
        <EditUserDialog
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            refresh();
          }}
          onFailure={reportFailure}
          text={text}
          user={editing}
        />
      ) : null}

      {newSecret ? (
        <SecretDialog
          link={newSecret}
          onClose={() => setNewSecret(null)}
          text={text}
        />
      ) : null}

      {mailing ? (
        <MailDialog
          onClose={() => setMailing(null)}
          onFailure={reportFailure}
          text={text}
          user={mailing}
        />
      ) : null}

      {assigningInviter ? (
        <InviterDialog
          onClose={() => setAssigningInviter(null)}
          onFailure={reportFailure}
          text={text}
          user={assigningInviter}
        />
      ) : null}

      {assigningOrder ? (
        <AssignOrderDialog
          onClose={() => setAssigningOrder(null)}
          onFailure={reportFailure}
          text={text}
          user={assigningOrder}
        />
      ) : null}
    </AdminShell>
  );
}

type Copy = (typeof copy)["zh-CN"];

function EditUserDialog({
  user,
  text,
  onClose,
  onSaved,
  onFailure
}: {
  user: AdminUser;
  text: Copy;
  onClose: () => void;
  onSaved: () => void;
  onFailure: (cause: unknown) => void;
}) {
  const accessToken = useAdminAuthStore((state) => state.accessToken)!;
  const [email, setEmail] = useState(user.email);
  const [password, setPassword] = useState("");
  const [remarks, setRemarks] = useState(user.remarks ?? "");
  const [speedLimit, setSpeedLimit] = useState(
    user.speed_limit_mbps?.toString() ?? ""
  );
  const [transferGib, setTransferGib] = useState(toGib(user.transfer_limit_bytes));
  const [expiresAt, setExpiresAt] = useState(
    user.expires_at
      ? new Date(user.expires_at * 1000).toISOString().slice(0, 10)
      : ""
  );

  const save = useMutation({
    mutationFn: () => {
      const update: AdminUserUpdate = {};
      if (email.trim() && email.trim() !== user.email) {
        update.email = email.trim();
      }
      if (password.trim()) {
        update.password = password.trim();
      }
      if (remarks !== (user.remarks ?? "")) {
        update.remarks = remarks;
      }
      const speed = speedLimit.trim() ? Number(speedLimit) : null;
      if (speed !== user.speed_limit_mbps) {
        update.speed_limit_mbps = speed;
      }
      if (transferGib.trim()) {
        const gib = Number(transferGib);
        if (!Number.isNaN(gib) && gib > 0) {
          update.transfer_limit_bytes = (
            BigInt(Math.round(gib * 1000)) * GIB / 1000n
          ).toString();
        }
      }
      // Epoch seconds, matching the admin surface. Three intents from one
      // box: a value sets the expiry; empty with a current expiry clears it
      // (the operator's way of making an account permanent, which a null
      // "leave alone" field cannot express); empty with none sends nothing.
      if (expiresAt) {
        update.expires_at = Math.floor(
          new Date(`${expiresAt}T23:59:59Z`).getTime() / 1000
        );
      } else if (user.expires_at !== null) {
        update.clear_expiry = true;
      }
      return updateUser(accessToken, user.id, update);
    },
    onSuccess: onSaved,
    onError: onFailure
  });

  const field = {
    display: "block",
    marginBottom: 14
  } as const;
  const input = {
    width: "100%",
    padding: "10px 13px",
    border: "1px solid #dfe5ee",
    borderRadius: 9,
    font: "inherit",
    marginTop: 6
  } as const;

  return (
    <div className="settings-dialog-backdrop">
      <div className="settings-dialog" style={{ maxWidth: 520 }}>
        <h3>{text.editTitle}</h3>
        <div style={{ ...field, color: "#707c93" }}>{user.email}</div>

        <label style={field}>
          <span>{text.email}</span>
          <input
            onChange={(event) => setEmail(event.target.value)}
            style={input}
            value={email}
          />
        </label>
        <label style={field}>
          <span>{text.password}</span>
          <input
            onChange={(event) => setPassword(event.target.value)}
            placeholder={text.passwordHint}
            style={input}
            value={password}
          />
        </label>
        <label style={field}>
          <span>{text.transferLimit}</span>
          <input
            onChange={(event) => setTransferGib(event.target.value)}
            style={input}
            value={transferGib}
          />
        </label>
        <label style={field}>
          <span>{text.expiresAt}</span>
          <input
            onChange={(event) => setExpiresAt(event.target.value)}
            placeholder={text.expiresHint}
            style={input}
            type="date"
            value={expiresAt}
          />
        </label>
        <label style={field}>
          <span>{text.speedLimit}</span>
          <input
            onChange={(event) => setSpeedLimit(event.target.value)}
            placeholder={text.speedLimitHint}
            style={input}
            value={speedLimit}
          />
        </label>
        <label style={field}>
          <span>{text.remarks}</span>
          <textarea
            onChange={(event) => setRemarks(event.target.value)}
            placeholder={text.remarksHint}
            rows={3}
            style={input}
            value={remarks}
          />
        </label>

        <div style={{ display: "flex", gap: 10, justifyContent: "flex-end" }}>
          <button onClick={onClose} type="button">
            {text.cancel}
          </button>
          <button
            disabled={save.isPending}
            onClick={() => save.mutate()}
            type="button"
          >
            {text.save}
          </button>
        </div>
      </div>
    </div>
  );
}

function SecretDialog({
  link,
  text,
  onClose
}: {
  link: string;
  text: Copy;
  onClose: () => void;
}) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="settings-dialog-backdrop">
      <div className="settings-dialog" style={{ maxWidth: 520 }}>
        <h3>{text.secretTitle}</h3>
        <p style={{ color: "#707c93" }}>{text.secretHint}</p>
        <code
          style={{
            display: "block",
            wordBreak: "break-all",
            background: "#f4f6fa",
            padding: 12,
            borderRadius: 8,
            marginBottom: 14
          }}
        >
          {link}
        </code>
        <div style={{ display: "flex", gap: 10, justifyContent: "flex-end" }}>
          <button
            onClick={() => {
              void navigator.clipboard.writeText(link).then(() => {
                setCopied(true);
              });
            }}
            type="button"
          >
            {copied ? text.copied : text.copy}
          </button>
          <button onClick={onClose} type="button">
            {text.cancel}
          </button>
        </div>
      </div>
    </div>
  );
}

function MailDialog({
  user,
  text,
  onClose,
  onFailure
}: {
  user: AdminUser;
  text: Copy;
  onClose: () => void;
  onFailure: (cause: unknown) => void;
}) {
  const accessToken = useAdminAuthStore((state) => state.accessToken)!;
  const [subject, setSubject] = useState("");
  const [body, setBody] = useState("");

  const send = useMutation({
    mutationFn: () => sendUserMail(accessToken, user.id, subject, body),
    onSuccess: onClose,
    onError: onFailure
  });

  const field = {
    display: "block",
    marginBottom: 14
  } as const;
  const input = {
    width: "100%",
    padding: "10px 13px",
    border: "1px solid #dfe5ee",
    borderRadius: 9,
    font: "inherit",
    marginTop: 6
  } as const;

  return (
    <div className="settings-dialog-backdrop">
      <div className="settings-dialog" style={{ maxWidth: 520 }}>
        <h3>{text.mailTitle}</h3>
        <div style={{ ...field, color: "#707c93" }}>
          {text.mailTo}: {user.email}
        </div>
        <label style={field}>
          <span>{text.mailSubject}</span>
          <input
            onChange={(event) => setSubject(event.target.value)}
            placeholder={text.mailSubjectHint}
            style={input}
            value={subject}
          />
        </label>
        <label style={field}>
          <span>{text.mailBody}</span>
          <textarea
            onChange={(event) => setBody(event.target.value)}
            placeholder={text.mailBodyHint}
            rows={8}
            style={input}
            value={body}
          />
        </label>
        <div style={{ display: "flex", gap: 10, justifyContent: "flex-end" }}>
          <button onClick={onClose} type="button">
            {text.cancel}
          </button>
          <button
            disabled={send.isPending || !subject.trim() || !body.trim()}
            onClick={() => send.mutate()}
            type="button"
          >
            {send.isPending ? text.loading : text.send}
          </button>
        </div>
      </div>
    </div>
  );
}

function InviterDialog({
  user,
  text,
  onClose,
  onFailure
}: {
  user: AdminUser;
  text: Copy;
  onClose: () => void;
  onFailure: (cause: unknown) => void;
}) {
  const accessToken = useAdminAuthStore((state) => state.accessToken)!;
  const [inviterId, setInviterId] = useState("");

  const save = useMutation({
    mutationFn: () =>
      assignUserInviter(accessToken, user.id, inviterId.trim() || null),
    onSuccess: onClose,
    onError: onFailure
  });

  const clear = useMutation({
    mutationFn: () => assignUserInviter(accessToken, user.id, null),
    onSuccess: onClose,
    onError: onFailure
  });

  const input = {
    width: "100%",
    padding: "10px 13px",
    border: "1px solid #dfe5ee",
    borderRadius: 9,
    font: "inherit",
    marginTop: 6
  } as const;

  return (
    <div className="settings-dialog-backdrop">
      <div className="settings-dialog" style={{ maxWidth: 480 }}>
        <h3>{text.inviterTitle}</h3>
        <div style={{ marginBottom: 14, color: "#707c93" }}>{user.email}</div>
        <label style={{ display: "block", marginBottom: 14 }}>
          <span>{text.inviterLabel}</span>
          <input
            onChange={(event) => setInviterId(event.target.value)}
            style={input}
            value={inviterId}
          />
        </label>
        <p style={{ color: "#707c93", marginTop: 0 }}>{text.inviterHint}</p>
        <div
          style={{
            display: "flex",
            gap: 10,
            justifyContent: "flex-end",
            flexWrap: "wrap"
          }}
        >
          <button onClick={onClose} type="button">
            {text.cancel}
          </button>
          <button
            disabled={clear.isPending}
            onClick={() => clear.mutate()}
            type="button"
          >
            {text.clearInviter}
          </button>
          <button
            disabled={save.isPending || !inviterId.trim()}
            onClick={() => save.mutate()}
            type="button"
          >
            {save.isPending ? text.loading : text.save}
          </button>
        </div>
      </div>
    </div>
  );
}

function AssignOrderDialog({
  user,
  text,
  onClose,
  onFailure
}: {
  user: AdminUser;
  text: Copy;
  onClose: () => void;
  onFailure: (cause: unknown) => void;
}) {
  const accessToken = useAdminAuthStore((state) => state.accessToken)!;
  const queryClient = useQueryClient();
  const [tradeNo, setTradeNo] = useState("");
  const [assigned, setAssigned] = useState(false);

  const assign = useMutation({
    mutationFn: () =>
      assignUserOrder(accessToken, tradeNo.trim(), user.id),
    onSuccess: () => {
      setAssigned(true);
      void queryClient.invalidateQueries({
        queryKey: ["admin-users"]
      });
      window.setTimeout(() => {
        setAssigned(false);
        onClose();
      }, 1200);
    },
    onError: onFailure
  });

  const input = {
    width: "100%",
    padding: "10px 13px",
    border: "1px solid #dfe5ee",
    borderRadius: 9,
    font: "inherit",
    marginTop: 6
  } as const;

  return (
    <div className="settings-dialog-backdrop">
      <div className="settings-dialog" style={{ maxWidth: 480 }}>
        <h3>{text.assignOrderTitle}</h3>
        <div style={{ marginBottom: 14, color: "#707c93" }}>
          {text.account}: {user.email}
        </div>
        <label style={{ display: "block", marginBottom: 14 }}>
          <span>{text.tradeNoLabel}</span>
          <input
            onChange={(event) => {
              setTradeNo(event.target.value);
              setAssigned(false);
            }}
            placeholder={text.tradeNoHint}
            style={input}
            value={tradeNo}
          />
        </label>
        <div style={{ display: "flex", gap: 10, justifyContent: "flex-end" }}>
          <button onClick={onClose} type="button">
            {text.cancel}
          </button>
          <button
            disabled={assign.isPending || !tradeNo.trim() || assigned}
            onClick={() => assign.mutate()}
            type="button"
          >
            {assigned ? text.assigned : assign.isPending ? text.loading : text.assign}
          </button>
        </div>
      </div>
    </div>
  );
}

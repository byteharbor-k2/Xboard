import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";

import {
  deleteMachine,
  getMachineHistory,
  getMachineInstallCommand,
  getMachineToken,
  listMachines,
  rotateMachineToken,
  saveMachine,
  type MachineCredential,
  type MachineDraft,
  type ManagedMachine
} from "../admin/machineManagementApi";
import { AdminShell } from "../components/AdminShell";
import { ApiError } from "../lib/http";
import { useAdminAuthStore } from "../store/adminAuth";
import { useAdminPreferences } from "../store/adminPreferences";

const copy = {
  "zh-CN": {
    eyebrow: "节点管理",
    title: "服务器管理",
    description: "管理承载 xboard-node 的机器、认证令牌和运行状态。",
    create: "添加服务器",
    name: "服务器名称",
    notes: "备注",
    status: "状态",
    load: "负载",
    nodes: "节点",
    lastSeen: "最后在线",
    actions: "操作",
    active: "已启用",
    disabled: "已禁用",
    offline: "尚未连接",
    edit: "编辑",
    credential: "部署信息",
    history: "负载历史",
    rotate: "重置 Token",
    remove: "删除",
    removeConfirm: "删除服务器后，其绑定节点将被解除。确定继续吗？",
    rotateConfirm: "重置后旧 Token 会立即失效。确定继续吗？",
    loading: "正在加载服务器…",
    empty: "还没有服务器，添加后可使用机器模式部署 xboard-node。",
    createTitle: "添加服务器",
    editTitle: "编辑服务器",
    enabled: "启用服务器",
    cancel: "取消",
    save: "保存",
    tokenTitle: "机器部署信息",
    tokenHint: "Token 用于 xboard-node 机器认证，请妥善保存。",
    token: "Token",
    command: "安装命令",
    close: "关闭",
    noHistory: "暂无负载上报记录",
    operationFailed: "操作失败"
  },
  "en-US": {
    eyebrow: "Infrastructure",
    title: "Machines",
    description: "Manage xboard-node machines, credentials, and health.",
    create: "Add machine",
    name: "Machine name",
    notes: "Notes",
    status: "Status",
    load: "Load",
    nodes: "Nodes",
    lastSeen: "Last seen",
    actions: "Actions",
    active: "Enabled",
    disabled: "Disabled",
    offline: "Never connected",
    edit: "Edit",
    credential: "Deployment",
    history: "History",
    rotate: "Reset token",
    remove: "Delete",
    removeConfirm: "Deleting this machine detaches its nodes. Continue?",
    rotateConfirm: "The old token becomes invalid immediately. Continue?",
    loading: "Loading machines…",
    empty: "No machines yet. Add one to deploy xboard-node in machine mode.",
    createTitle: "Add machine",
    editTitle: "Edit machine",
    enabled: "Enable machine",
    cancel: "Cancel",
    save: "Save",
    tokenTitle: "Machine deployment",
    tokenHint: "This token authenticates xboard-node. Store it securely.",
    token: "Token",
    command: "Install command",
    close: "Close",
    noHistory: "No load reports yet",
    operationFailed: "Operation failed"
  }
};

function formatTime(value: number | null, language: "zh-CN" | "en-US") {
  if (!value) return copy[language].offline;
  return new Intl.DateTimeFormat(language, {
    dateStyle: "medium",
    timeStyle: "short"
  }).format(new Date(value * 1000));
}

function formatPercent(used: number, total: number) {
  if (total <= 0) return "0%";
  return `${Math.min(100, Math.round((used / total) * 100))}%`;
}

function errorText(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback;
}

export function AdminMachinesPage() {
  const language = useAdminPreferences((state) => state.language);
  const accessToken = useAdminAuthStore((state) => state.accessToken)!;
  const queryClient = useQueryClient();
  const text = copy[language];
  const [editing, setEditing] = useState<ManagedMachine | null | undefined>();
  const [draft, setDraft] = useState<MachineDraft>({
    name: "",
    notes: "",
    is_active: true
  });
  const [credential, setCredential] = useState<MachineCredential>();
  const [historyMachine, setHistoryMachine] = useState<ManagedMachine>();
  const [error, setError] = useState("");

  const machinesQuery = useQuery({
    queryKey: ["admin", "machines"],
    queryFn: () => listMachines(accessToken)
  });
  const historyQuery = useQuery({
    queryKey: ["admin", "machines", historyMachine?.id, "history"],
    queryFn: () => getMachineHistory(accessToken, historyMachine!.id),
    enabled: Boolean(historyMachine)
  });
  const saveMutation = useMutation({
    mutationFn: () => saveMachine(accessToken, draft),
    onSuccess: async (result) => {
      await queryClient.invalidateQueries({ queryKey: ["admin", "machines"] });
      setEditing(undefined);
      if (result !== true) setCredential(result);
    }
  });
  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteMachine(accessToken, id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["admin", "machines"] })
  });

  function openCreate() {
    setDraft({ name: "", notes: "", is_active: true });
    setEditing(null);
    setError("");
  }

  function openEdit(machine: ManagedMachine) {
    setDraft({
      id: machine.id,
      name: machine.name,
      notes: machine.notes ?? "",
      is_active: machine.is_active
    });
    setEditing(machine);
    setError("");
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError("");
    try {
      await saveMutation.mutateAsync();
    } catch (caught) {
      setError(errorText(caught, text.operationFailed));
    }
  }

  async function showCredential(machine: ManagedMachine) {
    setError("");
    try {
      const [token, install] = await Promise.all([
        getMachineToken(accessToken, machine.id),
        getMachineInstallCommand(accessToken, machine.id)
      ]);
      setCredential({ id: machine.id, token: token.token, install_command: install.command });
    } catch (caught) {
      setError(errorText(caught, text.operationFailed));
    }
  }

  async function rotate(machine: ManagedMachine) {
    if (!window.confirm(text.rotateConfirm)) return;
    try {
      const result = await rotateMachineToken(accessToken, machine.id);
      setCredential({ id: machine.id, token: result.token });
    } catch (caught) {
      setError(errorText(caught, text.operationFailed));
    }
  }

  return (
    <AdminShell>
      <header className="admin-page-heading admin-page-heading-action">
        <div>
          <p>{text.eyebrow}</p>
          <h1>{text.title}</h1>
          <span>{text.description}</span>
        </div>
        <button className="admin-primary-button" onClick={openCreate} type="button">
          + {text.create}
        </button>
      </header>

      {error && <p className="admin-operation-error">{error}</p>}
      <section className="admin-card admin-table-wrap">
        {machinesQuery.isPending ? (
          <p className="admin-table-empty">{text.loading}</p>
        ) : !machinesQuery.data?.length ? (
          <p className="admin-table-empty">{text.empty}</p>
        ) : (
          <table className="admin-table machine-table">
            <thead><tr>
              <th>{text.name}</th><th>{text.status}</th><th>{text.load}</th>
              <th>{text.nodes}</th><th>{text.lastSeen}</th><th>{text.actions}</th>
            </tr></thead>
            <tbody>{machinesQuery.data.map((machine) => (
              <tr key={machine.id}>
                <td><strong>{machine.name}</strong><small>{machine.notes}</small></td>
                <td><span className={machine.is_active ? "admin-state-chip success" : "admin-state-chip"}>
                  {machine.is_active ? text.active : text.disabled}
                </span></td>
                <td>{machine.load_status ? (
                  <span>CPU {machine.load_status.cpu.toFixed(1)}% · MEM {formatPercent(
                    machine.load_status.mem.used, machine.load_status.mem.total
                  )}</span>
                ) : "—"}</td>
                <td>{machine.servers_count}</td>
                <td>{formatTime(machine.last_seen_at, language)}</td>
                <td><div className="admin-table-action machine-actions">
                  <button onClick={() => openEdit(machine)} type="button">{text.edit}</button>
                  <button onClick={() => void showCredential(machine)} type="button">{text.credential}</button>
                  <button onClick={() => setHistoryMachine(machine)} type="button">{text.history}</button>
                  <button onClick={() => void rotate(machine)} type="button">{text.rotate}</button>
                  <button className="danger" onClick={() => {
                    if (window.confirm(text.removeConfirm)) deleteMutation.mutate(machine.id);
                  }} type="button">{text.remove}</button>
                </div></td>
              </tr>
            ))}</tbody>
          </table>
        )}
      </section>

      {editing !== undefined && (
        <div className="admin-modal-backdrop" role="presentation">
          <form className="admin-modal machine-modal" onSubmit={(event) => void submit(event)}>
            <header><h2>{editing ? text.editTitle : text.createTitle}</h2>
              <button onClick={() => setEditing(undefined)} type="button">×</button></header>
            <div className="machine-form">
              <label>{text.name}<input required maxLength={255} value={draft.name}
                onChange={(event) => setDraft({ ...draft, name: event.target.value })} /></label>
              <label>{text.notes}<textarea value={draft.notes}
                onChange={(event) => setDraft({ ...draft, notes: event.target.value })} /></label>
              <label className="machine-check"><input checked={draft.is_active} type="checkbox"
                onChange={(event) => setDraft({ ...draft, is_active: event.target.checked })} />{text.enabled}</label>
              {error && <p className="admin-operation-error">{error}</p>}
            </div>
            <footer><button onClick={() => setEditing(undefined)} type="button">{text.cancel}</button>
              <button className="primary" disabled={saveMutation.isPending} type="submit">{text.save}</button></footer>
          </form>
        </div>
      )}

      {credential && (
        <div className="admin-modal-backdrop" role="presentation">
          <section className="admin-modal machine-modal">
            <header><h2>{text.tokenTitle}</h2><button onClick={() => setCredential(undefined)} type="button">×</button></header>
            <div className="machine-credential"><p>{text.tokenHint}</p>
              <label>{text.token}<code>{credential.token}</code></label>
              {credential.install_command && <label>{text.command}<code>{credential.install_command}</code></label>}
            </div>
            <footer><button className="primary" onClick={() => setCredential(undefined)} type="button">{text.close}</button></footer>
          </section>
        </div>
      )}

      {historyMachine && (
        <div className="admin-modal-backdrop" role="presentation">
          <section className="admin-modal machine-history-modal">
            <header><h2>{historyMachine.name} · {text.history}</h2><button onClick={() => setHistoryMachine(undefined)} type="button">×</button></header>
            <div className="admin-table-wrap">{!historyQuery.data?.length ? <p className="admin-table-empty">{text.noHistory}</p> :
              <table className="admin-table"><thead><tr><th>{text.lastSeen}</th><th>CPU</th><th>MEM</th><th>DISK</th></tr></thead>
                <tbody>{historyQuery.data.map((item) => <tr key={item.recorded_at}><td>{formatTime(item.recorded_at, language)}</td>
                  <td>{item.cpu.toFixed(1)}%</td><td>{formatPercent(item.mem_used, item.mem_total)}</td>
                  <td>{formatPercent(item.disk_used, item.disk_total)}</td></tr>)}</tbody></table>}</div>
            <footer><button className="primary" onClick={() => setHistoryMachine(undefined)} type="button">{text.close}</button></footer>
          </section>
        </div>
      )}
    </AdminShell>
  );
}

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState, type FormEvent } from "react";

import {
  deleteNodeGroup,
  listNodeGroups,
  saveNodeGroup,
  type ManagedNodeGroup,
  type NodeGroupDraft
} from "../admin/groupManagementApi";
import { AdminShell } from "../components/AdminShell";
import { ApiError } from "../lib/http";
import { useAdminAuthStore } from "../store/adminAuth";
import { useAdminPreferences } from "../store/adminPreferences";

type SortKey = "id" | "name" | "users_count" | "server_count";
type SortDirection = "asc" | "desc";

const copy = {
  "zh-CN": {
    eyebrow: "节点管理", title: "权限组管理", description: "管理所有权限组，包括添加、删除、编辑等操作。",
    add: "添加权限组", search: "搜索权限组...", id: "组ID", name: "组名称", users: "用户数量",
    nodes: "节点数量", actions: "操作", edit: "编辑权限组", remove: "删除", loading: "正在加载权限组…",
    empty: "还没有权限组。", noResult: "没有符合搜索条件的权限组。", createTitle: "创建权限组", editTitle: "编辑权限组",
    formDescription: "权限组用于关联用户、套餐和可访问的节点。", namePlaceholder: "请输入权限组名称",
    nameHint: "名称为 2–50 个字符，仅支持中文、英文、数字、下划线和连字符。", cancel: "取消", create: "创建权限组",
    save: "保存修改", saving: "保存中…", removeConfirm: "确定删除这个权限组吗？已被套餐、用户或节点使用时将无法删除。",
    operationFailed: "操作失败", required: "请输入权限组名称。", invalidName: "名称必须为 2–50 个字符，且只能包含中文、英文、数字、下划线或连字符。", selected: "共 {count} 项", perPage: "每页显示",
    page: "第 {page} / {pages} 页", first: "首页", previous: "上一页", next: "下一页", last: "末页"
  },
  "en-US": {
    eyebrow: "Nodes", title: "Access groups", description: "Create, edit, and remove node access groups.",
    add: "Add group", search: "Search groups...", id: "Group ID", name: "Name", users: "Users",
    nodes: "Nodes", actions: "Actions", edit: "Edit group", remove: "Delete", loading: "Loading groups…",
    empty: "No access groups yet.", noResult: "No groups match your search.", createTitle: "Create group", editTitle: "Edit group",
    formDescription: "Groups connect users and plans to the nodes they may access.", namePlaceholder: "Enter a group name",
    nameHint: "Use 2–50 Chinese or English letters, digits, underscores, or hyphens.", cancel: "Cancel", create: "Create group",
    save: "Save changes", saving: "Saving…", removeConfirm: "Delete this group? Groups assigned to plans, users, or nodes cannot be deleted.",
    operationFailed: "Operation failed", required: "Enter a group name.", invalidName: "Use 2–50 Chinese or English letters, digits, underscores, or hyphens.", selected: "{count} items", perPage: "Per page",
    page: "Page {page} of {pages}", first: "First", previous: "Previous", next: "Next", last: "Last"
  }
};

function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback;
}

const validGroupName = /^[\p{Script=Han}A-Za-z0-9_-]{2,50}$/u;

export function AdminNodeGroupsPage() {
  const language = useAdminPreferences((state) => state.language);
  const token = useAdminAuthStore((state) => state.accessToken)!;
  const text = copy[language];
  const client = useQueryClient();
  const [search, setSearch] = useState("");
  const [sortKey, setSortKey] = useState<SortKey>("id");
  const [sortDirection, setSortDirection] = useState<SortDirection>("desc");
  const [pageSize, setPageSize] = useState(10);
  const [page, setPage] = useState(1);
  const [editing, setEditing] = useState<ManagedNodeGroup | null | undefined>();
  const [name, setName] = useState("");
  const [error, setError] = useState("");

  const groupsQuery = useQuery({
    queryKey: ["admin", "node-groups"],
    queryFn: () => listNodeGroups(token)
  });
  const saveMutation = useMutation({
    mutationFn: (draft: NodeGroupDraft) => saveNodeGroup(token, draft),
    onSuccess: () => client.invalidateQueries({ queryKey: ["admin", "node-groups"] })
  });

  const filteredGroups = useMemo(() => {
    const needle = search.trim().toLocaleLowerCase();
    return [...(groupsQuery.data ?? [])]
      .filter((group) => !needle || group.name.toLocaleLowerCase().includes(needle) || String(group.id).includes(needle))
      .sort((left, right) => {
        const leftValue = left[sortKey];
        const rightValue = right[sortKey];
        const result = typeof leftValue === "string"
          ? leftValue.localeCompare(String(rightValue), language)
          : Number(leftValue) - Number(rightValue);
        return sortDirection === "asc" ? result : -result;
      });
  }, [groupsQuery.data, language, search, sortDirection, sortKey]);

  const pageCount = Math.max(1, Math.ceil(filteredGroups.length / pageSize));
  const currentPage = Math.min(page, pageCount);
  const visibleGroups = filteredGroups.slice((currentPage - 1) * pageSize, currentPage * pageSize);

  function changeSort(key: SortKey) {
    setPage(1);
    if (sortKey === key) setSortDirection((value) => value === "asc" ? "desc" : "asc");
    else { setSortKey(key); setSortDirection("asc"); }
  }

  function sortLabel(key: SortKey, label: string) {
    return `${label}${sortKey === key ? sortDirection === "asc" ? " ↑" : " ↓" : ""}`;
  }

  function openCreate() {
    setEditing(null);
    setName("");
    setError("");
  }

  function openEdit(group: ManagedNodeGroup) {
    setEditing(group);
    setName(group.name);
    setError("");
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    const normalizedName = name.trim();
    if (!normalizedName) { setError(text.required); return; }
    if (!validGroupName.test(normalizedName)) { setError(text.invalidName); return; }
    setError("");
    try {
      await saveMutation.mutateAsync({ ...(editing ? { id: editing.id } : {}), name: normalizedName });
      setEditing(undefined);
    } catch (caught) {
      setError(errorMessage(caught, text.operationFailed));
    }
  }

  async function remove(group: ManagedNodeGroup) {
    if (!window.confirm(text.removeConfirm)) return;
    setError("");
    try {
      await deleteNodeGroup(token, group.id);
      await client.invalidateQueries({ queryKey: ["admin", "node-groups"] });
    } catch (caught) {
      setError(errorMessage(caught, text.operationFailed));
    }
  }

  const loadError = groupsQuery.error ? errorMessage(groupsQuery.error, text.operationFailed) : "";

  return <AdminShell>
    <header className="admin-page-heading">
      <div><p>{text.eyebrow}</p><h1>{text.title}</h1><span>{text.description}</span></div>
      <button className="plan-primary-button" onClick={openCreate} type="button">+ {text.add}</button>
    </header>
    {editing === undefined && (error || loadError) && <p className="admin-operation-error">{error || loadError}</p>}
    <section className="admin-card" style={{ paddingBottom: 4 }}>
      <div style={{ display: "flex", gap: 12, padding: "18px 22px 0" }}>
        <input aria-label={text.search} placeholder={text.search} value={search}
          onChange={(event) => { setSearch(event.target.value); setPage(1); }}
          style={{ width: "min(380px, 100%)", padding: "10px 13px", border: "1px solid #dfe5ee", borderRadius: 9, font: "inherit" }} />
      </div>
      <div className="admin-table-wrap">
        {groupsQuery.isPending ? <p className="admin-table-empty">{text.loading}</p> : !groupsQuery.data?.length ? <p className="admin-table-empty">{text.empty}</p> : !visibleGroups.length ? <p className="admin-table-empty">{text.noResult}</p> :
          <table className="admin-table"><thead><tr>
            <th><button type="button" onClick={() => changeSort("id")} style={{ border: 0, padding: 0, background: "transparent", color: "inherit", font: "inherit", fontWeight: "inherit", cursor: "pointer" }}>{sortLabel("id", text.id)}</button></th>
            <th><button type="button" onClick={() => changeSort("name")} style={{ border: 0, padding: 0, background: "transparent", color: "inherit", font: "inherit", fontWeight: "inherit", cursor: "pointer" }}>{sortLabel("name", text.name)}</button></th>
            <th><button type="button" onClick={() => changeSort("users_count")} style={{ border: 0, padding: 0, background: "transparent", color: "inherit", font: "inherit", fontWeight: "inherit", cursor: "pointer" }}>{sortLabel("users_count", text.users)}</button></th>
            <th><button type="button" onClick={() => changeSort("server_count")} style={{ border: 0, padding: 0, background: "transparent", color: "inherit", font: "inherit", fontWeight: "inherit", cursor: "pointer" }}>{sortLabel("server_count", text.nodes)}</button></th>
            <th>{text.actions}</th>
          </tr></thead><tbody>{visibleGroups.map((group) => <tr key={group.id}>
            <td>{group.id}</td><td><strong>{group.name}</strong></td><td>{group.users_count}</td><td>{group.server_count}</td>
            <td><div className="machine-actions"><button onClick={() => openEdit(group)} type="button">{text.edit}</button>
              <button className="danger" onClick={() => void remove(group)} type="button">{text.remove}</button></div></td>
          </tr>)}</tbody></table>}
      </div>
      <div className="admin-pagination">
        <span>{text.selected.replace("{count}", String(filteredGroups.length))}</span>
        <label>{text.perPage}<select value={pageSize} onChange={(event) => { setPageSize(Number(event.target.value)); setPage(1); }}>
          {[10, 20, 50].map((size) => <option key={size} value={size}>{size}</option>)}
        </select></label>
        <span>{text.page.replace("{page}", String(currentPage)).replace("{pages}", String(pageCount))}</span>
        <div><button aria-label={text.first} disabled={currentPage === 1} onClick={() => setPage(1)} type="button">«</button>
          <button aria-label={text.previous} disabled={currentPage === 1} onClick={() => setPage(currentPage - 1)} type="button">‹</button>
          <button aria-label={text.next} disabled={currentPage === pageCount} onClick={() => setPage(currentPage + 1)} type="button">›</button>
          <button aria-label={text.last} disabled={currentPage === pageCount} onClick={() => setPage(pageCount)} type="button">»</button></div>
      </div>
    </section>
    {editing !== undefined && <div className="admin-modal-backdrop" role="presentation">
      <form className="admin-modal machine-modal" onSubmit={(event) => void submit(event)}>
        <header><h2>{editing ? text.editTitle : text.createTitle}</h2><button aria-label={text.cancel} onClick={() => setEditing(undefined)} type="button">×</button></header>
        <div className="machine-form"><p style={{ margin: 0, color: "#707c93" }}>{text.formDescription}</p>
          <label>{text.name}<input autoFocus maxLength={50} minLength={2} placeholder={text.namePlaceholder} required value={name} onChange={(event) => setName(event.target.value)} />
            <small style={{ color: "#7a879d", fontWeight: 500 }}>{text.nameHint}</small></label>
          {error && <p className="admin-operation-error">{error}</p>}
        </div>
        <footer><button onClick={() => setEditing(undefined)} type="button">{text.cancel}</button>
          <button className="primary" disabled={saveMutation.isPending} type="submit">{saveMutation.isPending ? text.saving : editing ? text.save : text.create}</button></footer>
      </form>
    </div>}
  </AdminShell>;
}

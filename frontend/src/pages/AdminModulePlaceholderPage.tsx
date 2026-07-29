import type { AdminNavItem } from "../admin/adminNavigation";
import { AdminShell } from "../components/AdminShell";
import { useAdminPreferences } from "../store/adminPreferences";

const copy = {
  "zh-CN": {
    eyebrow: "管理员模块",
    status: "前端骨架已建立",
    description: "该模块将在对应开发批次中接入表格、表单和后端接口。",
    pending: "尚未连接业务数据"
  },
  "en-US": {
    eyebrow: "Administration module",
    status: "Frontend shell ready",
    description: "Tables, forms, and backend APIs will be connected in the scheduled development batch.",
    pending: "Business data is not connected"
  }
};

export function AdminModulePlaceholderPage({ item }: { item: AdminNavItem }) {
  const language = useAdminPreferences((state) => state.language);
  const text = copy[language];

  return (
    <AdminShell>
      <header className="admin-page-heading">
        <div>
          <p>{text.eyebrow}</p>
          <h1>{item.label[language]}</h1>
          <span>{item.description[language]}</span>
        </div>
      </header>
      <section className="admin-card admin-placeholder">
        <span className="admin-placeholder-glyph">{item.glyph}</span>
        <h2>{text.status}</h2>
        <p>{text.description}</p>
        <small>{text.pending}</small>
      </section>
    </AdminShell>
  );
}

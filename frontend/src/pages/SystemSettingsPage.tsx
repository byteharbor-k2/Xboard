import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent
} from "react";

import {
  configureTelegramWebhook,
  getMailTemplate,
  getPlanOptions,
  getSystemSettings,
  listMailTemplates,
  resetMailTemplate,
  saveMailTemplate,
  saveSystemSettings,
  sendTestEmail,
  testMailTemplate,
  type MailTemplateDetail,
  type SettingValue,
  type SettingsValues,
  type SystemSettingsSection
} from "../admin/systemSettingsApi";
import {
  getSettingsDefaults,
  systemSettingsSections,
  type SettingsField
} from "../admin/systemSettingsSchema";
import { AdminShell } from "../components/AdminShell";
import { ApiError } from "../lib/http";
import { navigate, usePathname } from "../lib/navigation";
import { useAdminPreferences } from "../store/adminPreferences";
import { useAuthStore } from "../store/auth";

const sectionPaths: Record<SystemSettingsSection, string> = {
  site: "/admin/system/settings",
  safe: "/admin/system/settings/safe",
  subscribe: "/admin/system/settings/subscribe",
  invite: "/admin/system/settings/invite",
  server: "/admin/system/settings/server",
  email: "/admin/system/settings/email",
  telegram: "/admin/system/settings/telegram",
  app: "/admin/system/settings/app",
  subscribe_template: "/admin/system/settings/subscribe-template"
};

const copy = {
  "zh-CN": {
    title: "系统设置",
    description:
      "管理系统核心配置，包括站点、安全、订阅、邀请佣金、节点、邮件和通知等设置",
    loading: "加载中...",
    loadFailed: "配置加载失败",
    saving: "保存中...",
    autoSaved: "已自动保存",
    saveFailed: "保存失败",
    basicSettings: "基本设置",
    templates: "模板管理",
    testEmail: "发送测试邮件",
    sending: "发送中...",
    testEmailSuccess: "测试邮件发送成功",
    setWebhook: "一键设置",
    settingWebhook: "设置中...",
    webhookSuccess: "Webhook 设置成功",
    wsSupport: "目前支持 WebSocket 通信的节点端：Xboard Node",
    subscriptionFormat: "当前订阅路径格式：{path}/xxxxxxxxxx",
    subscriptionRestart: "修改订阅路径后，可能需要重启服务才能生效。",
    generateToken: "生成随机通信密钥",
    templateName: "邮件模板",
    subject: "邮件主题",
    content: "模板内容 (HTML)",
    preview: "实时预览",
    variables: "可用占位符",
    customized: "已自定义",
    save: "保存",
    reset: "恢复默认",
    sendTest: "发送测试",
    testAddress: "测试收件地址",
    cancel: "取消",
    confirm: "确认",
    unsaved: "有未保存的修改",
    selectTemplate: "选择邮件模板",
    resetConfirm: "确定恢复当前邮件模板的默认内容吗？",
    actionFailed: "操作失败"
  },
  "en-US": {
    title: "System settings",
    description:
      "Manage core site, security, subscription, commission, node, email, and notification settings",
    loading: "Loading...",
    loadFailed: "Failed to load settings",
    saving: "Saving...",
    autoSaved: "Automatically saved",
    saveFailed: "Save failed",
    basicSettings: "Basic settings",
    templates: "Template management",
    testEmail: "Send test email",
    sending: "Sending...",
    testEmailSuccess: "Test email sent",
    setWebhook: "Set webhook",
    settingWebhook: "Setting...",
    webhookSuccess: "Webhook configured",
    wsSupport: "WebSocket communication is currently supported by Xboard Node.",
    subscriptionFormat: "Current subscription format: {path}/xxxxxxxxxx",
    subscriptionRestart: "A service restart may be required after changing this path.",
    generateToken: "Generate a random communication key",
    templateName: "Email template",
    subject: "Email subject",
    content: "Template content (HTML)",
    preview: "Live preview",
    variables: "Available placeholders",
    customized: "Customized",
    save: "Save",
    reset: "Reset",
    sendTest: "Send test",
    testAddress: "Test recipient",
    cancel: "Cancel",
    confirm: "Confirm",
    unsaved: "Unsaved changes",
    selectTemplate: "Select email template",
    resetConfirm: "Reset this email template to its default content?",
    actionFailed: "Operation failed"
  }
};

function sectionFromPath(pathname: string): SystemSettingsSection {
  const entry = Object.entries(sectionPaths).find(
    ([section, path]) =>
      section !== "site" && pathname === path
  );
  return (entry?.[0] as SystemSettingsSection | undefined) ?? "site";
}

function inputValue(field: SettingsField, value: SettingValue | undefined) {
  const current = value ?? field.defaultValue;
  if (Array.isArray(current)) {
    return field.key === "email_whitelist_suffix"
      ? current.join("\n")
      : current.join(",");
  }
  return typeof current === "boolean" || current === null
    ? ""
    : String(current);
}

function parseValue(
  field: SettingsField,
  event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>
): SettingValue {
  if (field.type === "number") {
    return event.target.value === "" ? null : Number(event.target.value);
  }
  if (field.type === "list") {
    const separator =
      field.key === "email_whitelist_suffix" ? /\r?\n/ : ",";
    return event.target.value
      .split(separator)
      .map((item) => item.trim())
      .filter(Boolean);
  }
  if (field.type === "select") {
    const option = field.options?.find(
      (item) => String(item.value) === event.target.value
    );
    return option?.value ?? event.target.value;
  }
  return event.target.value;
}

function generateServerToken() {
  const characters =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  const length = Math.floor(Math.random() * 17) + 16;
  return Array.from(
    { length },
    () => characters[Math.floor(Math.random() * characters.length)]
  ).join("");
}

function MailTemplateManager({
  accessToken,
  language
}: {
  accessToken: string;
  language: "zh-CN" | "en-US";
}) {
  const labels = copy[language];
  const queryClient = useQueryClient();
  const viewer = useAuthStore((state) => state.viewer);
  const [selected, setSelected] = useState("");
  const [draft, setDraft] = useState<MailTemplateDetail | null>(null);
  const [saved, setSaved] = useState<MailTemplateDetail | null>(null);
  const [testOpen, setTestOpen] = useState(false);
  const [testAddress, setTestAddress] = useState(viewer?.email ?? "");
  const [resetOpen, setResetOpen] = useState(false);
  const [message, setMessage] = useState("");

  const templates = useQuery({
    queryKey: ["mail-template", "list"],
    queryFn: () => listMailTemplates(accessToken),
    retry: false
  });

  useEffect(() => {
    if (!selected && templates.data?.length) {
      setSelected(templates.data[0].name);
    }
  }, [selected, templates.data]);

  const detail = useQuery({
    queryKey: ["mail-template", "detail", selected],
    queryFn: () => getMailTemplate(accessToken, selected),
    enabled: Boolean(selected),
    retry: false
  });

  useEffect(() => {
    if (detail.data) {
      setDraft(detail.data);
      setSaved(detail.data);
      setMessage("");
    }
  }, [detail.data]);

  const saveTemplate = useMutation({
    mutationFn: () =>
      saveMailTemplate(accessToken, {
        name: draft!.name,
        subject: draft!.subject,
        content: draft!.content
      }),
    onSuccess: async () => {
      setSaved(draft);
      setMessage(labels.autoSaved);
      await queryClient.invalidateQueries({ queryKey: ["mail-template", "list"] });
    },
    onError: () => setMessage(labels.actionFailed)
  });

  const resetTemplate = useMutation({
    mutationFn: () => resetMailTemplate(accessToken, selected),
    onSuccess: async () => {
      setResetOpen(false);
      await queryClient.invalidateQueries({
        queryKey: ["mail-template", "detail", selected]
      });
      await queryClient.invalidateQueries({ queryKey: ["mail-template", "list"] });
    },
    onError: () => setMessage(labels.actionFailed)
  });

  const testTemplate = useMutation({
    mutationFn: () => testMailTemplate(accessToken, selected, testAddress),
    onSuccess: () => {
      setTestOpen(false);
      setMessage(labels.testEmailSuccess);
    },
    onError: () => setMessage(labels.actionFailed)
  });

  if (templates.isPending) {
    return <p className="settings-status">{labels.loading}</p>;
  }
  if (templates.isError) {
    return <p className="settings-status error">{labels.loadFailed}</p>;
  }

  const dirty =
    Boolean(draft && saved) &&
    (draft?.subject !== saved?.subject || draft?.content !== saved?.content);

  return (
    <div className="mail-template-manager">
      <label className="settings-field">
        <span>
          <strong>{labels.templateName}</strong>
        </span>
        <select value={selected} onChange={(event) => setSelected(event.target.value)}>
          <option disabled value="">
            {labels.selectTemplate}
          </option>
          {templates.data?.map((template) => (
            <option key={template.name} value={template.name}>
              {template.label}
              {template.customized ? ` · ${labels.customized}` : ""}
            </option>
          ))}
        </select>
      </label>

      {detail.isPending && <p className="settings-status">{labels.loading}</p>}
      {draft && (
        <>
          <div className="mail-template-editor">
            <div>
              <label className="settings-field">
                <span>
                  <strong>{labels.subject}</strong>
                </span>
                <input
                  value={draft.subject}
                  onChange={(event) =>
                    setDraft({ ...draft, subject: event.target.value })
                  }
                />
              </label>
              <label className="settings-field">
                <span>
                  <strong>{labels.content}</strong>
                </span>
                <textarea
                  className="settings-template-textarea"
                  value={draft.content}
                  onChange={(event) =>
                    setDraft({ ...draft, content: event.target.value })
                  }
                />
              </label>
            </div>
            <div>
              <strong>{labels.preview}</strong>
              <iframe
                sandbox=""
                srcDoc={draft.content}
                title={labels.preview}
              />
            </div>
          </div>

          <div className="mail-template-variables">
            <strong>{labels.variables}</strong>
            <div>
              {[...draft.required_vars, ...draft.optional_vars].map((variable) => (
                <code key={variable}>{`{{${variable}}}`}</code>
              ))}
            </div>
          </div>

          <div className="mail-template-actions">
            <button
              disabled={!dirty || saveTemplate.isPending}
              onClick={() => saveTemplate.mutate()}
              type="button"
            >
              {labels.save}
            </button>
            <button onClick={() => setTestOpen(true)} type="button">
              {labels.sendTest}
            </button>
            {draft.customized && (
              <button onClick={() => setResetOpen(true)} type="button">
                {labels.reset}
              </button>
            )}
            {dirty && <span>{labels.unsaved}</span>}
          </div>
        </>
      )}

      {message && <p className="settings-status">{message}</p>}

      {testOpen && (
        <div className="settings-dialog-backdrop">
          <div className="settings-dialog">
            <h3>{labels.sendTest}</h3>
            <label>
              {labels.testAddress}
              <input
                type="email"
                value={testAddress}
                onChange={(event) => setTestAddress(event.target.value)}
              />
            </label>
            <div>
              <button onClick={() => setTestOpen(false)} type="button">
                {labels.cancel}
              </button>
              <button
                disabled={testTemplate.isPending}
                onClick={() => testTemplate.mutate()}
                type="button"
              >
                {labels.sendTest}
              </button>
            </div>
          </div>
        </div>
      )}

      {resetOpen && (
        <div className="settings-dialog-backdrop">
          <div className="settings-dialog">
            <h3>{labels.reset}</h3>
            <p>{labels.resetConfirm}</p>
            <div>
              <button onClick={() => setResetOpen(false)} type="button">
                {labels.cancel}
              </button>
              <button
                disabled={resetTemplate.isPending}
                onClick={() => resetTemplate.mutate()}
                type="button"
              >
                {labels.confirm}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export function SystemSettingsPage() {
  const pathname = usePathname();
  const accessToken = useAuthStore((state) => state.accessToken)!;
  const language = useAdminPreferences((state) => state.language);
  const labels = copy[language];
  const selectedSection = sectionFromPath(pathname);
  const section = useMemo(
    () =>
      systemSettingsSections.find((item) => item.id === selectedSection) ??
      systemSettingsSections[0],
    [selectedSection]
  );
  const defaults = useMemo(() => getSettingsDefaults(section), [section]);
  const [draft, setDraft] = useState<SettingsValues>(defaults);
  const [status, setStatus] = useState("");
  const [emailTab, setEmailTab] = useState<"settings" | "templates">("settings");
  const [templateTab, setTemplateTab] = useState("subscribe_template_singbox");
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const settings = useQuery({
    queryKey: ["admin-system-settings", selectedSection],
    queryFn: () => getSystemSettings(accessToken, selectedSection),
    retry: false
  });

  const plans = useQuery({
    queryKey: ["admin-plans", "settings-trial"],
    queryFn: () => getPlanOptions(accessToken),
    enabled: selectedSection === "site",
    retry: false
  });

  useEffect(() => {
    if (settings.data) {
      setDraft({ ...defaults, ...settings.data });
      setStatus("");
    }
  }, [defaults, settings.data]);

  useEffect(
    () => () => {
      if (saveTimer.current) clearTimeout(saveTimer.current);
    },
    [selectedSection]
  );

  const autoSave = useMutation({
    mutationFn: (values: SettingsValues) =>
      saveSystemSettings(accessToken, values),
    onSuccess: () => setStatus(labels.autoSaved),
    onError: () => setStatus(labels.saveFailed)
  });

  const testEmail = useMutation({
    mutationFn: () => sendTestEmail(accessToken),
    onSuccess: () => setStatus(labels.testEmailSuccess),
    onError: () => setStatus(labels.actionFailed)
  });

  const webhook = useMutation({
    mutationFn: () => configureTelegramWebhook(accessToken),
    onSuccess: () => setStatus(labels.webhookSuccess),
    onError: () => setStatus(labels.actionFailed)
  });

  function scheduleSave(values: SettingsValues, delay = 1000) {
    if (saveTimer.current) clearTimeout(saveTimer.current);
    setStatus(labels.saving);
    saveTimer.current = setTimeout(() => autoSave.mutate(values), delay);
  }

  function updateField(field: SettingsField, value: SettingValue) {
    const next = { ...draft, [field.key]: value };
    setDraft(next);
    scheduleSave(next, selectedSection === "subscribe_template" ? 1500 : 1000);
  }

  function isVisible(field: SettingsField) {
    if (
      field.key.startsWith("recaptcha") ||
      field.key.startsWith("turnstile")
    ) {
      if (!draft.captcha_enable) return false;
    }
    if (!field.visibleWhen) return true;
    const current = draft[field.visibleWhen.key];
    return field.visibleWhen.value === undefined
      ? Boolean(current)
      : current === field.visibleWhen.value;
  }

  function renderControl(field: SettingsField) {
    const value = draft[field.key] ?? field.defaultValue;

    if (field.type === "toggle") {
      const enabled = Boolean(value);
      return (
        <button
          aria-checked={enabled}
          className={`settings-toggle ${enabled ? "active" : ""}`}
          onClick={() =>
            updateField(
              field,
              field.key === "force_https" || field.key === "stop_register"
                ? enabled
                  ? 0
                  : 1
                : !enabled
            )
          }
          role="switch"
          type="button"
        >
          <span />
        </button>
      );
    }

    if (field.type === "select") {
      const options =
        field.key === "try_out_plan_id"
          ? [
              ...(field.options ?? []),
              ...(plans.data ?? []).map((plan) => ({
                value: plan.id,
                label: {
                  "zh-CN": plan.name,
                  "en-US": plan.name
                }
              }))
            ]
          : field.options;
      return (
        <select
          value={inputValue(field, value)}
          onChange={(event) => updateField(field, parseValue(field, event))}
        >
          {options?.map((option) => (
            <option key={String(option.value)} value={String(option.value)}>
              {option.label[language]}
            </option>
          ))}
        </select>
      );
    }

    if (
      field.type === "textarea" ||
      (field.type === "list" && field.key === "email_whitelist_suffix")
    ) {
      return (
        <textarea
          className={
            field.type === "textarea" ? "settings-template-textarea" : undefined
          }
          placeholder={field.placeholder?.[language]}
          rows={field.rows ?? (field.type === "textarea" ? 20 : 4)}
          value={inputValue(field, value)}
          onChange={(event) => updateField(field, parseValue(field, event))}
        />
      );
    }

    if (field.type === "list") {
      return (
        <input
          placeholder={field.placeholder?.[language]}
          type="text"
          value={inputValue(field, value)}
          onChange={(event) => updateField(field, parseValue(field, event))}
        />
      );
    }

    return (
      <div className="settings-input-wrap">
        <input
          max={field.max}
          min={field.min}
          placeholder={field.placeholder?.[language]}
          step={field.step}
          type={field.type}
          value={inputValue(field, value)}
          onChange={(event) => updateField(field, parseValue(field, event))}
        />
        {field.key === "server_token" && (
          <button
            aria-label={labels.generateToken}
            onClick={() => updateField(field, generateServerToken())}
            title={labels.generateToken}
            type="button"
          >
            ↻
          </button>
        )}
      </div>
    );
  }

  const visibleFields =
    selectedSection === "subscribe_template"
      ? section.fields.filter((field) => field.key === templateTab)
      : section.fields.filter(isVisible);

  return (
    <AdminShell>
      <header className="settings-page-title">
        <h1>{labels.title}</h1>
        <p>{labels.description}</p>
      </header>
      <div className="settings-page-separator" />

      <div className="settings-layout">
        <select
          className="settings-mobile-select"
          value={sectionPaths[selectedSection]}
          onChange={(event) => navigate(event.target.value)}
        >
          {systemSettingsSections.map((item) => (
            <option key={item.id} value={sectionPaths[item.id]}>
              {item.title[language]}
            </option>
          ))}
        </select>
        <nav className="settings-section-nav" aria-label={labels.title}>
          {systemSettingsSections.map((item) => (
            <button
              className={item.id === selectedSection ? "active" : ""}
              key={item.id}
              onClick={() => navigate(sectionPaths[item.id])}
              type="button"
            >
              <i aria-hidden="true">{item.glyph}</i>
              <strong>{item.title[language]}</strong>
            </button>
          ))}
        </nav>

        <section className="settings-main">
          <header className="settings-section-title">
            <h2>{section.title[language]}</h2>
            <p>{section.description[language]}</p>
          </header>
          <div className="settings-page-separator" />

          {settings.isPending && (
            <p className="settings-status">{labels.loading}</p>
          )}
          {settings.isError && (
            <p className="settings-status error">
              {settings.error instanceof ApiError
                ? settings.error.message
                : labels.loadFailed}
            </p>
          )}

          {selectedSection === "email" && (
            <div className="settings-tabs">
              <button
                className={emailTab === "settings" ? "active" : ""}
                onClick={() => setEmailTab("settings")}
                type="button"
              >
                {labels.basicSettings}
              </button>
              <button
                className={emailTab === "templates" ? "active" : ""}
                onClick={() => setEmailTab("templates")}
                type="button"
              >
                {labels.templates}
              </button>
            </div>
          )}

          {selectedSection === "subscribe_template" && (
            <div className="settings-tabs settings-template-tabs">
              {section.fields.map((field) => (
                <button
                  className={field.key === templateTab ? "active" : ""}
                  key={field.key}
                  onClick={() => setTemplateTab(field.key)}
                  type="button"
                >
                  {field.label[language].replace(" 订阅模板", "").replace(" 配置模板", "").replace(" 配置模版", "")}
                </button>
              ))}
            </div>
          )}

          {selectedSection === "email" && emailTab === "templates" ? (
            <MailTemplateManager
              accessToken={accessToken}
              language={language}
            />
          ) : (
            <div className="settings-form">
              {visibleFields.map((field) => (
                <label
                  className={`settings-field ${
                    field.type === "toggle" ? "settings-field-toggle" : ""
                  }`}
                  key={field.key}
                >
                  <span>
                    <strong>{field.label[language]}</strong>
                    <small>{field.description[language]}</small>
                    {field.key === "subscribe_path" && (
                      <small>
                        {labels.subscriptionFormat.replace(
                          "{path}",
                          String(draft.subscribe_path || "s")
                        )}
                        <br />
                        {labels.subscriptionRestart}
                      </small>
                    )}
                  </span>
                  {renderControl(field)}
                </label>
              ))}

              {selectedSection === "server" &&
                Boolean(draft.server_ws_enable) && (
                  <div className="settings-info">ⓘ {labels.wsSupport}</div>
                )}

              {selectedSection === "email" && (
                <button
                  className="settings-action-button"
                  disabled={testEmail.isPending}
                  onClick={() => testEmail.mutate()}
                  type="button"
                >
                  {testEmail.isPending ? labels.sending : labels.testEmail}
                </button>
              )}

              {selectedSection === "telegram" && (
                <div className="settings-webhook-action">
                  <div>
                    <strong>{language === "zh-CN" ? "设置Webhook" : "Set webhook"}</strong>
                    <small>
                      {language === "zh-CN"
                        ? "设置机器人的webhook，不设置将无法收到Telegram通知。"
                        : "Configure the bot webhook to receive Telegram notifications."}
                    </small>
                  </div>
                  <button
                    disabled={webhook.isPending}
                    onClick={() => webhook.mutate()}
                    type="button"
                  >
                    {webhook.isPending
                      ? labels.settingWebhook
                      : labels.setWebhook}
                  </button>
                </div>
              )}
            </div>
          )}

          {status && <p className="settings-status">{status}</p>}
        </section>
      </div>
    </AdminShell>
  );
}

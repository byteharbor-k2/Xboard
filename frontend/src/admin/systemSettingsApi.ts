import { ApiError } from "../lib/http";

const adminApiPrefix =
  import.meta.env.VITE_ADMIN_API_PREFIX ?? "/api/v2/admin";

export const systemSettingsEndpoints = {
  fetch: `${adminApiPrefix}/config/fetch`,
  save: `${adminApiPrefix}/config/save`,
  testEmail: `${adminApiPrefix}/config/testSendMail`,
  telegramWebhook: `${adminApiPrefix}/config/setTelegramWebhook`,
  emailTemplateList: `${adminApiPrefix}/mail/template/list`,
  emailTemplateGet: `${adminApiPrefix}/mail/template/get`,
  emailTemplateSave: `${adminApiPrefix}/mail/template/save`,
  emailTemplateReset: `${adminApiPrefix}/mail/template/reset`,
  emailTemplateTest: `${adminApiPrefix}/mail/template/test`,
  plans: `${adminApiPrefix}/plan/fetch`
} as const;

export type SystemSettingsSection =
  | "site"
  | "safe"
  | "subscribe"
  | "invite"
  | "server"
  | "email"
  | "telegram"
  | "app"
  | "subscribe_template";

export type SettingValue = string | number | boolean | string[] | null;
export type SettingsValues = Record<string, SettingValue>;

type XboardResponse<T> = {
  data: T;
};

export type MailTemplateSummary = {
  name: string;
  label: string;
  customized: boolean;
  subject: string | null;
  updated_at: number | null;
};

export type MailTemplateDetail = {
  name: string;
  label: string;
  required_vars: string[];
  optional_vars: string[];
  customized: boolean;
  subject: string;
  content: string;
};

export type PlanOption = {
  id: number;
  name: string;
};

async function settingsRequest<T>(
  accessToken: string,
  endpoint: string,
  init?: RequestInit
) {
  const response = await fetch(endpoint, {
    ...init,
    credentials: "include",
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${accessToken}`,
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      ...init?.headers
    }
  });
  if (!response.ok) {
    throw new ApiError(response.status, {
      detail: `管理员设置接口尚未可用：${endpoint}`
    });
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

export function getSystemSettings(
  accessToken: string,
  section: SystemSettingsSection
) {
  const endpoint = new URL(
    systemSettingsEndpoints.fetch,
    window.location.origin
  );
  endpoint.searchParams.set("key", section);
  return settingsRequest<XboardResponse<Partial<Record<SystemSettingsSection, SettingsValues>>>>(
    accessToken,
    `${endpoint.pathname}${endpoint.search}`
  ).then((response) => response.data[section] ?? {});
}

export function saveSystemSettings(
  accessToken: string,
  values: SettingsValues
) {
  return settingsRequest<XboardResponse<boolean>>(
    accessToken,
    systemSettingsEndpoints.save,
    {
      method: "POST",
      body: JSON.stringify(values)
    }
  );
}

export function sendTestEmail(accessToken: string) {
  return settingsRequest<XboardResponse<Record<string, unknown>>>(
    accessToken,
    systemSettingsEndpoints.testEmail,
    { method: "POST" }
  );
}

export function configureTelegramWebhook(
  accessToken: string
) {
  return settingsRequest<
    XboardResponse<{
      success: boolean;
      webhook_url: string;
      webhook_base_url: string;
    }>
  >(
    accessToken,
    systemSettingsEndpoints.telegramWebhook,
    {
      method: "POST"
    }
  );
}

export function listMailTemplates(accessToken: string) {
  return settingsRequest<XboardResponse<MailTemplateSummary[]>>(
    accessToken,
    systemSettingsEndpoints.emailTemplateList
  ).then((response) => response.data);
}

export function getMailTemplate(accessToken: string, name: string) {
  const endpoint = new URL(
    systemSettingsEndpoints.emailTemplateGet,
    window.location.origin
  );
  endpoint.searchParams.set("name", name);
  return settingsRequest<XboardResponse<MailTemplateDetail>>(
    accessToken,
    `${endpoint.pathname}${endpoint.search}`
  ).then((response) => response.data);
}

export function saveMailTemplate(
  accessToken: string,
  values: Pick<MailTemplateDetail, "name" | "subject" | "content">
) {
  return settingsRequest<XboardResponse<boolean>>(
    accessToken,
    systemSettingsEndpoints.emailTemplateSave,
    { method: "POST", body: JSON.stringify(values) }
  );
}

export function resetMailTemplate(accessToken: string, name: string) {
  return settingsRequest<XboardResponse<boolean>>(
    accessToken,
    systemSettingsEndpoints.emailTemplateReset,
    { method: "POST", body: JSON.stringify({ name }) }
  );
}

export function testMailTemplate(
  accessToken: string,
  name: string,
  email?: string
) {
  return settingsRequest<XboardResponse<boolean>>(
    accessToken,
    systemSettingsEndpoints.emailTemplateTest,
    { method: "POST", body: JSON.stringify({ name, email }) }
  );
}

export function getPlanOptions(accessToken: string) {
  return settingsRequest<XboardResponse<PlanOption[]>>(
    accessToken,
    systemSettingsEndpoints.plans
  ).then((response) => response.data);
}

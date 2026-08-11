import type { AdminLanguage } from "./adminNavigation";
import type {
  SettingValue,
  SystemSettingsSection
} from "./systemSettingsApi";

type Localized = Record<AdminLanguage, string>;

export type SettingsField = {
  key: string;
  label: Localized;
  description: Localized;
  placeholder?: Localized;
  type: "text" | "password" | "url" | "number" | "toggle" | "select" | "textarea" | "list";
  defaultValue: SettingValue;
  options?: Array<{ value: string | number; label: Localized }>;
  min?: number;
  max?: number;
  step?: number;
  readOnly?: boolean;
  rows?: number;
  visibleWhen?: { key: string; value?: SettingValue };
};

export type SettingsSectionDefinition = {
  id: SystemSettingsSection;
  title: Localized;
  description: Localized;
  glyph: string;
  fields: SettingsField[];
};

const text = (zh: string, en: string): Localized => ({
  "zh-CN": zh,
  "en-US": en
});

const option = (value: string | number, zh: string, en: string) => ({
  value,
  label: text(zh, en)
});

const field = (
  key: string,
  zh: string,
  en: string,
  descriptionZh: string,
  descriptionEn: string,
  type: SettingsField["type"],
  defaultValue: SettingValue,
  extra: Partial<SettingsField> = {}
): SettingsField => ({
  key,
  label: text(zh, en),
  description: text(descriptionZh, descriptionEn),
  type,
  defaultValue,
  ...extra
});

export const systemSettingsSections: SettingsSectionDefinition[] = [
  {
    id: "site",
    glyph: "▥",
    title: text("站点设置", "Site settings"),
    description: text(
      "配置站点基本信息，包括站点名称、描述、货币单位等核心设置。",
      "Configure core site information, including its name, description, and currency."
    ),
    fields: [
      field("app_name", "站点名称", "Site name", "用于显示需要站点名称的地方。", "Shown wherever the site name is required.", "text", "", {
        placeholder: text("请输入站点名称", "Enter site name")
      }),
      field("app_description", "站点描述", "Site description", "用于显示需要站点描述的地方。", "Shown wherever the site description is required.", "text", "", {
        placeholder: text("请输入站点描述", "Enter site description")
      }),
      field("app_url", "站点网址", "Site URL", "当前网站最新网址，将会在邮件等需要用于网址处体现。", "The current public URL used in emails and other links.", "url", "", {
        placeholder: text("请输入站点URL，末尾不要/", "Enter site URL without a trailing slash")
      }),
      field("force_https", "强制HTTPS", "Force HTTPS", "当站点没有使用HTTPS，CDN或反代开启强制HTTPS时需要开启。", "Enable when HTTPS is enforced by a CDN or reverse proxy.", "toggle", 0),
      field("logo", "LOGO", "Logo", "用于显示需要LOGO的地方。", "Shown wherever the site logo is required.", "url", "", {
        placeholder: text("请输入LOGO URL，末尾不要/", "Enter logo URL without a trailing slash")
      }),
      field("subscribe_url", "订阅URL", "Subscription URL", "用于订阅所使用，留空则为站点URL。", "Used for subscriptions; leave blank to use the site URL.", "textarea", "", {
        placeholder: text("多个订阅地址用','隔开，留空则为站点URL", "Separate multiple URLs with commas; blank uses the site URL"),
        rows: 3
      }),
      field("tos_url", "用户条款(TOS)URL", "Terms of service URL", "用于跳转到用户条款(TOS)。", "Used to open the terms of service.", "url", "", {
        placeholder: text("请输入用户条款URL，末尾不要/", "Enter terms URL without a trailing slash")
      }),
      field("stop_register", "停止新用户注册", "Disable new registrations", "开启后任何人都将无法进行注册。", "No one can register after this is enabled.", "toggle", 0),
      field("ticket_must_wait_reply", "工单等待回复限制", "Ticket reply restriction", "开启后，用户在管理员回复前无法在同一工单内连续发送消息。", "Users cannot send another message before an administrator replies.", "toggle", false),
      field("try_out_plan_id", "注册试用", "Registration trial", "选择需要试用的订阅，如果没有选项请先前往订阅管理添加。", "Select the trial plan. Add a plan first if no option is available.", "select", 0, {
        options: [option(0, "关闭", "Disabled")]
      }),
      field("try_out_hour", "注册试用时长", "Trial duration", "注册试用时长，单位为小时。", "Trial duration in hours.", "number", 0, {
        min: 0,
        placeholder: text("0", "0"),
        visibleWhen: { key: "try_out_plan_id" }
      }),
      field("currency", "货币单位", "Currency", "仅用于展示使用，更改后系统中所有的货币单位都将发生变更。", "Display only; changing it updates currency labels throughout the system.", "text", "", {
        placeholder: text("CNY", "CNY")
      }),
      field("currency_symbol", "货币符号", "Currency symbol", "仅用于展示使用，更改后系统中所有的货币单位都将发生变更。", "Display only; changing it updates currency symbols throughout the system.", "text", "", {
        placeholder: text("¥", "¥")
      })
    ]
  },
  {
    id: "safe",
    glyph: "◆",
    title: text("安全设置", "Security settings"),
    description: text(
      "配置系统安全相关选项，包括登录验证、密码策略、API访问等安全设置。",
      "Configure login verification, password policy, API access, and related safeguards."
    ),
    fields: [
      field("email_verify", "邮箱验证", "Email verification", "开启后将会强制要求用户进行邮箱验证。", "Require users to verify their email addresses.", "toggle", false),
      field("email_gmail_limit_enable", "禁止使用Gmail多别名", "Block Gmail aliases", "开启后Gmail多别名将无法注册。", "Prevent registration through Gmail aliases.", "toggle", false),
      field("safe_mode_enable", "安全模式", "Safe mode", "开启后除了站点URL以外的绑定本站点的域名访问都将会被403。", "Return 403 for bound hostnames other than the configured site URL.", "toggle", false),
      field("secure_path", "后台路径", "Administrator path", "后台管理路径，修改后将会改变原有的admin路径。", "Changing this replaces the existing administrator path.", "text", "", {
        placeholder: text("admin", "admin")
      }),
      field("email_whitelist_enable", "邮箱后缀白名单", "Email suffix allowlist", "开启后在名单中的邮箱后缀才允许进行注册。", "Only listed email suffixes may register.", "toggle", false),
      field("email_whitelist_suffix", "邮箱后缀", "Email suffixes", "输入允许的邮箱后缀，每行一个。", "Enter one allowed email suffix per line.", "list", [], {
        placeholder: text("输入邮箱后缀，每行一个", "Enter one email suffix per line"),
        visibleWhen: { key: "email_whitelist_enable" }
      }),
      field("captcha_enable", "启用验证码", "Enable CAPTCHA", "开启后用户注册时需要通过验证码验证。", "Require CAPTCHA verification during registration.", "toggle", false),
      field("captcha_type", "验证码类型", "CAPTCHA provider", "选择要使用的验证码服务类型。", "Select the CAPTCHA provider.", "select", "recaptcha", {
        visibleWhen: { key: "captcha_enable" },
        options: [
          option("recaptcha", "Google reCAPTCHA v2", "Google reCAPTCHA v2"),
          option("recaptcha-v3", "Google reCAPTCHA v3", "Google reCAPTCHA v3"),
          option("turnstile", "Cloudflare Turnstile", "Cloudflare Turnstile")
        ]
      }),
      field("recaptcha_key", "reCAPTCHA密钥", "reCAPTCHA secret key", "输入您的reCAPTCHA密钥。", "Enter the reCAPTCHA secret key.", "password", "", {
        visibleWhen: { key: "captcha_type", value: "recaptcha" }
      }),
      field("recaptcha_site_key", "reCAPTCHA站点密钥", "reCAPTCHA site key", "输入您的reCAPTCHA站点密钥。", "Enter the reCAPTCHA site key.", "text", "", {
        visibleWhen: { key: "captcha_type", value: "recaptcha" }
      }),
      field("recaptcha_v3_secret_key", "reCAPTCHA v3密钥", "reCAPTCHA v3 secret key", "输入您的reCAPTCHA v3服务器密钥。", "Enter the reCAPTCHA v3 server key.", "password", "", {
        visibleWhen: { key: "captcha_type", value: "recaptcha-v3" }
      }),
      field("recaptcha_v3_site_key", "reCAPTCHA v3站点密钥", "reCAPTCHA v3 site key", "输入您的reCAPTCHA v3站点密钥。", "Enter the reCAPTCHA v3 site key.", "text", "", {
        visibleWhen: { key: "captcha_type", value: "recaptcha-v3" }
      }),
      field("recaptcha_v3_score_threshold", "分数阈值", "Score threshold", "设置验证分数阈值（0-1），分数越高表示越可能是真人操作。", "Set the verification threshold from 0 to 1.", "number", 0.5, {
        min: 0,
        max: 1,
        step: 0.1,
        visibleWhen: { key: "captcha_type", value: "recaptcha-v3" }
      }),
      field("turnstile_secret_key", "Turnstile密钥", "Turnstile secret key", "输入您的Cloudflare Turnstile密钥。", "Enter the Cloudflare Turnstile secret key.", "password", "", {
        visibleWhen: { key: "captcha_type", value: "turnstile" }
      }),
      field("turnstile_site_key", "Turnstile站点密钥", "Turnstile site key", "输入您的Cloudflare Turnstile站点密钥。", "Enter the Cloudflare Turnstile site key.", "text", "", {
        visibleWhen: { key: "captcha_type", value: "turnstile" }
      }),
      field("register_limit_by_ip_enable", "IP注册限制", "IP registration limit", "开启后将限制同一IP的注册次数。", "Limit registrations from the same IP address.", "toggle", false),
      field("register_limit_count", "注册次数", "Registration count", "同一IP允许的最大注册次数。", "Maximum registrations allowed from one IP.", "number", 3, {
        visibleWhen: { key: "register_limit_by_ip_enable" }
      }),
      field("register_limit_expire", "限制时长", "Limit duration", "注册限制的持续时间（分钟）。", "Registration limit duration in minutes.", "number", 60, {
        visibleWhen: { key: "register_limit_by_ip_enable" }
      }),
      field("password_limit_enable", "密码尝试限制", "Password attempt limit", "开启后将限制密码尝试次数。", "Limit password attempts.", "toggle", true),
      field("password_limit_count", "尝试次数", "Attempt count", "允许的最大密码尝试次数。", "Maximum password attempts.", "number", 5, {
        visibleWhen: { key: "password_limit_enable" }
      }),
      field("password_limit_expire", "锁定时长", "Lock duration", "账户锁定的持续时间（分钟）。", "Account lock duration in minutes.", "number", 60, {
        visibleWhen: { key: "password_limit_enable" }
      })
    ]
  },
  {
    id: "subscribe",
    glyph: "↗",
    title: text("订阅设置", "Subscription settings"),
    description: text(
      "管理用户订阅相关配置，包括订阅链接格式、更新频率、流量统计等设置。",
      "Manage subscription URL format, update behavior, and traffic settings."
    ),
    fields: [
      field("plan_change_enable", "允许用户更改订阅", "Allow subscription changes", "开启后用户将会可以对订阅计划进行变更。", "Allow users to change subscription plans.", "toggle", false),
      field("reset_traffic_method", "月流量重置方式", "Traffic reset method", "全局流量重置方式，默认每月1号。可以在订阅管理为订阅单独设置。", "Global reset method; individual plans can override it.", "select", 0, {
        options: [
          option(0, "每月1号", "First day of each month"),
          option(1, "按月重置", "Monthly reset"),
          option(2, "不重置", "No reset"),
          option(3, "每年1月1号", "January 1 each year"),
          option(4, "按年重置", "Yearly reset")
        ]
      }),
      field("surplus_enable", "开启折抵方案", "Enable proration", "开启后用户更换订阅将会由系统对原有订阅进行折抵，方案参考文档。", "Prorate the existing plan when users change subscriptions.", "toggle", false),
      field("new_order_event_id", "当订阅新购时触发事件", "New order event", "新购订阅完成时将触发该任务。", "Run after a new subscription order completes.", "select", 0, {
        options: [option(0, "不执行任何动作", "Do nothing"), option(1, "重置用户流量", "Reset user traffic")]
      }),
      field("renew_order_event_id", "当订阅续费时触发事件", "Renewal event", "续费订阅完成时将触发该任务。", "Run after a subscription renewal completes.", "select", 0, {
        options: [option(0, "不执行任何动作", "Do nothing"), option(1, "重置用户流量", "Reset user traffic")]
      }),
      field("change_order_event_id", "当订阅变更时触发事件", "Plan change event", "变更订阅完成时将触发该任务。", "Run after a plan change completes.", "select", 0, {
        options: [option(0, "不执行任何动作", "Do nothing"), option(1, "重置用户流量", "Reset user traffic")]
      }),
      field("subscribe_path", "订阅路径", "Subscription path", "订阅路径，修改后将会改变原有的subscribe路径。修改后可能需要重启服务。", "Changing the subscription path may require a service restart.", "text", "s", {
        placeholder: text("subscribe", "subscribe")
      }),
      field("show_info_to_server_enable", "在订阅中展示订阅信息", "Show subscription information", "开启后将会在用户订阅节点时输出订阅信息。", "Output subscription information with user nodes.", "toggle", false),
      field("show_protocol_to_server_enable", "在订阅中线路名称中显示协议名称", "Show protocol in route names", "开启后订阅线路会附带协议名称（例如: [Hy2]香港）。", "Prefix route names with the protocol, for example [Hy2] Hong Kong.", "toggle", false)
    ]
  },
  {
    id: "invite",
    glyph: "%",
    title: text("邀请&佣金设置", "Invite and commission"),
    description: text("邀请注册、佣金相关设置。", "Configure invitations and commission."),
    fields: [
      field("invite_force", "开启强制邀请", "Require invitations", "开启后只有被邀请的用户才可以进行注册。", "Only invited users may register.", "toggle", false),
      field("invite_commission", "邀请佣金百分比", "Invitation commission percent", "默认全局的佣金分配比例，你可以在用户管理单独配置单个比例。", "Default global commission rate; individual users may override it.", "number", 0),
      field("invite_gen_limit", "用户可创建邀请码上限", "Invitation code limit", "用户可创建邀请码上限。", "Maximum invitation codes a user may create.", "number", 0),
      field("invite_never_expire", "邀请码永不失效", "Invitation codes never expire", "开启后邀请码被使用后将不会失效，否则使用过后即失效。", "Keep invitation codes valid after use.", "toggle", false),
      field("commission_first_time_enable", "佣金仅首次发放", "First payment commission only", "开启后被邀请人首次支付时才会产生佣金，可以在用户管理对用户进行单独配置。", "Only the invitee's first payment generates commission.", "toggle", false),
      field("commission_auto_check_enable", "佣金自动确认", "Automatically confirm commission", "开启后佣金将会在订单完成3日后自动进行确认。", "Confirm commission three days after order completion.", "toggle", false),
      field("commission_withdraw_limit", "提现单申请门槛(元)", "Minimum withdrawal", "小于门槛金额的提现单将不会被提交。", "Withdrawals below this amount cannot be submitted.", "number", 0),
      field("commission_withdraw_method", "提现方式", "Withdrawal methods", "可以支持的提现方式，多个用逗号分隔。", "Separate supported withdrawal methods with commas.", "list", ["支付宝", "USDT", "Paypal"]),
      field("withdraw_close_enable", "关闭提现", "Disable withdrawal", "关闭后将禁止用户申请提现，且邀请佣金将会直接进入用户余额。", "Prevent withdrawals and move invitation commission directly into user balance.", "toggle", false),
      field("commission_distribution_enable", "三级分销", "Three-level distribution", "开启后佣金将按照设置的3层比例进行分成，三层比例合计请不要大于100%。", "Distribute commission across three levels; the total must not exceed 100%.", "toggle", false),
      field("commission_distribution_l1", "一级邀请人比例", "Level 1 rate", "请输入比例，如：50。", "Enter a percentage, for example 50.", "number", 0, {
        visibleWhen: { key: "commission_distribution_enable" }
      }),
      field("commission_distribution_l2", "二级邀请人比例", "Level 2 rate", "请输入比例，如：50。", "Enter a percentage, for example 50.", "number", 0, {
        visibleWhen: { key: "commission_distribution_enable" }
      }),
      field("commission_distribution_l3", "三级邀请人比例", "Level 3 rate", "请输入比例，如：50。", "Enter a percentage, for example 50.", "number", 0, {
        visibleWhen: { key: "commission_distribution_enable" }
      })
    ]
  },
  {
    id: "server",
    glyph: "▰",
    title: text("节点配置", "Node settings"),
    description: text(
      "配置节点通信和同步设置，包括通信密钥、轮询间隔、负载均衡等高级选项。",
      "Configure node communication and synchronization settings."
    ),
    fields: [
      field("server_token", "通讯密钥", "Communication key", "由系统生成的 256 位随机密钥，不允许手动输入。", "A system-generated 256-bit random key that cannot be entered manually.", "text", "", {
        readOnly: true
      }),
      field("server_pull_interval", "节点拉取动作轮询间隔", "Node pull interval", "节点从面板获取数据的间隔频率，单位为秒；Xboard-Node 机器模式最小为 30 秒。", "How often nodes pull data from the panel, in seconds; Xboard-Node machine mode requires at least 30 seconds.", "number", 60, {
        min: 30,
        max: 3600,
        step: 1
      }),
      field("server_push_interval", "节点推送动作轮询间隔", "Node push interval", "节点推送数据到面板的间隔频率，单位为秒；Xboard-Node 机器模式最小为 10 秒。", "How often nodes push data to the panel, in seconds; Xboard-Node machine mode requires at least 10 seconds.", "number", 60, {
        min: 10,
        max: 3600,
        step: 1
      }),
      field("server_ws_enable", "启用 WebSocket 通信", "Enable WebSocket communication", "开启后节点将通过 WebSocket 与面板进行实时通信，延迟更低、推送更及时。", "Use WebSocket for lower-latency real-time node communication.", "toggle", true),
      field("server_ws_url", "WebSocket 地址", "WebSocket URL", "节点连接面板的 WebSocket 地址，留空则自动使用站点网址。", "Leave blank to use the site URL.", "url", "", {
        visibleWhen: { key: "server_ws_enable" }
      })
    ]
  },
  {
    id: "email",
    glyph: "✉",
    title: text("邮件设置", "Email settings"),
    description: text(
      "配置系统邮件服务，用于发送验证码、密码重置、通知等邮件，支持多种SMTP服务商。",
      "Configure SMTP delivery for verification, password reset, and notification emails."
    ),
    fields: [
      field("email_host", "SMTP主机", "SMTP host", "SMTP服务器地址，例如：smtp.gmail.com。", "SMTP server address, for example smtp.gmail.com.", "text", ""),
      field("email_port", "SMTP端口", "SMTP port", "SMTP服务器端口，常用端口：25, 465, 587。", "SMTP port; common values are 25, 465, and 587.", "number", 465),
      field("email_encryption", "加密方式", "Encryption", "邮件加密方式。", "Mail transport encryption.", "select", "", {
        options: [option("", "无", "None"), option("ssl", "SSL/TLS", "SSL/TLS"), option("tls", "STARTTLS", "STARTTLS")]
      }),
      field("email_username", "SMTP用户名", "SMTP username", "SMTP认证用户名。", "SMTP authentication username.", "text", ""),
      field("email_password", "SMTP密码", "SMTP password", "SMTP认证密码或应用专用密码。", "SMTP password or application-specific password.", "password", ""),
      field("email_from_address", "发件人地址", "From address", "发件人邮箱地址。", "Sender email address.", "text", ""),
      field("remind_mail_enable", "邮件提醒", "Email reminders", "开启后用户订阅即将到期或流量不足时会收到邮件通知。", "Notify users when subscriptions are expiring or traffic is low.", "toggle", false)
    ]
  },
  {
    id: "telegram",
    glyph: "➤",
    title: text("Telegram设置", "Telegram settings"),
    description: text(
      "配置Telegram机器人功能，实现用户通知、账户绑定、指令交互等自动化服务。",
      "Configure Telegram notifications, account binding, and bot commands."
    ),
    fields: [
      field("telegram_bot_token", "机器人令牌", "Bot token", "请输入从Botfather获取的令牌。", "Enter the token issued by BotFather.", "password", ""),
      field("telegram_webhook_url", "Webhook Base URL", "Webhook Base URL", "这里只填写基础地址，系统会自动拼接 Telegram 的完整 Webhook 回调路径。留空时默认使用站点网址。", "Enter only the base URL. The callback path is added automatically.", "url", ""),
      field("telegram_bot_enable", "启用Telegram绑定引导", "Enable Telegram binding guide", "开启后将在用户端显示Telegram绑定引导，帮助用户绑定Telegram账户以接收通知。", "Show the Telegram binding guide in the user portal.", "toggle", false),
      field("telegram_discuss_link", "群组链接", "Group link", "填写后将在用户端显示或在需要的地方使用。", "Displayed in the user portal and other applicable locations.", "url", "")
    ]
  },
  {
    id: "app",
    glyph: "▣",
    title: text("APP设置", "App settings"),
    description: text(
      "管理移动应用程序相关配置，包括API接口、版本控制、推送通知等功能设置。",
      "Manage application versions and download links."
    ),
    fields: [
      field("windows_version", "Windows版本", "Windows version", "Windows客户端当前版本号。", "Current Windows client version.", "text", ""),
      field("windows_download_url", "Windows下载地址", "Windows download URL", "Windows客户端下载链接。", "Windows client download link.", "text", ""),
      field("macos_version", "macOS版本", "macOS version", "macOS客户端当前版本号。", "Current macOS client version.", "text", ""),
      field("macos_download_url", "macOS下载地址", "macOS download URL", "macOS客户端下载链接。", "macOS client download link.", "text", ""),
      field("android_version", "Android版本", "Android version", "Android客户端当前版本号。", "Current Android client version.", "text", ""),
      field("android_download_url", "Android下载地址", "Android download URL", "Android客户端下载链接。", "Android client download link.", "text", "")
    ]
  },
  {
    id: "subscribe_template",
    glyph: "⌘",
    title: text("订阅模板", "Subscription templates"),
    description: text("配置各个客户端的订阅模板。", "Configure templates for each supported client."),
    fields: [
      field("subscribe_template_singbox", "Sing-box 订阅模板", "Sing-box", "配置 Sing-box 的订阅模板格式。", "Configure the Sing-box template.", "textarea", "", { rows: 20 }),
      field("subscribe_template_clash", "Clash 订阅模板", "Clash", "配置 Clash 的订阅模板格式。", "Configure the Clash template.", "textarea", "", { rows: 20 }),
      field("subscribe_template_clashmeta", "Clash Meta 订阅模板", "Clash Meta", "配置 Clash Meta 的订阅模板格式。", "Configure the Clash Meta template.", "textarea", "", { rows: 20 }),
      field("subscribe_template_stash", "Stash 订阅模板", "Stash", "配置 Stash 的订阅模板格式。", "Configure the Stash template.", "textarea", "", { rows: 20 }),
      field("subscribe_template_surge", "Surge 配置模板", "Surge", "配置 Surge 订阅模板，支持 Surge 配置文件格式。", "Configure the Surge template.", "textarea", "", { rows: 20 }),
      field("subscribe_template_surfboard", "Surfboard 配置模版", "Surfboard", "配置 Surfboard 订阅模版。", "Configure the Surfboard template.", "textarea", "", { rows: 20 })
    ]
  }
];

export function getSettingsDefaults(section: SettingsSectionDefinition) {
  return Object.fromEntries(
    section.fields.map((setting) => [setting.key, setting.defaultValue])
  );
}

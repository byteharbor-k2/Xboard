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
  type: "text" | "password" | "url" | "number" | "toggle" | "select" | "textarea" | "list" | "status";
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
      "配置站点基本信息，包括站点名称、站点网址、订阅地址等核心设置。",
      "Configure core site information: its name, public URL, and subscription address."
    ),
    fields: [
      field("app_name", "站点名称", "Site name", "用于显示需要站点名称的地方。", "Shown wherever the site name is required.", "text", "", {
        placeholder: text("请输入站点名称", "Enter site name")
      }),
      field("app_url", "站点网址", "Site URL", "当前网站最新网址，将会在邮件等需要用于网址处体现。", "The current public URL used in emails and other links.", "url", "", {
        placeholder: text("请输入站点URL，末尾不要/", "Enter site URL without a trailing slash")
      }),
      field("subscribe_url", "订阅URL", "Subscription URL", "用于订阅所使用，留空则为站点URL。", "Used for subscriptions; leave blank to use the site URL.", "textarea", "", {
        placeholder: text("多个订阅地址用','隔开，留空则为站点URL", "Separate multiple URLs with commas; blank uses the site URL"),
        rows: 3
      }),
      field("tos_url", "用户条款(TOS)URL", "Terms of service URL", "用于跳转到用户条款(TOS)。", "Used to open the terms of service.", "url", "", {
        placeholder: text("请输入用户条款URL，末尾不要/", "Enter terms URL without a trailing slash")
      }),
      field("stop_register", "停止新用户注册", "Disable new registrations", "开启后任何人都将无法进行注册。", "No one can register after this is enabled.", "toggle", 0)
    ]
  },
  {
    id: "new_user",
    glyph: "✦",
    title: text("新用户福利", "New user benefits"),
    description: text(
      "为新注册用户配置免费试用与专属流量包优惠。套餐权益和价格沿用套餐管理中的设置。",
      "Configure a trial and a special traffic-package offer for new users. Plan benefits and prices come from plan management."
    ),
    fields: [
      field("try_out_plan_id", "注册试用套餐", "Registration trial plan", "新用户注册成功后立即获得该订阅套餐的流量、限速和权限组；留空则不赠送试用。", "Newly registered users immediately receive this subscription plan's traffic, speed, and access group. Leave blank to disable the trial.", "select", "", {
        options: [option("", "关闭试用", "Disabled")]
      }),
      field("try_out_hour", "试用时长（小时）", "Trial duration (hours)", "试用从注册成功时开始计时，默认 3 小时。", "The trial starts at successful registration and defaults to 3 hours.", "number", 3, {
        min: 1,
        visibleWhen: { key: "try_out_plan_id" }
      }),
      field("new_user_offer_plan_id", "新用户专属流量包", "New user traffic-package offer", "选择现有流量包；其一次性价格即为新用户专属优惠价，不会创建额外套餐。", "Choose an existing traffic package. Its one-time price is the special offer price; no extra product is created.", "select", "", {
        options: [option("", "不展示专属优惠", "No special offer")]
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
      field("email_whitelist_enable", "邮箱后缀白名单", "Email suffix allowlist", "开启后在名单中的邮箱后缀才允许进行注册。", "Only listed email suffixes may register.", "toggle", false),
      field("email_whitelist_suffix", "邮箱后缀", "Email suffixes", "输入允许的邮箱后缀，每行一个。", "Enter one allowed email suffix per line.", "list", [], {
        placeholder: text("输入邮箱后缀，每行一个", "Enter one email suffix per line"),
        visibleWhen: { key: "email_whitelist_enable" }
      }),
      field("captcha_enable", "启用人机验证", "Enable human verification", "开启后注册及邮箱验证码请求需要通过人机验证。", "Require human verification for registration and email-code requests.", "toggle", false),
      field("captcha_type", "人机验证服务", "Human verification provider", "目前仅支持 Cloudflare Turnstile。", "Cloudflare Turnstile is the supported provider.", "select", "turnstile", {
        options: [
          option("turnstile", "Cloudflare Turnstile", "Cloudflare Turnstile")
        ]
      }),
      field("turnstile_site_key", "Turnstile站点密钥（公开）", "Turnstile site key (public)", "填写 Cloudflare 提供的 Site Key。Cloudflare 小组件的 hostname allowlist 需包含 dev.sinx.it.com；生产环境请另行加入生产域名。", "Enter the public Site Key from Cloudflare. The widget hostname allowlist must include dev.sinx.it.com; add the production hostname separately.", "text", "", {
        visibleWhen: { key: "captcha_type", value: "turnstile" }
      }),
      field("turnstile_secret_key", "Turnstile密钥（仅服务器）", "Turnstile secret key (server only)", "密钥仅保存在服务器，不会返回给用户端；留空保存或编辑其他设置不会清除已保存密钥。", "This secret stays on the server and is never returned to users. Leave blank when saving or editing other settings to keep the stored secret.", "password", "", {
        placeholder: text("输入新密钥；留空保留已保存值", "Enter a new secret; leave blank to keep the stored value"),
        visibleWhen: { key: "captcha_type", value: "turnstile" }
      }),
      field("turnstile_secret_key_configured", "服务器密钥状态", "Server secret status", "仅显示是否已保存，不会显示密钥内容。", "Shows whether a secret is stored without exposing its value.", "status", false, {
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
      "管理用户套餐变更、折抵与全局流量重置方式。",
      "Manage plan changes, proration, and the global traffic reset policy."
    ),
    fields: [
      field("plan_change_enable", "允许用户更改订阅", "Allow subscription changes", "开启后用户将会可以对订阅计划进行变更。", "Allow users to change subscription plans.", "toggle", true),
      field("globalreset_traffic_method", "全局流量重置方式", "Global traffic reset method", "全局流量重置方式，默认按开通日每月重置。日历重置时间使用 Asia/Shanghai 时区；套餐可选择跟随全局设置或单独覆盖。", "Global reset method; the default is monthly from activation. Calendar resets use Asia/Shanghai time. Plans can follow this setting or override it.", "select", 1, {
        options: [
          option(0, "每月 1 日（Asia/Shanghai）", "First day of each month (Asia/Shanghai)"),
          option(1, "按开通日每月", "Monthly from activation"),
          option(2, "不重置", "Never"),
          option(3, "每年 1 月 1 日（Asia/Shanghai）", "January 1 each year (Asia/Shanghai)"),
          option(4, "按开通日每年", "Yearly from activation")
        ]
      }),
      field("surplus_enable", "开启折抵方案", "Enable proration", "开启后用户更换订阅将会由系统对原有订阅进行折抵，方案参考文档。", "Prorate the existing plan when users change subscriptions.", "toggle", true),
      field("default_remind_expire", "新用户默认到期提醒", "Default expiry reminder for new users", "仅设置新注册账号的初始偏好；已注册用户的个人设置不会改变。默认开启。", "Sets the initial preference for newly registered accounts only; existing users' preferences are unchanged. Enabled by default.", "toggle", true),
      field("default_remind_traffic", "新用户默认流量提醒", "Default traffic reminder for new users", "仅设置新注册账号的初始偏好；已注册用户的个人设置不会改变。默认开启。", "Sets the initial preference for newly registered accounts only; existing users' preferences are unchanged. Enabled by default.", "toggle", true)
    ]
  },
  {
    id: "invite",
    glyph: "%",
    title: text("邀请&佣金设置", "Invite and commission"),
    description: text("邀请注册、佣金相关设置。", "Configure invitations and commission."),
    fields: [
      field("invite_force", "开启强制邀请", "Require invitations", "开启后只有被邀请的用户才可以进行注册。", "Only invited users may register.", "toggle", false),
      field("invite_commission", "邀请佣金百分比", "Invitation commission percent", "默认全局的佣金分配比例，你可以在用户管理单独配置单个比例。", "Default global commission rate; individual users may override it.", "number", 10, { min: 0, max: 100 }),
      field("invite_gen_limit", "用户可创建邀请码上限", "Invitation code limit", "用户可创建邀请码上限。", "Maximum invitation codes a user may create.", "number", 0),
      field("invite_never_expire", "邀请码永不失效", "Invitation codes never expire", "开启后邀请码被使用后将不会失效，否则使用过后即失效。", "Keep invitation codes valid after use.", "toggle", false),
      field("commission_first_time_enable", "佣金仅首次发放", "First payment commission only", "开启后被邀请人首次支付时才会产生佣金，可以在用户管理对用户进行单独配置。", "Only the invitee's first payment generates commission.", "toggle", true),
      field("commission_auto_check_enable", "佣金自动确认", "Automatically confirm commission", "开启后佣金将在订单完成 3 天后自动确认并发放到用户站点余额。", "Automatically confirm and credit commission to the user's site balance 3 days after order completion.", "toggle", true),
      field("commission_payout_info", "佣金发放方式", "Commission payout", "佣金确认后直接发放至用户普通站点余额，可用于后续订单结算；不提供提现功能。", "Confirmed commission is credited directly to the user's ordinary site balance for future order checkout. Withdrawals are not available.", "status", true),
      field("commission_distribution_enable", "三级分销", "Three-level distribution", "开启后佣金将按照设置的3层比例进行分成，三层比例合计请不要大于100%。", "Distribute commission across three levels; the total must not exceed 100%.", "toggle", false),
      field("commission_distribution_l1", "一级邀请人比例", "Level 1 rate", "请输入比例，如：50。", "Enter a percentage, for example 50.", "number", 0, {
        min: 0,
        max: 100,
        visibleWhen: { key: "commission_distribution_enable" }
      }),
      field("commission_distribution_l2", "二级邀请人比例", "Level 2 rate", "请输入比例，如：50。", "Enter a percentage, for example 50.", "number", 0, {
        min: 0,
        max: 100,
        visibleWhen: { key: "commission_distribution_enable" }
      }),
      field("commission_distribution_l3", "三级邀请人比例", "Level 3 rate", "请输入比例，如：50。", "Enter a percentage, for example 50.", "number", 0, {
        min: 0,
        max: 100,
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
      field("email_delivery", "邮件投递方式", "Email delivery mode", "选择邮件如何送出：日志模式仅把邮件内容写入后台日志，适合开发调试；SMTP模式按下方配置真实发送邮件。", "Choose how mail is sent: log mode only writes mail contents to the backend log for development, SMTP mode sends real mail through the settings below.", "select", "log", {
        options: [
          option("log", "日志（开发模式）", "Log (development)"),
          option("smtp", "SMTP（真实发送）", "SMTP (real sending)")
        ]
      }),
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
    id: "subscribe_template",
    glyph: "⌘",
    title: text("订阅模板", "Subscription templates"),
    description: text(
      "配置各个客户端的订阅模板。留空即使用内置模板，清空即恢复默认。Save 前会校验模板能否解析、是否保留必需内容，不通过则拒绝保存。",
      "Configure templates for each supported client. Leaving one blank restores the built-in template. A template is validated before it is saved - one that will not parse, or that has lost what the renderer writes into, is refused rather than served to everyone."
    ),
    fields: [
      field("subscribe_template_singbox", "Sing-box 订阅模板", "Sing-box", "配置 Sing-box 的订阅模板格式。必须保留 outbounds 键，节点会写入其中。", "Configure the Sing-box template. The outbounds key must stay present; the nodes are written into it.", "textarea", "", { rows: 20 }),
      field("subscribe_template_clash", "Clash 订阅模板", "Clash", "配置 Clash 的订阅模板格式。必须保留 proxies、proxy-groups、rules 三个顶层键；$app_name 会被替换为站点名。", "Configure the Clash template. The proxies, proxy-groups and rules keys must all stay present; $app_name is replaced with the site name.", "textarea", "", { rows: 20 }),
      field("subscribe_template_clashmeta", "Clash Meta 订阅模板", "Clash Meta", "配置 Clash Meta（mihomo）的订阅模板格式。必须保留 proxies、proxy-groups、rules 三个顶层键。", "Configure the Clash Meta (mihomo) template. The proxies, proxy-groups and rules keys must all stay present.", "textarea", "", { rows: 20 }),
      field("subscribe_template_stash", "Stash 订阅模板", "Stash", "配置 Stash 的订阅模板格式。与 Clash Meta 同构，必须保留 proxies、proxy-groups、rules 三个顶层键。", "Configure the Stash template. It is Clash-shaped, so the proxies, proxy-groups and rules keys must all stay present.", "textarea", "", { rows: 20 }),
      field("subscribe_template_surge", "Surge 配置模板", "Surge", "配置 Surge 模板。必须保留 $proxies 与 $proxy_group 占位符；$subs_link、$subs_domain 和 $subscribe_info 也会被替换。", "Configure the Surge template. The $proxies and $proxy_group placeholders must stay; $subs_link, $subs_domain and $subscribe_info are replaced too.", "textarea", "", { rows: 20 }),
      field("subscribe_template_surfboard", "Surfboard 配置模板", "Surfboard", "配置 Surfboard 模板。写法与 Surge 相同，必须保留 $proxies 与 $proxy_group 占位符。", "Configure the Surfboard template. Its syntax follows Surge's, and the $proxies and $proxy_group placeholders must stay.", "textarea", "", { rows: 20 })
    ]
  }
];

export function getSettingsDefaults(section: SettingsSectionDefinition) {
  return Object.fromEntries(
    section.fields.map((setting) => [setting.key, setting.defaultValue])
  );
}

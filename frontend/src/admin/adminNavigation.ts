export type AdminLanguage = "zh-CN" | "en-US";

export type AdminNavItem = {
  id: string;
  href: string;
  glyph: string;
  label: Record<AdminLanguage, string>;
  description: Record<AdminLanguage, string>;
};

export type AdminNavGroup = {
  id: string;
  label: Record<AdminLanguage, string>;
  items: AdminNavItem[];
};

export const adminNavigation: AdminNavGroup[] = [
  {
    id: "overview",
    label: { "zh-CN": "概览", "en-US": "Overview" },
    items: [
      {
        id: "dashboard",
        href: "/admin",
        glyph: "⌂",
        label: { "zh-CN": "仪表盘", "en-US": "Dashboard" },
        description: {
          "zh-CN": "查看收入、用户、流量与系统运行概况。",
          "en-US": "Review revenue, users, traffic, and system health."
        }
      }
    ]
  },
  {
    id: "system",
    label: { "zh-CN": "系统管理", "en-US": "System" },
    items: [
      {
        id: "system-settings",
        href: "/admin/system/settings",
        glyph: "⚙",
        label: { "zh-CN": "系统配置", "en-US": "System settings" },
        description: {
          "zh-CN": "管理站点、安全、订阅、邀请、邮件和通知设置。",
          "en-US": "Manage site, security, subscription, email, and notification settings."
        }
      },
      {
        id: "plugins",
        href: "/admin/system/plugins",
        glyph: "◇",
        label: { "zh-CN": "插件管理", "en-US": "Plugins" },
        description: {
          "zh-CN": "管理新系统支持的扩展能力。",
          "en-US": "Manage extensions supported by the new platform."
        }
      },
      {
        id: "themes",
        href: "/admin/system/themes",
        glyph: "▣",
        label: { "zh-CN": "主题配置", "en-US": "Themes" },
        description: {
          "zh-CN": "管理用户站点视觉配置。",
          "en-US": "Manage visual settings for the user portal."
        }
      },
      {
        id: "notices",
        href: "/admin/content/notices",
        glyph: "▤",
        label: { "zh-CN": "公告管理", "en-US": "Notices" },
        description: {
          "zh-CN": "创建、排序和发布站内公告。",
          "en-US": "Create, sort, and publish portal notices."
        }
      },
      {
        id: "payments",
        href: "/admin/finance/payments",
        glyph: "¤",
        label: { "zh-CN": "支付配置", "en-US": "Payments" },
        description: {
          "zh-CN": "管理支付方式、手续费和启用状态。",
          "en-US": "Manage payment methods, fees, and availability."
        }
      },
      {
        id: "knowledge",
        href: "/admin/content/knowledge",
        glyph: "▥",
        label: { "zh-CN": "知识库管理", "en-US": "Knowledge base" },
        description: {
          "zh-CN": "管理帮助文章、分类和展示状态。",
          "en-US": "Manage help articles, categories, and visibility."
        }
      }
    ]
  },
  {
    id: "nodes",
    label: { "zh-CN": "节点管理", "en-US": "Infrastructure" },
    items: [
      {
        id: "machines",
        href: "/admin/nodes/machines",
        glyph: "▰",
        label: { "zh-CN": "服务器管理", "en-US": "Machines" },
        description: {
          "zh-CN": "管理服务器、节点绑定、Token和运行状态。",
          "en-US": "Manage machines, node bindings, tokens, and health."
        }
      },
      {
        id: "node-list",
        href: "/admin/nodes",
        glyph: "◎",
        label: { "zh-CN": "节点管理", "en-US": "Nodes" },
        description: {
          "zh-CN": "管理代理节点、协议、倍率和批量操作。",
          "en-US": "Manage proxy nodes, protocols, rates, and bulk actions."
        }
      },
      {
        id: "groups",
        href: "/admin/nodes/groups",
        glyph: "⌘",
        label: { "zh-CN": "权限组管理", "en-US": "Permission groups" },
        description: {
          "zh-CN": "管理用户与节点之间的访问分组。",
          "en-US": "Manage access groups connecting users and nodes."
        }
      },
      {
        id: "routes",
        href: "/admin/nodes/routes",
        glyph: "↝",
        label: { "zh-CN": "路由管理", "en-US": "Routes" },
        description: {
          "zh-CN": "管理节点路由和入口分组。",
          "en-US": "Manage node routes and entry groups."
        }
      },
      {
        id: "node-settings",
        href: "/admin/nodes/settings",
        glyph: "▰",
        label: { "zh-CN": "节点配置", "en-US": "Node settings" },
        description: {
          "zh-CN": "配置节点通信密钥、轮询和 WebSocket。",
          "en-US": "Configure node tokens, polling, and WebSocket communication."
        }
      },
      {
        id: "subscription-templates",
        href: "/admin/nodes/subscription-templates",
        glyph: "⌘",
        label: { "zh-CN": "订阅模板", "en-US": "Subscription templates" },
        description: {
          "zh-CN": "管理各客户端使用的订阅配置模板。",
          "en-US": "Manage subscription templates for supported clients."
        }
      }
    ]
  },
  {
    id: "subscriptions",
    label: { "zh-CN": "订阅与交易", "en-US": "Subscriptions & finance" },
    items: [
      {
        id: "plans",
        href: "/admin/finance/plans",
        glyph: "▧",
        label: { "zh-CN": "套餐管理", "en-US": "Plans" },
        description: {
          "zh-CN": "管理套餐、价格周期、流量和设备限制。",
          "en-US": "Manage plans, billing periods, traffic, and device limits."
        }
      },
      {
        id: "orders",
        href: "/admin/finance/orders",
        glyph: "▦",
        label: { "zh-CN": "订单管理", "en-US": "Orders" },
        description: {
          "zh-CN": "查询、分配、支付和取消订单。",
          "en-US": "Review, assign, settle, and cancel orders."
        }
      },
      {
        id: "coupons",
        href: "/admin/finance/coupons",
        glyph: "%",
        label: { "zh-CN": "优惠券管理", "en-US": "Coupons" },
        description: {
          "zh-CN": "管理折扣、使用范围和有效期。",
          "en-US": "Manage discounts, eligibility, and validity."
        }
      },
      {
        id: "gift-cards",
        href: "/admin/finance/gift-cards",
        glyph: "✦",
        label: { "zh-CN": "礼品卡管理", "en-US": "Gift cards" },
        description: {
          "zh-CN": "管理礼品卡模板、兑换码和使用记录。",
          "en-US": "Manage gift card definitions, codes, and usage."
        }
      }
    ]
  },
  {
    id: "users",
    label: { "zh-CN": "用户与支持", "en-US": "Users & support" },
    items: [
      {
        id: "users",
        href: "/admin/users",
        glyph: "◉",
        label: { "zh-CN": "用户管理", "en-US": "Users" },
        description: {
          "zh-CN": "管理用户、订阅凭据、封禁和流量。",
          "en-US": "Manage users, subscription credentials, access, and traffic."
        }
      },
      {
        id: "tickets",
        href: "/admin/support/tickets",
        glyph: "▱",
        label: { "zh-CN": "工单管理", "en-US": "Tickets" },
        description: {
          "zh-CN": "查看、回复和关闭用户工单。",
          "en-US": "Review, reply to, and close support tickets."
        }
      },
      {
        id: "traffic-resets",
        href: "/admin/users/traffic-resets",
        glyph: "↻",
        label: { "zh-CN": "流量重置记录", "en-US": "Traffic resets" },
        description: {
          "zh-CN": "查看用户流量重置历史和统计。",
          "en-US": "Review user traffic reset history and statistics."
        }
      }
    ]
  },
  {
    id: "security",
    label: { "zh-CN": "管理员安全", "en-US": "Admin security" },
    items: [
      {
        id: "admin-users",
        href: "/admin/security/administrators",
        glyph: "♙",
        label: { "zh-CN": "管理员与角色", "en-US": "Administrators & roles" },
        description: {
          "zh-CN": "管理管理员账户和权限角色。",
          "en-US": "Manage administrator accounts and roles."
        }
      },
      {
        id: "admin-devices",
        href: "/admin/security/devices",
        glyph: "▢",
        label: { "zh-CN": "登录设备", "en-US": "Login devices" },
        description: {
          "zh-CN": "查看和撤销管理员设备会话。",
          "en-US": "Review and revoke administrator device sessions."
        }
      },
      {
        id: "admin-mfa",
        href: "/admin/mfa",
        glyph: "◆",
        label: { "zh-CN": "管理员 MFA", "en-US": "Administrator MFA" },
        description: {
          "zh-CN": "管理管理员多因素认证。",
          "en-US": "Manage multi-factor authentication for administrators."
        }
      }
    ]
  }
];

export function findAdminNavItem(pathname: string) {
  return adminNavigation
    .flatMap((group) => group.items)
    .find((item) => item.href === pathname);
}

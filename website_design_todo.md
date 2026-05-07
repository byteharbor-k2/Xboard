# Website Design TODO

## 当前架构：双主题模式

```
Freedom 主题（/ 首页）            Xboard 主题（/app 用户功能）
├── 营销落地页                    ├── 注册/登录
├── 品牌介绍、使用场景、FAQ        ├── 个人中心、订阅管理
├── 纯静态 HTML + i18n           ├── Vue3 SPA (umi.js)
├── config.json: configs=[]      ├── config.json: theme_color, background_url, custom_html
└── 不涉及用户功能                └── 所有用户交互都在这里
```

需要注入 JS（如 spam tip 提示）时，放在 Xboard 主题的 `custom_html` 字段，因为用户注册页由 Xboard 主题渲染。

## 后续全定制前端的路径

当品牌需求需要完全统一风格时，可以让 Freedom 主题接管用户功能（注册/登录/订阅管理），直接调 Xboard 后端 API。

### 需要遵守的接口契约

**第 1 层：Blade 模板入口**
- `theme/{Name}/dashboard.blade.php` — Laravel 渲染入口
- `theme/{Name}/config.json` — 主题元信息 + 管理后台可配置字段

**第 2 层：后端传给模板的变量**
- `$title`, `$theme`, `$version`, `$description`, `$logo`, `$theme_config`

**第 3 层：后端 API（用户功能核心）**
- 认证：`POST /api/v1/passport/auth/register`, `/login`, `/comm/sendEmailVerify`
- 用户：`GET /api/v1/user/info`, `/order/fetch`, `/notice/fetch`, `/server/fetch`
- 完整路由定义：`app/Http/Routes/V1/`

### config.json 自定义字段示例

```json
{
  "configs": [
    {
      "label": "自定义页脚HTML",
      "field_name": "custom_html",
      "field_type": "textarea"
    },
    {
      "label": "主题色",
      "field_name": "primary_color",
      "field_type": "input",
      "default_value": "#22D3EE"
    }
  ]
}
```

定义后管理后台会自动出现对应的配置面板，值存在 `admin_setting('theme_{Name}')` 里，通过 `$theme_config` 传给 Blade 模板。

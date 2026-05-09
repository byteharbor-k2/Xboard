<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Traffic Usage Alert</title>
    <style type="text/css">
        img { max-width: 100%; }
        body {
            -webkit-font-smoothing: antialiased;
            -webkit-text-size-adjust: none;
            width: 100% !important;
            height: 100%;
            line-height: 1.6em;
            margin: 0;
            padding: 0;
        }
        @media only screen and (max-width: 640px) {
            .container { padding: 0 !important; width: 100% !important; }
            .content { padding: 10px !important; }
            .content-wrap { padding: 16px !important; }
        }
    </style>
</head>
<body style="font-family: 'Helvetica Neue',Helvetica,Arial,sans-serif; box-sizing: border-box; font-size: 14px; background-color: #0f172a; margin: 0; padding: 0;" bgcolor="#0f172a">
    <table class="body-wrap" width="100%" cellpadding="0" cellspacing="0" style="background-color: #0f172a; margin: 0; padding: 0;" bgcolor="#0f172a">
        <tr>
            <td></td>
            <td class="container" width="600" style="display: block !important; max-width: 600px !important; clear: both !important; margin: 0 auto;" valign="top">
                <div class="content" style="max-width: 600px; display: block; margin: 0 auto; padding: 30px 20px;">

                    <table width="100%" cellpadding="0" cellspacing="0" style="margin-bottom: 24px;">
                        <tr>
                            <td style="text-align: center; padding: 10px 0;">
                                <span style="font-size: 20px; font-weight: 700; color: #22D3EE; letter-spacing: 1px;">SinX Cloud</span>
                            </td>
                        </tr>
                    </table>

                    <table class="main" width="100%" cellpadding="0" cellspacing="0" style="border-radius: 8px; background-color: #1e293b; margin: 0; border: 1px solid rgba(34,211,238,0.2);" bgcolor="#1e293b">
                        <tr>
                            <td style="background: linear-gradient(135deg, #f59e0b, #d97706); text-align: center; border-radius: 8px 8px 0 0; padding: 20px;" bgcolor="#f59e0b">
                                <span style="font-size: 20px; font-weight: 600; color: #0f172a;">Traffic Usage Alert</span>
                            </td>
                        </tr>
                        <tr>
                            <td class="content-wrap" style="padding: 32px 28px;" valign="top">
                                <table width="100%" cellpadding="0" cellspacing="0">
                                    <tr>
                                        <td style="font-size: 15px; color: #e2e8f0; padding: 0 0 8px;" valign="top">
                                            Dear Customer,
                                        </td>
                                    </tr>
                                    <tr>
                                        <td style="font-size: 14px; color: #94a3b8; padding: 0 0 20px;" valign="top">
                                            You have used <strong style="color: #fbbf24;">80%</strong> of your monthly traffic allowance. Please manage your usage to avoid running out before the billing cycle ends.
                                        </td>
                                    </tr>

                                    <tr>
                                        <td style="padding: 0 0 8px;">
                                            <table width="100%" cellpadding="0" cellspacing="0" style="background-color: #0f172a; border-radius: 6px; overflow: hidden;">
                                                <tr>
                                                    <td style="padding: 4px;">
                                                        <table width="80%" cellpadding="0" cellspacing="0" style="background: linear-gradient(90deg, #22D3EE, #f59e0b); border-radius: 4px; height: 8px;">
                                                            <tr><td style="font-size: 1px; line-height: 1px;">&nbsp;</td></tr>
                                                        </table>
                                                    </td>
                                                </tr>
                                            </table>
                                        </td>
                                    </tr>
                                    <tr>
                                        <td style="text-align: right; font-size: 12px; color: #64748b; padding: 0 0 24px;">80% used</td>
                                    </tr>

                                    <tr>
                                        <td style="padding: 0 0 24px;">
                                            <table width="100%" cellpadding="0" cellspacing="0" style="border-top: 1px solid #334155;">
                                                <tr>
                                                    <td style="font-size: 14px; color: #94a3b8; padding: 20px 0 0;">
                                                        您本月的套餐流量已使用 <strong style="color: #fbbf24;">80%</strong>，请合理安排使用，避免提前耗尽。
                                                    </td>
                                                </tr>
                                            </table>
                                        </td>
                                    </tr>

                                    <tr>
                                        <td style="text-align: center; padding: 0 0 8px;" valign="top">
                                            <a href="{{url}}/#/subscribe" style="font-size: 14px; color: #0f172a; text-decoration: none; font-weight: 600; display: inline-block; border-radius: 6px; background-color: #22D3EE; padding: 10px 28px;">Check Usage</a>
                                        </td>
                                    </tr>
                                </table>
                            </td>
                        </tr>
                    </table>

                    <table width="100%" cellpadding="0" cellspacing="0" style="margin-top: 24px;">
                        <tr>
                            <td style="text-align: center; font-size: 12px; color: #475569; padding: 0 0 8px;">
                                &copy; {{name}}. All Rights Reserved.
                            </td>
                        </tr>
                        <tr>
                            <td style="text-align: center; font-size: 12px; padding: 0 0 8px;">
                                <a href="{{url}}/#/subscribe" style="color: #22D3EE; text-decoration: none;">My Subscription</a>
                                &nbsp;&middot;&nbsp;
                                <a href="{{url}}/#/knowledge" style="color: #22D3EE; text-decoration: none;">Tutorials</a>
                            </td>
                        </tr>
                        <tr>
                            <td style="text-align: center; font-size: 11px; color: #334155; padding: 8px 0 0;">
                                You received this email because your traffic usage at {{name}} has reached 80%.<br>
                                This is an automated alert — please do not reply directly.
                            </td>
                        </tr>
                    </table>

                </div>
            </td>
            <td></td>
        </tr>
    </table>
</body>
</html>
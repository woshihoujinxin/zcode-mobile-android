# ZCode Mobile (Android MVP)

手机扫码直连官方云（`zcode.z.ai`）远程控制 PC 桌面端 ZCode 的最小客户端。
方案与协议逆向记录见 `docs/specs/android-app-mvp.md`（仓库根）。

## 功能（MVP / L1）

- 扫码绑定：扫描桌面端 ZCode「Web 远程控制」二维码（`https://zcode.z.ai/remote/v4?sid=…&hash=…`）
- 粘贴连接链接兜底（桌面端二维码弹窗的「复制链接」可经任意渠道发到手机粘贴）
- 域名白名单（zcode.z.ai / zcode.chatglm.site）+ 必须 https + 必须 sid/hash，防二维码劫持
- 绑定凭据本机持久化，二次打开免扫码直接进入
- WebView 承载官方手机页：工作区/任务/会话列表、接管对话、权限审批、断线重连
- 附件选择（发图给 AI）、外链跳系统浏览器、加载失败重试、渲染崩溃自愈
- 返回键 = 网页历史回退；无历史则退回配对页（可重新扫码/解除绑定）

## 构建

依赖：JDK 17+（Android Studio JBR 即可）、Android SDK 34。

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
gradle assembleDebug   # 产物 app/build/outputs/apk/debug/app-debug.apk（debug 签名，可直接安装）
```

## 验证步骤（真机）

1. 安装：`adb install -r ZCode-Mobile-0.1.0-mvp.apk`（或直接传到手机点击安装）
2. PC 打开官方 ZCode 桌面端 → 某工作区 →「Web 远程控制」→ 生成二维码
3. 手机 App 点「扫码连接桌面端」→ 授权相机 → 扫码
4. 预期：进入会话页，能看到 PC 的工作区与任务列表（对应参考图），点开任务可查看流式对话并发送消息
5. 失效路径：PC 点「重置配对」→ 手机端应提示会话失效；回 App 返回键退到配对页 → 重新扫码

## 已知限制（按方案属 P1/二期）

- 无离线推送（WebView 后台冻结，回前台官方页自动重连）
- 白名单固定为官方域名；自建 relay 需改 `PairUrl.ALLOWED_HOSTS`
- debug 签名；公开发布需换正式签名与混淆配置

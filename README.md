# ZCode Mobile (Android MVP)

手机扫码直连官方云（`zcode.z.ai`）远程控制 PC 桌面端 ZCode 的最小客户端。
方案与协议逆向记录见 `docs/specs/android-app-mvp.md`（仓库根）。

## 功能（v0.3.1 凭据健康度）

- **多桌面绑定**：可同时绑定多台 PC（各自扫码），设备列表点击切换；重扫同一桌面自动更新配对凭据；旧版单设备数据自动迁移
- **凭据健康度**：内置 relay 凭据探针（零依赖 WebSocket 终端鉴权握手，与官方手机页协议一致），实时检测绑定凭据是否仍被官方云接受；设备列表以状态点展示（绿=有效 / 红=已失效 / 灰=未检测）
- **失效自愈**：桌面端「重置配对」会换发 sid/hash（mid 不变）——重扫后按 mid 识别同一台桌面并替换旧条目；PC 群控台轮询到凭据变更后自动换新凭据重连对应格子，无需手动刷新
- **深链接绑定**：手机浏览器/聊天工具点开桌面端「复制链接」（`https://zcode.z.ai/remote/v4?sid=…`）直接进入绑定
- **PC 群控台**（局域网）：手机作为已认证锚点，把配对凭据下发给同一 Wi-Fi 的 PC 浏览器；PC **直连官方云**建立独立会话（与手机互不转发），支持单屏/双列/四宫格同时操作多台桌面
- 扫码绑定 / 粘贴链接兜底、域名白名单 + 必须 https + 必须 sid/hash、凭据本机持久化
- WebView 承载官方手机页：工作区/任务/会话列表、接管对话、权限审批、断线重连、附件选择、渲染崩溃自愈

## 凭据为什么会失效（AUTH_FAILED）

官方 relay 的设备注册是**会话态**：桌面端在线时注册有效；「重置配对」（refresh/重置）会停掉旧连接、清空本地凭据并重新注册（新 sid + 新 hash）；桌面端长时间离线后旧注册同样会被拒绝。此时手机上保存的旧二维码链接会被 relay 明确拒绝（`error{code:"AUTH_FAILED"}`），官方页面渲染为「Mobile Connection Invalid」。本版本的探针在打开页面前先做同样一次鉴权握手，把这种情况转化为中文引导（重新扫码 / 仍要打开），并在群控台同步可见。

## 构建

依赖：JDK 17+（Android Studio JBR 即可）、Android SDK 34。

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
gradle assembleDebug   # 产物 app/build/outputs/apk/debug/app-debug.apk（debug 签名，可直接安装）
```

## 验证步骤（真机）

1. 安装：`adb install -r app-debug.apk`（或直接传到手机点击安装）
2. PC 打开官方 ZCode 桌面端 → 某工作区 →「Web 远程控制」→ 生成二维码
3. 手机 App 点「扫码连接桌面端」→ 授权相机 → 扫码（或在 PC 端复制链接后用手机浏览器点开）
4. 预期：进入会话页，能看到 PC 的工作区与任务列表，点开任务可查看流式对话并发送消息
5. 凭据失效路径：PC 点「重置配对」→ 设备列表红点「凭据已失效」，点击给出重新扫码引导；重新扫码后设备列表恢复，PC 群控台 5 秒内自动换新凭据重连
6. 群控台路径：App 内「PC 群控」→ 启动服务 → 同一 Wi-Fi 的 PC 浏览器打开展示的地址 → 点设备即在格子内直连官方云

## 已知限制（按方案属 P1/二期）

- 无离线推送（WebView 后台冻结，回前台官方页自动重连）
- 白名单固定为官方域名；自建 relay 需改 `PairUrl.ALLOWED_HOSTS`
- debug 签名；公开发布需换正式签名与混淆配置

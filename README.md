# HyperMonica

> **AI 生成声明**：本项目基于上游 HyperPasskey 的全部修改（包括 CI 自动构建与发布工作流）均由 AI 辅助生成，非人工编写。使用时请自行审查其行为，并按需修改。

基于 [HyperPasskey](https://github.com/howard20181/HyperPasskey) 修改的定制版 Xposed 模块（libxposed API 101）。

在国行 HyperOS 上，将 **[Monica](https://github.com/Monica-Pass/Monica)** 设为首选通行密钥（Passkey）与自动填充提供者，并拦截系统对凭据配置的重置。

## 与原版的区别

| 项目 | 原版 | 本定制版 |
|---|---|---|
| 定位 | 修复 HyperOS 通行密钥 | 同设备通行密钥保存至 **Monica** |
| 首选提供者 | 用户手动设置，可能被系统重置 | 每次凭据请求时自动校正为 Monica |
| 自动填充 | 不干预 | 强制切换为 Monica 的自动填充服务 |
| 小米扫一扫 | Hook 放行 FIDO 二维码交接 | 已移除（不需要跨设备扫码） |
| 模块名称 / 描述 | Fix HyperOS Passkey | HyperMonica |
| versionCode | 按 git 提交数生成 | 固定 10410，避免被在线仓库提示更新覆盖 |

## 工作原理

模块作用域：`system`（system_server）、`com.android.settings`、`com.miui.securitycenter`。

**system_server 层**
- `RequestSession` 构造完成后将混合认证服务指向 GMS（跨设备流程仍可用）
- 每次凭据请求时幂等校正（`ensureMonicaPreferred`）：
  - `credential_service`：确保 Monica 在提供者列表中（保留其他项，Google 存量密钥不受影响）
  - `credential_service_primary`：首选强制为 Monica
  - `autofill_service`：自动填充强制为 Monica
  - **Monica 未安装时不做任何干预**
- Android 15+ 强制使用 GMS 的 `CredentialChooserActivity` 作为凭据选择器（选择器中会出现 Monica 条目）

**设置层（com.android.settings）**
- 临时翻转 `miui.os.Build.IS_INTERNATIONAL_BUILD`，解锁被国行隐藏的"密码、通行密钥与自动填充"入口

**手机管家层（com.miui.securitycenter）**
- 拦截 `configForAutofillService`、`setDefaultConfigForAutofillAndCredentialManager`，阻止写入小米默认的 `autofill_service` / `credential_service` 配置
- 若拦截遗漏（MIUI 更新导致特征变化），system_server 层的校正在下一次凭据请求时兜底恢复

## 设备要求

继承自原版 Manifest 的静态 overlay 限制：

- HyperOS 2（`ro.miui.ui.version.code=816`）
- 国行（`ro.miui.build.region=cn`）
- 已安装 GMS 核心（`ro.miui.has_gmscore=1`）——凭据选择器 UI 依赖 GMS 组件
- Android 14+，已 root 并使用 LSPosed（或兼容 libxposed API 101 的框架）


## 验证

- 系统设置 → 密码、通行密钥与自动填充：首选应显示 Monica
- 在手机浏览器创建通行密钥：选择器默认 Monica，密钥保存进 Monica
- 手机管家清理/启动后配置不被重置

## 构建

```
gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/HyperMonica-1.4.1-monica.10410-debug.apk`

可选签名：在根目录 `local.properties` 中配置 `storeFile` / `storePassword` / `keyAlias` / `keyPassword`（该文件已被 .gitignore 忽略）。

### CI 自动构建与发布

仓库内置 GitHub Actions 工作流（`.github/workflows/build-release.yml`），自动构建 debug / release APK 并发布到 GitHub Release：

- 推送到 `main` → 自动构建并以提交数生成标签 `v1.4.1-monica.<提交数>`，创建对应 Release
- 推送 `v*` 标签（如 `v1.4.1-monica`）→ 自动构建并创建对应 Release
- 手动触发（Actions 页 → Run workflow）→ 默认只构建并上传产物；勾选 `create_release` 并填写 `tag_name` 可直接发布
- 签名：在仓库 Secrets 中配置 `KEYSTORE_B64`（keystore 的 base64）/ `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 后自动签名；未配置时 release 走 debug 签名

## 已知限制

- **跨设备（扫码）登录仍走 Google**：系统混合认证通道只分发给单一指定服务，第三方提供者无法接收，通行密钥会保存至 Google 密码管理器。此为 Android 平台限制，非模块缺陷
- 首选切换为 Monica 后，新创建的通行密钥默认进 Monica；Google 侧存量密钥仍在选择器中可用

## 许可证与致谢

本项目为 [HyperPasskey](https://github.com/howard20181/HyperPasskey)（作者 [howard20181](https://github.com/howard20181)）的衍生作品，遵循 [GPL-3.0](LICENSE) 许可证发布。

- 上游项目：<https://github.com/howard20181/HyperPasskey>
- 上游作者：howard20181
- 许可证：GNU General Public License v3.0

本项目的核心修改点（相对上游）：

1. 将 Monica 设为首选凭据提供者（`credential_service_primary`）与自动填充服务（`autofill_service`），在开机与每次凭据请求时幂等校正
2. 移除小米扫一扫（`com.xiaomi.scanner`）的 FIDO 交接 hook 及对应作用域
3. 重命名模块、调整 versionCode 避免被在线仓库提示更新覆盖
4. 依据 GPL-3.0 的传染性要求，本项目同样以 GPL-3.0 许可发布，源代码随仓库完整提供

如你在此基础上继续修改，需继续以 GPL-3.0 发布，并保留上述上游版权与来源声明。

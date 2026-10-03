# GitHub Release 自动更新 — 实施方案

> 基线：小米 13（2211133C）/ Android 16 / SDK 36 / 目标应用 `com.kuaishou.auto`（versionCode=1, targetSdk=36）。
> 标注约定：**【已验】** = 本机或真机实测/官方 AOSP 源码核对过；**【待实测】** = 尚未在真机跑通。
> 本方案只做"更新"这一件事，不改动 ScrollTask / ProgressDetector / OverlayService 的任务逻辑。

---

## 〇、先说结论：三个必须接受的前提

| # | 前提 | 依据 |
|---|---|---|
| 1 | **下载必须走 `api.github.com`，不能走 `github.com`** | 【已验】PC 与手机 `github.com:443` 均连接超时（`curl: (28) after 21s`）；而 `api.github.com` 200/0.65s、`objects.githubusercontent.com` / `release-assets.githubusercontent.com` 均可达 |
| 2 | **资产必须用 `assets/{id}` + `Accept: application/octet-stream`** | 【已验】手机实测该端点返回 `http=206`，断点续传 `Range` 生效，4 MB 拉取 1.8s（2.3 MB/s） |
| 3 | **"静默安装"只是尽力而为，必须实现"系统弹确认框"分支** | 【已验】AOSP `PackageInstaller.java` L3477-3513：需同时满足 `USER_ACTION_NOT_REQUIRED` + 目标 targetSdk 达标 + 安装者身份条件 + `UPDATE_PACKAGES_WITHOUT_USER_ACTION` 权限 |

这三条决定了整个架构。任何"直接读 `browser_download_url` 然后无脑 install"的写法，在本机网络环境下**第一次就会失败**。

---

## 一、现状盘点（只列与本功能相关的）

| 项 | 现状 | 影响 |
|---|---|---|
| 版本定义 | `app/build.gradle.kts` 硬编码 `versionCode = 1 / versionName = "1.0"` | 需要改成可从命令行注入 |
| 签名 | **无 `signingConfigs`**；`assembleRelease` 产出 `app-release-unsigned.apk` | 【已验】`apksigner verify` → `DOES NOT VERIFY / Missing META-INF/MANIFEST.MF`。**未签名 APK 无法作为更新包分发** |
| 网络权限 | Manifest 无 `INTERNET` | 网络层全部要新增 |
| HTTP 栈 | 无任何网络代码（全仓库 grep 无 `HttpURLConnection`/`okhttp`） | 从零写，或引 OkHttp（本方案不引依赖） |
| JSON | 无 JSON 依赖 | 用系统自带 `org.json`【已验】`android.jar` 内可用 |
| 协程 | 已有 `kotlinx-coroutines-android:1.9.0` | 下载/检查用协程即可，无需新依赖 |
| 设置存储 | `AppSettings`（SharedPreferences，单键） | 追加更新相关键，向后兼容 |
| 主界面 | `MainActivity` + `activity_main.xml`（权限区 + 取帧方式区 + 悬浮窗开关 + 退出） | 追加一个"更新"区块 |
| 悬浮窗 | `FloatingWindow.showMessage()` 已有 4 秒提示条 | 可直接用来提示"有新版本" |
| 版本控制 | **本项目不是 git 仓库**（`fatal: not a git repository`） | 无法打 tag、无法回滚 → 必须先做 P0 |

---

## 二、版本号契约（全链路唯一真相）

**Tag = 版本号来源。** 不手工维护 `versionCode`。

```
Git tag:  v1.2.3
          │
          ├─ CI 解析 → versionName = "1.2.3"
          └─ CI 计算 → versionCode = 1*1_000_000 + 2*1_000 + 3 = 1002003
                                     ↓
                       gradlew assembleRelease -PversionName=1.2.3 -PversionCode=1002003
```

客户端同样从 `tag_name` 反解：

```kotlin
// Version.kt —— 纯函数，可单测
fun parseTag(tag: String): IntArray? {                 // "v1.2.3"/"1.2.3" → [1,2,3]
    val s = tag.trim().removePrefix("v").removePrefix("V")
    val parts = s.split(".")
    if (parts.size != 3) return null
    val n = parts.map { it.toIntOrNull() ?: return null }
    if (n.any { it < 0 || it > 999 }) return null      // 越界直接判非法，避免组装溢出
    return n.toIntArray()
}
fun versionCodeOf(v: IntArray) = v[0] * 1_000_000L + v[1] * 1_000L + v[2]
```

**判定更新的条件（两个都要满足）：**
1. `remoteCode > installedCode`（`installedCode` 来自 `packageManager.getPackageInfo(pkg,0).longVersionCode`）
2. APK 资产存在且唯一

> **反循环保护**：`remoteCode == installedCode` 时一律判"已是最新"。若 tag 变了但 code 没变（CI 重打同版本），客户端**不重装**——否则会陷入反复安装。
> **降级保护**：`remoteCode < installedCode` 直接忽略（本地 debug 包版本更高时不倒灌）。

---

## 三、模块划分

```
app/src/main/java/com/kuaishou/auto/update/
├── UpdateConfig.kt        # OWNER/REPO、UA、超时、路径常量（唯一需要改的配置点）
├── Version.kt             # 版本解析/比较（纯函数，可单测）
├── ReleaseInfo.kt         # 模型：tag / versionCode / 更新说明 / 资产(id,name,size,digest)
├── GitHubReleases.kt      # HTTP：GET /releases/latest → ReleaseInfo
├── Downloader.kt          # 断点续传下载 + 实时 SHA-256 + 进度回调
├── ApkVerifier.kt         # 包名/版本/签名三重校验（不可跳过）
├── InstallGate.kt         # REQUEST_INSTALL_PACKAGES 与 canRequestPackageInstalls() 检查
├── UpdateInstaller.kt     # PackageInstaller 会话 + PendingIntent(MUTABLE) + 结果处理
└── UpdateManager.kt       # 编排：检查 → 下载 → 校验 → 安装 → 记录状态
app/src/main/java/com/kuaishou/auto/update/UpdateResultReceiver.kt   # manifest 注册的广播接收器
```

---

## 四、关键实现要点（每条都对应一个已验事实）

### 4.1 检查更新：`GitHubReleases`

```
GET https://api.github.com/repos/{OWNER}/{REPO}/releases/latest
Headers:  User-Agent: kuaishou-auto/{versionName}      ← 必填，GitHub 拒绝无 UA 请求
          Accept: application/vnd.github+json
```

返回字段（【已验】真实响应）：

| 字段 | 用途 | 实测样例 |
|---|---|---|
| `tag_name` | 版本号来源 | `"v2.102.0"` |
| `body` | 更新说明（原样展示） | markdown |
| `prerelease` / `draft` | 过滤：任一为 true 则忽略该 release | `false` |
| `assets[].name` | 选 APK（`.apk` 结尾且 `state=="uploaded"`） | — |
| `assets[].size` | 预检磁盘空间 | `1971` |
| `assets[].id` | **下载用**（拼 `/releases/assets/{id}`） | `599854636` |
| `assets[].digest` | SHA-256 校验 | `"sha256:afe49e9a..."` |

**限流治理**【已验】未认证 60 次/小时/IP（`X-RateLimit-Limit: 60`）：
- 自动检查最多 6 小时一次（`last_check_at` 落盘）
- 手动点「检查更新」不受此限，但两次间隔 < 10 秒直接忽略
- 收到 403/429 时读 `Retry-After` 与 `X-RateLimit-Reset`，把下一次检查时间推到该时刻
- 响应体上限 512 KB，超限直接断开

### 4.2 下载：`Downloader`

```
GET https://api.github.com/repos/{OWNER}/{REPO}/releases/assets/{id}
Headers:  Accept: application/octet-stream        ← 缺这个会拿到 JSON 元数据而不是文件
          User-Agent: ...
          Range: bytes={已下载字节数}-             ← 续传
```

| 规则 | 原因 |
|---|---|
| 落盘 `filesDir/updates/app-{code}.apk.part` | 私有目录无需任何存储权限；`.part` 便于续传 |
| 只有收到 **206** 才追加写入；收到 200 则清空重下 | 【已验】206 确已生效；200 意味着服务端忽略 Range，继续追加会得到损坏文件 |
| 边下边算 SHA-256，完成后与 `digest` 比对 | 不比对 = 静默装坏包 |
| 下载前 `StatFs(filesDir).availableBytes > size*2 + 50MB` | 避免写满 |
| 进度回调节流 ≥200 ms | 避免刷 UI |
| 超时：connect 10s / read 30s | 实测存在偶发的 138 KB/s 慢速段（同端点两次拉取 2.3 MB/s vs 0.14 MB/s），慢速必须能超时中断并续传 |
| **不发送/不保存任何 Cookie** | 该端点会下发 `__Host-user_session_same_site`；无账号体系，Cookie 只会污染请求 |

### 4.3 安装前校验：`ApkVerifier`（三重，缺一不可）

```kotlin
@Suppress("DEPRECATION")
val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
val arch = pm.getPackageArchiveInfo(apk.path, flags) ?: return Fail("无法解析 APK")

// ① 包名必须是自己
if (arch.packageName != context.packageName) return Fail("包名不匹配")

// ② 版本号必须等于远端声明值
if (arch.longVersionCode != remoteCode) return Fail("版本号不匹配")

// ③ 签名必须与已安装版本同源（否则系统必拒，提前拦住并给出可读错误）
val remote = signerHashes(arch)                                   // API 28+: apkContentsSigners
val local  = signerHashes(pm.getPackageInfo(context.packageName, flags))
if (remote.intersect(local).isEmpty()) return Fail("签名不一致：该包不是本应用的官方构建")
```
【已验】`android.jar`(API 36) 含 `GET_SIGNING_CERTIFICATES` / `SigningInfo.getApkContentsSigners()`；`minSdk=26` 故 26/27 走 `GET_SIGNATURES` 分支。

### 4.4 安装：`PackageInstaller`

```kotlin
val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
    setAppPackageName(context.packageName)
    setSize(apk.length())
    setInstallReason(PackageManager.INSTALL_REASON_USER)
    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)  // 尽力静默
}
val session = pm.packageInstaller.openSession(pm.packageInstaller.createSession(params))
session.openWrite("base.apk", 0, apk.length()).use { out -> run {
    FileInputStream(apk).use { it.copyTo(out) }
    session.fsync(out)
} }
session.commit(resultSender)
```

**三个必须注意的点：**

1. **`PendingIntent` 必须 `FLAG_MUTABLE`。**
   【已验】AOSP `PackageInstaller.java` L1979-1981：`targetSdk ≥ 35` 时用 immutable 的 `PendingIntent` 作 `statusReceiver` 会抛 `IllegalArgumentException`。本项目 targetSdk=36，**不设为 MUTABLE 必定崩溃**。
2. **必须同时处理 `STATUS_SUCCESS` 与 `STATUS_PENDING_USER_ACTION`。**
   【已验】AOSP `PackageInstallerSession.java` L1153-1171：拿不到 `UPDATE_PACKAGES_WITHOUT_USER_ACTION` 时必定走到 `USER_ACTION_REQUIRED` → 回调 `STATUS_PENDING_USER_ACTION`，此时 `EXTRA_INTENT` 里带着系统确认框的 Intent，**必须 `startActivity` 弹出来**，否则用户点完"下载"就永远卡住没反应。
   【已验】同一文件 L3466-3513 另有 60s 内重复静默安装的节流（`mSilentUpdatePolicy`），超限即回落成确认框 —— 所以确认框是常态而不是异常。
3. **广播接收器注册方式。**
   manifest 声明 `UpdateResultReceiver`，`android:exported="false"`（系统通过 `PendingIntent` 身份投递，无需 exported）。

### 4.5 安装后的状态收尾（本项目最容易被忽略、也最容易出事的一环）

安装自身 APK 会**杀掉本进程**。对本应用意味着：

| 被破坏的东西 | 后果 | 处理 |
|---|---|---|
| 无障碍服务 `AutoScrollService` | 系统通常关闭它（重装后需用户重新开启） | 更新后主界面必须显式提示"请重新开启无障碍服务" |
| MediaProjection 授权 | **必然失效**（Android 设计如此，进程级授权） | 提示重新授权；若用户在 `AppSettings` 里选的是"无障碍截图"，则无需授权 |
| 正在跑的任务 | 被硬中断 | **更新前弹确认**；默认策略：仅当任务未运行时才允许"立即安装" |

**检测"刚更新完"的可靠方式（不依赖广播）：**
在 `MainActivity.onCreate` 比较 `AppSettings.lastRunVersionCode` 与当前 `longVersionCode`：
- 不同 → 写入新值，标记"刚刚完成更新"，显示重新配置引导
- `ACTION_MY_PACKAGE_REPLACED` 广播作为**可选增强**（该广播属于隐式广播例外，但【待实测】本 ROM 的投递行为；不作为唯一依赖）

---

## 五、发布端（GitHub Actions）

### 5.1 签名配置（先改 `app/build.gradle.kts`）

```kotlin
val ksFile = (findProperty("ksFile") as String?) ?: System.getenv("KS_FILE")
    ?: "${rootDir}/keystore/release.jks"

android {
    signingConfigs {
        create("release") {
            if (ksFile != null && file(ksFile).exists()) {
                storeFile = file(ksFile)
                storePassword = System.getenv("KS_PASSWORD")
                keyAlias = System.getenv("KS_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    defaultConfig {
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "1.0"
    }
    buildTypes {
        release {
            // 未配置 keystore 时保持现状（产出 unsigned），配置后自动签名
            if (file(ksFile).exists()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false          // 首版不开混淆：更新通道稳定性优先
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
```
> 【已验】当前 `assembleRelease` 输出 `app-release-unsigned.apk`（2.89 MB），`apksigner verify` 报 `DOES NOT VERIFY`。所以签名不是"优化项"，是**前置条件**。
> 【已验】本机 JDK = Temurin 17.0.20.1，AGP 9.4.1 可用；release 构建已跑通（exit 0）。

### 5.2 密钥管理

- 本地生成：`keytool -genkeypair -v -keystore keystore/release.jks -alias kuaishou-auto -keyalg RSA -keysize 4096 -validity 10000`
- `keystore/` 与 `local.properties`、`app/build/`、`research/`（846 MB）一并写入 `.gitignore`
- GitHub Secrets：`KS_B64`（base64 的 jks）、`KS_PASSWORD`、`KS_ALIAS`、`KEY_PASSWORD`
- ⚠ **这个 jks 是所有用户信任的根。** 丢了 → 除了让用户卸载重装，无法再推送任何更新；泄漏 → 可以给所有用户推任意 APK。备份到至少两处离线介质。

### 5.3 `.github/workflows/release.yml`

```yaml
name: release
on:
  push:
    tags: ['v*']
permissions:
  contents: write
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - run: chmod +x ./gradlew

      - name: 解析版本
        run: |
          V="${GITHUB_REF_NAME#v}"
          IFS='.' read -r MA MI PA <<< "$V"
          echo "VNAME=$V"                 >> $GITHUB_ENV
          echo "VCODE=$((MA*1000000+MI*1000+PA))" >> $GITHUB_ENV

      - name: 还原签名密钥
        run: |
          mkdir -p keystore
          echo "${{ secrets.KS_B64 }}" | base64 -d > keystore/release.jks

      - name: 构建
        env:
          KS_FILE:     ${{ github.workspace }}/keystore/release.jks
          KS_PASSWORD: ${{ secrets.KS_PASSWORD }}
          KS_ALIAS:    ${{ secrets.KS_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        run: ./gradlew assembleRelease -PversionName=$VNAME -PversionCode=$VCODE

      - name: 产出与校验文件
        run: |
          APK="kuaishou-auto-${VNAME}.apk"
          cp app/build/outputs/apk/release/app-release.apk "$APK"
          sha256sum "$APK" > SHA256SUMS.txt
          cat SHA256SUMS.txt

      - uses: softprops/action-gh-release@v2
        with:
          files: |
            kuaishou-auto-*.apk
            SHA256SUMS.txt
          generate_release_notes: true
          draft: false
          prerelease: false
```

### 5.4 发布纪律（客户端依赖这些约定）

| 约定 | 原因 |
|---|---|
| Release 里**只能有一个 `.apk`** | 客户端按"唯一 `.apk` 资产"取包；多传一个就歧义 |
| 资产用默认生成的名字（`app-release.apk` → 改名后仍唯一即可） | — |
| tag 必须是 `vX.Y.Z` 三段数字 | `parseTag` 只认三段；否则客户端判为格式错误 |
| 永远先发 draft，验证 `apksigner verify` 通过后再 publish | draft 不会被 `/releases/latest` 返回 |
| 已发布 tag 不允许重打 | `versionCode` 必须单调递增 |

---

## 六、UI 设计

### 6.1 主界面新增「更新」区块（`activity_main.xml` 尾部，`title_settings` 之后）

```
┌─ 更新 ────────────────────────────────┐
│ 当前版本  1.0 (1)                     │
│ 自动检查更新                          [开关] │
│ 仅在 Wi-Fi 下载                       [开关] │
│ [ 检查更新 ]                          │
│ · 状态行（单行 TextView）：            │
│   已是最新 / 发现 v1.2.3 / 下载中 42% / │
│   校验失败 / 请先允许"安装未知应用"     │
│ [ 立即更新 ]  ← 仅在"发现新版本"时可见  │
│ 更新说明（可滚动，最多 8 行）           │
└───────────────────────────────────────┘
```

### 6.2 状态机（`UpdateManager` 单一状态源，主界面只渲染）

```
IDLE ──检查──▶ CHECKING ──┬─▶ UP_TO_DATE ──▶ IDLE
                          ├─▶ AVAILABLE ──下载──▶ DOWNLOADING ──┬─▶ VERIFYING ──┬─▶ READY_TO_INSTALL ──安装──▶ INSTALLING
                          └─▶ ERROR(code)                       ├─▶ ERROR(HASH) └─▶ ERROR(签名/版本)
```
错误码枚举（每条都给用户一句可执行的话，不给堆栈）：
`NO_NETWORK` / `RATE_LIMITED` / `SERVER_ERROR` / `ASSET_MISSING` / `TAG_MALFORMED` /
`HASH_MISMATCH` / `SIGNATURE_MISMATCH` / `VERSION_MISMATCH` / `NO_SPACE` / `NEED_INSTALL_PERMISSION` / `INSTALL_FAILED`

### 6.3 悬浮窗提示

复用 `FloatingWindow.showMessage()`（已有 4 秒自动消失）。触发点：
- 检查到新版本且任务正在运行 → 提示"发现新版本 v1.2.3，可停止任务后更新"
- 点「立即更新」时任务正在运行 → 提示"请先停止任务"

---

## 七、权限与 Manifest 变更

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

<receiver
    android:name=".update.UpdateResultReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="com.kuaishou.auto.INSTALL_RESULT" />
        <!-- 可选增强，不作为唯一依赖 -->
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
    </intent-filter>
</receiver>
```
【已验】`android.jar`(API 36) 中 `INTERNET` / `ACCESS_NETWORK_STATE` / `REQUEST_INSTALL_PACKAGES` 均存在。

### 需要 YG 拍板的两个产品决策

| # | 决策 | 影响 |
|---|---|---|
| A | **接受新增用户可见行为**：安装更新首次会引导用户去开"允许安装未知应用"（`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`，【已验】`android.jar` 含该常量） | 这是新增的、绕不开的系统授权步骤；不需要则不引入本功能 |
| B | **任务运行中是否允许安装** | 建议：禁止（安装必杀进程 → 任务硬中断、需重新授权）。备选：允许并明确警告"将中断当前任务"。默认取前者 |

---

## 八、风险清单（不粉饰）

| 风险 | 等级 | 缓解 |
|---|---|---|
| **签名密钥泄漏 = 可向所有用户推任意 APK** | 高 | 密钥只在 GitHub Secrets 与离线介质；仓库设为公开只放代码 |
| **签名密钥丢失 = 更新通道永久死亡** | 高 | 双份离线备份；第一次正式对外发布前务必确认备份可用 |
| **未认证 API 限流 60/h/IP** | 中 | 6 小时间隔 + 尊重 `Retry-After` |
| **"静默安装"不保证**（targetSdk 门槛、60s 节流、Android 15+ 侧载管控、厂商 ROM 收紧） | 中 | 必做 `STATUS_PENDING_USER_ACTION` 分支；文案不说"全自动" |
| **GitHub 网络不稳**（实测同端点 2.3 MB/s ↔ 0.14 MB/s 波动；`github.com` 完全不可达） | 中 | 断点续传 + read timeout 30s + 失败重试 2 次退避 |
| **本项目尚无 git 仓库** | 高 | P0 必须先 `git init` + 首次提交；否则无 tag 无回滚 |
| **更新后无障碍/投影授权失效**被用户当成"更新把应用弄坏了" | 中 | 4.5 的更新后引导是必需项，不是可选打磨项 |

---

## 九、里程碑与验收

| 阶段 | 内容 | 验收标准（可执行） |
|---|---|---|
| **P0 治理** | `git init`、`.gitignore`（含 `research/`、`app/build/`、`local.properties`、`keystore/`）、首次提交、生成 release.jks、建 GitHub 仓库 | `git log` 有提交；`keytool -list` 能读出别名 |
| **P1 发布链路** | 签名配置 + CI workflow + tag 规范 | 打 `v1.0.1` → Release 出现 1 个 APK + SHA256SUMS；下载后 `apksigner verify` 通过 |
| **P2 网络层** | `Version.kt` / `GitHubReleases.kt` / `UpdateConfig.kt` | 单测：`v1.2.3`→1002003、`v1.2`→null、`v1.2.3.4`→null；真机 logcat 打印解析结果与 PC 端 `curl` 一致 |
| **P3 下载校验** | `Downloader.kt` / `ApkVerifier.kt` | 飞行模式中途断网 → 报错且 `.part` 保留；恢复后字节数连续、sha256 相等；手动改 `.part` 一字节 → 必须报 `HASH_MISMATCH` 且删除 |
| **P4 安装链路** | `UpdateInstaller.kt` / `UpdateResultReceiver.kt` / 版本变更检测 | 真机 v1.0.1 → v1.0.2 全链路成功（`dumpsys package com.kuaishou.auto \| grep versionCode` 变 2）；两种安装路径（静默 / 弹框）至少各实测一次 |
| **P5 UI** | 主界面更新区块 + 悬浮窗提示 | 11 个错误码各自有中文文案；更新完成后出现"请重新开启无障碍服务"引导 |
| **P6 打磨** | Wi-Fi 开关、节流、`last_check_at` | 关掉 Wi-Fi 时下载按钮提示等待；1 小时内重复自动检查不产生新请求（看 `X-RateLimit-Remaining` 不下降） |

**日常发布节奏（P1 完成后即可用）：**
```
改代码 → git commit → git tag v1.0.2 → git push origin main --tags → 等 CI → Release 自动出现
```

---

## 十、被明确排除的方案（避免走回头路）

| 方案 | 为什么不用 |
|---|---|
| 读 `browser_download_url` 直下 | 【已验】该 URL 指向 `github.com`，本机 PC 与手机均 21s 超时不可达 |
| Firebase App Distribution / 自建对象存储 | 引入额外账号/成本；GitHub Release 已满足且已验证可达 |
| 应用宝/自签名 RPC 服务端下发 | 同一个 release 端点即可完成，无需额外服务 |
| 走 Google Play 内更新 | 本应用不上架 Play（无障碍+悬浮窗类工具的上架政策风险） |
| `ACTION_VIEW` + `FileProvider` 交给系统安装器 | 作为**兜底**保留可以，但每次都要求用户手动点安装，体验劣于 `PackageInstaller` 会话 |

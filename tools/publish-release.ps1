<#
    publish-release.ps1 —— 本地一键发版：构建签名包 → 建 GitHub Release → 上传 APK

    用法：
        pwsh -File tools/publish-release.ps1                   # 版本号自动从 git tag 递增
        pwsh -File tools/publish-release.ps1 -Version 1.2.3    # 指定版本号
        pwsh -File tools/publish-release.ps1 -DryRun           # 只构建和校验，不发到 GitHub
        pwsh -File tools/publish-release.ps1 -Draft:$false     # 直接发布（默认建 draft）

    前置：release-config.json 里的 repo 与 token 已填写（token 只需 repo 权限）。
#>
[CmdletBinding()]
param(
    [string] $Version,
    [string] $Notes,
    [switch] $DryRun,
    [switch] $SkipBuild,
    [switch] $Draft = $true
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$ProgressPreference = 'SilentlyContinue'

# ---------------------------------------------------------------- 基础路径
$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $root 'settings.gradle.kts'))) {
    throw "找不到仓库根目录（应含 settings.gradle.kts），脚本位置: $PSScriptRoot"
}
Set-Location $root

$cfgPath = Join-Path $root 'release-config.json'
$ksPath  = Join-Path $root 'keystore/release.jks'
$apkDir  = Join-Path $root 'app/build/outputs/apk/release'
$tmpDir  = Join-Path $env:TEMP ('ks-publish-' + [guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

function Step([string] $msg) { Write-Host ""; Write-Host "=== $msg ===" }

# ---------------------------------------------------------------- 配置
function Read-Config {
    if (-not (Test-Path $cfgPath)) { throw '缺少 release-config.json，请先运行 tools/gen-keystore.ps1' }
    $raw = [System.IO.File]::ReadAllText($cfgPath)
    if ($raw.Length -gt 0 -and $raw[0] -eq [char]0xFEFF) { $raw = $raw.Substring(1) }
    return ($raw | ConvertFrom-Json)
}

# ---------------------------------------------------------------- 版本号
function Parse-Version([string] $v) {
    $m = [regex]::Match($v.Trim(), "^v?(\d+)\.(\d+)\.(\d+)$")
    if (-not $m.Success) { throw "版本号格式必须是 X.Y.Z，收到: $v" }
    $parts = @([int]$m.Groups[1].Value, [int]$m.Groups[2].Value, [int]$m.Groups[3].Value)
    foreach ($n in $parts) { if ($n -gt 999) { throw "版本号每段不能超过 999: $v" } }
    return $parts
}

function To-VersionCode($parts) { return $parts[0] * 1000000 + $parts[1] * 1000 + $parts[2] }

function Get-NextVersion {
    # 必须按"版本号数值"取最大，不能用 git describe：
    # describe 是按提交可达性与日期挑 tag，多个 tag 指向同一提交时会返回旧的，
    # 于是算出的新版本可能比已发布的还低 —— 客户端的升级判据是
    # remoteCode > installedCode，那样更新就永远推不出去。
    $best = $null
    $bestCode = -1
    foreach ($t in @(& git tag --list "v[0-9]*.*" 2>$null)) {
        if (-not $t) { continue }
        try { $q = Parse-Version $t } catch { continue }
        $code = To-VersionCode $q
        if ($code -gt $bestCode) { $bestCode = $code; $best = $q }
    }
    if (-not $best) { return "1.0.0" }
    return ("{0}.{1}.{2}" -f $best[0], $best[1], ($best[2] + 1))
}

# ---------------------------------------------------------------- 主流程
$config = Read-Config
$repo  = if ($config.repo)  { $config.repo.Trim() }  else { "" }
$token = if ($config.token) { $config.token.Trim() } else { "" }

if ($repo -notmatch "^[^/\s]+/[^/\s]+$") {
    throw "release-config.json 的 repo 必须是 owner/仓库名 形式，当前: [$repo]"
}

$vName  = if ($Version) { $Version } else { Get-NextVersion }
$vParts = Parse-Version $vName
$vCode  = To-VersionCode $vParts
$vClean = "{0}.{1}.{2}" -f $vParts[0], $vParts[1], $vParts[2]
$tagName = "v$vClean"

Step '发布信息'
Write-Host "  仓库   : $repo"
Write-Host "  版本号 : $vClean  (versionCode=$vCode)"
Write-Host "  Tag    : $tagName"
$mode = if ($DryRun) { "DryRun — 不发到 GitHub" } elseif ($Draft) { "建 draft Release" } else { "直接发布" }
Write-Host "  模式   : $mode"

$apkSrc = Join-Path $apkDir "app-release.apk"
if (-not $SkipBuild) {
    Step '构建签名包'
    if (-not (Test-Path $ksPath)) { throw "找不到密钥库 $ksPath，请先运行 tools/gen-keystore.ps1" }
    $gradlew = Join-Path $root "gradlew.bat"
    & $gradlew --console=plain assembleRelease "-PversionCode=$vCode" "-PversionName=$vClean"
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败，退出码 $LASTEXITCODE" }
}

if (-not (Test-Path $apkSrc)) { throw "找不到签名产物 $apkSrc，请确认 release-config.json 的密钥库已配置" }

$apkName = "kuaishou-auto-$vClean.apk"
$apkOut  = Join-Path $tmpDir $apkName
Copy-Item $apkSrc $apkOut -Force

Step '产物校验'
$fi = Get-Item $apkOut
Write-Host ("  APK    : {0}  ({1:N2} MB)" -f $apkName, ($fi.Length / 1MB))

# 从 local.properties 找 Android SDK，用于调用 aapt2 / apksigner
$sdkDir = $null
$lp = Join-Path $root "local.properties"
if (Test-Path $lp) {
    $hit = Get-Content $lp | Where-Object { $_ -match "^sdk[.]dir=" } | Select-Object -First 1
    if ($hit) {
        # .properties 是 Java 转义规则（实测 sdk.dir=C\:\\Users\\...），
        # Regex.Unescape 能正确还原，且不会像手工替换那样受处理顺序影响
        $sdkDir = [System.Text.RegularExpressions.Regex]::Unescape((($hit -replace "^sdk[.]dir=", "").Trim()))
    }
}
$aapt2 = $null; $apksigner = $null
if ($sdkDir -and (Test-Path (Join-Path $sdkDir "build-tools"))) {
    $bt = Get-ChildItem (Join-Path $sdkDir "build-tools") -Directory | Sort-Object Name -Descending | Select-Object -First 1
    $aapt2     = Join-Path $bt.FullName "aapt2.exe"
    $apksigner = Join-Path $bt.FullName "apksigner.bat"
}

if ($aapt2 -and (Test-Path $aapt2)) {
    $badge = (& $aapt2 dump badging $apkOut 2>&1 | Select-String -Pattern "^package:") -join ""
    Write-Host "  badging: $($badge.Trim())"
    if ($badge -notmatch "versionName=.$vClean.") { throw "APK 内版本号与预期不符，包可能没重新构建" }
} else {
    Write-Host "  badging: 跳过（未找到 aapt2）"
}

if ($apksigner -and (Test-Path $apksigner)) {
    $out = & $apksigner verify --print-certs $apkOut 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw "APK 签名校验失败，未签名的包不能发版" }
    $cert = ($out | Select-String -Pattern "SHA-256 digest") -join ""
    Write-Host "  签名   : 通过  $($cert.Trim())"
} else {
    throw "找不到 apksigner，无法验证签名，拒绝发布"
}

$sha = (Get-FileHash $apkOut -Algorithm SHA256).Hash.ToLower()
Write-Host "  SHA256 : $sha"

if ($DryRun) {
    Step 'DryRun 结束'
    Write-Host "  产物留在: $apkOut"
    Write-Host "  未对 GitHub 做任何写操作。去掉 -DryRun 即真正发布。"
    return
}

if (-not $token) { throw "release-config.json 的 token 未填写，无法发布" }

# ---- 建 Release ----
Step '创建 Release'
$bodyText = if ($Notes) { $Notes } else { "自动构建发布 $vClean" + [Environment]::NewLine + [Environment]::NewLine + "SHA256: $sha" }
$payload = [ordered]@{
    tag_name         = $tagName
    name             = $tagName
    body             = $bodyText
    draft            = [bool]$Draft
    prerelease       = $false
    target_commitish = "main"
}
$payloadFile = Join-Path $tmpDir "release.json"
[System.IO.File]::WriteAllText($payloadFile, ($payload | ConvertTo-Json -Depth 5), (New-Object System.Text.UTF8Encoding($false)))

$respFile = Join-Path $tmpDir "release-resp.json"
& curl.exe -sS -m 60 -X POST `
    -H "Authorization: Bearer $token" `
    -H "Accept: application/vnd.github+json" `
    -H "Content-Type: application/json" `
    -H "User-Agent: kuaishou-auto-publish" `
    --data-binary "@$payloadFile" `
    "https://api.github.com/repos/$repo/releases" `
    -o $respFile
$apiExit = $LASTEXITCODE

$respRaw = [System.IO.File]::ReadAllText($respFile)
$resp = $null
try { $resp = $respRaw | ConvertFrom-Json } catch { }

if ($apiExit -ne 0 -or -not $resp -or -not $resp.id) {
    $msg = if ($resp -and $resp.message) { $resp.message } else { $respRaw }
    throw "创建 Release 失败: $msg"
}
Write-Host "  Release ID : $($resp.id)"
Write-Host "  页面       : $($resp.html_url)"

# ---- 上传 APK ----
Step '上传 APK'
$uploadUrl = "https://uploads.github.com/repos/$repo/releases/$($resp.id)/assets?name=$apkName"
$assetFile = Join-Path $tmpDir "asset-resp.json"
& curl.exe -sS -m 900 -X POST `
    -H "Authorization: Bearer $token" `
    -H "Accept: application/vnd.github+json" `
    -H "Content-Type: application/octet-stream" `
    -H "User-Agent: kuaishou-auto-publish" `
    --data-binary "@$apkOut" `
    $uploadUrl `
    -o $assetFile
$upExit = $LASTEXITCODE

$assetRaw = [System.IO.File]::ReadAllText($assetFile)
$asset = $null
try { $asset = $assetRaw | ConvertFrom-Json } catch { }

if ($upExit -ne 0 -or -not $asset -or -not $asset.id) {
    $msg = if ($asset -and $asset.message) { $asset.message } else { $assetRaw }
    Write-Host "  上传失败: $msg"
    Write-Host "  Release 已建好，可手动上传: $($resp.html_url)"
    throw "上传 APK 失败"
}
Write-Host "  已上传   : $($asset.name)  ($([math]::Round($asset.size/1MB,2)) MB)"
Write-Host "  下载地址 : $($asset.browser_download_url)"

# ---- 本地 tag，便于下次版本号递增 ----
Step '本地 git tag'
if (Test-Path (Join-Path $root ".git")) {
    $exists = (& git tag --list $tagName 2>$null) -join ""
    if ($exists) {
        Write-Host "  $tagName 已存在，跳过"
    } else {
        & git tag -a $tagName -m "release $vClean" 2>&1 | Out-Null
        Write-Host "  已创建 $tagName"
    }
} else {
    Write-Host "  当前不是 git 仓库，跳过打 tag"
}

Step '完成'
Write-Host "  Release 页面: $($resp.html_url)"
if ($Draft) { Write-Host "  这是 draft：确认无误后到页面点 Publish release，客户端才能检查到它。" }
Remove-Item -Recurse -Force $tmpDir -ErrorAction SilentlyContinue

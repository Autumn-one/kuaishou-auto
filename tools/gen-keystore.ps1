<#
    gen-keystore.ps1 —— 生成发布签名密钥库，并把口令回填到 release-config.json

    用法：
        pwsh -File tools/gen-keystore.ps1
        pwsh -File tools/gen-keystore.ps1 -Alias mykey -Force

    密钥库固定写到 keystore/release.jks（已在 .gitignore 中）。
    别名只在本脚本生成时使用；构建时会自动从密钥库里读出来，配置文件里不存别名。
    注意：PKCS12 密钥库只支持单一口令，不存在单独的「密钥口令」。
#>
[CmdletBinding()]
param(
    [string] $Alias    = 'kuaishou-auto',
    [string] $Password,
    [string] $Dname    = 'CN=KuaishouAuto, OU=Personal, O=KuaishouAuto, C=CN',
    [int]    $Validity = 10000,
    [switch] $Force
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# ---------- 仓库根目录（脚本在 tools/ 下，根目录是它的上一级）----------
$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $root 'settings.gradle.kts'))) {
    throw "找不到仓库根目录（应包含 settings.gradle.kts），脚本位置: $PSScriptRoot"
}
$cfgPath = Join-Path $root 'release-config.json'
$ksPath  = Join-Path $root 'keystore/release.jks'

Write-Host "仓库根目录 : $root"
Write-Host "密钥库路径 : $ksPath"

# ---------- 1. 读取 JSON 配置（缺失则建一份最小可用的）----------
function Read-Config {
    if (-not (Test-Path $cfgPath)) {
        $init = [ordered]@{
            '_readme'          = @('快手自动上滑 —— 发布配置（本文件不入库，也不会打进 APK）', 'repo: 你的 GitHub 仓库，格式 owner/仓库名', 'keystorePassword: 跑 tools/gen-keystore.ps1 会自动回填')
            'repo'             = ''
            'keystorePassword' = ''
        }
        [System.IO.File]::WriteAllText($cfgPath, ($init | ConvertTo-Json -Depth 6), (New-Object System.Text.UTF8Encoding($false)))
        Write-Host "已创建配置: $cfgPath"
    }
    $raw = [System.IO.File]::ReadAllText($cfgPath)
    if ($raw.Length -gt 0 -and $raw[0] -eq [char]0xFEFF) { $raw = $raw.Substring(1) }   # 去 BOM
    return ($raw | ConvertFrom-Json)
}

$config = Read-Config

# ---------- 2. 口令优先级：命令行 > JSON 配置 > 交互输入 ----------
if (-not $Password -and $config.keystorePassword) { $Password = $config.keystorePassword }

if (-not $Password) {
    Write-Host ''
    Write-Host '配置里还没有口令，请现在就设定一个。'
    Write-Host '（脚本会写回 release-config.json，之后构建自动读取）'
    while ($true) {
        $s1 = Read-Host '请输入密钥库口令（至少 6 位）'
        if ($s1.Length -lt 6) { Write-Host '口令太短，至少 6 位。'; continue }
        $s2 = Read-Host '请再输入一次确认'
        if ($s1 -ne $s2) { Write-Host '两次输入不一致，请重来。'; continue }
        $Password = $s1
        break
    }
}

# ---------- 3. 已存在则确认 ----------
if ((Test-Path $ksPath) -and -not $Force) {
    Write-Host ''
    Write-Host "密钥库已存在: $ksPath"
    $ans = Read-Host '覆盖会作废已有签名（已装用户将无法更新）。输入 OVERWRITE 确认覆盖，其它任意键退出'
    if ($ans -ne 'OVERWRITE') { Write-Host '已取消，未做任何修改。'; exit 0 }
}

# -Force 时必须先删掉旧库：否则 keytool 会因「别名已存在」而拒绝生成
if (Test-Path $ksPath) {
    $resolved = (Resolve-Path -LiteralPath $ksPath).Path
    Write-Host "已删除旧密钥库（-Force）: $resolved"
    Remove-Item -LiteralPath $resolved -Force
}

# ---------- 4. 生成 ----------
$ksDir = Split-Path -Parent $ksPath
if ($ksDir -and -not (Test-Path $ksDir)) { New-Item -ItemType Directory -Force -Path $ksDir | Out-Null }

$keytool = (Get-Command keytool -ErrorAction SilentlyContinue)
if (-not $keytool) { throw 'PATH 中找不到 keytool。请安装 JDK 17 并把 <JDK>/bin 加入 PATH。' }

Write-Host ''
Write-Host '正在生成密钥库 ...'
& $keytool.Source @(
    '-genkeypair', '-noprompt', '-v',
    '-storetype', 'PKCS12',
    '-keystore', $ksPath,
    '-alias', $Alias,
    '-keyalg', 'RSA',
    '-keysize', '4096',
    '-validity', $Validity,
    '-storepass', $Password,
    '-dname', $Dname
)
if ($LASTEXITCODE -ne 0) { throw "keytool -genkeypair 失败，退出码 $LASTEXITCODE" }
if (-not (Test-Path $ksPath)) { throw '生成失败：密钥库文件未出现。' }

$fi = Get-Item $ksPath
Write-Host ''
Write-Host "生成成功: $($fi.FullName)  ($([math]::Round($fi.Length/1KB,1)) KB)"

# ---------- 5. 回填 JSON（只写口令；repo 保持用户填的值）----------
if (-not $config.PSObject.Properties['keystorePassword']) {
    $config | Add-Member -NotePropertyName 'keystorePassword' -NotePropertyValue '' -Force
}
$config.keystorePassword = $Password

[System.IO.File]::WriteAllText($cfgPath, ($config | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
Write-Host ''
Write-Host '已回填 release-config.json: keystorePassword'

# ---------- 6. 自检 ----------
$check = & $keytool.Source -list -keystore $ksPath -storepass $Password 2>&1
$checkOk = ($LASTEXITCODE -eq 0)
Write-Host ''
Write-Host "密钥库自检: $(if ($checkOk) { '通过' } else { "失败（退出码 $LASTEXITCODE）" })"
$check | Select-Object -First 5 | ForEach-Object { Write-Host "  $_" }
if (-not $checkOk) {
    throw '自检失败：密钥库或写入 release-config.json 的口令有误，请检查上面的输出。'
}

Write-Host ''
Write-Host '下一步：'
if (-not $config.repo) {
    Write-Host '  1. 打开 release-config.json，把 repo 填成 owner/仓库名'
} else {
    Write-Host "  1. repo 已配置: $($config.repo)"
}
Write-Host '  2. 把密钥库与口令抄录到 GitHub Secrets: KS_B64 / KS_PASSWORD'
Write-Host '  3. 离线备份密钥库（至少两份不同介质）—— 丢失即无法再推送更新'

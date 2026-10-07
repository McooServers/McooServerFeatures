<#
.SYNOPSIS
    预取 Minecraft 版本清单与客户端/服务端 jar 到 Loom 缓存。

.DESCRIPTION
    【为什么需要这个脚本】

    在国内网络下，Java 的 TLS 握手会被中间设备拦截：

        Java  → piston-data.mojang.com  →  SSLHandshakeException: Remote host terminated the handshake
        PowerShell → piston-data.mojang.com →  正常 200

    已实测（2026-10-07）以下组合均**无法**绕过：
        -Dhttps.protocols=TLSv1.2 / -Djdk.tls.client.protocols=TLSv1.2
        -Djdk.httpclient.version=HTTP_1_1
        两者同时使用

    而 Java → libraries.minecraft.net 与 maven.fabricmc.net 是**通的**。
    所以问题只出在 piston-* 这两个域名的 Java TLS 指纹上。

    本脚本改用 PowerShell（走 Windows 原生 TLS，能通）把 4 个文件下载好，
    直接放进 Loom 的缓存目录，Gradle 就不会再自己去碰 piston-* 了。

    这 4 个文件：
        <loom-cache>/mojang_versions_manifest.json
        <loom-cache>/<版本>/mojang_minecraft_info.json
        <loom-cache>/<版本>/minecraft-server.jar
        <loom-cache>/<版本>/minecraft-client.jar

.PARAMETER McVersion
    Minecraft 版本号。默认读取 gradle.properties 里的 minecraft_version。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\fetch-mojang-jars.ps1
    powershell -ExecutionPolicy Bypass -File tools\fetch-mojang-jars.ps1 -McVersion 26.3
#>
[CmdletBinding()]
param(
    [string]$McVersion = $null
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# ---------- 1. 读版本号 ----------
if (-not $McVersion) {
    $props = Join-Path $root 'gradle.properties'
    if (-not (Test-Path $props)) { throw "找不到 gradle.properties" }
    $line = Select-String -Path $props -Pattern '^minecraft_version\s*=\s*(.+)$' |
            Select-Object -First 1
    if (-not $line) { throw "gradle.properties 里没有 minecraft_version" }
    $McVersion = $line.Matches[0].Groups[1].Value.Trim()
}
Write-Host "Minecraft 版本 : $McVersion" -ForegroundColor Cyan

# ---------- 2. 定位 Loom 缓存 ----------
$gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME }
              else { Join-Path $root '.gradle-home' }
$loomCache  = Join-Path $gradleHome 'caches\fabric-loom'
$verDir     = Join-Path $loomCache $McVersion

if (-not (Test-Path $gradleHome)) { throw "找不到 GRADLE_USER_HOME：$gradleHome" }
New-Item -ItemType Directory -Force -Path $verDir | Out-Null
Write-Host "Loom 缓存     : $verDir" -ForegroundColor Cyan

# ---------- 3. 下载工具（走 Windows 原生 TLS） ----------
function Invoke-Download {
    param([string]$Url, [string]$OutFile, [string]$ExpectSha1, [long]$ExpectSize = 0)

    # 已有且校验通过就跳过
    if (Test-Path $OutFile) {
        $len = (Get-Item $OutFile).Length
        if ($ExpectSize -gt 0 -and $len -ne $ExpectSize) {
            Write-Host "  缓存文件大小不符，重新下载..." -ForegroundColor Yellow
            Remove-Item $OutFile -Force
        } else {
            $sha = (Get-FileHash $OutFile -Algorithm SHA1).Hash.ToLower()
            if ($ExpectSha1 -and $sha -ne $ExpectSha1.ToLower()) {
                Write-Host "  缓存文件校验不符，重新下载..." -ForegroundColor Yellow
                Remove-Item $OutFile -Force
            } else {
                Write-Host ("  已就绪，跳过: {0}  ({1:N1} MB)" -f (Split-Path $OutFile -Leaf), ($len/1MB)) -ForegroundColor DarkGray
                return
            }
        }
    }

    $tmp = "$OutFile.part"
    $req = [System.Net.HttpWebRequest]::Create($Url)
    $req.Timeout = 60000
    $req.ReadWriteTimeout = 120000
    $req.UserAgent = 'Mozilla/5.0'
    $resp = $req.GetResponse()
    $in  = $resp.GetResponseStream()
    $out = [System.IO.File]::Create($tmp)
    try {
        $buf = New-Object byte[] 262144
        $total = 0; $sw = [System.Diagnostics.Stopwatch]::StartNew()
        while (($n = $in.Read($buf, 0, $buf.Length)) -gt 0) {
            $out.Write($buf, 0, $n); $total += $n
            if ($ExpectSize -gt 0) {
                $pct = [math]::Floor($total * 100 / $ExpectSize)
                Write-Host ("`r  下载中 {0,3}%  ({1:N1} / {2:N1} MB)" -f `
                    $pct, ($total/1MB), ($ExpectSize/1MB)) -NoNewline
            }
        }
        $sw.Stop()
        if ($ExpectSize -gt 0) { Write-Host "" }
    } finally {
        $out.Close(); $in.Close(); $resp.Close()
    }
    Move-Item $tmp $OutFile -Force

    # 校验
    if ($ExpectSha1) {
        $sha = (Get-FileHash $OutFile -Algorithm SHA1).Hash.ToLower()
        if ($sha -ne $ExpectSha1.ToLower()) {
            throw "SHA1 校验失败！期望 $($ExpectSha1) 实际 $sha"
        }
        Write-Host ("  OK  {0}  ({1:N1} MB)  sha1 已校验" -f (Split-Path $OutFile -Leaf), ((Get-Item $OutFile).Length/1MB)) -ForegroundColor Green
    }
}

# ---------- 4. 版本清单 ----------
Write-Host "`n[1/3] 版本清单" -ForegroundColor Cyan
$manifestPath = Join-Path $loomCache 'mojang_versions_manifest.json'
$manifestUrl  = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
if (Test-Path $manifestPath) {
    Write-Host "  已存在，跳过" -ForegroundColor DarkGray
} else {
    Invoke-Download -Url $manifestUrl -OutFile $manifestPath
}
$manifest = Get-Content $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json

# ---------- 5. 版本信息 ----------
Write-Host "`n[2/3] 版本信息 json" -ForegroundColor Cyan
$entry = $manifest.versions | Where-Object { $_.id -eq $McVersion } | Select-Object -First 1
if (-not $entry) {
    throw "版本清单里找不到 $McVersion。该版本可能尚未发布，或用 -McVersion 指定其它版本。"
}
$infoPath = Join-Path $verDir 'mojang_minecraft_info.json'
if (Test-Path $infoPath) {
    Write-Host "  已存在，跳过" -ForegroundColor DarkGray
} else {
    Invoke-Download -Url $entry.url -OutFile $infoPath
}
$info = Get-Content $infoPath -Raw -Encoding UTF8 | ConvertFrom-Json

# ---------- 6. jar ----------
Write-Host "`n[3/3] 游戏 jar" -ForegroundColor Cyan
Invoke-Download -Url $info.downloads.server.url `
                -OutFile (Join-Path $verDir 'minecraft-server.jar') `
                -ExpectSha1 $info.downloads.server.sha1 `
                -ExpectSize $info.downloads.server.size
Invoke-Download -Url $info.downloads.client.url `
                -OutFile (Join-Path $verDir 'minecraft-client.jar') `
                -ExpectSha1 $info.downloads.client.sha1 `
                -ExpectSize $info.downloads.client.size

Write-Host "`n完成。现在可以运行 .\gradlew.bat build" -ForegroundColor Green

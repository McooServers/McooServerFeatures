<#
.SYNOPSIS
    启动本地 Fabric 测试服并抓日志，用于验证 mcoo mod 真的能加载、且不影响服务器。

.DESCRIPTION
    为什么需要这个脚本：
    单元测试证明不了「jar 能不能在真服务器上加载」。那是 M0 的硬门槛之一，
    只能真跑一次。所以本脚本是 M1~M5 每轮交付的必跑项。

    它做四件事：
      1. 用 Zulu 25 拉起 testserver\ 下的 Fabric 服务端（端口 25599，不碰线上服）
      2. 把本轮日志归档成 testserver\runs\<tag>-<时间戳>.log
      3. 看到 "Done (" 之后自动敲 stop（除非 -NoAutoStop）
      4. 退出后给出判定：mod 有没有被发现、有没有 ERROR/Exception、退出码多少

    三个踩过的坑，写在这免得以后有人改回去：

      - 必须跑 fabric-server-launch.jar（installer 生成的 0.6KB 桩），
        不能跑 fabric-server-launch-26.2-0.19.5.jar。后者是下载器，
        启动时要去 piston-meta.mojang.com 抓原版服务端，在本机会被 TLS 拦截打断。

      - 不要用 BeginOutputReadLine + 事件回调抓子进程输出。实测在 PS 5.1 下
        回调一次都不触发（java -version 都能收到 0 行），日志写出来是 0 字节。
        这里改成不重定向子进程输出，直接读 Minecraft 自己写的 logs\latest.log。

      - 管道给 Select-Object -Last 会吞掉中途的输出，看起来像脚本崩了。
        排查时别用它。

    首次启动要从 libraries.minecraft.net 下载 MC 依赖，会比较久。

.PARAMETER TimeoutSec
    从启动到超时的秒数，默认 900。首次启动建议给足。

.PARAMETER NoAutoStop
    ready 之后不停，留着手工敲命令。

.PARAMETER Tag
    日志文件名标记，默认 run。分轮次跑时传不同值方便对照。

.PARAMETER JvmArgs
    额外的 JVM 参数。故障注入测试用它传 -Dmcoo.faultInject=true 之类。

.EXAMPLE
    .\run-testserver.ps1
    常规用法：起来 -> ready -> 自动停 -> 看报告。

.EXAMPLE
    .\run-testserver.ps1 -NoAutoStop -Tag debug
    需要手工敲命令时用。停服用 stop。
#>
[CmdletBinding()]
param(
    [int]$TimeoutSec = 900,
    [switch]$NoAutoStop,
    [switch]$Visible,
    [string]$Tag = 'run',
    [string[]]$JvmArgs = @()
)

$ErrorActionPreference = 'Stop'

# ---- 路径 ----
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ServerDir   = Join-Path $ProjectRoot 'testserver'
$LaunchJar   = Join-Path $ServerDir 'fabric-server-launch.jar'
$RunDir      = Join-Path $ServerDir 'runs'
$McLog       = Join-Path $ServerDir 'logs\latest.log'

if (-not (Test-Path $LaunchJar)) {
    throw @"
找不到 Fabric 服务端启动器：$LaunchJar

testserver/ 没有进版本库 —— 里面有 Mojang 的 jar，不能再分发出去。
所以新克隆的仓库要自己搭一次测试服：

  1. 下载 Fabric 26.2 的服务端安装包（Loader 0.19.5），解压到 testserver\，
     得到 fabric-server-launch.jar
  2. 把 fabric-api-0.161.0+26.2.jar 放进 testserver\mods\
  3. 手动跑一次 fabric-server-launch.jar，接受 EULA、让它把 server.jar 拉下来
  4. 按 README.md「本地测试服」那节改 server.properties（端口 25599 等）

改完再跑本脚本即可。
"@
}
if (-not (Test-Path (Join-Path $ServerDir 'server.jar'))) {
    throw "找不到原版 server.jar：$(Join-Path $ServerDir 'server.jar')`n先手动跑一次 fabric-server-launch.jar 让它把原版服务端拉下来（eula.txt 里要有 eula=true）"
}

# ---- 把刚构建出来的 mod jar 同步进 mods/ ----
# 这一步刻意做在脚本里，而不是靠人记得手动拷。
# 漏拷一次**不会报错**，只会让你拿着上一个版本的 jar 去验新代码，
# 结果是"自检全绿"但验的根本不是刚写的那份 —— 这种假绿最难发现，
# 所以这里强制同步，并把实际要加载的那份打印出来。
$ModsDir  = Join-Path $ServerDir 'mods'
$LibsDir  = Join-Path $ProjectRoot 'build\libs'
$LoadedMod = $null

$BuiltJar = Get-ChildItem -Path $LibsDir -Filter '*.jar' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1

if ($null -eq $BuiltJar) {
    Write-Host '  [警告] build\libs 里没有 jar，本次不带 mcoo 启动' -ForegroundColor Yellow
} else {
    New-Item -ItemType Directory -Force -Path $ModsDir | Out-Null
    # 清掉 mods/ 下其它版本的 mcoo jar —— 版本号变了会残留多份，
    # 同时加载同一个 mod 的两份会直接让服务器起不来。
    Get-ChildItem -Path $ModsDir -Filter 'mcoo-server-features-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ne $BuiltJar.Name } |
        ForEach-Object {
            Write-Host "  [清理] 旧版 mod jar：$($_.Name)" -ForegroundColor DarkGray
            Remove-Item $_.FullName -Force
        }
    $Target = Join-Path $ModsDir $BuiltJar.Name
    Copy-Item $BuiltJar.FullName $Target -Force
    $LoadedMod = Get-Item $Target
}

# ---- 找 Java 25（Minecraft 26.2 要求 Java 25，本机是 Zulu 25）----
$JavaHome_ = $env:JAVA_HOME
if (-not $JavaHome_) { $JavaHome_ = 'C:\Program Files\Zulu\zulu-25' }
$JavaExe = Join-Path $JavaHome_ 'bin\java.exe'
if (-not (Test-Path $JavaExe)) {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if (-not $cmd) { throw '找不到 Java。Minecraft 26.2 需要 Java 25。' }
    $JavaExe = $cmd.Source
}

New-Item -ItemType Directory -Force -Path $RunDir | Out-Null

# 把上一轮的 latest.log 挪走，保证本轮读到的日志一定是新的
if (Test-Path $McLog) {
    $Stale = Join-Path $ServerDir ("logs\stale-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
    try { Move-Item $McLog $Stale -Force } catch { }
}

$Stamp   = Get-Date -Format 'yyyyMMdd-HHmmss'
$RunLog  = Join-Path $RunDir "$Tag-$Stamp.log"

# -Xmx2G：本机 16GB，测试服没必要吃太多
$JArgs = @('-Xms512m', '-Xmx2G', '-XX:+UseG1GC') + $JvmArgs + @('-jar', "`"$LaunchJar`"", 'nogui')

Write-Host '=== 启动 Fabric 测试服 ===' -ForegroundColor Cyan
Write-Host "  目录 : $ServerDir"
Write-Host "  Java : $JavaExe"
Write-Host "  本轮 : $RunLog"
if ($LoadedMod) {
    Write-Host ("  mod  : {0}  ({1:N0} 字节，{2})" -f `
        $LoadedMod.Name, $LoadedMod.Length, $LoadedMod.LastWriteTime.ToString('HH:mm:ss')) -ForegroundColor Yellow
} else {
    Write-Host '  mod  : （无）' -ForegroundColor Yellow
}
if ($JvmArgs.Count -gt 0) { Write-Host "  额外 : $($JvmArgs -join ' ')" -ForegroundColor Yellow }
Write-Host ''

# ------------------------------------------------------------------
#  可见窗口模式：把服务端开在一个真正的 CMD 窗口里，自己敲指令
# ------------------------------------------------------------------
if ($Visible) {
    # 为什么要有这个模式：服务端控制台能直接敲 /gamemode /op /tp /time set 这些，
    # 比让 AI 代劳再转述快得多，也省掉"AI 猜错了模式，用户没法自己纠正"的问题。
    #
    # 刻意用 cmd /k 而不是 /c：/k 在服务端退出后保留窗口，
    # 这样能亲眼看到 "Stopping server"、存盘结果和退出码，
    # 而不是窗口一闪就没了、什么都看不到。
    Write-Host '  模式 : 可见窗口（在新 CMD 里运行，可直接敲指令）' -ForegroundColor Green
    Write-Host ''
    Write-Host '  新窗口里可以做的事：' -ForegroundColor Cyan
    Write-Host '    /gamemode creative    切创造模式' -ForegroundColor DarkGray
    Write-Host '    /gamemode survival    切生存模式' -ForegroundColor DarkGray
    Write-Host '    /op <名字>            给某人管理员' -ForegroundColor DarkGray
    Write-Host '    /time set day         改成白天（免得夜里抓不到怪）' -ForegroundColor DarkGray
    Write-Host '    /weather clear        停雨' -ForegroundColor DarkGray
    Write-Host '    /give @s minecraft:chest 6  给自己 6 个箱子' -ForegroundColor DarkGray
    Write-Host '    stop                  停服（会正常存盘）' -ForegroundColor DarkGray
    Write-Host ''
    Write-Host '  这条命令会立刻返回；服务端在新窗口里自己跑。' -ForegroundColor Yellow
    Write-Host '  AI 这边读日志用：testserver\logs\latest.log' -ForegroundColor DarkGray

    # cmd /k 后面接整条命令行。JArgs 里的路径引号已经在组装时带上了。
    $cmdLine = '"' + $JavaExe + '" ' + ($JArgs -join ' ')
    Start-Process -FilePath $env:ComSpec `
                  -ArgumentList @('/k', 'title Mcoo 测试服（敲 stop 停服） & chcp 65001 & ' + $cmdLine) `
                  -WorkingDirectory $ServerDir | Out-Null

    Write-Host ''
    Write-Host '  已在新窗口启动。' -ForegroundColor Green
    Write-Host '===========================================' -ForegroundColor Cyan
    return
}

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName              = $JavaExe
$psi.Arguments             = ($JArgs -join ' ')
$psi.WorkingDirectory      = $ServerDir
$psi.UseShellExecute       = $false
# 只重定向 stdin（要靠它敲 stop）。stdout/stderr 故意不重定向，
# 让服务端直接写控制台 + 它自己的 logs\latest.log，绕开异步回调不可靠的问题。
$psi.RedirectStandardInput = $true

$proc = New-Object System.Diagnostics.Process
$proc.StartInfo = $psi

$Printed  = 0
$Ready    = $false
$SentStop = $false
$TimedOut = $false
$ExitCode = -1

try {
    [void]$proc.Start()

    $Deadline = (Get-Date).AddSeconds($TimeoutSec)
    $LastBeat  = Get-Date
    while (-not $proc.HasExited) {
        # 不逐行回显：子进程没被重定向，它自己的输出已经在往控制台写了。
        # 这里再 echo 一遍会出现每行两遍，看着像日志重复，实际只是回显。
        # 改成每 5 秒报一次进度，下载依赖那种长时间静默也不像卡死。
        if (Test-Path $McLog) {
            $All = @(Get-Content $McLog -Encoding UTF8 -ErrorAction SilentlyContinue)
            $Printed = $All.Count
            if (-not $Ready -and ($All -match 'Done \(')) { $Ready = $true }
        }
        if (((Get-Date) - $LastBeat).TotalSeconds -ge 5) {
            $Secs = [int]((Get-Date) - $proc.StartTime).TotalSeconds
            Write-Host ("[进度] 已运行 {0}s，日志 {1} 行" -f $Secs, $Printed) -ForegroundColor DarkGray
            $LastBeat = Get-Date
        }

        if ($Ready -and -not $NoAutoStop -and -not $SentStop) {
            Write-Host "`n[就绪] 检测到 Done，自动发送 stop ...`n" -ForegroundColor Green
            # 绕开 StandardInput 这个 StreamWriter 来发命令，直接写底层字节流。
            #
            # 为什么要这么绕：只要访问过一次 Process.StandardInput，.NET Framework
            # 就会按控制台代码页（这台机器是 GBK）自动冲出一段编码前缀。实测
            # 直接写 BaseStream 发过去的命令在服务端长这样：
            #     [INFO]: ﻿stop<--[HERE]
            # 服务端只会回一句 Unknown or incomplete command 然后把你无视掉。
            #
            # 顺序不能反：必须先用 WriteLine('') 把那段前缀冲掉（它自己带换行，
            # 服务端读到一个空行会忽略），再写真正的 stop。
            # 另注：本机 .NET 是 4.0.30319，ProcessStartInfo 没有
            # StandardInputEncoding 属性，所以只能走 BaseStream 这条路。
            $proc.StandardInput.WriteLine('')
            $proc.StandardInput.Flush()
            Start-Sleep -Milliseconds 200

            $Cmd  = [System.Text.Encoding]::UTF8.GetBytes("stop`n")
            $Base = $proc.StandardInput.BaseStream
            $Base.Write($Cmd, 0, $Cmd.Length)
            $Base.Flush()
            $SentStop = $true
        }
        if ((Get-Date) -gt $Deadline) {
            Write-Host "`n[超时] 超过 $TimeoutSec 秒仍未结束，强制结束。" -ForegroundColor Red
            $TimedOut = $true
            try { $proc.Kill() } catch {}
            break
        }
        Start-Sleep -Milliseconds 250
    }

    [void]$proc.WaitForExit(30000)
    Start-Sleep -Milliseconds 800
    $ExitCode = if ($proc.HasExited) { $proc.ExitCode } else { -1 }
}
finally {
    try { if (-not $proc.HasExited) { $proc.Kill() } } catch {}
    try { $proc.Dispose() } catch {}
}

# 归档本轮日志
if (Test-Path $McLog) {
    Copy-Item $McLog $RunLog -Force
}

# ---------------- 判定 ----------------
Write-Host ''
Write-Host '================ 测试服报告 ================' -ForegroundColor Cyan
Write-Host "  日志   : $RunLog"
Write-Host "  退出码 : $ExitCode   （0 = 正常退出）"

$LogLines = @(Get-Content $RunLog -Encoding UTF8 -ErrorAction SilentlyContinue)

if ($Ready) { Write-Host '  服务端 : 已就绪 (Done)' -ForegroundColor Green }
else        { Write-Host '  服务端 : 未就绪' -ForegroundColor Red }

# 本 mod 有没有被 Fabric 发现 / 有没有打日志
$Hit = @($LogLines | Where-Object { $_ -match 'mcoo' })
if ($Hit.Count -gt 0) {
    Write-Host '  mcoo   : 日志中出现' -ForegroundColor Green
    $Hit | Select-Object -First 10 | ForEach-Object { Write-Host "           $_" -ForegroundColor DarkGray }
} elseif ($LoadedMod) {
    # jar 确实放进去了，日志里却一个 mcoo 都没有 = mod 根本没加载成功。
    # 这是硬失败，不是警告 —— 后者会让人以为"测过了"。
    Write-Host '  mcoo   : 失败 —— jar 已放进 mods/，但日志里没有任何 mcoo 输出，mod 没加载' -ForegroundColor Red
} else {
    Write-Host '  mcoo   : 跳过 —— 本次没有加载任何 mcoo jar' -ForegroundColor Yellow
}

# 自检结论：从日志里把「通过 N 项，失败 M 项」捞出来提到报告头部。
# 不这么做的话，结论淹没在几百行日志里，失败项很容易被划过去 —— 这正是
# 「修了 bug 又跑了一遍，但跑的还是旧 jar / 漏看了失败项」这类假绿的温床。
$SelfLines = @($LogLines | Where-Object { $_ -match '自检结束' })
if ($SelfLines.Count -gt 0) {
    foreach ($sl in $SelfLines) {
        if ($sl -match '失败\s*(\d+)\s*项') {
            $nFail = [int]$Matches[1]
        } else {
            $nFail = -1
        }
        if ($nFail -eq 0) {
            Write-Host "  自检   : $sl" -ForegroundColor Green
        } else {
            Write-Host "  自检   : $sl" -ForegroundColor Red
        }
    }
    # 失败项要匹配自检块内部的那种写法（`[mcoo][gui自检] [失败] ...`），
    # 不能只找「[失败]」三个字 —— 启动横幅里那句
    # 「日志里会逐项打印 [通过] / [失败]」会把模式说明本身当成失败项报出来。
    $SelfFails = @($LogLines | Where-Object { $_ -match '\[mcoo\]\[[^\]]+\]\s*\[失败\]' })
    if ($SelfFails.Count -gt 0) {
        Write-Host '  ---- 自检失败项 ----' -ForegroundColor Red
        $SelfFails | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    }
}

# 有没有炸。
# 锚定到 log4j 的级别标记 [xxx/ERROR]:，并且大小写敏感。
# 之前写的是裸 'ERROR'，结果连「Unknown or incomplete command. See below for
# error」这种正常提示都被当成错误报出来。
$ErrLines = @($LogLines | Where-Object {
    $_ -cmatch '/ERROR\]:|Exception in thread|Caused by:|FATAL'
})
if ($ErrLines.Count -gt 0) {
    Write-Host "  错误   : $($ErrLines.Count) 条" -ForegroundColor Red
    Write-Host '  ---- 错误摘录 ----' -ForegroundColor Red
    $ErrLines | Select-Object -First 25 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
} else {
    Write-Host '  错误   : 无 ERROR / Exception' -ForegroundColor Green
}

$WarnCount = @($LogLines | Where-Object { $_ -match '^\s*WARN\b' }).Count
Write-Host "  警告   : $WarnCount 条"
Write-Host "  日志行 : $($LogLines.Count)"
if ($TimedOut) { Write-Host '  超时   : 是' -ForegroundColor Red }
Write-Host '===========================================' -ForegroundColor Cyan
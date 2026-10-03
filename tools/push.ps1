# ---------------------------------------------------------------------------------------
# 收尾推送：一个重要节点做完就走这一步。
#
# 规矩来自用户（2026-10-02）：**每完成一个比较重要的节点就推一次仓库**，
# 不要攒到最后一次性提交 —— 攒着的话，"上一次能跑的版本"就只存在于这台机器的
# 未提交状态里，而真机验证、迁移执行这类证据恰恰是丢不起的。
#
# 用法：
#     . .\tools\env.ps1
#     .\tools\push.ps1 -Message "M9: 上滑关闭闹钟（真机验证 28/28）"
#     .\tools\push.ps1 -Message "..." -WhatIfOnly      # 只检查不提交
#
# 它做四件事，顺序刻意如此：
#   1. 乱码检查 —— 本机控制台是 GBK 代码页，源码经过 shell 文本管道会被毁（STATUS §1.1 #18）。
#      混乱码的提交一旦推上去就进了历史，比本地出错难收拾得多。
#   2. 打印**将要提交**的清单，并拒绝任何本机缓存/构建产物/第三方二进制混进来。
#   3. 提交（提交信息由调用者给，要求写"做了什么 + 怎么验证的"）。
#   4. 推送，并核对远端 HEAD 与本地 HEAD 一致 —— 不一致就报错，不装作成功。
# ---------------------------------------------------------------------------------------

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Message,
    [switch]$AllowBigFiles,
    [switch]$WhatIfOnly
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Fail($msg) { Write-Host "✗ $msg" -ForegroundColor Red; exit 1 }
function Ok($msg)   { Write-Host "✓ $msg" -ForegroundColor Green }
function Info($msg) { Write-Host "  $msg" }

<#
.SYNOPSIS
    Runs a native command (git/python) and returns its combined output, without its stderr killing us.

.DESCRIPTION
    Native tools write normal, expected chatter to **stderr** -- `git add` warns about LF→CRLF,
    `git push` prints "To github.com:…". Under `$ErrorActionPreference = 'Stop'` PowerShell turns any
    foreign stderr into a fatal error, and `2>&1` does **not** disarm it. That is how this script first
    failed twice: once reported "push failed" after a successful push, once died at `git add -A` on a
    line-ending warning.

    So every native call goes through here: the preference is relaxed for the duration, stderr is
    collected as text, and the caller decides based on `$LASTEXITCODE` only.
#>
function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $Exe @Arguments 2>&1 | ForEach-Object { "$_" })
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    return [pscustomobject]@{ Output = $output; Code = $code }
}

# ---- 1. 乱码检查 ----------------------------------------------------------------------
Info '乱码检查…'
$enc = Invoke-Native python @('tools\check-encoding.py')
if ($enc.Code -ne 0) { $enc.Output | ForEach-Object { Info $_ }; Fail '乱码检查未通过；不要提交（源码只能用编辑工具读写，别走 shell 管道）' }
Ok ($enc.Output | Select-Object -Last 1)

# ---- 2. 暂存并审查 --------------------------------------------------------------------
# `core.quotepath=false`：本仓库的文件名有中文（docs/继续-明天.md 之类）。git 默认把非 ASCII
# 路径按八进制转义输出（"\346\226\207…"），拿这种字符串去 Get-Item 会报
# "Illegal characters in path"。这是**仓库级**设置，不动用户的全局配置。
Invoke-Native git @('config', 'core.quotepath', 'false') | Out-Null

$added = Invoke-Native git @('add', '-A')
if ($added.Code -ne 0) { $added.Output | ForEach-Object { Info $_ }; Fail 'git add 失败' }

$staged = @((Invoke-Native git @('diff', '--cached', '--name-only')).Output)
if ($staged.Count -eq 0) { Ok '没有改动，无需提交'; exit 0 }
Info "将要提交 $($staged.Count) 个文件"

$forbidden = $staged | Where-Object {
    $_ -match '^\.gradle-home/|^\.npm-cache/|^node_modules/|/build/|^android/local\.properties$|\.apk$|\.keystore$|\.jks$|^android/app/src/main/assets/public/'
}
if ($forbidden -and -not $AllowBigFiles) {
    $forbidden | ForEach-Object { Info "  不该入库: $_" }
    Fail '暂存区里有缓存/构建产物/密钥/第三方二进制；检查 .gitignore（确实要提交就加 -AllowBigFiles）'
}

# 体积兜底：单个文件超过 20 MB 先拦一下，因为 Git 历史里的二进制删不掉
$big = @()
foreach ($f in $staged) {
    $item = Get-Item -LiteralPath $f -ErrorAction SilentlyContinue
    if ($item -and ($item.Length / 1MB) -gt 20) {
        $big += ('{0} ({1:N1} MB)' -f $f, ($item.Length / 1MB))
    }
}
if ($big -and -not $AllowBigFiles) {
    $big | ForEach-Object { Info "  大文件: $_" }
    Fail '有超过 20 MB 的文件；入库后无法从历史里删掉（确实要提交就加 -AllowBigFiles）'
}

if ($WhatIfOnly) { Ok '仅检查模式：以上都会提交，未执行'; exit 0 }

# ---- 3. 提交 --------------------------------------------------------------------------
$commit = Invoke-Native git @('-c', 'i18n.commitEncoding=UTF-8', 'commit', '-q', '-m', $Message)
if ($commit.Code -ne 0) { $commit.Output | ForEach-Object { Info $_ }; Fail '提交失败' }
$head = (Invoke-Native git @('rev-parse', 'HEAD')).Output[0].Trim()
Ok "已提交 $($head.Substring(0,7))"

# ---- 4. 推送 + 核对 -------------------------------------------------------------------
# 推送的进度走 stderr，走 Invoke-Native 才不会把它当致命错误（见该函数的说明）。
$push = Invoke-Native git @('push')
if ($push.Code -ne 0) {
    $push.Output | ForEach-Object { Info $_ }
    Fail '推送失败（远端 HEAD 未更新；本地提交仍在，修好网络或权限后重跑 git push 即可）'
}
$push.Output | ForEach-Object { Info $_ }

Invoke-Native git @('fetch', '-q', 'origin') | Out-Null
$branch = (Invoke-Native git @('branch', '--show-current')).Output[0].Trim()
$counts = ((Invoke-Native git @('rev-list', '--left-right', '--count', "HEAD...origin/$branch")).Output[0]) -split '\s+'
if ($counts[0] -ne '0' -or $counts[1] -ne '0') {
    Fail "本地与远端不一致（ahead=$($counts[0]) behind=$($counts[1])）"
}
Ok "远端与本地一致：$($head.Substring(0,7)) on $branch"

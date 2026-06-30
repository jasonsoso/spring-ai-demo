$base = "http://localhost:8081"
$failed = @()
$passed = 0

function Test-Url {
    param([string]$Path, [string]$Label = $Path)
    try {
        $r = Invoke-WebRequest -Uri "$base$Path" -UseBasicParsing -TimeoutSec 10
        if ($r.StatusCode -eq 200) {
            $script:passed++
            Write-Host "[OK] $Label"
            return $true
        }
        $script:failed += "$Label -> HTTP $($r.StatusCode)"
        Write-Host "[FAIL] $Label -> HTTP $($r.StatusCode)"
        return $false
    } catch {
        $script:failed += "$Label -> $($_.Exception.Message)"
        Write-Host "[FAIL] $Label -> $($_.Exception.Message)"
        return $false
    }
}

Write-Host "=== 1. 静态资源 (26 files) ==="
$assets = @(
    "/",
    "/css/components.css",
    "/css/tabs/chat.css",
    "/css/tabs/embedding.css",
    "/css/tabs/rag.css",
    "/css/tabs/ecommerce.css",
    "/css/tabs/agent.css",
    "/css/tabs/agent-memory.css",
    "/css/tabs/agent-mysql-memory.css",
    "/css/tabs/agent-tools.css",
    "/css/tabs/mcp.css",
    "/css/tabs/multi-agent.css",
    "/js/core/utils.js",
    "/js/core/tabs.js",
    "/js/tabs/chat.js",
    "/js/tabs/embedding.js",
    "/js/tabs/rag.js",
    "/js/tabs/ecommerce.js",
    "/js/tabs/agent.js",
    "/js/tabs/agent-memory.js",
    "/js/tabs/agent-tools.js",
    "/js/tabs/mcp.js",
    "/js/tabs/multi-agent.js",
    "/js/tabs/ask-user.js",
    "/js/tabs/todo-write.js",
    "/js/tabs/subagent.js",
    "/js/tabs/a2a.js"
)
foreach ($a in $assets) { Test-Url $a | Out-Null }

Write-Host "`n=== 2. index.html 结构检查 ==="
$html = (Invoke-WebRequest -Uri "$base/" -UseBasicParsing).Content
$checks = @(
    @{ Name = "无内联 style"; Pass = ($html -notmatch '<style>') },
    @{ Name = "无内联 script"; Pass = ($html -notmatch '<script>') },
    @{ Name = "含 link components.css"; Pass = ($html -match 'href="/css/components.css"') },
    @{ Name = "含 utils.js"; Pass = ($html -match 'src="/js/core/utils.js"') },
    @{ Name = "16 个 tab-content"; Pass = (([regex]::Matches($html, 'class="tab-content"')).Count -eq 16) },
    @{ Name = "16 个 tab-btn"; Pass = (([regex]::Matches($html, 'class="tab-btn')).Count -eq 16) }
)
foreach ($c in $checks) {
    if ($c.Pass) { $passed++; Write-Host "[OK] $($c.Name)" }
    else { $failed += $c.Name; Write-Host "[FAIL] $($c.Name)" }
}

Write-Host "`n=== 3. onclick 全局函数是否在 JS 中定义 ==="
$staticRoot = Join-Path $PSScriptRoot "..\src\main\resources\static"
$allJs = Get-ChildItem -Path (Join-Path $staticRoot "js") -Recurse -Filter "*.js" | ForEach-Object { Get-Content $_.FullName -Raw }
$jsBundle = $allJs -join "`n"
$onclickMatches = [regex]::Matches($html, 'onclick="([a-zA-Z0-9_]+)\(')
$funcs = $onclickMatches | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
foreach ($fn in $funcs) {
    if ($jsBundle -match "function $fn\s*\(") {
        $passed++
        Write-Host "[OK] function $fn"
    } else {
        $failed += "missing function $fn"
        Write-Host "[FAIL] missing function $fn"
    }
}

Write-Host "`n=== 4. 后端连通性（轻量，不依赖 API Key） ==="
# swagger / api-docs
Test-Url "/v3/api-docs" "OpenAPI docs" | Out-Null

Write-Host "`n=== 汇总 ==="
Write-Host "通过: $passed"
Write-Host "失败: $($failed.Count)"
if ($failed.Count -gt 0) {
    Write-Host "失败项:"
    $failed | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
exit 0

# 讀取專案根目錄的 .env（KEY=VALUE，# 開頭為註解）設成環境變數。
# 已經存在的環境變數不覆蓋，方便臨時用 $env:XXX 改設定。
$envFile = Join-Path (Split-Path -Parent $PSScriptRoot) ".env"
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile -Encoding UTF8) {
        $line = $line.Trim()
        if ($line -eq "" -or $line.StartsWith("#")) { continue }
        $i = $line.IndexOf("=")
        if ($i -lt 1) { continue }
        $key = $line.Substring(0, $i).Trim()
        $value = $line.Substring($i + 1).Trim().Trim('"')
        if (-not (Test-Path "env:$key")) { Set-Item -Path "env:$key" -Value $value }
    }
}

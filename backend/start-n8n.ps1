# Porneste n8n local (instalat cu npm, fara Docker) cu variabilele din backend/.env.
# Workflow-urile citesc $env.N8N_API_KEY, $env.GEMINI_API_KEY si $env.CLERK_DOMAIN, iar n8n 2.x
# blocheaza implicit accesul la $env - fara acest script, fiecare webhook raspunde 500
# ("access to env vars denied").
#
# Fisierul este intentionat doar ASCII: Windows PowerShell 5.1 citeste scripturile UTF-8
# fara BOM in codificarea sistemului, iar diacriticele/ghilimelele tipografice strica parsarea.
#
# Utilizare (PowerShell):  .\backend\start-n8n.ps1

$envFile = Join-Path $PSScriptRoot ".env"
if (-not (Test-Path $envFile)) {
    Write-Error "Lipseste $envFile (copiaza .env.example in .env si completeaza valorile)."
    exit 1
}

foreach ($line in Get-Content $envFile -Encoding UTF8) {
    $trimmed = $line.Trim()
    if ($trimmed -eq "" -or $trimmed.StartsWith("#") -or -not $trimmed.Contains("=")) { continue }
    $name, $value = $trimmed.Split("=", 2)
    Set-Item -Path ("Env:" + $name.Trim()) -Value $value.Trim()
}

$env:N8N_BLOCK_ENV_ACCESS_IN_NODE = "false"

foreach ($required in @("N8N_API_KEY", "CLERK_DOMAIN")) {
    $item = Get-Item ("Env:" + $required) -ErrorAction SilentlyContinue
    if (-not $item -or -not $item.Value) {
        Write-Warning "$required lipseste din .env - endpoint-urile care il folosesc vor esua."
    }
}

Write-Host "Pornesc n8n cu variabilele din $envFile ..."
n8n start

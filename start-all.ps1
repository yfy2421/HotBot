param(
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

function Get-ShellExecutable {
    if (Get-Command pwsh -ErrorAction SilentlyContinue) {
        return "pwsh"
    }
    return "powershell.exe"
}

function Test-PortListening {
    param(
        [int]$Port
    )

    try {
        return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop)
    }
    catch {
        return $false
    }
}

function Test-ProcessCommandLine {
    param(
        [string]$Pattern
    )

    $processes = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like $Pattern }
    return [bool]$processes
}

function Wait-PortListening {
    param(
        [string]$Name,
        [int]$Port,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-PortListening -Port $Port) {
            Write-Host "[$Name] 已就绪（端口 $Port 已监听）。"
            return
        }
        Start-Sleep -Seconds 1
    }

    throw "[$Name] 启动超时：等待端口 $Port 监听超过 $TimeoutSeconds 秒。"
}

function Wait-HttpReady {
    param(
        [string]$Name,
        [string]$Url,
        [int]$TimeoutSeconds = 120
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-RestMethod -Uri $Url -Method Get -TimeoutSec 5
            if ($response -and $response.ready -eq $true) {
                Write-Host "[$Name] 预热完成（ready=true）。"
                return
            }
        }
        catch {
        }
        Start-Sleep -Seconds 1
    }

    throw "[$Name] 启动超时：等待 $Url ready 超过 $TimeoutSeconds 秒。"
}

function Get-DotEnvAssignments {
    param(
        [string]$EnvFilePath
    )

    if (-not $EnvFilePath -or -not (Test-Path $EnvFilePath)) {
        return ""
    }

    $assignments = New-Object System.Collections.Generic.List[string]
    foreach ($line in Get-Content -Path $EnvFilePath -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }

        $parts = $trimmed -split '=', 2
        if ($parts.Count -ne 2) {
            continue
        }

        $name = $parts[0].Trim()
        if (-not $name) {
            continue
        }

        $value = $parts[1]
        $escapedValue = $value.Replace("'", "''")
        $assignments.Add("`$env:$name = '$escapedValue'")
    }

    return [string]::Join('; ', $assignments)
}

function Start-ServiceWindow {
    param(
        [string]$Name,
        [string]$WorkingDirectory,
        [string]$Command,
        [int]$Port = 0,
        [string]$ProcessPattern = "",
        [string]$EnvFilePath = ""
    )

    if ($Port -gt 0 -and (Test-PortListening -Port $Port)) {
        Write-Host "[$Name] 已在运行，跳过启动（端口 $Port 已监听）。"
        return
    }

    if ($ProcessPattern -and (Test-ProcessCommandLine -Pattern $ProcessPattern)) {
        Write-Host "[$Name] 已在运行，跳过启动（检测到现有进程）。"
        return
    }

    $shell = Get-ShellExecutable
    $envAssignments = Get-DotEnvAssignments -EnvFilePath $EnvFilePath
    $scriptParts = New-Object System.Collections.Generic.List[string]
    if ($envAssignments) {
        $scriptParts.Add($envAssignments)
    }
    $scriptParts.Add("Set-Location '$WorkingDirectory'")
    $scriptParts.Add($Command)
    $scriptBlock = [string]::Join('; ', $scriptParts)

    if ($DryRun) {
        Write-Host "[$Name] DryRun -> $scriptBlock"
        return
    }

    Start-Process -FilePath $shell -WorkingDirectory $WorkingDirectory -ArgumentList @(
        "-NoExit",
        "-Command",
        $scriptBlock
    ) | Out-Null

    Write-Host "[$Name] 已启动。"
}

function Get-DotEnvValue {
    param(
        [string]$Key,
        [string]$EnvFilePath,
        [string]$Default = ""
    )

    if (-not $EnvFilePath -or -not (Test-Path $EnvFilePath)) {
        return $Default
    }

    foreach ($line in Get-Content -Path $EnvFilePath -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }
        $parts = $trimmed -split '=', 2
        if ($parts.Count -ne 2) {
            continue
        }
        if ($parts[0].Trim() -eq $Key) {
            return $parts[1].Trim()
        }
    }
    return $Default
}

$root = $PSScriptRoot
$mlServerDir = Join-Path $root "ml-server"
$botServerDir = Join-Path $root "bot-server"
$envFilePath = Join-Path $root ".env"

$mlPort = [int](Get-DotEnvValue -Key "ML_SERVER_PORT" -EnvFilePath $envFilePath -Default "5000")
$botPort = [int](Get-DotEnvValue -Key "BOT_SERVER_PORT" -EnvFilePath $envFilePath -Default "8080")

Start-ServiceWindow -Name "ml-server" -WorkingDirectory $mlServerDir -Command "uvicorn main:app --host 0.0.0.0 --port $mlPort" -Port $mlPort
if (-not $DryRun) {
    Wait-PortListening -Name "ml-server" -Port $mlPort -TimeoutSeconds 60
    Wait-HttpReady -Name "ml-server" -Url "http://localhost:$mlPort/api/ready" -TimeoutSeconds 300
}

Start-ServiceWindow -Name "bot-server" -WorkingDirectory $botServerDir -Command "mvn -DskipTests package; if (`$LASTEXITCODE -eq 0) { java -jar target/hotspot-bot-1.0.0.jar }" -Port $botPort -EnvFilePath $envFilePath
if (-not $DryRun) {
    Wait-PortListening -Name "bot-server" -Port $botPort -TimeoutSeconds 300
    Wait-HttpReady -Name "bot-server" -Url "http://localhost:$botPort/api/ready" -TimeoutSeconds 300
}

Start-ServiceWindow -Name "weixin-gateway" -WorkingDirectory $mlServerDir -Command "python run_weixin_gateway.py" -ProcessPattern "*run_weixin_gateway.py*"

param(
    [string]$Jar = (Join-Path $PSScriptRoot '..\backend\target\foodflow-api.jar'),
    [int]$Port = 8096
)

$ErrorActionPreference = 'Stop'
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java.exe' }
$jarPath = (Resolve-Path -LiteralPath $Jar).Path
$runRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('foodflow-reliability-' + [guid]::NewGuid().ToString('N'))
$databaseBase = (Join-Path $runRoot 'foodflow').Replace('\', '/')
$databaseFile = Join-Path $runRoot 'foodflow.mv.db'
$snapshotFile = Join-Path $runRoot 'snapshot.mv.db'
$logOut = Join-Path $runRoot 'api.stdout.log'
$logErr = Join-Path $runRoot 'api.stderr.log'
New-Item -ItemType Directory -Path $runRoot | Out-Null

$oldDbUrl = $env:DB_URL
$oldDemoPassword = $env:DEMO_PASSWORD
$env:DB_URL = "jdbc:h2:file:$databaseBase;MODE=MySQL;DATABASE_TO_LOWER=TRUE"
$env:DEMO_PASSWORD = 'FoodFlow-reliability-check!'
$process = $null

function Start-FoodFlow {
    $script:process = Start-Process -FilePath $java -ArgumentList @('-jar', $jarPath, "--server.port=$Port") -PassThru -WindowStyle Hidden -RedirectStandardOutput $logOut -RedirectStandardError $logErr
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($script:process.HasExited) { throw "FoodFlow exited during startup. See $logErr" }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/health" -TimeoutSec 2
            if ($health.status -eq 'UP') { return }
        } catch { Start-Sleep -Milliseconds 500 }
    }
    throw "FoodFlow did not become healthy. See $logErr"
}

function Stop-FoodFlow {
    if ($script:process -and -not $script:process.HasExited) {
        Stop-Process -Id $script:process.Id
        $script:process.WaitForExit(15000) | Out-Null
        if (-not $script:process.HasExited) { throw "FoodFlow process $($script:process.Id) did not stop." }
    }
    $script:process = $null
}

function Sign-In {
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $csrf = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/auth/csrf" -WebSession $session
    $headers = @{}; $headers[$csrf.headerName] = $csrf.token
    Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/api/v1/auth/login" -WebSession $session -ContentType 'application/json' -Headers $headers -Body (@{ username='customer'; password=$env:DEMO_PASSWORD } | ConvertTo-Json) | Out-Null
    return $session
}

function New-TestOrder($session) {
    $csrf = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/auth/csrf" -WebSession $session
    $headers = @{}; $headers[$csrf.headerName] = $csrf.token; $headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
    $body = @{ restaurantId='green'; address='Flat 201, Road 12, Jubilee Hills 500033'; area='Jubilee Hills, Hyderabad'; items=@(@{ dishId='bowl'; quantity=1 }) } | ConvertTo-Json -Depth 5
    return Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/api/v1/orders" -WebSession $session -ContentType 'application/json' -Headers $headers -Body $body
}

try {
    Start-FoodFlow
    $session = Sign-In
    $beforeRestart = New-TestOrder $session
    Stop-FoodFlow

    if (-not (Test-Path -LiteralPath $databaseFile)) { throw "Persistent database file was not created at $databaseFile" }
    Copy-Item -LiteralPath $databaseFile -Destination $snapshotFile

    Start-FoodFlow
    $session = Sign-In
    $orders = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/orders" -WebSession $session
    if (-not ($orders | Where-Object id -eq $beforeRestart.id)) { throw 'Order was missing after a service restart.' }
    $afterSnapshot = New-TestOrder $session
    Stop-FoodFlow

    Copy-Item -LiteralPath $snapshotFile -Destination $databaseFile -Force
    Start-FoodFlow
    $session = Sign-In
    $restoredOrders = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/orders" -WebSession $session
    if (-not ($restoredOrders | Where-Object id -eq $beforeRestart.id)) { throw 'Snapshot restore lost the order captured in the backup.' }
    if ($restoredOrders | Where-Object id -eq $afterSnapshot.id) { throw 'Snapshot restore retained an order created after the backup.' }
    Write-Output "PASS: service restart preserved order $($beforeRestart.id); snapshot restore removed later order $($afterSnapshot.id)."
    Write-Output "Temporary database, snapshot, and logs: $runRoot"
}
finally {
    Stop-FoodFlow
    if ($null -eq $oldDbUrl) { Remove-Item Env:\DB_URL -ErrorAction SilentlyContinue } else { $env:DB_URL = $oldDbUrl }
    if ($null -eq $oldDemoPassword) { Remove-Item Env:\DEMO_PASSWORD -ErrorAction SilentlyContinue } else { $env:DEMO_PASSWORD = $oldDemoPassword }
}

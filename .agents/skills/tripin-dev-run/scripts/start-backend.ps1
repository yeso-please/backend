<#
.SYNOPSIS
  TriPin Spring backend를 dev-rds 또는 local profile로 점검하고 실행한다.

.DESCRIPTION
  Spring은 .env를 읽지 않으므로 backend/.env에서 허용 목록의 키만 이 프로세스 환경으로 읽어 온다.
  이미 설정된 프로세스 환경변수가 .env보다 우선한다. 비밀값은 출력하지 않는다.
  dev-rds는 공용 개발 RDS 대상(tripin_dev, schema app, TLS verify-full, CA 파일)을 검사한 뒤 실행하고,
  local은 DB 관련 변수를 비운 뒤 compose.yaml의 PostgreSQL을 띄워 실행한다.
  Windows PowerShell 5.1과 PowerShell 7 모두에서 동작한다.

.EXAMPLE
  pwsh -File .agents/skills/tripin-dev-run/scripts/start-backend.ps1 -SpringProfile dev-rds -CheckOnly
  pwsh -File .agents/skills/tripin-dev-run/scripts/start-backend.ps1 -SpringProfile dev-rds -RdsCaPath "$env:USERPROFILE\.aws\rds\global-bundle.pem"
  pwsh -File .agents/skills/tripin-dev-run/scripts/start-backend.ps1 -SpringProfile local
#>
[CmdletBinding()]
param(
    [ValidateSet('dev-rds', 'local')]
    [string]$SpringProfile = 'dev-rds',
    # RDS CA bundle 경로. 지정하면 DB_URL의 sslrootcert를 이 경로로 바꾼다(.env 파일은 수정하지 않는다).
    [string]$RdsCaPath = '',
    # 비우면 임베딩 서버 연결을 설정하지 않는다. 이미 EMBEDDING_SERVICE_BASE_URL이 있으면 그 값을 쓴다.
    [string]$EmbeddingBaseUrl = 'http://127.0.0.1:8000',
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
$backendDir = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
$gradlew = Join-Path $backendDir 'gradlew.bat'
$problems = New-Object System.Collections.Generic.List[string]

function Write-Step([string]$message) { Write-Host "[tripin-dev-run] $message" }

# ----- .env 읽기 (허용 키만, 프로세스 환경 우선) -----
$dbKeys = @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'FLYWAY_USER', 'FLYWAY_PASSWORD', 'DB_POOL_SIZE')
$appKeys = @('JWT_SECRET', 'COURSE_RESTAURANT_SELECTION_SECRET', 'KAKAO_REST_API_KEY',
    'EMBEDDING_SERVICE_BASE_URL', 'CORS_ALLOWED_ORIGINS', 'RDS_CA_PATH')
$allowed = if ($SpringProfile -eq 'dev-rds') { $dbKeys + $appKeys } else { $appKeys }

if ($SpringProfile -eq 'local') {
    # local profile은 DB_URL이 있으면 그 DB로 붙는다. RDS 값이 섞이지 않게 이 프로세스에서만 지운다.
    foreach ($key in $dbKeys) { Remove-Item "Env:$key" -ErrorAction SilentlyContinue }
}

$loaded = New-Object System.Collections.Generic.List[string]
$envFile = Join-Path $backendDir '.env'
if (Test-Path -LiteralPath $envFile) {
    foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
        $text = $line.Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }
        $separator = $text.IndexOf('=')
        if ($separator -le 0) { continue }
        $key = $text.Substring(0, $separator).Trim()
        if ($allowed -notcontains $key) { continue }
        if ([Environment]::GetEnvironmentVariable($key)) { continue }
        $value = $text.Substring($separator + 1).Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ($value) {
            [Environment]::SetEnvironmentVariable($key, $value, 'Process')
            $loaded.Add($key)
        }
    }
    Write-Step ".env에서 읽은 키: $(if ($loaded.Count) { $loaded -join ', ' } else { '(없음)' })"
} else {
    Write-Step '.env 파일이 없습니다. 현재 셸 환경변수만 사용합니다.'
}

# ----- profile별 점검 -----
if ($SpringProfile -eq 'dev-rds') {
    if (-not $RdsCaPath -and $env:RDS_CA_PATH) { $RdsCaPath = $env:RDS_CA_PATH }
    $dbUrl = $env:DB_URL
    if ($RdsCaPath) {
        if (Test-Path -LiteralPath $RdsCaPath -PathType Leaf) {
            $caUri = (Resolve-Path -LiteralPath $RdsCaPath).Path -replace '\\', '/'
            if ($dbUrl -match 'sslrootcert=[^&]*') {
                $dbUrl = [regex]::Replace($dbUrl, 'sslrootcert=[^&]*', { param($m) "sslrootcert=$caUri" })
            } elseif ($dbUrl) {
                $dbUrl = "$dbUrl&sslrootcert=$caUri"
            }
            $env:DB_URL = $dbUrl
        } else {
            $problems.Add("RdsCaPath/RDS_CA_PATH 파일이 없습니다: $RdsCaPath")
        }
    }

    if (-not $dbUrl) {
        $problems.Add('DB_URL이 없습니다. backend/.env 또는 환경변수에 개발 RDS JDBC URL을 넣으세요.')
    } elseif ($dbUrl -notmatch '^jdbc:postgresql://(?<host>[^:/?]+)(:\d+)?/(?<db>[^?]+)\?(?<query>.*)$') {
        $problems.Add('DB_URL 형식이 jdbc:postgresql://<host>:5432/<db>?... 가 아닙니다.')
    } else {
        $dbHost = $Matches['host']; $db = $Matches['db']; $query = $Matches['query']
        if ($dbHost -notlike '*.rds.amazonaws.com') { $problems.Add('DB_URL host가 RDS endpoint(*.rds.amazonaws.com)가 아닙니다.') }
        if ($dbHost -match 'prod') { $problems.Add('DB_URL host에 prod가 포함되어 있습니다. 운영 대상으로 보이므로 중단합니다.') }
        if ($db -ne 'tripin_dev') { $problems.Add("database가 tripin_dev가 아닙니다: $db") }
        if ($query -notmatch '(^|&)currentSchema=app(&|$)') { $problems.Add('DB_URL에 currentSchema=app이 없습니다.') }
        if ($query -notmatch '(^|&)sslmode=verify-full(&|$)') { $problems.Add('DB_URL의 sslmode가 verify-full이 아닙니다.') }
        if ($query -match '(^|&)sslrootcert=(?<ca>[^&]+)') {
            $caPath = [Uri]::UnescapeDataString($Matches['ca'])
            if (-not (Test-Path -LiteralPath $caPath -PathType Leaf)) {
                $problems.Add("RDS CA 파일이 이 컴퓨터에 없습니다: $caPath (runbook 9.2로 받은 뒤 -RdsCaPath로 지정)")
            }
        } else {
            $problems.Add('DB_URL에 sslrootcert(CA bundle 경로)가 없습니다.')
        }
        if (-not $problems.Count) { Write-Step "DB 대상: <rds-endpoint>/$db (schema app, verify-full, CA 확인됨)" }
    }

    foreach ($key in @('DB_PASSWORD', 'FLYWAY_PASSWORD')) {
        if (-not [Environment]::GetEnvironmentVariable($key)) { $problems.Add("$key 가 없습니다.") }
    }
    if (-not $env:DB_USERNAME) { Write-Step 'DB_USERNAME 없음 → application-dev-rds.yml 기본값 tripin_app 사용' }
    if (-not $env:FLYWAY_USER) { Write-Step 'FLYWAY_USER 없음 → application-dev-rds.yml 기본값 tripin_migrator 사용' }
    if (-not $env:JWT_SECRET) {
        $problems.Add('JWT_SECRET이 없습니다. dev-rds에는 기본값이 없어 기동이 막힙니다.')
    } elseif ([Text.Encoding]::UTF8.GetByteCount($env:JWT_SECRET) -lt 32) {
        $problems.Add('JWT_SECRET이 32바이트보다 짧습니다.')
    }
} else {
    $secretYaml = Join-Path $backendDir 'config\application-secret.yaml'
    if ((Test-Path -LiteralPath $secretYaml) -and
        (Get-Content -LiteralPath $secretYaml -Encoding UTF8 | Where-Object { $_ -match '^\s*datasource\s*:' })) {
        $problems.Add('config/application-secret.yaml에 spring.datasource가 활성화되어 있어 local profile이 그 DB로 붙습니다. 주석 처리 후 다시 실행하세요.')
    }
    $null = & docker info --format '{{.ServerVersion}}' 2>$null
    if ($LASTEXITCODE -ne 0) { $problems.Add('Docker daemon에 연결할 수 없습니다. Docker Desktop을 켜세요.') }
}

# ----- 공통 점검: 포트, JDK 21 -----
$listener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener) {
    $owner = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
    $problems.Add("8080 포트를 이미 사용 중입니다(PID $($listener.OwningProcess) $($owner.ProcessName)). 이미 떠 있는 backend인지 확인하세요.")
}

Push-Location $backendDir
try {
    $toolchains = (& $gradlew -q javaToolchains 2>&1 | Out-String)
    if ($toolchains -notmatch 'Language Version:\s+21\b') {
        $problems.Add('Gradle이 JDK 21을 찾지 못했습니다(build.gradle toolchain 21). JDK 21을 설치하세요. 예: winget install EclipseAdoptium.Temurin.21.JDK')
    }
} finally {
    Pop-Location
}

if ($problems.Count) {
    Write-Step '실행할 수 없습니다:'
    foreach ($problem in $problems) { Write-Host "  - $problem" }
    exit 1
}
Write-Step "점검 통과 (profile=$SpringProfile)"
if ($CheckOnly) { exit 0 }

# ----- 실행 -----
if ($SpringProfile -eq 'local') {
    Push-Location $backendDir
    try {
        & docker compose up -d --wait postgres
        if ($LASTEXITCODE -ne 0) { Write-Step 'docker compose로 PostgreSQL을 시작하지 못했습니다.'; exit 1 }
    } finally {
        Pop-Location
    }
}

if (-not $env:EMBEDDING_SERVICE_BASE_URL -and $EmbeddingBaseUrl) { $env:EMBEDDING_SERVICE_BASE_URL = $EmbeddingBaseUrl }
$env:SPRING_PROFILES_ACTIVE = $SpringProfile
Write-Step "bootRun 시작 (profile=$SpringProfile, embedding=$(if ($env:EMBEDDING_SERVICE_BASE_URL) { $env:EMBEDDING_SERVICE_BASE_URL } else { '없음' }))"
Write-Step "로그에서 'Started BackendApplication'을 확인하세요. BUILD SUCCESSFUL만으로는 기동 성공이 아닙니다."

Push-Location $backendDir
try {
    & $gradlew bootRun
    exit $LASTEXITCODE
} finally {
    Pop-Location
}

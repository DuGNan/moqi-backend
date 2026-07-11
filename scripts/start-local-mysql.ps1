$ErrorActionPreference = "Stop"

$mysqlRoot = "E:\middleware\mysql-8.4.9"
$myIni = Join-Path $mysqlRoot "my.ini"
$mysqlAdmin = Join-Path $mysqlRoot "bin\mysqladmin.exe"
$mysqld = Join-Path $mysqlRoot "bin\mysqld.exe"

if (!(Test-Path $mysqld)) {
    throw "MySQL binary not found: $mysqld"
}

$listener = Get-NetTCPConnection -LocalPort 3306 -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    Write-Output "MySQL is already running on 127.0.0.1:3306"
    exit 0
}

Start-Process -FilePath $mysqld -ArgumentList "--defaults-file=$myIni" -WindowStyle Hidden | Out-Null
Start-Sleep -Seconds 5

& $mysqlAdmin --protocol=tcp -h 127.0.0.1 -P 3306 -u root ping

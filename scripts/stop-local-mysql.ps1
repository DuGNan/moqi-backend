$ErrorActionPreference = "Stop"

$mysqlRoot = "E:\middleware\mysql-8.4.9"
$mysqlAdmin = Join-Path $mysqlRoot "bin\mysqladmin.exe"

try {
    & $mysqlAdmin --protocol=tcp -h 127.0.0.1 -P 3306 -u root shutdown
    Write-Output "MySQL stopped."
} catch {
    Write-Output "MySQL is not running or could not be stopped with mysqladmin."
    exit 1
}

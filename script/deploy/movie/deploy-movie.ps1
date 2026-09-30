# ==============================================================================
# 电影服务 (movie-server) 一键本地编译打包并部署到测试环境 (pro143 / movie-test)
# ==============================================================================
param (
    [string]$RemoteHost = "movie-test"
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Get-Item $PSScriptRoot).Parent.Parent.Parent.FullName
Set-Location $RepoRoot

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "1. 本地 Maven 编译打包 aifuli-module-movie-server ..." -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
mvn clean package -DskipTests -pl aifuli-module-movie-server -am
if ($LASTEXITCODE -ne 0) {
    Write-Error "Maven 构建失败，请检查编译错误"
    exit 1
}

$JarPath = Join-Path $RepoRoot "aifuli-module-movie-server\target\aifuli-module-movie-server.jar"
if (-not (Test-Path $JarPath)) {
    Write-Error "找不到构建产物: $JarPath"
    exit 1
}

Write-Host "`n==========================================================" -ForegroundColor Cyan
Write-Host "2. 备份远程当前运行的 Jar 包并上传新版本 ..." -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
$Timestamp = Get-Date -Format "yyyyMMddHHmmss"
ssh $RemoteHost "mkdir -p /data/supply-movie/backup; if [ -f /data/supply-movie/app/movie-server.jar ]; then cp -f /data/supply-movie/app/movie-server.jar /data/supply-movie/backup/movie-server-$Timestamp.jar; fi"
scp $JarPath "$RemoteHost`:/data/supply-movie/app/movie-server.jar"
if ($LASTEXITCODE -ne 0) {
    Write-Error "SCP 上传失败"
    exit 1
}

Write-Host "`n==========================================================" -ForegroundColor Cyan
Write-Host "3. 重启远程 movie-server 服务并验证状态 ..." -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
ssh $RemoteHost "/data/supply-movie/bin/restart.sh"
Start-Sleep -Seconds 5
ssh $RemoteHost "/data/supply-movie/bin/status.sh"

Write-Host "`n部署成功！日志查看命令: ssh $RemoteHost /data/supply-movie/bin/logs.sh" -ForegroundColor Green

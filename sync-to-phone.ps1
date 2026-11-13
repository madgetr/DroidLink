$LocalDir = "C:\Users\trevo\bot"
$TarFile = "$env:TEMP\bot_sync.tar.gz"
$RemoteHost = "termux-phone"
$RemotePath = "/data/data/com.termux/files/home/bot"

Set-Location $LocalDir

Write-Host "Packaging files..." -ForegroundColor Cyan

# Remove old temp tar if it exists
if (Test-Path $TarFile) { Remove-Item $TarFile -Force }

# Use tar to create a clean archive with forward-slash paths
tar --exclude='.git' --exclude='venv' --exclude='__pycache__' --exclude='.vscode' --exclude='sync-to-phone.ps1' --exclude='agent' -czf $TarFile .

Write-Host "Uploading archive to phone..." -ForegroundColor Cyan
scp -q $TarFile "$RemoteHost`:~/bot_sync.tar.gz"

Write-Host "Wiping remote folder and extracting..." -ForegroundColor Cyan
ssh $RemoteHost "rm -rf $RemotePath && mkdir -p $RemotePath && tar -xzf ~/bot_sync.tar.gz -C $RemotePath && rm ~/bot_sync.tar.gz && chmod +x $RemotePath/*.sh"
$SyncExit = $LASTEXITCODE

if ($SyncExit -eq 0) {
    Write-Host "Restarting API..." -ForegroundColor Cyan
    ssh $RemoteHost "$RemotePath/stop.sh; $RemotePath/start.sh"
}

# Clean up local temp file
if (Test-Path $TarFile) { Remove-Item $TarFile -Force }

if ($SyncExit -eq 0) {
    Write-Host "Sync completed successfully!" -ForegroundColor Green
} else {
    Write-Host "Sync failed with exit code $SyncExit" -ForegroundColor Red
}
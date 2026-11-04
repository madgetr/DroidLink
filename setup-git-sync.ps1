$LocalDir = "C:\Users\trevo\bot"
$HostName = "192.168.2.250"
$Port = 8022
$User = "u0_a124"
$KeyPath = "$HOME\.ssh\id_rsa"
$RemoteGitPath = "/data/data/com.termux/files/home/bot.git"
$RemoteWorkTree = "/data/data/com.termux/files/home/bot"

Write-Host "=== 1. Setting up local Git repository ===" -ForegroundColor Cyan
Set-Location $LocalDir
if (!(Test-Path ".git")) {
    git init
    git add -A
    git commit -m "Initial commit"
    Write-Host "Local repository initialized and committed." -ForegroundColor Green
} else {
    Write-Host "Local Git repository already exists." -ForegroundColor Yellow
}

Write-Host "=== 2. Configuring bare Git repo and post-receive hook on Termux ===" -ForegroundColor Cyan
$remoteSetupCommands = @"
git init --bare $RemoteGitPath
mkdir -p $RemoteGitPath/hooks
cat << 'EOF' > $RemoteGitPath/hooks/post-receive
#!/bin/bash
git --work-tree=$RemoteWorkTree --git-dir=$RemoteGitPath checkout -f
EOF
chmod +x $RemoteGitPath/hooks/post-receive
echo "Remote bare repository and post-receive hook configured successfully."
"@

ssh -p $Port -i $KeyPath "$User@$HostName" $remoteSetupCommands

Write-Host "=== 3. Linking local repo to phone remote ===" -ForegroundColor Cyan
$existingRemote = git remote get-url phone 2>&1
if ($LASTEXITCODE -ne 0) {
    git remote add phone "ssh://$User@$HostName`:$Port$RemoteGitPath"
    Write-Host "Added 'phone' remote." -ForegroundColor Green
} else {
    git remote set-url phone "ssh://$User@$HostName`:$Port$RemoteGitPath"
    Write-Host "Updated 'phone' remote URL." -ForegroundColor Green
}

Write-Host "=== 4. Pushing code to phone ===" -ForegroundColor Cyan
git push -u phone master

Write-Host "Setup complete! From now on, just run 'git push phone master' to deploy changes." -ForegroundColor Green
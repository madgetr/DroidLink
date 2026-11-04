$HostName = "192.168.2.250"
$Port = 8022
$User = "u0_a124"
$KeyPath = "$HOME\.ssh\id_rsa"
$RemoteGitPath = "/data/data/com.termux/files/home/bot.git"
$RemoteWorkTree = "/data/data/com.termux/files/home/bot"

Write-Host "Updating Termux packages and installing Git..." -ForegroundColor Cyan

# Run commands cleanly over SSH without complex heredoc parsing bugs
ssh -p $Port -i $KeyPath "$User@$HostName" "pkg update -y && pkg install -y git"

Write-Host "Configuring remote bare Git repo and auto-checkout hook..." -ForegroundColor Cyan

$remoteSetup = @"
rm -rf $RemoteGitPath
git init --bare $RemoteGitPath
mkdir -p $RemoteGitPath/hooks
echo '#!/bin/bash' > $RemoteGitPath/hooks/post-receive
echo 'git --work-tree=$RemoteWorkTree --git-dir=$RemoteGitPath checkout -f' >> $RemoteGitPath/hooks/post-receive
chmod +x $RemoteGitPath/hooks/post-receive
echo 'Bare repo and post-receive hook ready.'
"@

ssh -p $Port -i $KeyPath "$User@$HostName" $remoteSetup

Write-Host "Pushing code to phone..." -ForegroundColor Cyan
Set-Location "C:\Users\trevo\bot"
git push -u phone master --force
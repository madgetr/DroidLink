$HostName = "192.168.2.250"
$Port = 8022
$User = "u0_a124"
$KeyPath = "$HOME\.ssh\id_rsa"
$RemoteGitPath = "/data/data/com.termux/files/home/bot.git"
$RemoteWorkTree = "/data/data/com.termux/files/home/bot"

Write-Host "Cleaning up incorrect directories and setting up correct paths..." -ForegroundColor Cyan

$remoteCommands = @"
rm -rf /data/data/com.termux/files/home/bo*
mkdir -p $RemoteGitPath $RemoteWorkTree
git init --bare $RemoteGitPath
mkdir -p $RemoteGitPath/hooks
echo '#!/bin/bash' > $RemoteGitPath/hooks/post-receive
echo 'git --work-tree=$RemoteWorkTree --git-dir=$RemoteGitPath checkout -f' >> $RemoteGitPath/hooks/post-receive
chmod +x $RemoteGitPath/hooks/post-receive
echo 'Successfully initialized bare repo at $RemoteGitPath'
"@

ssh -p $Port -i $KeyPath "$User@$HostName" $remoteCommands

Write-Host "Updating local Git remote URL..." -ForegroundColor Cyan
Set-Location "C:\Users\trevo\bot"
git remote set-url phone "ssh://$User@$HostName`:$Port$RemoteGitPath"

Write-Host "Pushing code to phone..." -ForegroundColor Cyan
git push -u phone master --force
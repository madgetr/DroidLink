$HostName = "192.168.2.250"
$Port = 8022
$User = "u0_a124"
$KeyPath = "$HOME\.ssh\id_rsa"

Write-Host "Re-initializing bare Git repo in Termux home..." -ForegroundColor Cyan

$remoteFix = @"
rm -rf ~/bot.git ~/bot
git init --bare ~/bot.git
mkdir -p ~/bot.git/hooks ~/bot
echo '#!/bin/bash' > ~/bot.git/hooks/post-receive
echo 'git --work-tree=$HOME/bot --git-dir=$HOME/bot.git checkout -f' >> ~/bot.git/hooks/post-receive
chmod +x ~/bot.git/hooks/post-receive
echo 'Remote bare repo and hook re-initialized successfully.'
"@

ssh -p $Port -i $KeyPath "$User@$HostName" $remoteFix

Write-Host "Updating local Git remote URL..." -ForegroundColor Cyan
Set-Location "C:\Users\trevo\bot"
git remote set-url phone "ssh://$User@$HostName`:$Port/~/bot.git"

Write-Host "Pushing code to phone..." -ForegroundColor Cyan
git push -u phone master --force
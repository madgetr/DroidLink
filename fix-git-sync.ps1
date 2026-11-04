$HostName = "192.168.2.250"
$Port = 8022
$User = "u0_a124"
$KeyPath = "$HOME\.ssh\id_rsa"
$RemoteGitPath = "/data/data/com.termux/files/home/bot.git"
$RemoteWorkTree = "/data/data/com.termux/files/home/bot"

Write-Host "Installing Git on Termux and configuring remote repo..." -ForegroundColor Cyan

$remoteCommands = @"
pkg install -y git
rm -rf $RemoteGitPath
git init --bare $RemoteGitPath
mkdir -p $RemoteGitPath/hooks
cat << 'EOF' > $RemoteGitPath/hooks/post-receive
#!/bin/bash
git --work-tree=$RemoteWorkTree --git-dir=$RemoteGitPath checkout -f
EOF
chmod +x $RemoteGitPath/hooks/post-receive
echo "Remote repository and hook configured successfully."
"@

ssh -p $Port -i $KeyPath "$User@$HostName" $remoteCommands

Write-Host "Pushing code to phone..." -ForegroundColor Cyan
Set-Location "C:\Users\trevo\bot"
git push -u phone master --force
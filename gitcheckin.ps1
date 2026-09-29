param(
    [Parameter(Position = 0)]
    [string]$comment
)

if ([string]::IsNullOrWhiteSpace($comment)) {
    Write-Host "Usage: gitcheckin <comment>"
    exit 1
}

git checkout parent-pin
git status
git add *
git commit -m $comment
git status

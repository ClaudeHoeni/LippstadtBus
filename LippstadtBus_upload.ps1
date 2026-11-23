Set-Location "$HOME\Desktop\LippstadtBus"

$status = git status --porcelain
if (-not $status) {
    Write-Host "Keine neuen Aenderungen gefunden." -ForegroundColor Yellow
    Read-Host "Enter zum Schliessen"
    exit 0
}

$zeit = Get-Date -Format "yyyy-MM-dd HH:mm"
git add -A
git commit -m "Update LippstadtBus ($zeit)"
git push origin main

if ($LASTEXITCODE -eq 0) {
    Write-Host "Upload erfolgreich! GitHub Actions baut jetzt ABFAHRTEN.apk." -ForegroundColor Green
} else {
    Write-Host "Fehler beim Upload!" -ForegroundColor Red
}

Read-Host "Enter zum Schliessen"
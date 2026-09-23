<#
.SINOSSI
    Costruisce il pacchetto del programma per il computer: pc/dist/pactum-computer.zip.

    Fa `dotnet publish` self-contained per win-x64 A CARTELLA (non single-file): niente
    eseguibile che si scompatta da solo. Lo zip contiene una cartella `Pactum\` con
    `Pactum.exe`, le dll del runtime .NET e la cartella `ui`. Il programma gira da lì:
    non si copia da nessuna parte, l'avvio al login punta alla cartella scompattata.

    Usa lo SDK .NET 8 scompattato in %LOCALAPPDATA%\Microsoft\dotnet-sdk-8 (o quello nel PATH).

.ESEMPIO
    powershell -ExecutionPolicy Bypass -File pc\prove\crea-pacchetto.ps1
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'

$radicePc = Split-Path -Parent $PSScriptRoot            # ...\pc
$progetto = Join-Path $radicePc 'Pactum\Pactum.csproj'
$dist = Join-Path $radicePc 'dist'
$staging = Join-Path $dist 'Pactum'                     # diventa la cartella dentro lo zip
$zip = Join-Path $dist 'pactum-computer.zip'

$dotnet = Join-Path $env:LOCALAPPDATA 'Microsoft\dotnet-sdk-8\dotnet.exe'
if (-not (Test-Path $dotnet)) {
    $c = Get-Command dotnet -ErrorAction SilentlyContinue
    if (-not $c) { throw "Non trovo dotnet. Scompatta lo SDK .NET 8 in $env:LOCALAPPDATA\Microsoft\dotnet-sdk-8." }
    $dotnet = $c.Source
}

if (-not (Test-Path (Join-Path $radicePc 'ui\index.html'))) {
    Write-Warning "pc\ui\index.html non c'è: nel pacchetto va l'interfaccia segnaposto."
}

Write-Host "Pulizia..." -ForegroundColor Cyan
Remove-Item -Recurse -Force $staging -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $dist | Out-Null

$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$env:DOTNET_NOLOGO = '1'

Write-Host "Pubblicazione (self-contained, a cartella)..." -ForegroundColor Cyan
& $dotnet publish $progetto -c Release -r win-x64 `
    "-p:PubblicazionePactum=true" `
    -o $staging
if ($LASTEXITCODE -ne 0) { throw "publish fallito (codice $LASTEXITCODE)" }

$exe = Join-Path $staging 'Pactum.exe'
if (-not (Test-Path $exe)) { throw "Pactum.exe non prodotto." }
if (-not (Test-Path (Join-Path $staging 'ui\index.html'))) { throw "La cartella ui non è finita accanto all'exe." }
# Controllo del pacchetto normale: self-contained = il runtime .NET è accanto all'exe (niente single-file).
if (-not (Test-Path (Join-Path $staging 'coreclr.dll'))) { throw "Manca coreclr.dll: la pubblicazione non è self-contained a cartella." }

Write-Host "Creo lo zip..." -ForegroundColor Cyan
Remove-Item $zip -ErrorAction SilentlyContinue
# -Path sulla cartella (senza \*): lo zip contiene la cartella Pactum\ con tutto dentro.
Compress-Archive -Path $staging -DestinationPath $zip -CompressionLevel Optimal

$mb = [math]::Round((Get-Item $zip).Length / 1MB, 1)
$exeMb = [math]::Round((Get-Item $exe).Length / 1MB, 2)
$nFile = (Get-ChildItem $staging -Recurse -File).Count
Write-Host ""
Write-Host "Fatto." -ForegroundColor Green
Write-Host "  zip:  $zip  ($mb MB)"
Write-Host "  exe:  $exeMb MB, $nFile file nella cartella Pactum\"
Write-Host ""
Write-Host "Prova rapida (avvia e chiudi dopo 5s, cartella dati temporanea, niente avvio al login):" -ForegroundColor DarkGray
Write-Host "  & '$exe' --no-installa --dati `"`$env:TEMP\pactum-prova`" --esci-dopo 5"

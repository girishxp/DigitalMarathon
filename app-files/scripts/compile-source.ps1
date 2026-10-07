# Compile the source checkout only. This does not launch or replace an app.
$ErrorActionPreference = 'Stop'
$CompileRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$CompileVersion = (Get-Content -LiteralPath (Join-Path $CompileRoot 'VERSION') -Raw).Trim()
$HookVersion = '2.2.2'
$HookSha256 = '2c7904423bc680af02d9ea9557ae233c35199e302d072773a9d0304b568acd41'
$HookJar = Join-Path $CompileRoot "lib\jnativehook-$HookVersion.jar"
$HookUrl = "https://repo.maven.apache.org/maven2/com/github/kwhat/jnativehook/$HookVersion/jnativehook-$HookVersion.jar"
$BuildWork = $null
$HookPart = $null
$LocationPushed = $false
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

try {
    if ($CompileVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'VERSION must contain a semantic version.' }
    $SourceDirectory = Join-Path $CompileRoot 'src'
    $Resources = Join-Path $CompileRoot 'resources'
    $Guide = Join-Path $CompileRoot 'docs\Digital-Marathon-Product-Guide.pdf'
    if (-not (Test-Path -LiteralPath $SourceDirectory -PathType Container)) { throw 'The src directory is missing.' }
    if (-not (Test-Path -LiteralPath $Resources -PathType Container)) { throw 'The resources directory is missing.' }
    if (-not (Test-Path -LiteralPath $Guide -PathType Leaf)) { throw 'The product-guide PDF is missing.' }

    if ($env:JAVA_HOME) {
        $CompileJavac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
        $CompileJar = Join-Path $env:JAVA_HOME 'bin\jar.exe'
    } else {
        $JavacCommand = Get-Command javac.exe -CommandType Application -ErrorAction SilentlyContinue
        $JarCommand = Get-Command jar.exe -CommandType Application -ErrorAction SilentlyContinue
        if (-not $JavacCommand -or -not $JarCommand) { throw 'Install a Java 21 JDK and set JAVA_HOME or PATH.' }
        $CompileJavac = $JavacCommand.Source
        $CompileJar = $JarCommand.Source
    }
    if (-not (Test-Path -LiteralPath $CompileJavac -PathType Leaf) -or -not (Test-Path -LiteralPath $CompileJar -PathType Leaf)) {
        throw 'JAVA_HOME must point to a Java 21 JDK containing javac.exe and jar.exe.'
    }
    $JavacVersion = (& $CompileJavac -version 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $JavacVersion -notmatch '^javac\s+21(\.|\s|$)') {
        throw 'Java 21 is required because the Windows code uses its preview API.'
    }
    $JarVersion = (& $CompileJar --version 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $JarVersion -notmatch '^jar\s+21(\.|\s|$)') {
        throw 'The jar and javac tools must both come from Java 21.'
    }

    $Library = Join-Path $CompileRoot 'lib'
    $BuildDirectory = Join-Path $CompileRoot 'build'
    $AppDirectory = Join-Path $CompileRoot 'app'
    New-Item -ItemType Directory -Force -Path $Library, $BuildDirectory, $AppDirectory | Out-Null
    if (-not (Test-Path -LiteralPath $HookJar -PathType Leaf)) {
        $HookPart = Join-Path $Library ('.jnativehook-download-' + [Guid]::NewGuid().ToString() + '.tmp')
        Write-Host "Downloading the pinned JNativeHook $HookVersion dependency from Maven Central..."
        Invoke-WebRequest -UseBasicParsing -Uri $HookUrl -OutFile $HookPart -TimeoutSec 120
        if ((Get-FileHash -LiteralPath $HookPart -Algorithm SHA256).Hash.ToLowerInvariant() -ne $HookSha256) {
            throw 'The dependency SHA-256 does not match the pinned release.'
        }
        Move-Item -LiteralPath $HookPart -Destination $HookJar
        $HookPart = $null
    }
    if ((Get-FileHash -LiteralPath $HookJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $HookSha256) {
        throw 'The existing JNativeHook dependency does not match the pinned SHA-256.'
    }

    $BuildWork = Join-Path $BuildDirectory ('source-compile-' + [Guid]::NewGuid().ToString())
    $Classes = Join-Path $BuildWork 'classes'
    $Stage = Join-Path $BuildWork 'stage'
    New-Item -ItemType Directory -Force -Path $Classes, $Stage | Out-Null
    $Sources = @(Get-ChildItem -LiteralPath $SourceDirectory -Filter '*.java' -File -Recurse | Sort-Object FullName)
    if ($Sources.Count -eq 0) { throw 'No Java sources were found.' }
    $SourceArguments = @($Sources | ForEach-Object {
        $Relative = $_.FullName.Substring($CompileRoot.Length + 1).Replace('\', '/')
        '"' + $Relative.Replace('"', '\"') + '"'
    })
    $SourceList = Join-Path $BuildWork 'sources.txt'
    [IO.File]::WriteAllLines($SourceList, [string[]]$SourceArguments, $Utf8NoBom)
    Push-Location -LiteralPath $CompileRoot
    $LocationPushed = $true
    Write-Host "Compiling Digital Marathon $CompileVersion with Java 21..."
    & $CompileJavac --release 21 --enable-preview -encoding UTF-8 -cp $HookJar -d $Classes "@$SourceList"
    if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE." }
    Get-ChildItem -LiteralPath $Classes -Force | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $Stage -Recurse -Force }
    Get-ChildItem -LiteralPath $Resources -Force | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $Stage -Recurse -Force }
    Copy-Item -LiteralPath $Guide -Destination (Join-Path $Stage 'Digital-Marathon-Product-Guide.pdf')
    $Manifest = Join-Path $BuildWork 'manifest.mf'
    $ManifestText = "Manifest-Version: 1.0`nMain-Class: com.inputactivitytracker.Main`nImplementation-Title: Digital Marathon`nImplementation-Version: $CompileVersion`n`n"
    [IO.File]::WriteAllText($Manifest, $ManifestText, $Utf8NoBom)
    $BuiltJar = Join-Path $BuildWork 'digital-marathon.jar'
    & $CompileJar --create --file $BuiltJar --manifest $Manifest -C $Stage '.'
    if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE." }
    $OutputJar = Join-Path $AppDirectory 'digital-marathon.jar'
    Move-Item -LiteralPath $BuiltJar -Destination $OutputJar -Force
    Write-Host "Built $OutputJar"
} finally {
    if ($LocationPushed) { Pop-Location }
    if ($HookPart -and (Test-Path -LiteralPath $HookPart)) { Remove-Item -LiteralPath $HookPart -Force }
    if ($BuildWork -and (Test-Path -LiteralPath $BuildWork)) { Remove-Item -LiteralPath $BuildWork -Recurse -Force }
}

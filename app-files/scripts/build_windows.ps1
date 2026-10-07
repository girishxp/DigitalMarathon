param(
    [ValidateSet("run", "clean")]
    [string]$Mode = "run"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Version = (Get-Content (Join-Path $Root "VERSION") -Raw).Trim()
$HookVersion = "2.2.2"
$HookJar = Join-Path $Root "lib\jnativehook-$HookVersion.jar"
$HookUrl = "https://repo.maven.apache.org/maven2/com/github/kwhat/jnativehook/$HookVersion/jnativehook-$HookVersion.jar"
$HookFallbackUrl = "https://downloads.sourceforge.net/project/jnativehook.mirror/$HookVersion/jnativehook-$HookVersion.jar"
$Cache = Join-Path $Root ".build-tools"
$InputDir = Join-Path $Root "build\package-input"
$Dest = Join-Path $Root "build\windows"
$AppDir = Join-Path $Dest "Digital Marathon"
$Exe = Join-Path $AppDir "Digital Marathon.exe"
$Log = Join-Path $Root "digital-marathon-startup.log"
$Stamp = Join-Path $Dest ".digital-marathon-version"

Start-Transcript -Path $Log -Force | Out-Null
try {
    if ($Mode -eq "clean") {
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $Dest, $InputDir
    }
    elseif ((Test-Path $Exe) -and (Test-Path $Stamp) -and ((Get-Content $Stamp -Raw).Trim() -eq $Version)) {
        Write-Host "Opening $Exe"
        Start-Process -FilePath $Exe
        return
    }
    elseif (Test-Path $AppDir) {
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $Dest
    }

    New-Item -ItemType Directory -Force -Path (Join-Path $Root "lib"), (Join-Path $Root "build"), $Cache | Out-Null

    if (-not (Test-Path $HookJar) -or (Get-Item $HookJar).Length -lt 600000) {
        Remove-Item -Force -ErrorAction SilentlyContinue $HookJar
        Write-Host "Downloading JNativeHook..."
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $HookUrl -OutFile $HookJar
        }
        catch {
            Remove-Item -Force -ErrorAction SilentlyContinue $HookJar
            Invoke-WebRequest -UseBasicParsing -Uri $HookFallbackUrl -OutFile $HookJar
        }
        if ((Get-Item $HookJar).Length -lt 600000) { throw "The JNativeHook download was incomplete." }
    }

    $JPackage = $null
    $SystemJPackage = Get-Command jpackage.exe -ErrorAction SilentlyContinue
    if ($SystemJPackage) {
        try {
            $JPackageVersionText = (& $SystemJPackage.Source --version 2>$null | Select-Object -First 1).ToString().Trim()
            $JPackageMajor = [int](($JPackageVersionText -split '\.')[0])
            if ($JPackageMajor -eq 21) { $JPackage = $SystemJPackage.Source }
        }
        catch {
            $JPackage = $null
        }
    }

    if (-not $JPackage) {
        $Architecture = if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64") { "aarch64" } else { "x64" }
        $JdkDir = Join-Path $Cache "jdk-21-windows-$Architecture"
        $Existing = Get-ChildItem -Path $JdkDir -Filter jpackage.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $Existing) {
            Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $JdkDir
            New-Item -ItemType Directory -Force -Path $JdkDir | Out-Null
            $Archive = Join-Path $Cache "jdk-21-windows-$Architecture.zip"
            $JdkUrl = "https://api.adoptium.net/v3/binary/latest/21/ga/windows/$Architecture/jdk/hotspot/normal/eclipse?project=jdk"
            Write-Host "Downloading a private Java 21 build tool..."
            Invoke-WebRequest -UseBasicParsing -Uri $JdkUrl -OutFile $Archive
            Expand-Archive -Path $Archive -DestinationPath $JdkDir -Force
            Remove-Item -Force $Archive
            $Existing = Get-ChildItem -Path $JdkDir -Filter jpackage.exe -Recurse | Select-Object -First 1
        }
        if ($Existing) { $JPackage = $Existing.FullName }
    }

    if (-not $JPackage -or -not (Test-Path $JPackage)) {
        throw "A Java 21 JDK with jpackage could not be prepared."
    }

    $AppJar = Join-Path $Root "app\digital-marathon.jar"
    if (-not (Test-Path $AppJar)) { throw "Missing application JAR: $AppJar" }

    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $InputDir
    New-Item -ItemType Directory -Force -Path $InputDir | Out-Null
    Copy-Item $AppJar (Join-Path $InputDir "digital-marathon.jar")
    Copy-Item $HookJar (Join-Path $InputDir "jnativehook-$HookVersion.jar")

    if (-not (Test-Path $Exe)) {
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $Dest
        New-Item -ItemType Directory -Force -Path $Dest | Out-Null
        Write-Host "Building Digital Marathon $Version for Windows..."
        & $JPackage `
            --type app-image `
            --name "Digital Marathon" `
            --dest $Dest `
            --input $InputDir `
            --main-jar "digital-marathon.jar" `
            --main-class "com.inputactivitytracker.Main" `
            --app-version $Version `
            --vendor "Girish Gupta" `
            --description "Live mouse distance and keyboard press counter" `
            --icon (Join-Path $Root "resources\app-icon.ico") `
            --java-options "-Dfile.encoding=UTF-8" `
            --java-options "--enable-preview" `
            --java-options "--enable-native-access=ALL-UNNAMED" `
            --java-options "--add-opens=java.desktop/java.awt=ALL-UNNAMED" `
            --java-options "--add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED"
        if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }
        Set-Content -Path $Stamp -Value $Version -Encoding ASCII
    }

    if (-not (Test-Path $Exe)) { throw "Built application executable was not found: $Exe" }
    Write-Host "Opening $Exe"
    Start-Process -FilePath $Exe
}
finally {
    Stop-Transcript | Out-Null
}

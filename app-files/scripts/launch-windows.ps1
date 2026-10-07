# Windows PowerShell 5.1 launcher. The release includes Java and the built app.
# This script is installed at app-files\scripts\launch-windows.ps1.
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$LogFile = $null
$Process = $null
$OutputStream = $null
$ErrorStream = $null

function Show-LaunchError([string]$Message) {
    try {
        Add-Type -AssemblyName System.Windows.Forms -ErrorAction Stop
        [System.Windows.Forms.MessageBox]::Show(
            $Message, 'Digital Marathon could not start',
            [System.Windows.Forms.MessageBoxButtons]::OK,
            [System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    }
    catch {
        # This fallback also works when Windows Forms is unavailable.
        try {
            $Shell = New-Object -ComObject WScript.Shell
            $Shell.Popup($Message, 0, 'Digital Marathon could not start', 16) | Out-Null
        }
        catch { }
    }
}

function Write-LaunchLog([string]$Message) {
    if ($LogFile) {
        [System.IO.File]::AppendAllText($LogFile,
            ('[{0}] {1}{2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'),
                $Message, [Environment]::NewLine),
            (New-Object System.Text.UTF8Encoding($false)))
    }
}

function ConvertTo-WindowsArgument([string]$Argument) {
    # ProcessStartInfo.Arguments uses Windows command-line quoting, not a shell.
    # Double backslashes before a quote, and at the end of a quoted argument.
    $Builder = New-Object System.Text.StringBuilder
    [void]$Builder.Append('"')
    $Backslashes = 0
    foreach ($Character in $Argument.ToCharArray()) {
        if ($Character -eq '\') {
            $Backslashes++
        }
        elseif ($Character -eq '"') {
            [void]$Builder.Append(('\' * (2 * $Backslashes + 1)))
            [void]$Builder.Append('"')
            $Backslashes = 0
        }
        else {
            [void]$Builder.Append(('\' * $Backslashes))
            [void]$Builder.Append($Character)
            $Backslashes = 0
        }
    }
    [void]$Builder.Append(('\' * (2 * $Backslashes)))
    [void]$Builder.Append('"')
    return $Builder.ToString()
}

try {
    $AppFiles = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
    $PackageRoot = [System.IO.Path]::GetFullPath((Join-Path $AppFiles '..'))
    $Java = Join-Path $AppFiles 'runtime\windows-x64\bin\javaw.exe'
    $AppJar = Join-Path $AppFiles 'app\digital-marathon.jar'
    $HookJar = Join-Path $AppFiles 'lib\jnativehook-2.2.2.jar'

    $UserLogRoot = [Environment]::GetFolderPath(
        [Environment+SpecialFolder]::LocalApplicationData)
    if ([string]::IsNullOrWhiteSpace($UserLogRoot)) {
        $UserLogRoot = [System.IO.Path]::GetTempPath()
    }
    $LogDirectory = Join-Path $UserLogRoot 'DigitalMarathon\logs'
    [System.IO.Directory]::CreateDirectory($LogDirectory) | Out-Null
    $LogPrefix = 'windows-{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss-fff'), $PID
    $LogFile = Join-Path $LogDirectory ($LogPrefix + '-startup.log')
    $OutputLog = Join-Path $LogDirectory ($LogPrefix + '-stdout.log')
    $ErrorLog = Join-Path $LogDirectory ($LogPrefix + '-stderr.log')
    Write-LaunchLog ('Package: ' + $PackageRoot)
    Write-LaunchLog ('Runtime: ' + $Java)

    foreach ($RequiredFile in @($Java, $AppJar, $HookJar)) {
        if (-not [System.IO.File]::Exists($RequiredFile)) {
            throw ('A required file is missing: ' + $RequiredFile +
                [Environment]::NewLine +
                'Extract the entire ZIP before launching, and keep all files together.')
        }
    }

    # Remove inherited Java injection settings in this launcher process only.
    # The bundled Java 21 and its preview/native options match the supplied JAR.
    foreach ($Variable in @('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS')) {
        [Environment]::SetEnvironmentVariable($Variable, $null, 'Process')
    }
    $JavaArguments = @(
        '-Dfile.encoding=UTF-8',
        '--enable-preview',
        '--enable-native-access=ALL-UNNAMED',
        '--add-opens=java.desktop/java.awt=ALL-UNNAMED',
        '--add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED',
        '-cp', ($AppJar + ';' + $HookJar),
        'com.inputactivitytracker.Main'
    )
    $StartInfo = New-Object System.Diagnostics.ProcessStartInfo
    $StartInfo.FileName = $Java
    $StartInfo.Arguments = (($JavaArguments | ForEach-Object {
        ConvertTo-WindowsArgument $_
    }) -join ' ')
    $StartInfo.WorkingDirectory = $PackageRoot
    $StartInfo.UseShellExecute = $false
    $StartInfo.CreateNoWindow = $true
    $StartInfo.RedirectStandardOutput = $true
    $StartInfo.RedirectStandardError = $true

    $OutputStream = [System.IO.File]::Open($OutputLog,
        [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::ReadWrite)
    $ErrorStream = [System.IO.File]::Open($ErrorLog,
        [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::ReadWrite)
    $Process = New-Object System.Diagnostics.Process
    $Process.StartInfo = $StartInfo
    if (-not $Process.Start()) { throw 'The bundled Java runtime could not start.' }
    Write-LaunchLog ('Started Java process ' + $Process.Id + '.')
    Write-LaunchLog ('Application errors: ' + $ErrorLog)

    # Drain both pipes concurrently so application output cannot block Java.
    # The helper stays hidden while the GUI is open and records startup failures.
    $OutputTask = $Process.StandardOutput.BaseStream.CopyToAsync($OutputStream)
    $ErrorTask = $Process.StandardError.BaseStream.CopyToAsync($ErrorStream)
    $Process.WaitForExit()
    $OutputTask.GetAwaiter().GetResult()
    $ErrorTask.GetAwaiter().GetResult()
    Write-LaunchLog ('Java exited with code ' + $Process.ExitCode + '.')
    if ($Process.ExitCode -ne 0) {
        throw ('Digital Marathon stopped with exit code ' + $Process.ExitCode + '.' +
            [Environment]::NewLine + 'Application details: ' + $ErrorLog)
    }
}
catch {
    $Failure = $_.Exception.Message
    try { Write-LaunchLog ('ERROR: ' + $Failure) } catch { }
    if ($LogFile) {
        $Failure += [Environment]::NewLine + [Environment]::NewLine +
            'Startup log: ' + $LogFile
    }
    Show-LaunchError $Failure
    exit 1
}
finally {
    if ($OutputStream) { $OutputStream.Dispose() }
    if ($ErrorStream) { $ErrorStream.Dispose() }
    if ($Process) { $Process.Dispose() }
}

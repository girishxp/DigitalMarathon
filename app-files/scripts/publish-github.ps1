# Owner-operated publisher. Uses installed Git/GitHub CLI; no credentials embedded.
# PowerShell 5.1+ and .NET are included on supported Windows computers.
$ErrorActionPreference = 'Stop'
$Version = '2.1.30'
$GitHubRepo = 'girishxp/DigitalMarathon'
# Explicit host keeps an unrelated enterprise login or GH_HOST out of this publisher.
$GitHubRepoTarget = "github.com/$GitHubRepo"
$Branch = 'main'
$Package = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$Parent = Split-Path $Package -Parent
$RepoDir = if ($env:DIGITAL_MARATHON_REPO) { $env:DIGITAL_MARATHON_REPO } else { Join-Path $HOME 'Developer\DigitalMarathon' }
$Source = if ($env:DIGITAL_MARATHON_SOURCE) { $env:DIGITAL_MARATHON_SOURCE } else { '' }
# A separate source snapshot is optional; the default comes from the release ZIP.
$ZipName = "digital-marathon-cross-platform-v$Version-click-to-launch.zip"
$ChecksumName = "SHA256SUMS-v$Version.txt"
$ZipPath = if ($env:DIGITAL_MARATHON_ZIP) { $env:DIGITAL_MARATHON_ZIP } else { Join-Path $Parent $ZipName }
$Checksum = if ($env:DIGITAL_MARATHON_CHECKSUM) { $env:DIGITAL_MARATHON_CHECKSUM } else { Join-Path $Parent $ChecksumName }
$ChecksumExplicit = [bool]$env:DIGITAL_MARATHON_CHECKSUM
$DryRun = $false
$Temp = $null
$Zip = $null
function Run-Tool([string]$Program, [string[]]$Arguments) {
    # Windows PowerShell can wrap ordinary native stderr as an ErrorRecord.
    # Treat the native exit code as the command result, including successful Git progress.
    $previous=$ErrorActionPreference; $ErrorActionPreference='Continue'
    try { $result = & $Program @Arguments 2>&1; $code=$LASTEXITCODE } finally { $ErrorActionPreference=$previous }
    if ($code -ne 0) { throw "$Program failed during publishing/preflight. No unverified draft will be published. Check its authentication, permissions and network connection." }
    return ($result | Out-String).Trim()
}
function Optional-Git([string[]]$Arguments) {
    $previous=$ErrorActionPreference; $ErrorActionPreference='Continue'
    try { $result=& git @Arguments 2>$null; if ($LASTEXITCODE -eq 0) { return ($result | Out-String).Trim() }; return '' } finally { $ErrorActionPreference=$previous }
}
function Allowed([string]$Name) {
    if ($Name -match '(^|/)(runtime|build|qa|logs|\.git|credentials)(/|$)|(^|/)\.env$|activity-(history|buckets)|\.(jar|class|dll|dylib|exe|pem|key)$') { return $false }
    if ($Name -match '^(README\.md|QUICK_START\.txt|CHANGELOG\.md|ANALYTICS\.md|PUBLISHING\.md|THIRD_PARTY_NOTICES\.txt|LICENSE(\.txt)?|\.gitignore|\.gitattributes|Publish Digital Marathon\.command|Publish Digital Marathon - Windows\.bat|Start Digital Marathon - Windows\.bat|Start Screen Recorder - Linux\.sh|REPAIR_MAC_PERMISSIONS\.command)$') { return $true }
    if ($Name -match '^app-files/(VERSION|README\.md|pom\.xml|[^/]+\.(command|bat|sh))$|^app-files/src/.+\.java$|^app-files/native/.+\.(c|m|h)$|^app-files/docs/.+\.(md|txt|pdf)$|^app-files/licenses/.+\.txt$|^app-files/scripts/.+\.(sh|ps1|rules)$') { return $true }
    return $Name -match '^app-files/resources/(app-icon(-mac)?\.png|header-icon\.png|app-icon\.(ico|icns)|update-feed\.json|analytics-config\.json)$'
}
function Private-ZipPath([string]$Name) {
    return $Name -match '(^|/)(\.git|\.gitmodules|\.env[^/]*|\.digital_marathon|qa|test-user|temporary-history|logs|history|userdata|recordings|credentials[^/]*|secrets[^/]*|activity-buckets\.dat|activity\.db|activity-history[^/]*|analytics-settings\.json|update-settings\.json|update-preferences\.json|update-session\.json|\.update-session-[^/]*|preferences\.json)(/|$)|\.(log|csv|jpg|jpeg|pem|key|p12|pfx)$'
}
function Entry-Bytes([IO.Compression.ZipArchive]$Archive, [string]$Name) {
    $entry = $Archive.GetEntry($Name)
    if ($null -eq $entry) { throw "Verified package entry is missing: $Name" }
    $stream = $entry.Open(); $memory = New-Object IO.MemoryStream
    try { $stream.CopyTo($memory); return ,$memory.ToArray() } finally { $stream.Dispose(); $memory.Dispose() }
}
function Bytes-Hash([byte[]]$Bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() } finally { $sha.Dispose() }
}
try {
    for ($i = 0; $i -lt $args.Count; $i++) {
        switch ($args[$i]) {
            '--dry-run' { $DryRun = $true }
            '--help' { Write-Host 'Usage: Publish Digital Marathon - Windows.bat [--dry-run] [--source PATH] [--zip PATH] [--checksum PATH] [--repo-dir PATH]'; exit 0 }
            { $_ -in '--source','--zip','--checksum','--repo-dir' } {
                $option = $args[$i]; $i++
                if ($i -ge $args.Count) { throw "Missing value for $option" }
                if ([string]::IsNullOrWhiteSpace($args[$i])) { throw "Empty value for $option" }
                switch ($option) { '--source' { $Source=$args[$i] } '--zip' { $ZipPath=$args[$i] } '--checksum' { $Checksum=$args[$i]; $ChecksumExplicit=$true } '--repo-dir' { $RepoDir=$args[$i] } }
            }
            default { throw "Unknown argument: $($args[$i])" }
        }
    }
    foreach ($tool in 'git','gh') { if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Install $tool before publishing. See PUBLISHING.md." } }
    if (-not (Test-Path -LiteralPath $ZipPath -PathType Leaf) -and $ZipPath -eq (Join-Path $Parent $ZipName)) { $ZipPath=Join-Path $HOME "Downloads\$ZipName" }
    if (-not $ChecksumExplicit -and -not (Test-Path -LiteralPath $Checksum -PathType Leaf)) { $Checksum=Join-Path (Split-Path $ZipPath -Parent) $ChecksumName }
    if (-not (Test-Path -LiteralPath $ZipPath -PathType Leaf) -or (Split-Path $ZipPath -Leaf) -ne $ZipName) { throw "Expected the exact $ZipName build. Keep the original ZIP beside digital-marathon, or use --zip PATH." }
    if ($ChecksumExplicit -or (Test-Path -LiteralPath $Checksum)) {
        if (-not (Test-Path -LiteralPath $Checksum -PathType Leaf) -or (Split-Path $Checksum -Leaf) -ne $ChecksumName) { throw "Expected the supplied $ChecksumName checksum file." }
    } else { $Checksum='' }
    if ($Source -and -not (Test-Path -LiteralPath $Source -PathType Container)) { throw "Supplied source snapshot missing: $Source." }
    if (-not (Test-Path -LiteralPath $RepoDir -PathType Container)) { throw "Source checkout missing: $RepoDir. See first-time setup in PUBLISHING.md." }
    $RepoDir=(Resolve-Path -LiteralPath $RepoDir).Path
    $Temp=Join-Path ([IO.Path]::GetTempPath()) ('digital-marathon-publish-'+[Guid]::NewGuid().ToString('N')); [void](New-Item -ItemType Directory -Path $Temp)
    # Validate and upload a temporary snapshot, leaving the owner's original assets untouched.
    $OriginalZip=$ZipPath
    $stagedZip=Join-Path $Temp $ZipName; Copy-Item -LiteralPath $ZipPath -Destination $stagedZip; $ZipPath=$stagedZip
    if ($Checksum) { $stagedChecksum=Join-Path $Temp $ChecksumName; Copy-Item -LiteralPath $Checksum -Destination $stagedChecksum; $Checksum=$stagedChecksum }
    if ([IO.Path]::GetFullPath((Run-Tool 'git' @('-C',$RepoDir,'rev-parse','--show-toplevel'))) -ne [IO.Path]::GetFullPath($RepoDir)) { throw 'Use the repository root as --repo-dir.' }
    if ((Run-Tool 'git' @('-C',$RepoDir,'symbolic-ref','--short','HEAD')) -ne $Branch) { throw 'Checkout must be on main.' }
    if (Run-Tool 'git' @('-C',$RepoDir,'status','--porcelain','--untracked-files=all')) { throw 'Checkout has uncommitted files. Commit them before publishing.' }
    [void](Run-Tool 'git' @('-C',$RepoDir,'var','GIT_AUTHOR_IDENT'))
    [void](Run-Tool 'git' @('-C',$RepoDir,'var','GIT_COMMITTER_IDENT'))
    $origin=Run-Tool 'git' @('-C',$RepoDir,'remote','get-url','origin')
    if ($origin -cnotmatch '^(https://github\.com/girishxp/DigitalMarathon(\.git)?|git@github\.com:girishxp/DigitalMarathon(\.git)?|ssh://git@github\.com/girishxp/DigitalMarathon\.git)$') { throw 'Origin must be exactly girishxp/DigitalMarathon on github.com, with no embedded credentials.' }
    $RepoItems=@(Get-ChildItem -LiteralPath $RepoDir -Force | Where-Object Name -ne '.git' | ForEach-Object { $_; if ($_.PSIsContainer) { Get-ChildItem -LiteralPath $_.FullName -Recurse -Force } })
    if (@($RepoItems | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) { throw 'Checkout contains symlinks/reparse points. Use a plain sanitized source checkout.' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    # Older Windows PowerShell/.NET decompresses ZIP entries without checking CRC.
    # Compare each entry with its central-directory CRC explicitly. ZIP64/multidisk
    # packages are refused; this exact portable release is a standard single-disk ZIP.
    Add-Type -TypeDefinition @'
using System;
using System.IO;
public static class DigitalMarathonZipCrc {
    private static readonly uint[] Table = MakeTable();
    private static uint[] MakeTable() {
        uint[] t = new uint[256];
        for (uint n=0; n<256; n++) { uint c=n; for (int k=0;k<8;k++) c=(c&1)!=0 ? 0xedb88320U^(c>>1) : c>>1; t[n]=c; }
        return t;
    }
    public static uint Compute(Stream stream) {
        uint c=0xffffffffU; byte[] buffer=new byte[65536]; int size;
        while ((size=stream.Read(buffer,0,buffer.Length))>0) for (int i=0;i<size;i++) c=Table[(c^buffer[i])&255]^(c>>8);
        return c^0xffffffffU;
    }
    public static uint[] Expected(string path, int entryCount) {
        using (FileStream file=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.Read)) {
            int length=(int)Math.Min(file.Length,65557L); byte[] tail=new byte[length];
            file.Position=file.Length-length;
            int got=0; while(got<length) { int n=file.Read(tail,got,length-got); if(n==0) throw new InvalidDataException("Truncated ZIP"); got+=n; }
            int end=-1;
            for(int i=length-22;i>=0;i--) if(BitConverter.ToUInt32(tail,i)==0x06054b50U && i+22+BitConverter.ToUInt16(tail,i+20)==length) { end=i; break; }
            if(end<0) throw new InvalidDataException("ZIP directory missing");
            ushort disk=BitConverter.ToUInt16(tail,end+4), centralDisk=BitConverter.ToUInt16(tail,end+6);
            ushort diskCount=BitConverter.ToUInt16(tail,end+8), count=BitConverter.ToUInt16(tail,end+10);
            uint centralSize=BitConverter.ToUInt32(tail,end+12), offset=BitConverter.ToUInt32(tail,end+16);
            long endPosition=file.Length-length+end;
            if(disk!=0 || centralDisk!=0 || diskCount!=count || count==65535 || offset==uint.MaxValue || centralSize==uint.MaxValue || count!=entryCount || (long)offset+centralSize!=endPosition) throw new InvalidDataException("Unsupported or inconsistent ZIP directory");
            uint[] expectedCrc=new uint[count];
            file.Position=offset;
            using(BinaryReader reader=new BinaryReader(file)) {
                // ZipArchive.Entries preserves the central-directory entry order.
                for(int i=0;i<count;i++) {
                    if(reader.ReadUInt32()!=0x02014b50U) throw new InvalidDataException("Bad ZIP entry directory");
                    file.Position+=12; expectedCrc[i]=reader.ReadUInt32();
                    file.Position+=8;
                    ushort name=reader.ReadUInt16(), extra=reader.ReadUInt16(), comment=reader.ReadUInt16();
                    file.Position+=12+name+extra+comment;
                }
                if(file.Position!=(long)offset+centralSize) throw new InvalidDataException("ZIP directory size mismatch");
                return expectedCrc;
            }
        }
    }
}
'@
    $Zip=[IO.Compression.ZipFile]::OpenRead($ZipPath)
    $ExpectedCrc=[DigitalMarathonZipCrc]::Expected($ZipPath,$Zip.Entries.Count)
    for ($i=0;$i -lt $Zip.Entries.Count;$i++) {
        $stream=$Zip.Entries[$i].Open()
        try { if ([DigitalMarathonZipCrc]::Compute($stream) -ne $ExpectedCrc[$i]) { throw "ZIP entry CRC mismatch: $($Zip.Entries[$i].FullName)" } } finally { $stream.Dispose() }
    }
    $Names=@{}; foreach ($entry in $Zip.Entries) {
        if ($Names.ContainsKey($entry.FullName) -or $entry.FullName -cnotmatch '^digital-marathon/' -or $entry.FullName -match '(^|/)\.{1,2}(/|$)|\\|//|[\x00-\x1f\x7f:*?\[]|(^|/)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\.[^/]*)?(/|$)') { throw 'ZIP must contain one safe digital-marathon root with no duplicate entries.' }
        if ((($entry.ExternalAttributes -shr 16) -band 61440) -eq 40960) { throw 'ZIP symlinks are not permitted.' }
        if (Private-ZipPath $entry.FullName) { throw "Private or QA entry refused in the release ZIP: $($entry.FullName)" }
        $Names[$entry.FullName]=$true
    }
    $ActualSha=(Get-FileHash -LiteralPath $ZipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($Checksum) {
        $Matches=@(Get-Content -LiteralPath $Checksum | Where-Object { $_ -match ('^([a-fA-F0-9]{64})\s+\*?'+[Regex]::Escape($ZipName)+'$') })
        if ($Matches.Count -ne 1 -or $Matches[0].Substring(0,64).ToLowerInvariant() -ne $ActualSha) { throw 'ZIP SHA-256 does not match its checksum file.' }
    }
    $Utf8=New-Object Text.UTF8Encoding($false)
    if ($Utf8.GetString((Entry-Bytes $Zip 'digital-marathon/app-files/VERSION')).Trim() -ne $Version) { throw 'ZIP application version mismatch.' }
    $jarBytes=Entry-Bytes $Zip 'digital-marathon/app-files/app/digital-marathon.jar'
    $jarMemory=New-Object IO.MemoryStream(,$jarBytes)
    $jar=[IO.Compression.ZipArchive]::new($jarMemory,[IO.Compression.ZipArchiveMode]::Read,$false)
    try { $manifest=$Utf8.GetString((Entry-Bytes $jar 'META-INF/MANIFEST.MF')); if ($manifest -notmatch ('(?m)^Implementation-Version: '+[Regex]::Escape($Version)+'\r?$')) { throw 'Packaged JAR version mismatch.' } } finally { $jar.Dispose(); $jarMemory.Dispose() }
    [xml]$plist=$Utf8.GetString((Entry-Bytes $Zip 'digital-marathon/Digital Marathon.app/Contents/Info.plist'))
    $metadata=@{}; $nodes=@($plist.plist.dict.ChildNodes | Where-Object NodeType -eq 'Element')
    for ($i=0;$i -lt $nodes.Count;$i+=2) { $metadata[$nodes[$i].InnerText]=$nodes[$i+1].InnerText }
    if ($metadata.CFBundleIdentifier -ne 'com.girishgupta.inputactivitytracker' -or $metadata.CFBundleShortVersionString -ne $Version) { throw 'Wrong Mac product or version.' }
    if (-not $Source) {
        $Source=Join-Path $Temp 'source'; [void](New-Item -ItemType Directory -Path $Source)
        foreach ($entry in $Zip.Entries) {
            if ($entry.FullName.EndsWith('/')) { continue }
            $relative=$entry.FullName.Substring('digital-marathon/'.Length)
            if (Allowed $relative) {
                $destination=Join-Path $Source $relative
                [void](New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent))
                [IO.File]::WriteAllBytes($destination,(Entry-Bytes $Zip $entry.FullName))
            }
        }
        if (-not (Test-Path -LiteralPath (Join-Path $Source '.gitignore') -PathType Leaf) -or -not (Test-Path -LiteralPath (Join-Path $Source '.gitattributes') -PathType Leaf)) { throw 'Packaged publishing rules are missing. Use the corrected complete ZIP.' }
    }
    $Source=(Resolve-Path -LiteralPath $Source).Path
    if ($Source -eq $RepoDir -or ($Source+'\').StartsWith($RepoDir+'\',[StringComparison]::OrdinalIgnoreCase) -or ($RepoDir+'\').StartsWith($Source+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Snapshot and checkout must be separate, non-nested folders.' }
    $SourceItems=@(Get-ChildItem -LiteralPath $Source -Recurse -Force)
    if (@($SourceItems | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) { throw 'Source snapshot must not contain symlinks/reparse points.' }
    if ((Get-Content -LiteralPath (Join-Path $Source 'app-files\VERSION') -Raw).Trim() -ne $Version -or -not (Test-Path -LiteralPath (Join-Path $Source 'app-files\src\com\inputactivitytracker\Main.java'))) { throw 'Digital Marathon source/version is missing or incorrect.' }
    $SourceFiles=@($SourceItems | Where-Object { -not $_.PSIsContainer })
    foreach ($file in $SourceFiles) {
        $relative=$file.FullName.Substring($Source.Length+1).Replace('\','/')
        if (-not (Allowed $relative)) { throw "Non-source or private file refused: $relative" }
        if ($relative -match '\.(java|md|txt|properties|json|sh|ps1|command|bat)$') {
            if ((Get-Content -LiteralPath $file.FullName -Raw) -match '(github_pat_[a-zA-Z0-9_]{20,}|gh[pousr]_[a-zA-Z0-9]{20,}|phx_[a-zA-Z0-9_]{20,}|AKIA[a-zA-Z0-9]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----)') { throw "Possible credential in $relative. Remove it before publishing." }
        }
        if ($relative -notin '.gitignore','.gitattributes','LICENSE','LICENSE.txt') {
            if ((Bytes-Hash (Entry-Bytes $Zip "digital-marathon/$relative")) -ne (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()) { throw "Snapshot differs from verified ZIP: $relative" }
        }
    }
    if ($SourceFiles.Count -lt 5) { throw 'Source snapshot is incomplete.' }
    # Every allowlisted package entry must exist in the snapshot as well.
    foreach ($entry in $Zip.Entries) {
        if ($entry.FullName.EndsWith('/')) { continue }
        $relative=$entry.FullName.Substring('digital-marathon/'.Length)
        if (Allowed $relative) {
            $snapshotFile=Join-Path $Source $relative
            if (-not (Test-Path -LiteralPath $snapshotFile -PathType Leaf)) { throw "Snapshot is missing packaged source: $relative" }
            if ((Bytes-Hash (Entry-Bytes $Zip $entry.FullName)) -ne (Get-FileHash -LiteralPath $snapshotFile -Algorithm SHA256).Hash.ToLowerInvariant()) { throw "Packaged source differs from snapshot: $relative" }
        }
    }
    $Tracked=@((Run-Tool 'git' @('-C',$RepoDir,'ls-files')) -split '\r?\n' | Where-Object { $_ })
    foreach ($relative in $Tracked) { if (-not (Allowed $relative)) { throw "Tracked file outside sanitized scope: $relative" } }
    [void](Run-Tool 'gh' @('auth','status','--hostname','github.com'))
    if ((Run-Tool 'gh' @('api','user','--hostname','github.com','--jq','.login')) -ne 'girishxp') { throw 'Sign in to GitHub CLI on github.com as girishxp.' }
    $info=(Run-Tool 'gh' @('repo','view',$GitHubRepoTarget,'--json','nameWithOwner,isPrivate,viewerPermission')) | ConvertFrom-Json
    if ($info.nameWithOwner -cne $GitHubRepo -or $info.isPrivate -or $info.viewerPermission -ne 'ADMIN') { throw 'A public girishxp/DigitalMarathon repository with owner access is required.' }
    $Refs=(Run-Tool 'git' @('-C',$RepoDir,'ls-remote','--heads','--tags','origin')) -split '\r?\n'
    $LocalHead=Optional-Git @('-C',$RepoDir,'rev-parse','--verify','HEAD')
    $RemoteHead=($Refs | Where-Object { $_ -match '\srefs/heads/main$' } | ForEach-Object { ($_ -split '\s+')[0] }) -join ''
    if ($LocalHead -ne $RemoteHead) { throw 'Local main must exactly match remote main. Sync it before publishing.' }
    if ($Refs | Where-Object { $_ -match ('\srefs/tags/v'+[Regex]::Escape($Version)+'(\^\{\})?$') }) { throw "Remote tag v$Version already exists. Use a new version." }
    $localTags=(Run-Tool 'git' @('-C',$RepoDir,'tag','--list',"v$Version"))
    if ($localTags) { throw "Local tag v$Version already exists." }
    $releases=(Run-Tool 'gh' @('api',"repos/$GitHubRepo/releases",'--hostname','github.com','--paginate','--jq','.[].tag_name')) -split '\r?\n'
    if ($releases -contains "v$Version") { throw "Release v$Version already exists, including a draft. Use a new version." }
    if (-not $Checksum) {
        $Checksum=Join-Path $Temp $ChecksumName
        [IO.File]::WriteAllText($Checksum,"$ActualSha  $ZipName`n",$Utf8)
        Write-Host 'Checksum generated from the checked ZIP; no separate download is required.'
    }
    Write-Host "`nVerified Digital Marathon $Version`nRepository: $GitHubRepo`nCheckout: $RepoDir`nSource files: $($SourceFiles.Count)`nZIP: $OriginalZip`nSHA-256: $ActualSha"
    if ($DryRun) { Write-Host '`nDRY RUN PASSED. No checkout, tag, GitHub release or analytics data changed.'; exit 0 }
    if ((Read-Host 'Publish this verified source and package to GitHub? [y/N]') -notmatch '^(y|yes)$') { Write-Host 'Cancelled. Nothing changed.'; exit 0 }
    foreach ($relative in $Tracked) { if (-not (Test-Path -LiteralPath (Join-Path $Source $relative) -PathType Leaf)) { Remove-Item -LiteralPath (Join-Path $RepoDir $relative) } }
    foreach ($file in $SourceFiles) { $relative=$file.FullName.Substring($Source.Length+1); $destination=Join-Path $RepoDir $relative; [void](New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent)); Copy-Item -LiteralPath $file.FullName -Destination $destination }
    [void](Run-Tool 'git' @('-C',$RepoDir,'add','--all','--','.'))
    if ((Run-Tool 'git' @('-C',$RepoDir,'diff','--cached','--name-only'))) { Write-Host (Run-Tool 'git' @('-C',$RepoDir,'commit','-m',"Digital Marathon v$Version")) }
    $commit=Run-Tool 'git' @('-C',$RepoDir,'rev-parse','HEAD')
    Write-Host (Run-Tool 'git' @('-C',$RepoDir,'push','origin',$Branch))
    [void](Run-Tool 'git' @('-C',$RepoDir,'tag','-a',"v$Version",$commit,'-m',"Digital Marathon v$Version"))
    [void](Run-Tool 'git' @('-C',$RepoDir,'push','origin',"refs/tags/v$Version"))
    $tagRef="refs/tags/v$Version^{}"
    $remoteTag=Run-Tool 'git' @('-C',$RepoDir,'ls-remote','origin',"refs/tags/v$Version",$tagRef)
    $tagCommit=(($remoteTag -split '\r?\n' | Where-Object { ($_ -split '\s+')[1] -eq $tagRef } | ForEach-Object { ($_ -split '\s+')[0] }) -join '')
    if ($tagCommit -ne $commit) { throw 'Remote release tag does not resolve to the reviewed commit. No release was published.' }
    $notes=Join-Path $Temp 'release-notes.md'
    [IO.File]::WriteAllText($notes,"Digital Marathon v$Version`n`nDownload the combined Mac/Windows package, extract it and open the launcher for your computer.`n`nSHA-256: ``$ActualSha```n`nSource and owner publishing instructions are included.`n",$Utf8)
    [void](Run-Tool 'gh' @('release','create',"v$Version",'--repo',$GitHubRepoTarget,'--target',$commit,'--verify-tag','--title',"Digital Marathon v$Version",'--notes-file',$notes,'--draft','--latest=false'))
    [void](Run-Tool 'gh' @('release','upload',"v$Version",$ZipPath,$Checksum,'--repo',$GitHubRepoTarget))
    $remote=Join-Path $Temp 'remote-assets'; [void](New-Item -ItemType Directory -Path $remote)
    [void](Run-Tool 'gh' @('release','download',"v$Version",'--repo',$GitHubRepoTarget,'--pattern',$ZipName,'--pattern',$ChecksumName,'--dir',$remote))
    if ((Get-FileHash -LiteralPath (Join-Path $remote $ZipName) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ActualSha -or (Get-FileHash -LiteralPath (Join-Path $remote $ChecksumName) -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $Checksum -Algorithm SHA256).Hash) { throw 'Uploaded asset verification failed. Release remains a draft; inspect it manually.' }
    if ((Run-Tool 'gh' @('release','view',"v$Version",'--repo',$GitHubRepoTarget,'--json','assets','--jq','.assets | length')) -ne '2') { throw 'Unexpected draft assets. Inspect it before publication.' }
    $verifiedTag=Run-Tool 'git' @('-C',$RepoDir,'ls-remote','origin',$tagRef)
    if (($verifiedTag -split '\s+')[0] -ne $commit) { throw 'Release tag changed during upload. The release remains a draft.' }
    [void](Run-Tool 'gh' @('release','edit',"v$Version",'--repo',$GitHubRepoTarget,'--draft=false','--latest'))
    if ((Run-Tool 'gh' @('api',"repos/$GitHubRepo/releases/latest",'--hostname','github.com','--jq','.tag_name')) -ne "v$Version") { throw 'Release was published, but Latest verification failed. Inspect GitHub manually.' }
    Write-Host "`nPublished and verified: https://github.com/$GitHubRepo/releases/tag/v$Version"
} catch { Write-Error $_.Exception.Message -ErrorAction Continue; exit 1 } finally { if ($Zip) { $Zip.Dispose() }; if ($Temp -and (Test-Path -LiteralPath $Temp)) { Remove-Item -LiteralPath $Temp -Recurse -Force } }

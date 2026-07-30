$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Fail([string] $Message) {
    throw "Windows package error: $Message"
}

function Require-Command([string] $Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $command) { Fail "missing required tool: $Name" }
    return $command.Source
}

function Invoke-Native([string] $File, [string[]] $Arguments) {
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { Fail "$File exited with code $LASTEXITCODE" }
}

function Write-Utf8([string] $Path, [string] $Value) {
    [System.IO.File]::WriteAllText($Path, $Value, [System.Text.UTF8Encoding]::new($false))
}

if (-not [Environment]::Is64BitOperatingSystem) { Fail 'Windows x64 is required' }
if ($env:PROCESSOR_ARCHITECTURE -ne 'AMD64') { Fail "Windows x64 is required; found $env:PROCESSOR_ARCHITECTURE" }

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot
$maven = Require-Command 'mvn'
$jarTool = Require-Command 'jar'
$msiExec = Require-Command 'msiexec.exe'
$javaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHome)) { Fail 'JAVA_HOME is required' }
$java = Join-Path $javaHome 'bin/java.exe'
$jdeps = Join-Path $javaHome 'bin/jdeps.exe'
$jlink = Join-Path $javaHome 'bin/jlink.exe'
$jpackage = Join-Path $javaHome 'bin/jpackage.exe'
foreach ($tool in @($java, $jdeps, $jlink, $jpackage)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { Fail "missing JDK tool: $tool" }
}

$javaVersionOutput = (& $java -version 2>&1 | Out-String)
$javaVersionMatch = [regex]::Match($javaVersionOutput, 'version "([^"]+)"')
if (-not $javaVersionMatch.Success) { Fail 'could not resolve Java version' }
$javaVersion = $javaVersionMatch.Groups[1].Value
if ($javaVersion.Split('.')[0] -ne '21') { Fail "JDK 21 is required; found $javaVersion" }

$mavenArgs = @('-B', '-q', 'clean', 'package')
if (-not [string]::IsNullOrWhiteSpace($env:SHED_BUILD_COMMIT)) {
    $mavenArgs += "-Dshed.build.commit=$env:SHED_BUILD_COMMIT"
}
Invoke-Native $maven $mavenArgs
Invoke-Native $maven @('-B', '-q', 'artifact:check-buildplan')

$versionOutput = Invoke-Native $maven @('-q', '-DforceStdout', 'help:evaluate', '-Dexpression=project.version')
$version = (($versionOutput | Select-Object -Last 1) | Out-String).Trim()
if ([string]::IsNullOrWhiteSpace($version)) { Fail 'could not resolve project version' }
$timestampOutput = Invoke-Native $maven @('-q', '-DforceStdout', 'help:evaluate', '-Dexpression=project.build.outputTimestamp')
$outputTimestamp = (($timestampOutput | Select-Object -Last 1) | Out-String).Trim()
if ([string]::IsNullOrWhiteSpace($outputTimestamp)) { Fail 'could not resolve Maven output timestamp' }

$appName = 'Shed'
$architecture = 'windows-x64'
$jarName = "shed-$version.jar"
$jarPath = Join-Path $repoRoot "target/$jarName"
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) { Fail "missing packaged jar: $jarPath" }
$packageRoot = Join-Path $repoRoot 'target/windows-package'
$inputDir = Join-Path $packageRoot 'input'
$runtimeDir = Join-Path $packageRoot 'runtime'
$appImageDir = Join-Path $packageRoot 'app-image'
$distDir = Join-Path $packageRoot 'dist'
foreach ($directory in @($inputDir, $appImageDir, $distDir)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $inputDir $jarName)

$moduleOutput = Invoke-Native $jdeps @('--multi-release', '21', '--ignore-missing-deps', '--print-module-deps', $jarPath)
$modules = (($moduleOutput | Select-Object -Last 1) | Out-String).Trim()
if (-not $modules.Contains('java.desktop')) { Fail 'runtime module analysis did not include java.desktop' }
$modulePath = Join-Path $packageRoot 'runtime-modules.txt'
Write-Utf8 $modulePath "$modules`n"
Invoke-Native $jlink @('--add-modules', $modules, '--output', $runtimeDir, '--strip-debug', '--no-header-files', '--no-man-pages', '--compress=zip-6')

Invoke-Native $jpackage @('--type', 'app-image', '--dest', $appImageDir, '--input', $inputDir, '--main-jar', $jarName,
    '--main-class', 'shed.Texteditor', '--name', $appName, '--app-version', $version, '--vendor', 'Shed',
    '--description', 'Shed text editor', '--copyright', 'Copyright Shed', '--runtime-image', $runtimeDir)

$appDirectory = Join-Path $appImageDir $appName
$appExecutable = Join-Path $appDirectory "$appName.exe"
$appJar = Join-Path $appDirectory "app/$jarName"
$runtimeJava = Join-Path $appDirectory 'runtime/bin/java.exe'
foreach ($path in @($appExecutable, $appJar, $runtimeJava)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { Fail "missing app image path: $path" }
}
$runtimeVersionOutput = (& $runtimeJava -version 2>&1 | Out-String)
$runtimeVersionMatch = [regex]::Match($runtimeVersionOutput, 'version "([^"]+)"')
if (-not $runtimeVersionMatch.Success -or $runtimeVersionMatch.Groups[1].Value.Split('.')[0] -ne '21') {
    Fail 'bundled runtime is not Java 21'
}
$runtimeVersion = $runtimeVersionMatch.Groups[1].Value
$jarEntries = Invoke-Native $jarTool @('tf', $appJar)
if ($jarEntries -notcontains 'assets/hackregfont.ttf') { Fail 'bundled font is missing' }

Invoke-Native $jpackage @('--type', 'msi', '--dest', $distDir, '--name', $appName, '--app-version', $version, '--app-image', $appDirectory)
$generatedMsi = Get-ChildItem -LiteralPath $distDir -Filter '*.msi' -File | Select-Object -First 1
if ($null -eq $generatedMsi) { Fail 'jpackage did not produce an MSI' }
$artifactName = "$appName-$version-$architecture.msi"
$artifactPath = Join-Path $distDir $artifactName
Move-Item -LiteralPath $generatedMsi.FullName -Destination $artifactPath

$installerImage = Join-Path $packageRoot 'installer-image'
$installProcess = Start-Process -FilePath $msiExec -ArgumentList @('/a', $artifactPath, '/qn', "TARGETDIR=$installerImage") -Wait -PassThru
if ($installProcess.ExitCode -ne 0) { Fail "MSI administrative extraction failed with code $($installProcess.ExitCode)" }
$installedExecutable = Get-ChildItem -LiteralPath $installerImage -Filter "$appName.exe" -File -Recurse | Select-Object -First 1
if ($null -eq $installedExecutable) { Fail 'MSI does not contain the application executable' }
$installedRoot = Split-Path -Parent $installedExecutable.FullName
if (-not (Test-Path -LiteralPath (Join-Path $installedRoot "app/$jarName") -PathType Leaf)) { Fail 'MSI app is missing the main jar' }
if (-not (Test-Path -LiteralPath (Join-Path $installedRoot 'runtime/bin/java.exe') -PathType Leaf)) { Fail 'MSI app is missing the bundled runtime' }

$jarSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $jarPath).Hash.ToLowerInvariant()
$modulesSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $modulePath).Hash.ToLowerInvariant()
$msiSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $artifactPath).Hash.ToLowerInvariant()
$signature = (Get-AuthenticodeSignature -LiteralPath $appExecutable).Status.ToString()
$inputsPath = Join-Path $distDir "$appName-$version-$architecture.inputs.sha256"
$checksumPath = "$artifactPath.sha256"
$reportPath = Join-Path $distDir "$appName-$version-$architecture.validation.txt"
Write-Utf8 $inputsPath "$jarSha256  $jarName`n$modulesSha256  runtime-modules.txt`n"
Write-Utf8 $checksumPath "$msiSha256  $artifactName`n"
$report = (@(
    "artifact=$artifactName",
    "artifact_sha256=$msiSha256",
    "app_directory=$appName",
    'architecture=x64',
    "bundle_version=$version",
    "input_jar=$jarName",
    "input_jar_sha256=$jarSha256",
    "maven_output_timestamp=$outputTimestamp",
    'maven_build_plan=reproducible',
    'java_feature=21',
    "runtime_java_version=$runtimeVersion",
    "runtime_modules=$modules",
    "runtime_modules_sha256=$modulesSha256",
    'installer_contents=validated',
    "signing=$signature",
    'notarization=not-applicable'
) -join "`n") + "`n"
Write-Utf8 $reportPath $report

Write-Output "Windows package ready: $artifactPath"
Write-Output "checksum: $checksumPath"
Write-Output "inputs: $inputsPath"
Write-Output "validation: $reportPath"

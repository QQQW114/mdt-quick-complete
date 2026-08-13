$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$src = Join-Path $root "src\main\java"
$libs = Join-Path $root "libs"
$out = Join-Path $root "build"
$classes = Join-Path $out "classes"

$mindustryJar = Get-ChildItem -Path $libs -Filter "mindustry-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if(-not $mindustryJar){ throw "未找到 compileOnly 依赖 jar（libs/mindustry-*.jar）" }

New-Item -ItemType Directory -Force -Path $classes | Out-Null

$sources = Get-ChildItem -Path $src -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
if(-not $sources){ throw "未找到源码" }

Write-Host "javac 编译中..."
javac --release 17 -encoding UTF-8 -cp $mindustryJar.FullName -d $classes $sources
if($LASTEXITCODE -ne 0){ throw "javac 编译失败" }

$jar = Join-Path $out "quick-complete.jar"
Copy-Item (Join-Path $root "mod.hjson") (Join-Path $classes "mod.hjson") -Force
if(Test-Path (Join-Path $root "preview.png")){
  Copy-Item (Join-Path $root "preview.png") (Join-Path $classes "preview.png") -Force
}

Push-Location $classes
jar cf $jar .
Pop-Location

Write-Host "构建完成: $jar"

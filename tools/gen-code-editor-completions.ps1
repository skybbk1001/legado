Param(
  [string]$ProjectRoot = (Get-Location).Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-FileText([string]$path) {
  if (!(Test-Path $path)) { return "" }
  return Get-Content -Raw -LiteralPath $path
}

function Get-BlockContent([string]$text, [string]$name) {
  if ([string]::IsNullOrWhiteSpace($text)) { return "" }
  $pattern = "(?m)(interface|class|object|data\s+class)\s+" + [regex]::Escape($name) + "\b"
  $m = [regex]::Match($text, $pattern)
  if (!$m.Success) { return "" }
  $startIdx = $m.Index + $m.Length
  $braceIdx = $text.IndexOf("{", $startIdx)
  if ($braceIdx -lt 0) { return "" }
  $depth = 0
  for ($i = $braceIdx; $i -lt $text.Length; $i++) {
    $ch = $text[$i]
    if ($ch -eq '{') { $depth++ }
    elseif ($ch -eq '}') {
      $depth--
      if ($depth -eq 0) {
        return $text.Substring($braceIdx + 1, $i - $braceIdx - 1)
      }
    }
  }
  return ""
}

function Is-VisibleMember([string]$prefix) {
  return ($prefix -notmatch "\b(private|protected|internal)\b")
}

function Get-SignatureBlock([string]$text, [string]$name) {
  if ([string]::IsNullOrWhiteSpace($text)) { return "" }
  $pattern = "(?m)(interface|class|object|data\\s+class)\\s+" + [regex]::Escape($name) + "\\b"
  $m = [regex]::Match($text, $pattern)
  if (!$m.Success) { return "" }
  $startIdx = $m.Index + $m.Length
  $braceIdx = $text.IndexOf("{", $startIdx)
  if ($braceIdx -lt 0) { return "" }
  $depth = 0
  for ($i = $braceIdx; $i -lt $text.Length; $i++) {
    $ch = $text[$i]
    if ($ch -eq '{') { $depth++ }
    elseif ($ch -eq '}') {
      $depth--
      if ($depth -eq 0) {
        return $text.Substring($startIdx, $i - $startIdx + 1)
      }
    }
  }
  return ""
}

function Get-BaseNamesFromSignature([string]$sig) {
  $names = @()
  if ([string]::IsNullOrWhiteSpace($sig)) { return $names }
  $rx = [regex]::new("(?s)\\s*:\\s*([^\\{]+)\\{")
  $m = $rx.Match($sig)
  if (!$m.Success) { return $names }
  $baseList = $m.Groups[1].Value
  foreach ($part in ($baseList -split ",")) {
    $t = ($part.Trim() -split "\\s+")[0]
    if ($t) { $names += $t.Trim() }
  }
  return $names
}

function MergeMethodsProps([hashtable]$target, [hashtable]$addon) {
  if ($null -eq $target) { return $addon }
  if ($null -eq $addon) { return $target }
  $target.methods = NormalizeMethods (UniqueSort ($target.methods + $addon.methods))
  $target.props = UniqueSort ($target.props + $addon.props)
  return $target
}

function Get-TopLevelLines([string]$block) {
  $lines = $block -split "`n"
  $depth = 0
  $top = @()
  foreach ($line in $lines) {
    if ($depth -eq 0) { $top += $line }
    $open = ([regex]::Matches($line, "\{")).Count
    $close = ([regex]::Matches($line, "\}")).Count
    $depth += ($open - $close)
    if ($depth -lt 0) { $depth = 0 }
  }
  return $top
}

$TypePathCache = @{}
function Resolve-TypePath([string]$name, [string]$root) {
  if ([string]::IsNullOrWhiteSpace($name)) { return $null }
  if ($TypePathCache.ContainsKey($name)) { return $TypePathCache[$name] }
  $baseDir = Join-Path $root "app/src/main/java/io/legado/app"
  $match = Get-ChildItem -Path $baseDir -Recurse -Filter *.kt |
    Select-String -Pattern ("(?m)^(interface|class|object|data\\s+class)\\s+" + [regex]::Escape($name) + "\\b") -List |
    Select-Object -First 1
  $path = $null
  if ($match) { $path = $match.Path }
  $TypePathCache[$name] = $path
  return $path
}

function Get-EntryFromType([string]$typeName, [string]$root) {
  $path = Resolve-TypePath $typeName $root
  if ([string]::IsNullOrWhiteSpace($path)) { return $null }
  $text = Get-FileText $path
  $block = Get-BlockContent $text $typeName
  if ([string]::IsNullOrWhiteSpace($block)) { return $null }
  $methods = NormalizeMethods (Get-Functions $block)
  $props = UniqueSort (Get-Properties $block)
  return @{ methods = $methods; props = $props }
}

function MergeDirectBases([hashtable]$entry, [string]$sig, [string]$root) {
  $bases = Get-BaseNamesFromSignature $sig
  if (!$bases -or $bases.Count -eq 0) { return $entry }
  foreach ($b in $bases) {
    $baseEntry = Get-EntryFromType $b $root
    if ($baseEntry) {
      $entry = MergeMethodsProps $entry $baseEntry
    }
  }
  return $entry
}

function Get-Functions([string]$block) {
  $names = @()
  if ([string]::IsNullOrWhiteSpace($block)) { return $names }
  $rx = [regex]::new("(?m)^[^\n]*\bfun\s+([A-Za-z_][\w]*)\s*\(([^)]*)\)")
  foreach ($line in (Get-TopLevelLines $block)) {
    foreach ($m in $rx.Matches($line)) {
      $prefix = $m.Value
      if (Is-VisibleMember $prefix) {
        $name = $m.Groups[1].Value
        $args = $m.Groups[2].Value
        if ([string]::IsNullOrWhiteSpace($args)) {
          $names += ($name + "()")
        } else {
          $names += $name
        }
      }
    }
  }
  return $names
}

function Get-Properties([string]$block) {
  $names = @()
  if ([string]::IsNullOrWhiteSpace($block)) { return $names }
  $rx = [regex]::new("(?m)^[^\n]*\b(val|var)\s+([A-Za-z_][\w]*)\b")
  foreach ($line in (Get-TopLevelLines $block)) {
    foreach ($m in $rx.Matches($line)) {
      $prefix = $m.Value
      if (Is-VisibleMember $prefix) {
        $names += $m.Groups[2].Value
      }
    }
  }
  return $names
}

function Get-PrimaryConstructorProps([string]$text, [string]$name) {
  if ([string]::IsNullOrWhiteSpace($text)) { return @() }
  $pattern = "(?m)(data\s+class|class)\s+" + [regex]::Escape($name) + "\s*\("
  $m = [regex]::Match($text, $pattern)
  if (!$m.Success) { return @() }
  $start = $m.Index + $m.Length
  $depth = 1
  $i = $start
  for (; $i -lt $text.Length; $i++) {
    $ch = $text[$i]
    if ($ch -eq '(') { $depth++ }
    elseif ($ch -eq ')') {
      $depth--
      if ($depth -eq 0) { break }
    }
  }
  if ($i -le $start) { return @() }
  $params = $text.Substring($start, $i - $start)
  $rx = [regex]::new("(?m)(?:^|,)\s*(?:@[\w.()\s]+\s*)*(val|var)\s+([A-Za-z_][\w]*)\b")
  $names = @()
  foreach ($m2 in $rx.Matches($params)) {
    $names += $m2.Groups[2].Value
  }
  return $names
}

function UniqueSort([string[]]$arr) {
  if ($null -eq $arr) { return @() }
  return @($arr | Where-Object { $_ -and $_.Trim() -ne "" } | Sort-Object -Unique)
}

function NormalizeMethods([string[]]$methods) {
  $methods = UniqueSort $methods
  $base = @{}
  foreach ($m in $methods) {
    if ($m.EndsWith("()")) {
      $name = $m.Substring(0, $m.Length - 2)
      if (-not $base.ContainsKey($name)) { $base[$name] = $false }
    } else {
      $base[$m] = $true
    }
  }
  $out = @()
  foreach ($k in $base.Keys) {
    if ($base[$k]) { $out += $k } else { $out += ($k + "()") }
  }
  return $out | Sort-Object
}

function ConvertIsPropsToMethods($data) {
  if ($null -eq $data) { return $data }
  $props = @()
  $methods = @()
  if ($data.props) { $props = @($data.props) }
  if ($data.methods) { $methods = @($data.methods) }
  $remain = @()
  foreach ($p in $props) {
    if ($p -match '^is[A-Z]') {
      $methods += ($p + '()')
    } else {
      $remain += $p
    }
  }
  $data.props = UniqueSort $remain
  $data.methods = UniqueSort $methods
  return $data
}

function NormalizeEntry($data) {
  if ($null -eq $data) { return $data }
  $data.props = @($data.props) | Where-Object { $_ -is [string] -and $_.Trim() -ne "" }
  $data.methods = @($data.methods) | Where-Object { $_ -is [string] -and $_.Trim() -ne "" }
  $methodBases = @{}
  foreach ($m in $data.methods) {
    if ($m.EndsWith("()")) {
      $methodBases[$m.Substring(0, $m.Length - 2)] = $true
    } else {
      $methodBases[$m] = $true
    }
  }
  $data.props = $data.props | Where-Object { $_ -ne $null -and -not $methodBases.ContainsKey($_) }
  return $data
}

$root = $ProjectRoot
$paths = @{
  encode = Join-Path $root "app/src/main/java/io/legado/app/help/JsEncodeUtils.kt"
  java = Join-Path $root "app/src/main/java/io/legado/app/help/JsExtensions.kt"
  source = Join-Path $root "app/src/main/java/io/legado/app/data/entities/BaseSource.kt"
  cookie = Join-Path $root "app/src/main/java/io/legado/app/help/http/CookieStore.kt"
  cache = Join-Path $root "app/src/main/java/io/legado/app/help/CacheManager.kt"
  baseBook = Join-Path $root "app/src/main/java/io/legado/app/data/entities/BaseBook.kt"
  book = Join-Path $root "app/src/main/java/io/legado/app/data/entities/Book.kt"
  chapter = Join-Path $root "app/src/main/java/io/legado/app/data/entities/BookChapter.kt"
}

$encodeText = Get-FileText $paths.encode
$javaText = Get-FileText $paths.java
$sourceText = Get-FileText $paths.source
$cookieText = Get-FileText $paths.cookie
$cacheText = Get-FileText $paths.cache
$baseBookText = Get-FileText $paths.baseBook
$bookText = Get-FileText $paths.book
$chapterText = Get-FileText $paths.chapter

$javaSig = Get-SignatureBlock $javaText "JsExtensions"
$sourceSig = Get-SignatureBlock $sourceText "BaseSource"
$cookieSig = Get-SignatureBlock $cookieText "CookieStore"
$cacheSig = Get-SignatureBlock $cacheText "CacheManager"
$bookSig = Get-SignatureBlock $bookText "Book"
$chapterSig = Get-SignatureBlock $chapterText "BookChapter"

$javaBlock = Get-BlockContent $javaText "JsExtensions"
$sourceBlock = Get-BlockContent $sourceText "BaseSource"
$cookieBlock = Get-BlockContent $cookieText "CookieStore"
$cacheBlock = Get-BlockContent $cacheText "CacheManager"
$encodeBlock = Get-BlockContent $encodeText "JsEncodeUtils"
$baseBookBlock = Get-BlockContent $baseBookText "BaseBook"
$bookBlock = Get-BlockContent $bookText "Book"
$chapterBlock = Get-BlockContent $chapterText "BookChapter"

$javaMethodsSrc = NormalizeMethods (Get-Functions $javaBlock)
$javaPropsSrc = UniqueSort (Get-Properties $javaBlock)
$sourceMethodsSrc = NormalizeMethods (Get-Functions $sourceBlock)
$sourcePropsSrc = UniqueSort (Get-Properties $sourceBlock)
$cookieMethodsSrc = NormalizeMethods (Get-Functions $cookieBlock)
$cookiePropsSrc = UniqueSort (Get-Properties $cookieBlock)
$cacheMethodsSrc = NormalizeMethods (Get-Functions $cacheBlock)
$cachePropsSrc = UniqueSort (Get-Properties $cacheBlock)
$encodeMethodsSrc = NormalizeMethods (Get-Functions $encodeBlock)
$encodePropsSrc = UniqueSort (Get-Properties $encodeBlock)
$baseBookMethodsSrc = NormalizeMethods (Get-Functions $baseBookBlock)
$baseBookPropsSrc = UniqueSort (Get-Properties $baseBookBlock)
$bookMethodsSrc = NormalizeMethods (Get-Functions $bookBlock)
$bookPropsSrc = UniqueSort ((Get-Properties $bookBlock) + (Get-PrimaryConstructorProps $bookText "Book"))
$chapterMethodsSrc = NormalizeMethods (Get-Functions $chapterBlock)
$chapterPropsSrc = UniqueSort ((Get-Properties $chapterBlock) + (Get-PrimaryConstructorProps $chapterText "BookChapter"))

$java = @{ methods = NormalizeMethods (UniqueSort ($javaMethodsSrc + $encodeMethodsSrc)); props = UniqueSort ($javaPropsSrc + $encodePropsSrc) }
$source = @{ methods = $sourceMethodsSrc; props = $sourcePropsSrc }
$cookie = @{ methods = $cookieMethodsSrc; props = $cookiePropsSrc }
$cache = @{ methods = $cacheMethodsSrc; props = $cachePropsSrc }
$book = @{ methods = NormalizeMethods (UniqueSort ($bookMethodsSrc + $baseBookMethodsSrc)); props = UniqueSort ($bookPropsSrc + $baseBookPropsSrc) }
$chapter = @{ methods = NormalizeMethods (UniqueSort $chapterMethodsSrc); props = UniqueSort $chapterPropsSrc }

$java = MergeDirectBases $java $javaSig $root
$source = MergeDirectBases $source $sourceSig $root
$cookie = MergeDirectBases $cookie $cookieSig $root
$cache = MergeDirectBases $cache $cacheSig $root
$book = MergeDirectBases $book $bookSig $root
$chapter = MergeDirectBases $chapter $chapterSig $root

$java = ConvertIsPropsToMethods $java
$source = ConvertIsPropsToMethods $source
$cookie = ConvertIsPropsToMethods $cookie
$cache = ConvertIsPropsToMethods $cache
$book = ConvertIsPropsToMethods $book
$chapter = ConvertIsPropsToMethods $chapter

$java = NormalizeEntry $java
$source = NormalizeEntry $source
$cookie = NormalizeEntry $cookie
$cache = NormalizeEntry $cache
$book = NormalizeEntry $book
$chapter = NormalizeEntry $chapter

$custom = [ordered]@{
  java    = $java
  source  = $source
  cookie  = $cookie
  cache   = $cache
  book    = $book
  chapter = $chapter
}

$json = $custom | ConvertTo-Json -Depth 6
$lines = $json -split "`n"
$indented = @()
for ($i = 0; $i -lt $lines.Length; $i++) {
  if ($i -eq 0) {
    $indented += ("      const CUSTOM_COMPLETIONS = " + $lines[$i])
  } else {
    $indented += ("      " + $lines[$i])
  }
}
$block = "// <AUTO_COMPLETIONS>" + "`n" + ($indented -join "`n") + ";" + "`n" + "      // </AUTO_COMPLETIONS>"

$editorPath = Join-Path $root "app/src/main/assets/web/code-editor/editor.html"
$editorText = Get-Content -Raw -LiteralPath $editorPath
$pattern = "// <AUTO_COMPLETIONS>[\s\S]*?// </AUTO_COMPLETIONS>"
if ($editorText -notmatch $pattern) {
  throw "AUTO_COMPLETIONS marker not found in editor.html"
}
$updated = [regex]::Replace($editorText, $pattern, [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $block })
Set-Content -LiteralPath $editorPath -Value $updated

Write-Output "Updated completions in editor.html"



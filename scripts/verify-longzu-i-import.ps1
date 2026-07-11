param(
    [string]$SourcePath = "E:\books\龙族ⅰ-火之晨曦.txt",
    [string]$Database = "moqi_dev",
    [string]$HostName = "127.0.0.1",
    [int]$Port = 3306,
    [string]$User = "root",
    [string]$Password = ""
)

$ErrorActionPreference = "Stop"

function Get-MySqlExe {
    $configured = "E:\middleware\mysql-8.4.9\bin\mysql.exe"
    if (Test-Path $configured) {
        return $configured
    }

    $command = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    throw "Cannot find mysql.exe. Expected $configured or mysql.exe on PATH."
}

function Convert-FromHexUtf8([string]$Hex) {
    if ([string]::IsNullOrEmpty($Hex)) {
        return ""
    }

    $bytes = New-Object byte[] ($Hex.Length / 2)
    for ($i = 0; $i -lt $bytes.Length; $i++) {
        $bytes[$i] = [Convert]::ToByte($Hex.Substring($i * 2, 2), 16)
    }
    return [System.Text.Encoding]::UTF8.GetString($bytes)
}

function Get-ChapterType([string]$Title) {
    if ($Title -eq "献词") {
        return "dedication"
    }
    if ($Title -like "序幕*") {
        return "prologue"
    }
    if ($Title -eq "尾声") {
        return "epilogue"
    }
    return "chapter"
}

function Read-BookSections([string]$Path) {
    $lines = Get-Content -LiteralPath $Path -Encoding UTF8
    $sections = @()
    $i = 0
    while ($i -lt $lines.Count) {
        if ($lines[$i] -notmatch "^={10,}\s*$") {
            $i++
            continue
        }

        $titleIndex = $i + 1
        while ($titleIndex -lt $lines.Count -and [string]::IsNullOrWhiteSpace($lines[$titleIndex])) {
            $titleIndex++
        }

        $closingIndex = $titleIndex + 1
        while ($closingIndex -lt $lines.Count -and [string]::IsNullOrWhiteSpace($lines[$closingIndex])) {
            $closingIndex++
        }

        if ($titleIndex -ge $lines.Count -or $closingIndex -ge $lines.Count -or $lines[$closingIndex] -notmatch "^={10,}\s*$") {
            $i++
            continue
        }

        $nextIndex = $closingIndex + 1
        while ($nextIndex -lt $lines.Count -and $lines[$nextIndex] -notmatch "^={10,}\s*$") {
            $nextIndex++
        }

        $bodyStart = $closingIndex + 1
        $bodyEnd = $nextIndex - 1
        $bodyLines = if ($bodyEnd -ge $bodyStart) { $lines[$bodyStart..$bodyEnd] } else { @() }
        $body = (($bodyLines -join "`n") -replace "^\s+", "" -replace "\s+$", "")

        $sections += [pscustomobject]@{
            No = $sections.Count + 1
            Title = $lines[$titleIndex].Trim()
            ChapterType = Get-ChapterType $lines[$titleIndex].Trim()
            Content = $body
        }

        $i = $nextIndex
    }

    return $sections
}

function Invoke-MySqlQuery([string]$Sql) {
    $mysql = Get-MySqlExe
    $args = @("--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names", "-h", $HostName, "-P", $Port.ToString(), "-u", $User)
    if ($Password -ne "") {
        $args += "-p$Password"
    }
    $args += $Database
    $args += "-e"
    $args += $Sql

    $output = & $mysql @args
    if ($LASTEXITCODE -ne 0) {
        throw "mysql query failed with exit code $LASTEXITCODE"
    }
    return $output
}

if (!(Test-Path -LiteralPath $SourcePath)) {
    throw "Source file not found: $SourcePath"
}

$expected = Read-BookSections $SourcePath
$rows = Invoke-MySqlQuery "SELECT chapter_no, chapter_type, HEX(title), HEX(content) FROM chapters WHERE work_id = (SELECT id FROM works WHERE title = '龙族Ⅰ-火之晨曦' AND deleted = 0 ORDER BY id DESC LIMIT 1) AND deleted = 0 ORDER BY chapter_no;"

if ($rows.Count -ne $expected.Count) {
    throw "Section count mismatch. Source=$($expected.Count), DB=$($rows.Count)"
}

for ($i = 0; $i -lt $expected.Count; $i++) {
    $parts = $rows[$i] -split "`t", 4
    $chapterNo = [int]$parts[0]
    $chapterType = $parts[1]
    $title = Convert-FromHexUtf8 $parts[2]
    $content = Convert-FromHexUtf8 $parts[3]
    $source = $expected[$i]

    if ($chapterNo -ne $source.No -or $chapterType -ne $source.ChapterType -or $title -ne $source.Title -or $content -ne $source.Content) {
        throw "Content mismatch at section $($source.No): $($source.Title)"
    }
}

$counts = @{}
$perChapterTables = @("chapter_outlines", "chapter_briefs", "chapter_generations", "chapter_summaries", "chapter_key_events")
foreach ($name in @($perChapterTables + @("setting_candidates", "setting_entries", "foreshadowing_items"))) {
    $count = Invoke-MySqlQuery "SELECT COUNT(*) FROM $name WHERE work_id = (SELECT id FROM works WHERE title = '龙族Ⅰ-火之晨曦' AND deleted = 0 ORDER BY id DESC LIMIT 1);"
    $counts[$name] = [int]$count
    if ($counts[$name] -le 0) {
        throw "Imported story table $name has no records."
    }
}

foreach ($name in $perChapterTables) {
    if ($counts[$name] -ne $expected.Count) {
        throw "Imported story table $name should have $($expected.Count) records, got $($counts[$name])."
    }
}

$integrityRows = Invoke-MySqlQuery @"
SELECT 'missing_outline', COUNT(*)
FROM chapters c
LEFT JOIN chapter_outlines o ON o.chapter_id = c.id AND o.work_id = c.work_id AND o.deleted = 0
WHERE c.work_id = (SELECT id FROM works WHERE title = '龙族Ⅰ-火之晨曦' AND deleted = 0 ORDER BY id DESC LIMIT 1)
  AND c.deleted = 0
  AND o.id IS NULL
UNION ALL
SELECT 'missing_brief', COUNT(*)
FROM chapters c
LEFT JOIN chapter_briefs b ON b.chapter_id = c.id AND b.work_id = c.work_id AND b.deleted = 0
WHERE c.work_id = (SELECT id FROM works WHERE title = '龙族Ⅰ-火之晨曦' AND deleted = 0 ORDER BY id DESC LIMIT 1)
  AND c.deleted = 0
  AND b.id IS NULL
UNION ALL
SELECT 'missing_generation', COUNT(*)
FROM chapters c
LEFT JOIN chapter_generations g ON g.chapter_id = c.id AND g.work_id = c.work_id AND g.deleted = 0
WHERE c.work_id = (SELECT id FROM works WHERE title = '龙族Ⅰ-火之晨曦' AND deleted = 0 ORDER BY id DESC LIMIT 1)
  AND c.deleted = 0
  AND g.id IS NULL
UNION ALL
SELECT 'generation_content_mismatch', COUNT(*)
FROM chapters c
JOIN chapter_generations g ON g.chapter_id = c.id AND g.work_id = c.work_id AND g.deleted = 0
WHERE c.work_id = (SELECT id FROM works WHERE title = '龙族Ⅰ-火之晨曦' AND deleted = 0 ORDER BY id DESC LIMIT 1)
  AND c.deleted = 0
  AND c.content <> g.generated_content;
"@

foreach ($row in $integrityRows) {
    $parts = $row -split "`t", 2
    if ([int]$parts[1] -ne 0) {
        throw "Chapter import integrity check failed: $($parts[0])=$($parts[1])."
    }
}

Write-Output "正文还原验证通过：$($expected.Count) 个章节块可按 chapter_no 完整还原标题、类型和正文。"
Write-Output "章节拆解与知识层记录统计："
foreach ($key in $counts.Keys | Sort-Object) {
    Write-Output ("- {0}: {1}" -f $key, $counts[$key])
}
Write-Output "章节主流程完整性验证通过：每个章节都有大纲、简报和导入稿，且导入稿正文与章节正文一致。"
Write-Output "结论：当前数据库可完整还原《龙族Ⅰ-火之晨曦》的章节正文，并已具备章节大纲、章节摘要、导入稿记录、设定、伏笔和关键事件的基础结构化存储。"

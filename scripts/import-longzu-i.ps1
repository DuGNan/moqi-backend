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

function Convert-ToSqlString([string]$Value) {
    if ($null -eq $Value) {
        return "NULL"
    }

    return "'" + $Value.Replace("\", "\\").Replace("'", "''") + "'"
}

function Convert-ToSqlJson($Value) {
    if ($null -eq $Value) {
        return "NULL"
    }

    if ($Value -is [array] -and $Value.Count -eq 0) {
        return "'[]'"
    }

    if ($Value -is [hashtable] -and $Value.Count -eq 0) {
        return "'{}'"
    }

    $json = $Value | ConvertTo-Json -Compress -Depth 8
    return Convert-ToSqlString $json
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

if (!(Test-Path -LiteralPath $SourcePath)) {
    throw "Source file not found: $SourcePath"
}

$sections = Read-BookSections $SourcePath
if ($sections.Count -eq 0) {
    throw "No book sections parsed from $SourcePath"
}

$mysql = Get-MySqlExe
$sqlPath = Join-Path $env:TEMP ("moqi-import-longzu-i-{0}.sql" -f ([guid]::NewGuid().ToString("N")))
$lines = New-Object System.Collections.Generic.List[string]

$lines.Add("SET NAMES utf8mb4;")
$lines.Add("USE ``$Database``;")
$lines.Add("START TRANSACTION;")
$lines.Add("SET @work_title = '龙族Ⅰ-火之晨曦';")
$lines.Add("SELECT id INTO @existing_work_id FROM works WHERE title = @work_title AND deleted = 0 ORDER BY id DESC LIMIT 1;")
$lines.Add("DELETE FROM chapter_generations WHERE work_id = @existing_work_id;")
$lines.Add("DELETE FROM chapter_briefs WHERE work_id = @existing_work_id;")
$lines.Add("DELETE FROM chapter_outlines WHERE work_id = @existing_work_id;")
$lines.Add("DELETE FROM chapter_key_events WHERE work_id = @existing_work_id;")
$lines.Add("DELETE FROM chapter_summaries WHERE work_id = @existing_work_id;")
$lines.Add("DELETE FROM foreshadowing_items WHERE work_id = @existing_work_id;")
$lines.Add("UPDATE setting_candidates SET confirmed_setting_id = NULL WHERE work_id = @existing_work_id;")
$lines.Add("DELETE FROM setting_entries WHERE work_id = @existing_work_id;")
$lines.Add("DELETE FROM setting_candidates WHERE work_id = @existing_work_id;")
$lines.Add("DELETE FROM chapters WHERE work_id = @existing_work_id;")
$lines.Add("DELETE FROM works WHERE id = @existing_work_id;")
$lines.Add("INSERT INTO works (title, status) VALUES (@work_title, 'draft');")
$lines.Add("SET @work_id = LAST_INSERT_ID();")

foreach ($section in $sections) {
    $lines.Add(("INSERT INTO chapters (work_id, title, chapter_no, chapter_type, content, workflow_status) VALUES (@work_id, {0}, {1}, {2}, {3}, 'done');" -f `
        (Convert-ToSqlString $section.Title), $section.No, (Convert-ToSqlString $section.ChapterType), (Convert-ToSqlString $section.Content)))
    $lines.Add(("SET @chapter_{0} = LAST_INSERT_ID();" -f $section.No))
}

foreach ($section in $sections) {
    $plain = ($section.Content -replace "\s+", " ").Trim()
    $summary = if ([string]::IsNullOrWhiteSpace($plain)) {
        "{0}是《龙族Ⅰ-火之晨曦》的结构性章节。" -f $section.Title
    } elseif ($plain.Length -gt 180) {
        $plain.Substring(0, 180)
    } else {
        $plain
    }
    $outline = @{
        goal = "保存并还原章节《$($section.Title)》的原始内容"
        coreConflict = "导入既有小说时不改写正文，只提取可检索结构"
        scenes = @(@{
            id = "scene_1"
            title = $section.Title
            content = $summary
            tags = @("imported_source")
        })
        characterChange = ""
        emotionPace = ""
        constraints = @("不得改写原文", "章节正文以 chapters.content 为唯一事实源")
        openQuestions = @()
        imported = $true
    }
    $basis = @{
        source = "import_longzu_i"
        chapterNo = $section.No
        chapterTitle = $section.Title
        chapterType = $section.ChapterType
    }
    $wordCount = $section.Content.Length

    $lines.Add(("INSERT INTO chapter_outlines (work_id, chapter_id, outline_status, outline_content, revision) VALUES (@work_id, @chapter_{0}, 'confirmed', {1}, 0);" -f `
        $section.No, (Convert-ToSqlJson $outline)))
    $lines.Add("SET @outline_id = LAST_INSERT_ID();")
    $lines.Add(("INSERT INTO chapter_briefs (work_id, chapter_id, brief_status, brief_content) VALUES (@work_id, @chapter_{0}, 'confirmed', {1});" -f `
        $section.No, (Convert-ToSqlString $summary)))
    $lines.Add("SET @brief_id = LAST_INSERT_ID();")
    $lines.Add(("INSERT INTO chapter_generations (work_id, chapter_id, brief_id, outline_id, outline_revision, generation_status, generation_mode, length_preset, basis_snapshot_json, generated_content, word_count) VALUES (@work_id, @chapter_{0}, @brief_id, @outline_id, 0, 'accepted', 'imported_source', 'source_length', {1}, {2}, {3});" -f `
        $section.No, (Convert-ToSqlJson $basis), (Convert-ToSqlString $section.Content), $wordCount))
}

$settings = @(
    @{ Type = "character"; Name = "路明非"; Content = "主角，卡塞尔学院 S 级新生，从普通高中生进入龙族世界。"; Chapter = 4; Aliases = @("明非") },
    @{ Type = "character"; Name = "诺诺"; Content = "陈墨瞳，卡塞尔学院学生，红发，参与引导路明非进入学院并在关键行动中保护他。"; Chapter = 4; Aliases = @("陈墨瞳", "NoNo") },
    @{ Type = "character"; Name = "恺撒"; Content = "学生会主席，加图索家族成员，自由一日核心人物。"; Chapter = 6; Aliases = @("恺撒·加图索") },
    @{ Type = "character"; Name = "楚子航"; Content = "狮心会会长，学院重要战力，与恺撒并列为路明非入学后的主要压力来源。"; Chapter = 6; Aliases = @() },
    @{ Type = "character"; Name = "老唐"; Content = "路明非的星际网友，后续与青铜与火之王诺顿线索发生关联。"; Chapter = 4; Aliases = @() },
    @{ Type = "character"; Name = "康斯坦丁"; Content = "序幕中被呼唤的弟弟，与青铜与火之王相关，是全书重要伏笔。"; Chapter = 3; Aliases = @() },
    @{ Type = "organization"; Name = "卡塞尔学院"; Content = "研究龙族并训练混血种的学院，是路明非进入龙族世界后的核心场域。"; Chapter = 4; Aliases = @() },
    @{ Type = "rule"; Name = "言灵"; Content = "龙族血裔以龙文释放的超自然能力，是血统与战斗能力的重要表现。"; Chapter = 5; Aliases = @() },
    @{ Type = "place"; Name = "青铜城"; Content = "青铜与火之王诺顿的宫殿，位于夔门相关水域，是青铜计划的核心目标。"; Chapter = 7; Aliases = @("白帝城") },
    @{ Type = "item"; Name = "七宗罪"; Content = "与龙王和炼金术相关的重要武器线索。"; Chapter = 13; Aliases = @() }
)

foreach ($setting in $settings) {
    $lines.Add(("INSERT INTO setting_candidates (work_id, chapter_id, source_type, source_id, setting_type, name, content, candidate_status) VALUES (@work_id, @chapter_{0}, 'chapter_content', @chapter_{0}, {1}, {2}, {3}, 'confirmed');" -f `
        $setting.Chapter, (Convert-ToSqlString $setting.Type), (Convert-ToSqlString $setting.Name), (Convert-ToSqlString $setting.Content)))
    $lines.Add("SET @candidate_id = LAST_INSERT_ID();")
    $lines.Add(("INSERT INTO setting_entries (work_id, setting_type, name, aliases_json, content, attributes_json, source_chapter_id, source_candidate_id, entry_status) VALUES (@work_id, {0}, {1}, {2}, {3}, {4}, @chapter_{5}, @candidate_id, 'active');" -f `
        (Convert-ToSqlString $setting.Type), (Convert-ToSqlString $setting.Name), (Convert-ToSqlJson $setting.Aliases), (Convert-ToSqlString $setting.Content), (Convert-ToSqlJson @{ importedFrom = "龙族Ⅰ验证导入" }), $setting.Chapter))
    $lines.Add("SET @setting_id = LAST_INSERT_ID();")
    $lines.Add("UPDATE setting_candidates SET confirmed_setting_id = @setting_id WHERE id = @candidate_id;")
}

$foreshadowing = @(
    @{ Title = "康斯坦丁呼唤哥哥"; Description = "序幕中康斯坦丁与哥哥的对话埋下青铜与火之王兄弟关系线。"; Chapter = 3; Status = "paid_off"; Payoff = 11 },
    @{ Title = "老唐真实身份"; Description = "开篇星际网友老唐后续与诺顿线索连接，是跨章节身份反转伏笔。"; Chapter = 4; Status = "pending_payoff"; Payoff = 11 },
    @{ Title = "路明非 S 级血统"; Description = "路明非被评为 S 级但能力表现异常，支撑后续主角身份谜团。"; Chapter = 5; Status = "pending_payoff"; Payoff = $null },
    @{ Title = "青铜城与白帝城"; Description = "序幕白帝城和水下青铜城互相照应，连接古代献祭与现代青铜计划。"; Chapter = 3; Status = "paid_off"; Payoff = 12 }
)

foreach ($item in $foreshadowing) {
    $payoff = if ($null -eq $item.Payoff) { "NULL" } else { "@chapter_$($item.Payoff)" }
    $lines.Add(("INSERT INTO foreshadowing_items (work_id, source_chapter_id, title, description, source_text, status, expected_payoff_chapter_id, actual_payoff_chapter_id) VALUES (@work_id, @chapter_{0}, {1}, {2}, NULL, {3}, {4}, {4});" -f `
        $item.Chapter, (Convert-ToSqlString $item.Title), (Convert-ToSqlString $item.Description), (Convert-ToSqlString $item.Status), $payoff))
}

foreach ($section in $sections) {
    $summary = if ([string]::IsNullOrWhiteSpace($section.Content)) {
        "{0}是《龙族Ⅰ-火之晨曦》的结构性章节。" -f $section.Title
    } else {
        $plain = ($section.Content -replace "\s+", " ").Trim()
        if ($plain.Length -gt 180) { $plain.Substring(0, 180) } else { $plain }
    }
    $openQuestions = @()
    if ($section.Title -eq "序幕-白帝城") { $openQuestions = @("哥哥身份与康斯坦丁关系如何回收") }
    if ($section.Title -eq "卡塞尔之门") { $openQuestions = @("卡塞尔学院为何选择路明非", "老唐身份是否只是网友") }
    $lines.Add(("INSERT INTO chapter_summaries (work_id, chapter_id, summary, character_changes_json, new_settings_json, new_foreshadowing_json, open_questions_json, summary_status, content_revision) VALUES (@work_id, @chapter_{0}, {1}, {2}, {3}, {4}, {5}, 'confirmed', 0);" -f `
        $section.No, (Convert-ToSqlString $summary), (Convert-ToSqlJson @()), (Convert-ToSqlJson @()), (Convert-ToSqlJson @()), (Convert-ToSqlJson $openQuestions)))
    $lines.Add(("INSERT INTO chapter_key_events (work_id, chapter_id, event_title, event_content, event_type, occurred_order, related_setting_ids_json, related_foreshadowing_ids_json) VALUES (@work_id, @chapter_{0}, {1}, {2}, 'plot', 1, {3}, {4});" -f `
        $section.No, (Convert-ToSqlString ("记录章节：" + $section.Title)), (Convert-ToSqlString $summary), (Convert-ToSqlJson @()), (Convert-ToSqlJson @())))
}

$lines.Add("COMMIT;")
$lines.Add("SELECT @work_id AS imported_work_id, COUNT(*) AS chapter_count FROM chapters WHERE work_id = @work_id;")

Set-Content -LiteralPath $sqlPath -Value $lines -Encoding UTF8

$args = @("--default-character-set=utf8mb4", "-h", $HostName, "-P", $Port.ToString(), "-u", $User)
if ($Password -ne "") {
    $args += "-p$Password"
}
$args += $Database
$args += "-e"
$args += "source $($sqlPath.Replace('\', '/'))"

try {
    & $mysql @args
    if ($LASTEXITCODE -ne 0) {
        throw "mysql import failed with exit code $LASTEXITCODE"
    }
    Write-Output "Imported 龙族Ⅰ-火之晨曦 with $($sections.Count) sections."
}
finally {
    Remove-Item -LiteralPath $sqlPath -Force -ErrorAction SilentlyContinue
}

#Requires -Version 7.2

[CmdletBinding()]
param(
    [Parameter(Mandatory)] [uri] $ApiBaseUrl,
    [Parameter(Mandatory)] [long] $WorkId,
    [Parameter(Mandatory)] [long] $ChapterId,
    [Parameter(Mandatory)] [int] $ScenePlanNo,
    [Parameter(Mandatory)] [long] $CapacityAssessmentId,
    [Parameter(Mandatory)] [string] $OutputDirectory,
    [ValidateRange(6, 100)] [int] $SampleCount = 6,
    [ValidateRange(6, 200)] [int] $MaxAttempts = 12,
    [ValidateSet('short', 'medium', 'long', 'custom')] [string] $LengthPreset = 'custom',
    [ValidateRange(1, 200000)] [int] $CustomWordCount = 3000,
    [ValidateRange(0.0, 2.0)] [double] $Temperature = 0.7,
    [ValidateSet('', 'continue_long_chapter')] [string] $CapacityDecision = '',
    [ValidateRange(1, 300)] [int] $PollIntervalSeconds = 5,
    [ValidateRange(30, 14400)] [int] $OperationTimeoutSeconds = 1800
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Join-ApiUri {
    param([string] $PathAndQuery)
    return [uri]::new($ApiBaseUrl, $PathAndQuery)
}

function Save-Json {
    param([Parameter(Mandatory)] $Value, [Parameter(Mandatory)] [string] $Path)
    $parent = Split-Path -Parent $Path
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $temporary = "$Path.tmp"
    $Value | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $temporary -Encoding utf8NoBOM
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Invoke-MoqiApi {
    param(
        [Parameter(Mandatory)] [ValidateSet('GET', 'POST')] [string] $Method,
        [Parameter(Mandatory)] [string] $Path,
        $Body,
        [string] $EvidencePath
    )
    $parameters = @{ Method = $Method; Uri = Join-ApiUri $Path; ContentType = 'application/json; charset=utf-8' }
    if ($null -ne $Body) { $parameters.Body = ($Body | ConvertTo-Json -Depth 30 -Compress) }
    try {
        $response = Invoke-RestMethod @parameters
        if ($EvidencePath) { Save-Json $response $EvidencePath }
        if ($response.code -ne 'SUCCESS') { throw "Moqi API returned $($response.code): $($response.message)" }
        return $response.data
    } catch {
        if ($EvidencePath) {
            Save-Json ([ordered]@{ failedAt = (Get-Date).ToString('o'); method = $Method; path = $Path; error = $_.Exception.Message }) "$EvidencePath.error.json"
        }
        throw
    }
}

function Get-Sha256 {
    param([Parameter(Mandatory)] [string] $Text)
    $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

function Wait-MoqiResource {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $StatusProperty,
        [Parameter(Mandatory)] [string[]] $TerminalStatuses,
        [Parameter(Mandatory)] [string] $EvidencePath
    )
    $deadline = (Get-Date).AddSeconds($OperationTimeoutSeconds)
    do {
        $value = Invoke-MoqiApi GET $Path $null $EvidencePath
        $status = [string]$value.$StatusProperty
        if ($TerminalStatuses -contains $status) { return $value }
        Start-Sleep -Seconds $PollIntervalSeconds
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Path; last $StatusProperty=$status"
}

function New-Scorecard {
    param([int] $SampleNumber, [long] $GenerationId, [string] $Path)
    if (Test-Path -LiteralPath $Path) { return }
    $dimensions = @('开场定位','因果链','人物主动性','技术过程','冲突推进','名词介绍','连续性','场景描写','对话质量','结尾牵引') |
        ForEach-Object { [ordered]@{ name = $_; codexDraftScore = $null; userScore = $null; note = '' } }
    Save-Json ([ordered]@{
        schemaVersion = 1; sampleNumber = $SampleNumber; generationId = $GenerationId
        structuralBlockers = @(); dimensions = @($dimensions)
        codexDraftCompleted = $false; userConfirmed = $false; userConfirmedAt = $null
    }) $Path
}

$resolvedOutput = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $resolvedOutput | Out-Null
foreach ($name in @('raw','samples','database','browser','scorecards','summary')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $resolvedOutput $name) | Out-Null
}

$manifestPath = Join-Path $resolvedOutput 'run-manifest.json'
$baselineDir = Join-Path $resolvedOutput 'raw\baseline'
$model = Invoke-MoqiApi GET '/api/system/model-status' $null (Join-Path $baselineDir 'model-status.json')
if (-not $model.configured -or -not $model.available) { throw 'Provider/model is not configured and available.' }
$brief = Invoke-MoqiApi GET "/api/chapters/$ChapterId/generation-brief-preview?scenePlanNo=$ScenePlanNo" $null (Join-Path $baselineDir 'brief.json')
$capacity = Invoke-MoqiApi GET "/api/chapter-capacity-assessments/$CapacityAssessmentId" $null (Join-Path $baselineDir 'capacity.json')
if ([long]$capacity.workId -ne $WorkId -or [long]$capacity.chapterId -ne $ChapterId -or [int]$capacity.scenePlanNo -ne $ScenePlanNo) {
    throw 'Capacity assessment does not belong to the requested work/chapter/scene plan.'
}
if ($capacity.status -ne 'ready') { throw "Capacity assessment is not ready: $($capacity.status)" }
if ($capacity.result.status -eq 'requires_long_context') { throw 'Real Provider requires long context. Stop this batch and record evidence in #112.' }
if ($capacity.result.status -eq 'too_dense' -and $CapacityDecision -ne 'continue_long_chapter') {
    throw 'Capacity result is too_dense. This runner will not split a chapter or infer user intent.'
}

$configuration = [ordered]@{
    apiBaseUrl = $ApiBaseUrl.AbsoluteUri.TrimEnd('/'); workId = $WorkId; chapterId = $ChapterId
    scenePlanNo = $ScenePlanNo; capacityAssessmentId = $CapacityAssessmentId
    capacityInputFingerprint = $capacity.inputFingerprint; capacityBriefFingerprint = $capacity.briefFingerprint
    briefTemplateVersion = $brief.templateVersion; briefFingerprint = $brief.fingerprint
    provider = $model.provider; model = $model.activeModel; modelConfigVersion = $model.configVersion
    lengthPreset = $LengthPreset; customWordCount = $CustomWordCount; temperature = $Temperature
    capacityDecision = $CapacityDecision
}
$configurationJson = $configuration | ConvertTo-Json -Depth 20 -Compress
$configurationFingerprint = Get-Sha256 $configurationJson

if (Test-Path -LiteralPath $manifestPath) {
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -AsHashtable
    if ($manifest.configurationFingerprint -ne $configurationFingerprint) {
        throw "Configuration drift detected. Existing=$($manifest.configurationFingerprint), current=$configurationFingerprint"
    }
} else {
    $manifest = [ordered]@{
        schemaVersion = 1; issue = 108; createdAt = (Get-Date).ToString('o'); updatedAt = (Get-Date).ToString('o')
        targetSampleCount = $SampleCount; maxAttempts = $MaxAttempts
        configurationFingerprint = $configurationFingerprint; configuration = $configuration
        attempts = @(); completedSampleCount = 0; humanScoringComplete = $false
    }
    Save-Json $manifest $manifestPath
}

$completed = [int]$manifest.completedSampleCount
$nextAttempt = @($manifest.attempts).Count + 1
while ($completed -lt $SampleCount -and $nextAttempt -le $MaxAttempts) {
    $currentModel = Invoke-MoqiApi GET '/api/system/model-status' $null $null
    $currentBrief = Invoke-MoqiApi GET "/api/chapters/$ChapterId/generation-brief-preview?scenePlanNo=$ScenePlanNo" $null $null
    $currentCapacity = Invoke-MoqiApi GET "/api/chapter-capacity-assessments/$CapacityAssessmentId" $null $null
    $configurationStillMatches = @(
        $currentModel.provider -eq $configuration.provider
        $currentModel.activeModel -eq $configuration.model
        $currentModel.configVersion -eq $configuration.modelConfigVersion
        $currentBrief.templateVersion -eq $configuration.briefTemplateVersion
        $currentBrief.fingerprint -eq $configuration.briefFingerprint
        $currentCapacity.inputFingerprint -eq $configuration.capacityInputFingerprint
        $currentCapacity.briefFingerprint -eq $configuration.capacityBriefFingerprint
    ) -notcontains $false
    if (-not $configurationStillMatches) {
        throw 'Configuration or source fingerprint drift detected during the batch.'
    }
    $attemptNumber = $nextAttempt
    $attemptKey = "golden-108-$($configurationFingerprint.Substring(0, 16))-attempt-$('{0:d3}' -f $attemptNumber)"
    $attemptDir = Join-Path $resolvedOutput ("raw\attempt-{0:d3}" -f $attemptNumber)
    New-Item -ItemType Directory -Force -Path $attemptDir | Out-Null
    $attempt = [ordered]@{ attemptNumber = $attemptNumber; idempotencyKey = $attemptKey; startedAt = (Get-Date).ToString('o'); status = 'running' }
    try {
        $created = Invoke-MoqiApi POST "/api/chapters/$ChapterId/generations" ([ordered]@{
            scenePlanNo = $ScenePlanNo; selectionMode = 'all'; sceneKeys = @(); baseGenerationId = $null
            idempotencyKey = $attemptKey; lengthPreset = $LengthPreset; customWordCount = $CustomWordCount
            temperature = $Temperature; capacityAssessmentId = $CapacityAssessmentId
            capacityDecision = $(if ($CapacityDecision) { $CapacityDecision } else { $null }); includeCurrentContent = $false
        }) (Join-Path $attemptDir 'generation-created.json')
        $attempt.generationId = [long]$created.generationId
        $generation = Wait-MoqiResource "/api/generations/$($created.generationId)" generationStatus @('preview','failed','canceled','rejected','accepted') (Join-Path $attemptDir 'generation.json')
        $attempt.generationStatus = $generation.generationStatus
        if ($generation.generationStatus -ne 'preview' -or [string]::IsNullOrWhiteSpace([string]$generation.generatedContent)) {
            $attempt.status = 'generation_failed'; $attempt.error = "terminal status=$($generation.generationStatus)"
        } else {
            $completed++
            $sampleDir = Join-Path $resolvedOutput ("samples\sample-{0:d2}" -f $completed)
            New-Item -ItemType Directory -Force -Path $sampleDir | Out-Null
            $generation.generatedContent | Set-Content -LiteralPath (Join-Path $sampleDir 'original.txt') -Encoding utf8NoBOM
            Save-Json $generation (Join-Path $sampleDir 'generation.json')
            $attempt.sampleNumber = $completed
            New-Scorecard $completed ([long]$created.generationId) (Join-Path $resolvedOutput ("scorecards\sample-{0:d2}.json" -f $completed))

            $evaluation = Invoke-MoqiApi POST "/api/chapters/$ChapterId/generations/$($created.generationId)/evaluation-reports" ([ordered]@{
                generationSceneId = $null; idempotencyKey = "$attemptKey-evaluation"
            }) (Join-Path $attemptDir 'evaluation-created.json')
            $evaluation = Wait-MoqiResource "/api/chapters/$ChapterId/generations/$($created.generationId)/evaluation-reports/$($evaluation.id)" reportStatus @('ready','failed','canceled') (Join-Path $attemptDir 'evaluation.json')
            Save-Json $evaluation (Join-Path $sampleDir 'evaluation.json')
            $attempt.evaluationReportId = [long]$evaluation.id; $attempt.evaluationStatus = $evaluation.reportStatus
            $attempt.evaluationConclusion = $evaluation.conclusion

            if ($evaluation.reportStatus -eq 'ready' -and $evaluation.conclusion -eq 'needs_revision') {
                $revision = Invoke-MoqiApi POST "/api/chapters/$ChapterId/generations/$($created.generationId)/bounded-revisions" ([ordered]@{
                    evaluationReportId = $evaluation.id; idempotencyKey = "$attemptKey-bounded-revision"
                }) (Join-Path $attemptDir 'revision-created.json')
                $revision = Wait-MoqiResource "/api/chapters/$ChapterId/generations/$($created.generationId)/bounded-revisions/$($revision.id)" revisionStatus @('candidate_ready','needs_human','failed','canceled') (Join-Path $attemptDir 'revision.json')
                Save-Json $revision (Join-Path $sampleDir 'revision.json')
                $attempt.boundedRevisionId = [long]$revision.id; $attempt.revisionStatus = $revision.revisionStatus
                if ($null -ne $revision.resultGenerationId) {
                    $revised = Invoke-MoqiApi GET "/api/generations/$($revision.resultGenerationId)" $null (Join-Path $attemptDir 'revised-generation.json')
                    if (-not [string]::IsNullOrWhiteSpace([string]$revised.generatedContent)) {
                        $revised.generatedContent | Set-Content -LiteralPath (Join-Path $sampleDir 'revised.txt') -Encoding utf8NoBOM
                    }
                    if ($null -ne $revision.resultReportId) {
                        $reevaluation = Invoke-MoqiApi GET "/api/chapters/$ChapterId/generations/$($revision.resultGenerationId)/evaluation-reports/$($revision.resultReportId)" $null (Join-Path $attemptDir 'reevaluation.json')
                        Save-Json $reevaluation (Join-Path $sampleDir 'reevaluation.json')
                    }
                }
            }
            $attempt.status = 'sample_recorded'
        }
    } catch {
        $attempt.status = 'failed'; $attempt.error = $_.Exception.Message
    } finally {
        try {
            $calls = Invoke-MoqiApi GET "/api/llm-calls?workId=$WorkId&chapterId=$ChapterId&page=1&pageSize=100" $null (Join-Path $attemptDir 'llm-calls.json')
            $attempt.llmCallEvidenceCount = @($calls.items).Count
        } catch { $attempt.llmCallEvidenceError = $_.Exception.Message }
        $attempt.finishedAt = (Get-Date).ToString('o')
        $manifest.attempts = @($manifest.attempts) + @($attempt)
        $manifest.completedSampleCount = $completed; $manifest.updatedAt = (Get-Date).ToString('o')
        Save-Json $manifest $manifestPath
    }
    $nextAttempt++
}

Save-Json ([ordered]@{
    configurationFingerprint = $configurationFingerprint; completedSampleCount = $completed
    targetSampleCount = $SampleCount; attemptCount = @($manifest.attempts).Count
    passedCollectionGate = ($completed -ge $SampleCount); humanScoringComplete = $false
    note = 'Codex scoring drafts and user confirmation are separate follow-up steps.'
}) (Join-Path $resolvedOutput 'summary\collection-summary.json')

if ($completed -lt $SampleCount) { throw "Collected $completed/$SampleCount complete prose samples after $(@($manifest.attempts).Count) attempts." }
Write-Host "Golden Case collection complete: $completed samples. Human scoring remains unconfirmed."

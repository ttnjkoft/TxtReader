# 發版一鍵包：版號+1 → build → commit+push → GitHub release+上傳APK
# 用法: .\release.bat <版號> [說明]
# 例如: .\release.bat 1.1 "修掉缺字bug"
# 前提: github-token.txt 放你的 PAT 第一行（只在這台電腦，絕不進版控）
param(
    [string]$VersionName,
    [string]$Notes = "更新"
)
$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($VersionName)) {
    Write-Output "用法: .\release.bat <版號> [說明]"
    Write-Output "例如: .\release.bat 1.1 `"修掉缺字bug`""
    exit 1
}

function Read-TextKeepBom([string]$path) {
    $bytes = [IO.File]::ReadAllBytes($path)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        return @{ Text = [Text.Encoding]::UTF8.GetString($bytes, 3, $bytes.Length - 3); Bom = $true }
    }
    return @{ Text = [Text.Encoding]::UTF8.GetString($bytes); Bom = $false }
}

function Write-TextKeepBom([string]$path, [string]$text, [bool]$bom) {
    $enc = New-Object Text.UTF8Encoding($bom)
    [IO.File]::WriteAllText($path, $text, $enc)
}

$gradleFile = "app\build.gradle.kts"
$vf = "version.json"
Copy-Item -LiteralPath $gradleFile "$gradleFile.bak"
Copy-Item -LiteralPath $vf "$vf.bak"
try {
    # 1) 版號 +1、版名換新
    $g = Read-TextKeepBom $gradleFile
    $m = [regex]::Match($g.Text, 'versionCode\s*=\s*(\d+)')
    if (-not $m.Success) { throw "找不到 versionCode" }
    $newCode = [int]$m.Groups[1].Value + 1
    $t = [regex]::Replace($g.Text, 'versionCode\s*=\s*\d+', "versionCode = $newCode")
    $t = [regex]::Replace($t, 'versionName\s*=\s*"[^"]*"', "versionName = `"$VersionName`"")
    Write-TextKeepBom $gradleFile $t $g.Bom
    Write-Output "版號: $newCode / $VersionName"

    $v = Read-TextKeepBom $vf
    $jo = $v.Text | ConvertFrom-Json
    $jo.versionCode = $newCode
    $jo.versionName = $VersionName
    $jo.apkUrl = "https://github.com/ttnjkoft/TxtReader/releases/download/v$VersionName/TxtReader.apk"
    $jo.notes = $Notes
    Write-TextKeepBom $vf (($jo | ConvertTo-Json) + "`n") $v.Bom

    # 2) build（失敗就還原兩個檔，不留殘局）
    & "$PSScriptRoot\gradlew.bat" assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw "build 失敗" }
    $apk = "app\build\outputs\apk\release\TxtReader.apk"
    if (-not (Test-Path -LiteralPath $apk)) { throw "找不到 $apk" }

    Remove-Item -LiteralPath "$gradleFile.bak", "$vf.bak"

    # 3) commit + push
    git add -A
    git commit -m "發版 v$VersionName：$Notes"
    git push origin master

    # 4) GitHub release + 上傳 APK（冪等：release 已存在就跳過建立，同名 asset 先刪再傳）
    $tokenFile = "github-token.txt"
    if (-not (Test-Path -LiteralPath $tokenFile)) { throw "缺 github-token.txt（把 PAT 貼在第一行）" }
    $token = (Get-Content $tokenFile -TotalCount 1).Trim()
    if ([string]::IsNullOrWhiteSpace($token)) { throw "github-token.txt 是空的" }
    $headers = @{ Authorization = "Bearer $token"; Accept = "application/vnd.github+json" }
    $rel = $null
    try {
        $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/ttnjkoft/TxtReader/releases/tags/v$VersionName" -Headers $headers
        Write-Output "release v$VersionName 已存在，跳過建立"
    } catch {
        $relBody = @{ tag_name = "v$VersionName"; name = "v$VersionName"; body = $Notes } | ConvertTo-Json
        try {
            $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/ttnjkoft/TxtReader/releases" -Method Post -Headers $headers -Body $relBody -ContentType "application/json"
        } catch {
            throw "建 release 失敗：$($_.Exception.Message)"
        }
    }
    foreach ($d in @($rel.assets) | Where-Object { $_.name -eq "TxtReader.apk" }) {
        Invoke-RestMethod -Uri ("https://api.github.com/repos/ttnjkoft/TxtReader/releases/assets/" + $d.id) -Method Delete -Headers $headers | Out-Null
        Write-Output "刪掉舊的同名 APK 再傳"
    }
    $uploadUrl = ($rel.upload_url -replace '\{\?name,label\}', "?name=TxtReader.apk")
    Invoke-RestMethod -Uri $uploadUrl -Method Post -Headers @{ Authorization = "Bearer $token" } -ContentType "application/vnd.android.package-archive" -InFile $apk | Out-Null
    Write-Output "完成：v$VersionName 已發佈，手機明天自動提示（或按檢查更新）"
} catch {
    if (Test-Path -LiteralPath "$gradleFile.bak") { Move-Item -LiteralPath "$gradleFile.bak" $gradleFile -Force }
    if (Test-Path -LiteralPath "$vf.bak") { Move-Item -LiteralPath "$vf.bak" $vf -Force }
    Write-Output "失敗已還原：$($_.Exception.Message)"
    exit 1
}

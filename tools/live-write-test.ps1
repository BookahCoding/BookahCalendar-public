<#
.SYNOPSIS
  Runs the live write test of BookahCalendar against the owner's real Nextcloud, inside a throw-away calendar. NEEDS THE OWNER'S YES each time.

.DESCRIPTION
  Run on the build PC in Windows PowerShell 5.1:
    powershell -NoProfile -ExecutionPolicy Bypass -File D:\Projects\BookahCalendar\tools\live-write-test.ps1 -OwnerApproved

  What it does: reads the server address from $env:BOOKAH_PRIVATE_DIR\config.json and the user name and app password from the
  Windows Credential Manager (BookahCalendar-nextcloud), hands them to the Gradle test only as environment variables of that one process,
  and runs `LiveWriteTest`. The test makes a calendar named bookahtest-<random>, creates, edits, conflicts and deletes one event in it,
  then deletes the calendar and its trash bin entries and checks that nothing is left. Personal and every other calendar are never touched.
  Nothing secret is printed: the test prints step names and HTTP status codes only.

  -Series runs the repeating-events cycle (LiveSeriesTest). -Tasks runs the task tick cycle (LiveTaskTest): it makes a throw-away TASK list
  bookahtest-<random> (VTODO only), creates one plain task with a raw request, ticks it, undoes the tick, checks a wrong ETag gets 412 and
  that a change elsewhere gives a conflict with nothing written, then removes the list for good (X-NC-CalDAV-No-Trashbin) and checks 0 left.
#>
param([switch]$OwnerApproved, [switch]$Series, [switch]$Tasks)
$ErrorActionPreference = "Stop"
if (-not $OwnerApproved) { throw "This writes (and removes) a test calendar on the real Nextcloud: run again with -OwnerApproved after the owner has said yes." }
. "$PSScriptRoot\..\spike\Cred.ps1"
if (-not $env:BOOKAH_PRIVATE_DIR) { throw "Set the environment variable BOOKAH_PRIVATE_DIR to the folder that holds config.json (outside Git)." }
$cfg = Get-Content -Raw -LiteralPath (Join-Path $env:BOOKAH_PRIVATE_DIR "config.json") | ConvertFrom-Json
$cred = Get-BookahSecret "nextcloud"
$env:BOOKAH_LIVE_SERVER = $cfg.base.TrimEnd('/')
$env:BOOKAH_LIVE_USER = $cred.User
$env:BOOKAH_LIVE_PASS = $cred.Secret
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
Push-Location (Join-Path $PSScriptRoot "..\android")
try {
    # -Series runs the repeating-events cycle (phase 4) instead of the plain-event cycle (phase 3)
    # -Tasks runs the task tick and undo cycle (phase 4, tasks slice 2)
    $testClass = if ($Tasks) { "dev.bookah.calendar.data.LiveTaskTest" } elseif ($Series) { "dev.bookah.calendar.data.LiveSeriesTest" } else { "dev.bookah.calendar.data.LiveWriteTest" }
    & .\gradlew.bat --no-daemon :core-data:testDebugUnitTest --tests $testClass -i 2>&1 | Select-String -Pattern "^\s*LIVE |FAILED|BUILD (SUCCESSFUL|FAILED)|AssertionError" | ForEach-Object { $_.Line.Trim() }
    Write-Host "gradle exit code: $LASTEXITCODE"
} finally {
    Pop-Location
    Remove-Item Env:BOOKAH_LIVE_SERVER, Env:BOOKAH_LIVE_USER, Env:BOOKAH_LIVE_PASS -ErrorAction SilentlyContinue
}

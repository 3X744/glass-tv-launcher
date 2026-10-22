# Undoes the Glass TV setup on a Fire TV Stick: re-enables the debloated packages and gives Home back to Amazon.
# Tested on a Fire TV Stick 4K Max (2nd gen, Fire OS 8.1). Pass your stick's address with -Stick or set FIRETV_IP.
# Usage:  .\firestick-restore.ps1 -Stick 192.168.1.50  (re-enable everything)
#         .\firestick-restore.ps1 com.amazon.tahoe     (re-enable just the packages you name)
#         .\firestick-restore.ps1 -Dns                 (only turn off AdGuard Private DNS, back to the default)
#         .\firestick-restore.ps1 -Glass               (only give Home back to Amazon: Glass's Home service off)
# Needs ADB Debugging on (Settings > My Fire TV > Developer Options) and adb on PATH.
param([string[]]$Only, [string]$Stick = $env:FIRETV_IP, [switch]$Dns, [switch]$Glass, [switch]$Screensaver)

if (-not $Stick) { throw 'No Fire TV address. Pass -Stick <ip> or set FIRETV_IP.' }
if ($Stick -notmatch ':') { $Stick = "${Stick}:5555" }

$Disabled = @(
  # Telemetry and tracking
  'com.amazon.tv.acr', 'com.amazon.hybridadidservice', 'com.amazon.device.telemetry.emitter', 'com.amazon.dp.logger',
  'com.amazon.wirelessmetrics.service', 'com.amazon.perfcollection', 'com.amazon.neo.minerva.logcat',
  'com.amazon.client.metrics.api', 'com.amazon.neo.minerva', 'com.amazon.perfc', 'com.amazon.ceviche',
  'com.amazon.tv.developer.dataservice', 'com.amazon.livedeviceservice', 'com.amazon.privacypassservice',
  'com.amazon.wifilocker', 'com.amazon.ftvspatial',
  # Ads, promos, recommendations
  'com.amazon.kso.blackbird', 'com.amazon.sneakpeek', 'com.amazon.media.recommendations',
  'com.amazon.providers.contentsupport', 'com.amazon.tv.easyupgrade', 'com.amazon.shoptv.client',
  'com.amazon.shoptv.firetv.client', 'com.amazon.hedwig', 'com.amazon.tv.livetv', 'com.amazon.franktvinput',
  'com.amazon.systemnotices', 'com.amazon.tv.releasenotes', 'com.amazon.uxnotification', 'com.amazon.tv.earlyaccess',
  # Preloaded apps and setup helpers
  'com.amazon.gamehub', 'com.amazon.bueller.music', 'com.amazon.bueller.photos', 'com.amazon.tahoe',
  'com.amazon.minitv.android.app', 'com.amazon.tmm.tutorial', 'com.amazon.storm.lightning.tutorial',
  'com.amazon.whisperjoin.middleware.v2.np', 'com.amazon.tv.ffsprovisioneeclient', 'com.amazon.ods.kindleconnect',
  'com.amazon.tv.ftvambient'
)

adb connect $Stick | Out-Null

# Fire TV Glass (com.ivantrj.glasslauncher) takes Home through an accessibility service; nothing else used one.
if ($Glass -or (-not $Only -and -not $Dns -and -not $Screensaver)) {
  adb -s $Stick shell settings delete secure enabled_accessibility_services | Out-Null
  adb -s $Stick shell settings put secure accessibility_enabled 0
  # Notification access for the Now Playing pill
  adb -s $Stick shell cmd notification disallow_listener com.ivantrj.glasslauncher/com.ivantrj.glasslauncher.MediaListener
  "Glass Home button and Now Playing access => off (Home opens Amazon's launcher; uninstall Glass in Settings > Applications if wanted)"
  if ($Glass) { return }
}

# Amazon's screensaver (com.amazon.ftv.screensaver) is set to Never during setup so Glass's koi pond takes over. Picking Never in Fire TV Settings also sets the sleep timeout to
# 2147460000 ms (never sleep); this puts it back to 900000 (15 min).
if ($Screensaver -or (-not $Only -and -not $Dns -and -not $Glass)) {
  adb -s $Stick shell settings put secure screensaver_enabled 1
  adb -s $Stick shell settings put system screen_off_timeout 900000
  adb -s $Stick shell settings put secure contextual_screen_off_timeout 900000
  "Amazon screensaver => on (Start Time 15 minutes)"
  if ($Screensaver) { return }
}

# If you tried AdGuard Private DNS (dns.adguard-dns.com), this puts Private DNS back to the default.
if ($Dns -or (-not $Only -and -not $Glass -and -not $Screensaver)) {
  adb -s $Stick shell settings delete global private_dns_specifier | Out-Null
  adb -s $Stick shell settings put global private_dns_mode opportunistic
  "Private DNS => default (opportunistic)"
  if ($Dns) { return }
}

$targets = if ($Only) { $Only } else { $Disabled }
foreach ($p in $targets) {
  $r = (cmd /c "adb -s $Stick shell pm enable $p 2>&1") | Select-Object -First 1
  "$p => $r"
}

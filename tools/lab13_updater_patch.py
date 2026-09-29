from pathlib import Path

# Updater installabile: MusicLab, non Meld.
p = Path('app/src/main/kotlin/com/metrolist/music/utils/Updater.kt')
s = p.read_text()
old = 'private const val GITHUB_API_BASE = "https://api.github.com/repos/FrancescoGrazioso/Meld"'
new = 'private const val GITHUB_API_BASE = "https://api.github.com/repos/Br174/MusicLab"'
if old not in s:
    raise SystemExit('Updater base Meld non trovata')
s = s.replace(old, new, 1)
s = s.replace('name == "Meld.apk" -> "universal" to "foss"', 'name == "Meld.apk" || name == "MusicLab.apk" -> "universal" to "foss"', 1)
s = s.replace('name == "Meld-with-Google-Cast.apk" -> "universal" to "gms"', 'name == "Meld-with-Google-Cast.apk" || name == "MusicLab-with-Google-Cast.apk" -> "universal" to "gms"', 1)
p.write_text(s)

# Versioning MusicLab indipendente dalla baseline Meld.
p = Path('app/build.gradle.kts')
s = p.read_text()
anchor = 'val appNameOverride = System.getenv("METROLIST_APP_NAME")?.takeIf { it.isNotBlank() }\n'
if anchor not in s:
    raise SystemExit('Anchor appName non trovato')
s = s.replace(anchor, anchor + '''val musicLabVersionCodeOverride = System.getenv("MUSICLAB_VERSION_CODE")?.toIntOrNull()\nval musicLabVersionNameOverride = System.getenv("MUSICLAB_VERSION_NAME")?.takeIf { it.isNotBlank() }\nval meldBaseVersion = System.getenv("MELD_BASE_VERSION")?.takeIf { it.isNotBlank() } ?: "0.8.9"\n''', 1)
s = s.replace('        versionCode = 25\n        versionName = "0.8.9"\n', '        versionCode = musicLabVersionCodeOverride ?: 25\n        versionName = musicLabVersionNameOverride ?: "0.8.9"\n', 1)
anchor = '        buildConfigField("String", "ARCHITECTURE", "\\\"universal\\\"")\n'
if anchor not in s:
    raise SystemExit('Anchor BuildConfig ARCHITECTURE non trovato')
s = s.replace(anchor, anchor + '        buildConfigField("String", "MELD_BASE_VERSION", "\\\"$meldBaseVersion\\\"")\n', 1)
p.write_text(s)

# Ricorda l'ultima release Meld notificata, per evitare spam.
p = Path('app/src/main/kotlin/com/metrolist/music/constants/PreferenceKeys.kt')
s = p.read_text()
anchor = 'val LastUpdateCheckTimeKey = longPreferencesKey("lastUpdateCheckTime")\n'
if anchor not in s:
    raise SystemExit('Anchor preference updater non trovato')
s = s.replace(anchor, anchor + 'val LastMeldUpstreamNotifiedTagKey = stringPreferencesKey("lastMeldUpstreamNotifiedTag")\n', 1)
p.write_text(s)

# MainActivity: MusicLab installabile + Meld solo segnalazione upstream.
p = Path('app/src/main/kotlin/com/metrolist/music/MainActivity.kt')
s = p.read_text()
anchor = 'import com.metrolist.music.constants.UpdateNotificationsEnabledKey\n'
if anchor not in s:
    raise SystemExit('Import UpdateNotificationsEnabledKey non trovato')
s = s.replace(anchor, anchor + 'import com.metrolist.music.constants.LastMeldUpstreamNotifiedTagKey\n', 1)
anchor = 'import com.metrolist.music.utils.Updater\n'
if anchor not in s:
    raise SystemExit('Import Updater non trovato')
s = s.replace(anchor, 'import com.metrolist.music.utils.MeldUpstreamMonitor\n' + anchor, 1)

old = '''                        Updater.checkForUpdate().onSuccess { (releaseInfo, hasUpdate) ->
                            if (releaseInfo != null) {
                                onLatestVersionNameChange(releaseInfo.versionName)
                                if (hasUpdate && notifEnabled) {
                                    val downloadUrl = Updater.getDownloadUrlForCurrentVariant(releaseInfo)
                                    if (downloadUrl != null) {
                                        val intent = Intent(Intent.ACTION_VIEW, downloadUrl.toUri())

                                        val flags =
                                            PendingIntent.FLAG_UPDATE_CURRENT or
                                                (PendingIntent.FLAG_IMMUTABLE)
                                        val pending = PendingIntent.getActivity(this@MainActivity, 1001, intent, flags)

                                        val notif =
                                            NotificationCompat
                                                .Builder(this@MainActivity, "updates")
                                                .setSmallIcon(R.drawable.update)
                                                .setContentTitle(getString(R.string.update_available_title))
                                                .setContentText(releaseInfo.versionName)
                                                .setContentIntent(pending)
                                                .setAutoCancel(true)
                                                .build()

                                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) ==
                                            PackageManager.PERMISSION_GRANTED
                                        ) {
                                            NotificationManagerCompat.from(this@MainActivity).notify(1001, notif)
                                        }
                                    }
                                }
                            }
                        }
'''
if old not in s:
    raise SystemExit('Blocco updater MainActivity non trovato')
new = old + '''
                        MeldUpstreamMonitor.checkForUpdate(BuildConfig.MELD_BASE_VERSION).onSuccess { (upstream, hasUpstreamUpdate) ->
                            if (upstream != null && hasUpstreamUpdate && notifEnabled) {
                                val lastNotifiedTag = dataStore.data.first()[LastMeldUpstreamNotifiedTagKey].orEmpty()
                                if (lastNotifiedTag != upstream.tagName && upstream.releaseUrl.isNotBlank()) {
                                    val intent = Intent(Intent.ACTION_VIEW, upstream.releaseUrl.toUri())
                                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                    val pending = PendingIntent.getActivity(this@MainActivity, 1002, intent, flags)
                                    val notif =
                                        NotificationCompat
                                            .Builder(this@MainActivity, "updates")
                                            .setSmallIcon(R.drawable.update)
                                            .setContentTitle("Nuova base Meld disponibile")
                                            .setContentText("${upstream.versionName}: da valutare per MusicLab")
                                            .setContentIntent(pending)
                                            .setAutoCancel(true)
                                            .build()
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) ==
                                        PackageManager.PERMISSION_GRANTED
                                    ) {
                                        NotificationManagerCompat.from(this@MainActivity).notify(1002, notif)
                                        dataStore.edit { settings ->
                                            settings[LastMeldUpstreamNotifiedTagKey] = upstream.tagName
                                        }
                                    }
                                }
                            }
                        }
'''
s = s.replace(old, new, 1)
p.write_text(s)

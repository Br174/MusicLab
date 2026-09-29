from pathlib import Path


def replace_exact(path: str, old: str, new: str, count: int = 1):
    p = Path(path)
    s = p.read_text(encoding="utf-8")
    actual = s.count(old)
    if actual != count:
        raise SystemExit(f"{path}: expected {count} occurrences, found {actual}: {old[:100]!r}")
    p.write_text(s.replace(old, new, count), encoding="utf-8")


def replace_all(path: str, old: str, new: str, minimum: int = 1):
    p = Path(path)
    s = p.read_text(encoding="utf-8")
    actual = s.count(old)
    if actual < minimum:
        raise SystemExit(f"{path}: expected at least {minimum} occurrences, found {actual}: {old!r}")
    p.write_text(s.replace(old, new), encoding="utf-8")


# Main notification: Meld remains the source notification, never the APK installer.
main = "app/src/main/kotlin/com/metrolist/music/MainActivity.kt"
replace_exact(
    main,
    '''                                if (hasUpdate && notifEnabled) {
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
                                }''',
    '''                                if (hasUpdate && notifEnabled) {
                                    val releaseUrl = Updater.getUpstreamReleasePageUrl(releaseInfo)
                                    val intent = Intent(Intent.ACTION_VIEW, releaseUrl.toUri())

                                    val flags =
                                        PendingIntent.FLAG_UPDATE_CURRENT or
                                            PendingIntent.FLAG_IMMUTABLE
                                    val pending = PendingIntent.getActivity(this@MainActivity, 1001, intent, flags)

                                    val notif =
                                        NotificationCompat
                                            .Builder(this@MainActivity, "updates")
                                            .setSmallIcon(R.drawable.update)
                                            .setContentTitle(getString(R.string.meld_upstream_update_title))
                                            .setContentText("Meld ${releaseInfo.versionName}")
                                            .setContentIntent(pending)
                                            .setAutoCancel(true)
                                            .build()

                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) ==
                                        PackageManager.PERMISSION_GRANTED
                                    ) {
                                        NotificationManagerCompat.from(this@MainActivity).notify(1001, notif)
                                    }
                                }''',
)
replace_all(
    main,
    "latestVersionName != BuildConfig.VERSION_NAME",
    "Updater.isUpstreamUpdateAvailable(latestVersionName)",
    minimum=1,
)

# Settings: source review page, never Meld.apk.
settings = "app/src/main/kotlin/com/metrolist/music/ui/screens/settings/SettingsScreen.kt"
replace_all(
    settings,
    "latestVersionName != BuildConfig.VERSION_NAME",
    "Updater.isUpstreamUpdateAvailable(latestVersionName)",
    minimum=2,
)
replace_exact(
    settings,
    '''                    val releaseInfo = Updater.getCachedLatestRelease()
                    val downloadUrl = releaseInfo?.let { Updater.getDownloadUrlForCurrentVariant(it) }

                    if (downloadUrl != null) {''',
    '''                    val releaseInfo = Updater.getCachedLatestRelease()
                    val releaseUrl = releaseInfo?.let { Updater.getUpstreamReleasePageUrl(it) }

                    if (releaseUrl != null) {''',
)
replace_exact(
    settings,
    '''                                    Text(
                                        text = stringResource(R.string.new_version_available),
                                    )''',
    '''                                    Text(
                                        text = stringResource(R.string.meld_upstream_update_title),
                                    )''',
)
replace_exact(
    settings,
    '''                                        text = latestVersionName,''',
    '''                                        text = "Meld $latestVersionName",''',
)
replace_exact(
    settings,
    '''                                onClick = { uriHandler.openUri(downloadUrl) }''',
    '''                                onClick = { uriHandler.openUri(releaseUrl) }''',
)

# Account sheet: same behavior and badge semantics.
account = "app/src/main/kotlin/com/metrolist/music/ui/screens/settings/AccountSettings.kt"
replace_all(
    account,
    "latestVersionName != BuildConfig.VERSION_NAME",
    "Updater.isUpstreamUpdateAvailable(latestVersionName)",
    minimum=2,
)
replace_exact(
    account,
    '''                val releaseInfo = Updater.getCachedLatestRelease()
                val downloadUrl = releaseInfo?.let { Updater.getDownloadUrlForCurrentVariant(it) }
                
                if (downloadUrl != null) {''',
    '''                val releaseInfo = Updater.getCachedLatestRelease()
                val releaseUrl = releaseInfo?.let { Updater.getUpstreamReleasePageUrl(it) }

                if (releaseUrl != null) {''',
)
replace_exact(
    account,
    '''                            Text(text = stringResource(R.string.new_version_available))''',
    '''                            Text(text = stringResource(R.string.meld_upstream_update_title))''',
)
replace_exact(account, '''                        description = latestVersionName,''', '''                        description = "Meld $latestVersionName",''')
replace_exact(account, '''                            uriHandler.openUri(downloadUrl)''', '''                            uriHandler.openUri(releaseUrl)''')

# Meld changelog history follows imported Meld baseline, not MusicLab's own app version.
changelog = "app/src/main/kotlin/com/metrolist/music/ui/screens/settings/ChangelogScreen.kt"
replace_exact(
    changelog,
    '''                Updater.compareVersions(BuildConfig.VERSION_NAME, release.tagName) >= 0''',
    '''                Updater.compareVersions(Updater.IMPORTED_MELD_VERSION, release.tagName) >= 0''',
)

# Updater settings make the independent Meld source baseline visible.
updater_settings = "app/src/main/kotlin/com/metrolist/music/ui/screens/settings/UpdaterSettings.kt"
replace_exact(
    updater_settings,
    '''                            Text("$arch - $variant")''',
    '''                            Text(
                                "$arch - $variant\\n" +
                                    stringResource(R.string.meld_upstream_baseline, Updater.IMPORTED_MELD_VERSION),
                            )''',
)

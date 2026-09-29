# Audit upstream Meld v0.9.2

- Upstream autorizzato: `FrancescoGrazioso/Meld`
- Baseline MusicLab/Meld integrata: `v0.8.9` (`645d84ee0fb8ea168c37490afa78de073b956453`)
- Target analizzato: `v0.9.2`
- Commit upstream rilevati: **36**
- File modificati upstream: **619**
- File con potenziale conflitto rispetto a MusicLab: **19**
- Stato: **solo audit, nessuna modifica Meld importata automaticamente**

## Potenziali conflitti MusicLab

- `.github/workflows/build.yml`
- `.github/workflows/build_quick.yml`
- `.gitmodules`
- `app/build.gradle.kts`
- `app/src/main/kotlin/com/metrolist/music/App.kt`
- `app/src/main/kotlin/com/metrolist/music/MainActivity.kt`
- `app/src/main/kotlin/com/metrolist/music/constants/Dimensions.kt`
- `app/src/main/kotlin/com/metrolist/music/constants/PreferenceKeys.kt`
- `app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt`
- `app/src/main/kotlin/com/metrolist/music/ui/component/BottomSheet.kt`
- `app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt`
- `app/src/main/kotlin/com/metrolist/music/ui/menu/SongMenu.kt`
- `app/src/main/kotlin/com/metrolist/music/ui/player/Thumbnail.kt`
- `app/src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt`
- `app/src/main/kotlin/com/metrolist/music/ui/screens/settings/SettingsScreen.kt`
- `app/src/main/kotlin/com/metrolist/music/ui/utils/ShowMediaInfo.kt`
- `app/src/main/kotlin/com/metrolist/music/utils/DataStore.kt`
- `app/src/main/kotlin/com/metrolist/music/utils/Updater.kt`
- `docs/spotify-gql-hashes.json`

## Regola di integrazione

MotorLab non deve applicare automaticamente i 36 commit. Per ogni nuova release Meld deve:

1. confrontare Meld con l'ultima baseline realmente integrata;
2. distinguere modifiche importabili, modifiche già presenti, adattamenti e conflitti;
3. creare una LAB di integrazione separata;
4. preservare le funzioni MusicLab già approvate;
5. sottoporre a Bruno i conflitti funzionali importanti prima della promozione;
6. aggiornare `upstream/meld-state.json` soltanto dopo integrazione e test approvati.

## Verifica

Audit eseguito con GitHub Actions run `36569275771`; artefatto `Meld-Upstream-Audit-v0.9.2-LAB13`, ID `11032519899`, digest `sha256:6b8a708ce78143fcfc4da8e33003d3252750879797495f550d15c19c3c3fb9e0`.

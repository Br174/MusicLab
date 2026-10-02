# LAB20 — MusicLab AI Brain Implementation Plan

## Base e sicurezza
- Base: `mother/musiclab-lab19-final` @ `6f3efabd77d6e0a918b91091bb1862986d1dc5fc`.
- LAB: `uab-build/musiclab-ai-brain-20`.
- MADRE non modificabile.
- Cambi additivi e compatibili con API esistenti.

## Task 1 — Pure Brain rules (TDD)
Creare test Node per regole pure, quindi implementare `src/brain.js`.
Verifiche:
- una fonte assente non rigetta il candidato;
- Two-Key Candidate Rule;
- performer originale diverso richiesto per Cover salvo classificazioni alternative;
- query bilingui preservano il titolo originale;
- missioni linguistiche non dipendono dalla sola traduzione letterale;
- mapping `APPROVED/PROBABLE/UNCERTAIN/REJECTED`;
- selettore Precisa/Selezionata/Ampia/Esplora/Tutto;
- Coverage Map sceglie gap non ancora esplorati.

## Task 2 — D1 schema additivo
Aggiungere `0003_music_brain.sql` senza DELETE/reset distruttivi:
- alias/opere collegate/adattamenti;
- evidenze per candidato;
- score/status/version type;
- decision history e user verification/rejection;
- search missions/coverage history.

## Task 3 — Backend integration
Integrare le regole del Brain nel Worker esistente:
- Planner genera missioni Cover/Originali;
- prompt centrale esplicita source-evidence/no-veto e `UNCERTAIN` over deletion;
- initial resta FAST;
- expand usa missioni e Coverage Map;
- salvataggio D1 delle evidenze e degli stati;
- mantenere endpoint esistenti.

## Task 4 — Source lanes
Usare prima fonti gia disponibili; aggiungere adapter modulari progressivamente.
- MusicBrainz/Wikidata/Discogs/Last.fm/COVER.INFO/SHS come evidence providers;
- Web/Tavily solo DEEP, se gratuito/no-card verificato;
- YouTube/YTM come last-resort discovery oltre che resolver.
Nessuna fonte singola elimina un candidato.

## Task 5 — App integration
- Coverage selector a 5 modalita nelle viste Cover/Originali senza cambiare grafica non richiesta;
- sezione `Da verificare` per UNCERTAIN;
- azioni ascolta/conferma/rifiuta/verifica meglio;
- titoli cliccabili: hidden internal MusicLab title search, senza AI;
- risultati progressivi.

## Task 6 — Verification
Backend:
- test pure rules;
- migrazione D1 locale;
- compatibilita endpoint.
Android:
- test regressione applicabili;
- build LAB20 con applicationId isolato;
- benchmark: Il mondo + brano poche cover + hit inglese internazionale + titolo adattato diverso + molte Originali + caso incerto + caso MB scarso.

## Task 7 — Artifact-first
Dopo build/test riusciti:
- produrre APK LAB20;
- produrre ZIP sorgente/consegna;
- pubblicare immediatamente entrambi per test Bruno;
- eventuale root-cause/prevention deferrabile resta tracciata senza mutare artefatto consegnato.

## Criteri di accettazione LAB20
- Cover: quando il repertorio lo consente, avvicinarsi a 100 risultati utili attraverso pool piu ampio.
- Originali: tutte le versioni plausibili senza target artificiale.
- Versioni straniere cercate come adattamenti/opere correlate, non sola traduzione letterale.
- AI puo contraddire fonti; fonte assente non e veto.
- Memoria D1 evita lavoro ripetuto e conserva decisioni manuali.
- Playback corrente e player LAB19 non regrediscono.

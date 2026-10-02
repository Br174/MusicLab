# MusicLab AI Brain — Design approvato

Data: 2026-09-30
Base immutabile: `mother/musiclab-lab19-final` @ `6f3efabd77d6e0a918b91091bb1862986d1dc5fc`
LAB: `uab-build/musiclab-ai-brain-20`

## Obiettivo
Ampliare il motore AI gia presente in MusicLab senza sostituirlo. Il Brain deve trovare Cover, Originali e Crediti con alta copertura, memoria progressiva D1, fonti multiple come evidenze e decisione editoriale finale affidata all'AI.

## Principi non negoziabili
1. Le fonti sono Evidence Providers, non autorita di veto.
2. L'AI puo contraddire una fonte quando il quadro complessivo lo giustifica.
3. Assenza di conferma non equivale a falso: in dubbio `UNCERTAIN`, non eliminazione.
4. Discovery a maglie larghe: discover -> retain -> evidence -> classify -> rank -> display.
5. Cancellazione immediata solo per junk evidente, duplicato certo o opera chiaramente diversa.
6. Nessun servizio a pagamento/auto-upgrade; esaurita una quota gratuita si degrada con fallback gratuiti.
7. D1 `musiclab-intelligence-db` resta l'unica memoria centrale.

## Identita dell'opera
Il Brain costruisce una Work Signature con titolo canonico, artista originale, alias, titoli adattati/tradotti reali, autori, compositori, parolieri, publisher, anno, lingua e identificatori opzionali (ISWC, MusicBrainz Work, IPI). ISRC resta a livello registrazione. Gli identificatori aiutano ma non bloccano.

## Definizioni
- Cover: stessa composizione/opera + performer principale diverso.
- Originali: stessa composizione + stesso performer originale + registrazione realmente differente (rerecording, live, acoustic, duet, TV/radio, altra lingua, remix appropriato). Una ristampa dello stesso master non e una nuova versione.
- Crediti: Work, Recording e Release vengono arricchiti dopo la discovery; un credito mancante non elimina un candidato.

## Two-Key Candidate Rule
Una possibile cover con performer differente entra nel Candidate Pool quando ha almeno due segnali coerenti e almeno uno e medio/forte. Esempi: titolo+compositore, titolo+paroliere, titolo adattato+autore, stesso ISWC+performer diverso, stesso MB Work+performer diverso, autori coincidenti+adattamento documentato. Titolo+album da soli non bastano. La regola ammette il candidato; l'AI decide lo stato finale.

## Dual-Language Query Engine
Il titolo originale viene preservato. Ogni missione genera terminologia di ricerca sia italiana sia inglese (es. cover/versione/reinterpretazione e cover version/recorded by/rendition/adaptation). Per adattamenti stranieri si aggiungono lingua locale, alias reali, autori/compositori e relazioni di opera. La traduzione letterale del titolo e solo una pista ausiliaria.

## Language Adaptation Engine
Per le cover straniere il Planner cerca adattamenti noti, titoli reali alternativi, opere collegate/derivate, autori/compositori/parolieri, identificatori e indizi geografici. Un titolo completamente diverso puo essere collegato alla stessa composizione.

## Evidenze, punteggi e stati
Ogni candidato conserva evidenze positive e negative. Almeno due indici indipendenti:
- `same_work_score` 0..100
- `version_type_score` 0..100
Per Originali possono aggiungersi `same_artist_score` e `different_recording_score`.

Stati D1: `APPROVED`, `PROBABLE`, `UNCERTAIN`, `REJECTED`. Il rifiuto viene conservato per evitare riscoperte inutili. Le decisioni manuali dell'utente vengono registrate con le evidenze che le hanno motivate, formando memoria decisionale contestuale.

## Copertura utente
Cinque viste sullo stesso Candidate Pool gia salvato, senza rilanciare la ricerca: Precisa (~85+), Selezionata (~70+), Ampia (~50+), Esplora (~30+), Tutto (~15+). Le soglie sono iniziali e calibrabili, non probabilita scientifiche. Anche Tutto elimina junk evidente.

## Planner e Coverage Map
Per Cover il target visibile e circa 100 risultati utili; il pool interno puo arrivare a 180–250 candidati. Un'unica chiamata Planner crea molte missioni diverse, eseguite in ondate parallele controllate (circa 6–8 alla volta). Le famiglie includono decenni, paesi, lingue, tipi di versione, release, titoli/alias, crediti/identificatori, TV/radio/tribute/long-tail. La Coverage Map registra celle gia esplorate e genera solo missioni per i buchi.

Per Originali non esiste target artificiale: si continua finche 2–3 round consecutivi non producono progresso materiale.

## Source Router
Ordine logico iniziale: D1 -> conoscenza AI/Work Signature -> MusicBrainz -> Wikidata -> Discogs -> Last.fm -> COVER.INFO -> SecondHandSongs -> Web/Tavily deep reserve -> YouTube/YTM last-resort discovery. Nessuna fonte ha veto automatico.

COVER.INFO e best-effort/evidence; non va reso dipendenza fragile se manca API pubblica stabile.

## YouTube / YouTube Music
Tre ruoli distinti:
1. Tap sul titolo: ricerca interna MusicLab grezza sul titolo, senza AI.
2. Playback resolver: localizza la registrazione dopo decisione editoriale AI.
3. Discovery di ultima istanza: se le altre fonti trovano poco, genera candidati che tornano all'AI Judge.

## Velocita
Tre corsie: FAST (D1/cache + AI rapida), NORMAL (fonti strutturate + ondate parallele), DEEP (solo gap/Verifica meglio: Web/Tavily/YouTube/reviewer). I risultati devono poter apparire progressivamente.

## Compatibilita
Le API esistenti `/api/v1/resolve`, `/api/v1/discover/initial`, `/api/v1/discover/expand`, playback binding e technical destination restano compatibili. Le nuove strutture D1 sono additive e non devono cancellare la memoria valida. Il motore app esistente (CloudMusicDiscovery, Gemini discovery, resolver YTM/YT) viene esteso, non riscritto.

## Fase 2 esclusa da LAB20 iniziale
Audio Judge (Chroma/HPCP/MFCC/AcoustID) resta evoluzione futura dopo stabilizzazione Metadata Brain.

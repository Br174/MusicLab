# MusicLab Music Intelligence

Backend centrale AI-first di MusicLab. L'AI decide metadati, crediti, originale, cover e classificazioni; YouTube/YouTube Music restano esclusivamente sorgenti tecniche di riproduzione e di browse ID dopo che l'identità canonica è stata stabilita.

## Principi

- playback indipendente: nessuna chiamata di questo backend deve essere necessaria per avviare audio/video;
- cache/memoria prima dell'AI;
- primi risultati Cover/Originali rapidi, approfondimento progressivo in background;
- un'unica memoria condivisa per Cover, Originali e metadati globali;
- nessuna chiave Gemini dentro il Worker sorgente o nel repository.

## Configurazione Cloudflare

1. `npm install`
2. `npx wrangler d1 create musiclab-intelligence-db`
3. copiare l'identificativo D1 ottenuto in `wrangler.jsonc` al posto di `INSERIRE_DATABASE_ID_DOPO_LA_CREAZIONE`;
4. `npm run db:remote`
5. `npx wrangler secret put GEMINI_API_KEY`
6. `npm run deploy`

Dopo il deploy, l'URL del Worker va impostato come endpoint del motore MusicLab. Fino a quel momento la LAB può usare il fallback Gemini diretto già configurato nell'app.

## API

- `GET /stato`
- `POST /api/v1/resolve` — metadati/crediti canonici globali
- `POST /api/v1/discover/initial` — primo blocco rapido Cover/Originali
- `POST /api/v1/discover/expand` — approfondimento
- `POST /api/v1/playback-binding` — associazione tecnica a un playback ID
- `POST /api/v1/technical-destination` — memorizza browse ID artista/album solo dopo risoluzione canonica AI

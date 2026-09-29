import json
import os
import urllib.error
import urllib.request

API_KEY = os.environ["GEMINI_API_KEY"]
MODEL = "gemini-3.5-flash-lite"
URL = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent"

PROMPT = """Sei il resolver musicale canonico centrale di MusicLab.

Devi identificare la SPECIFICA REGISTRAZIONE realmente riprodotta. La tua risposta è l'unica autorità editoriale dell'app. YouTube e YouTube Music forniscono soltanto il playback tecnico e NON decidono artista, album, anno o crediti.

Titolo osservato: Mi Sono Innamorato Di Te - Gianluca Grignani
Artista/canale osservato: Alessandra De Angelis
Playback ID tecnico: non disponibile

ATTENZIONE: il campo artista/canale osservato può essere un uploader, un canale personale, un'etichetta o chi ha caricato il video. Non copiarlo automaticamente come artista. Analizza anche eventuali nomi di interpreti incorporati nel titolo del video. Il campo artist della risposta deve essere l'interprete REALE di questa registrazione, non l'autore del caricamento. Non trasformare automaticamente la registrazione nell'originale della composizione: se il playback è una cover o un live, restituisci l'artista della cover/live e classificala correttamente.

Restituisci titolo canonico della registrazione, artista reale, album reale se esiste, anno, lingua, categoria (originale|cover|live|remix|adattamento), crediti reali, confidence da 0 a 1 e il ruolo del nome osservato (performer|uploader|channel|label|unknown). Non inventare album o crediti.

JSON obbligatorio: {"title":"","artist":"","album":null,"year":null,"language":null,"category":null,"confidence":0.0,"observedArtistRole":"performer|uploader|channel|label|unknown","credits":{"songwriters":[],"composers":[],"lyricists":[],"producers":[],"label":null}}"""


def call(use_search: bool):
    body = {
        "contents": [{"role": "user", "parts": [{"text": PROMPT}]}],
        "generationConfig": {
            "temperature": 0.12,
            "maxOutputTokens": 1800,
            "responseMimeType": "application/json",
        },
    }
    if use_search:
        body["tools"] = [{"google_search": {}}]
    request = urllib.request.Request(
        URL,
        method="POST",
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "x-goog-api-key": API_KEY,
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            raw = response.read().decode("utf-8")
            print(f"HTTP={response.status} SEARCH={use_search}")
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        print(f"HTTP={exc.code} SEARCH={use_search}")
    print(raw)
    try:
        root = json.loads(raw)
        text = "\n".join(
            part.get("text", "")
            for part in root.get("candidates", [{}])[0].get("content", {}).get("parts", [])
            if part.get("text")
        )
        print("--- TESTO AI ---")
        print(text)
    except Exception as exc:
        print("PARSE_ERROR", repr(exc))


call(False)
call(True)

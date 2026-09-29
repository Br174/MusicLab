import json
import os
import urllib.error
import urllib.request

API_KEY = os.environ["GEMINI_API_KEY"]
MODEL = "gemini-3.5-flash-lite"
URL = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent"

BASE_PROMPT = """Sei il resolver musicale canonico centrale di MusicLab.
Titolo osservato: Mi Sono Innamorato Di Te - Gianluca Grignani
Artista/canale osservato: Alessandra De Angelis
L'AI è l'unica autorità editoriale. YouTube/YouTube Music forniscono solo il playback tecnico.
Il campo canale osservato può essere un uploader e non va assunto come artista.
Restituisci SOLO JSON con title, artist, category, confidence e observedArtistRole."""

ADJUDICATION_PROMPT = """Sei il resolver musicale canonico centrale di MusicLab e devi correggere una prima identificazione contraddittoria.

Dati osservati dal playback tecnico:
- titolo completo del video: Mi Sono Innamorato Di Te - Gianluca Grignani
- nome del canale/uploader: Alessandra De Angelis
- candidato interprete esplicito ricavabile dal titolo: Gianluca Grignani

Una prima passata ha proposto Alessandra De Angelis come performer, ma questo è in conflitto con il nome dell'interprete scritto esplicitamente nel titolo. Decidi TU, usando la tua conoscenza musicale e la struttura del titolo, chi è l'interprete reale della registrazione. Il nome del canale non è una fonte editoriale e non deve prevalere per il solo fatto di essere il canale.

Regole:
1. Se il titolo usa la forma “titolo canzone - nome artista” e il suffisso è un artista musicale riconoscibile, trattalo come forte indizio dell'interprete.
2. Considera il nome del canale come uploader finché non hai una ragione musicale indipendente per ritenerlo l'interprete.
3. Non confondere l'interprete di questa registrazione con l'autore/originale della composizione.
4. L'AI resta l'unica autorità finale: gli indizi servono solo per la tua decisione.

Restituisci SOLO JSON:
{"title":"titolo canonico","artist":"interprete reale della registrazione","category":"originale|cover|live|remix|adattamento","confidence":0.0,"observedArtistRole":"performer|uploader|channel|label|unknown","reason":"breve motivo"}"""


def call(label: str, prompt: str):
    body = {
        "contents": [{"role": "user", "parts": [{"text": prompt}]}],
        "generationConfig": {
            "temperature": 0.08,
            "maxOutputTokens": 900,
            "responseMimeType": "application/json",
        },
    }
    request = urllib.request.Request(
        URL,
        method="POST",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "x-goog-api-key": API_KEY},
    )
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            raw = response.read().decode("utf-8")
            print(f"=== {label} HTTP={response.status} ===")
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        print(f"=== {label} HTTP={exc.code} ===")
    root = json.loads(raw)
    text = "\n".join(
        part.get("text", "")
        for part in root.get("candidates", [{}])[0].get("content", {}).get("parts", [])
        if part.get("text")
    )
    print(text or raw)


call("BASE", BASE_PROMPT)
call("ARBITRAGGIO", ADJUDICATION_PROMPT)

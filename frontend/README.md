# EBank AI Assistant — Frontend Angular

Interface web du chatbot Ebank (texte, image, PDF, audio), en complément de Telegram.
Le frontend ne contient aucune logique IA : il envoie la question ou le fichier au backend et affiche la réponse.

## Prérequis

- Node.js 20
- npm 10
- Angular CLI 19 (optionnel : `npx ng` fonctionne sans installation globale)

## Installation

```bash
cd frontend
npm install
```

## Lancement

Avec Docker (recommandé) : `docker compose up -d --build` à la racine du projet, puis ouvrir http://localhost:4200.

En développement, avec la Gateway démarrée sur le port 8080 :

```bash
npx ng serve
```

## URL

http://localhost:4200

## Backend utilisé

Le navigateur appelle des URL relatives `/chatbot/...` :

- dans Docker, Nginx (conteneur `frontend`) relaie `/chatbot/**` vers `gateway-service:8080` ;
- avec `ng serve`, le proxy de développement (`proxy.conf.json`) relaie vers la Gateway `http://127.0.0.1:8080`.

| Entrée | Endpoint | Envoi | Réponse |
|---|---|---|---|
| Texte | `GET /chatbot/chat?query=...` | paramètre `query` | texte brut |
| Image | `POST /chatbot/chat/image` | multipart `file` (image/*) + `query` optionnel | JSON `{answer, transcription: null}` |
| Audio (fichier ou micro) | `POST /chatbot/chat/audio` | multipart `file` (audio/*) | JSON `{answer, transcription}` |
| PDF | `POST /chatbot/chat/pdf` | multipart `file` (application/pdf) + `query` optionnel | JSON `{answer, transcription: null}` |

Fichiers limités à 10 Mo. L'enregistrement micro fonctionne sur `http://localhost:4200` (contexte sécurisé du navigateur).

## Architecture

```
Angular (navigateur, localhost:4200)
   ↓  /chatbot/...
Nginx (Docker) ou proxy ng serve
   ↓
API Gateway (8080)
   ↓
Ebank Chatbot (8098)
   ↓
Spring AI / ReAct
   ↓
MCP + RAG
```

Telegram continue d'appeler directement le chatbot, en parallèle de cette interface.

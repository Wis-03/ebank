# Ebank — Spring Cloud & Agentic AI

Plateforme bancaire en microservices (Spring Boot / Spring Cloud) avec un assistant **Agentic AI** (Spring AI, GPT-4o)
accessible depuis **Telegram** et depuis une **interface web Angular**. L'agent utilise des outils **MCP Streamable HTTP**,
un **RAG multimodal** (PDF, images, audio) stocké dans **PostgreSQL + pgvector**, une **mémoire** de conversation et un
cycle **ReAct** explicite.

<img src="screens/arch.jpg">

## 1. Architecture

```
Telegram ──────────────────────────────┐
                                       v
Angular (4200) ──► API Gateway (8080) ──► Ebank Chatbot Service (8098)
                        │                        │
                        │                  AI Agent (Spring AI, GPT-4o)
                        │                  Goal · Memory · ReAct · Few-shot
                        │                   /            |            \
                        │           MCP Streamable      RAG          Memory
                        │            /     |     \       |
                        ├──► Ebank (8077)  Customer (8066)  MCP RH (8989)   PostgreSQL + pgvector
                        │        └──── OpenFeign ────┘
                        │
        Discovery Service / Eureka (8761)  ·  Config Service (8888)
```

- Tous les services s'enregistrent dans **Eureka** et récupèrent leur configuration depuis le **Config Server**.
- La **Gateway** route vers les services par découverte (`lb://NOM-EUREKA`).
- **Ebank Service** appelle **Customer Service** par **OpenFeign** (`@FeignClient(name = "CUSTOMER-SERVICE")`).
- L'interface **Angular** appelle `/chatbot/...` ; Nginx (conteneur `frontend`) relaie ces appels vers la Gateway.
- Le **chatbot** consomme les outils des serveurs MCP (Ebank, Customer, RH) en **MCP Streamable HTTP**.

## 2. Services

| Service | Port | Rôle |
|---|---|---|
| `discovery-service` | 8761 | Eureka Server |
| `config-service` | 8888 | Spring Cloud Config Server (dépôt `native`, `config-repo` embarqué) |
| `gateway-service` | 8080 | Spring Cloud Gateway (routes `/ebank/**`, `/customers/**`, `/chatbot/**`, `/mcp-rh/**`) |
| `customer-service` | 8066 | Clients (REST) + serveur MCP (`getCustomerById`) |
| `ebank-service` | 8077 | Comptes bancaires (REST, H2) + serveur MCP + client OpenFeign vers Customer |
| `mcp-server` | 8989 | Serveur MCP RH (`getEmployee`, `getAllEmployees`) |
| `ebank-chatbot-service` (racine) | 8098 | Agent IA, RAG, multimodal, Telegram, API de chat |
| `frontend` | 4200 | Interface Angular (Nginx dans Docker) |
| `postgres` | 5433 (hôte) / 5432 (interne) | PostgreSQL 17 + pgvector (base vectorielle du RAG) |

## 3. Technologies

Java 21 · Spring Boot 3.5 · Spring Cloud 2025.0 (Eureka, Config, Gateway, OpenFeign) · Spring AI 1.1 (OpenAI, MCP,
PGVector, PDF reader) · OpenAI GPT-4o, text-embedding-3-small, whisper-1 · PostgreSQL 17 + pgvector · Telegram Bots ·
Angular 19 + Tailwind CSS · Docker Compose.

## 4. Variables d'environnement

Les secrets ne sont jamais écrits dans le code, la configuration ou les images Docker. Copier `.env.example` en `.env`
(fichier ignoré par Git) et le compléter, ou définir les variables dans l'environnement du shell :

| Variable | Obligatoire | Usage |
|---|---|---|
| `OPENAI_API_KEY` | oui | GPT-4o, embeddings, Whisper |
| `PGVECTOR_PASSWORD` | oui | mot de passe PostgreSQL / pgvector |
| `TELEGRAM_BOT_TOKEN` | non | bot Telegram ; sans token le chatbot démarre sans Telegram |

## 5. Lancement

```bash
cp .env.example .env      # puis renseigner les valeurs
docker compose up -d --build
docker compose ps         # tous les conteneurs doivent être "healthy"
```

Les services démarrent dans l'ordre grâce aux healthchecks (Discovery → Config → Customer / Ebank / MCP → Gateway /
Chatbot → Frontend). Dans Docker, les services communiquent par leurs noms (`config-service:8888`,
`discovery-service:8761`, `ebank-service:8077/mcp`, `postgres:5432`...), jamais par `localhost`.

Arrêt : `docker compose down` (les données pgvector sont conservées dans le volume `ebank-rag-pgdata`).

Lancement sans Docker (développement) : PostgreSQL pgvector sur `localhost:5433`, puis démarrer dans l'ordre
`discovery-service`, `config-service`, `customer-service`, `ebank-service`, `mcp-server`, `gateway-service`, le chatbot
(racine) avec `mvn spring-boot:run`, et le frontend avec `npm install && npx ng serve`.

## 6. URLs utiles

| URL | Description |
|---|---|
| http://localhost:4200 | Interface Angular |
| http://localhost:8080 | API Gateway |
| http://localhost:8761 | Tableau de bord Eureka |
| http://localhost:8888/ebank-chatbot-service/default | Configuration servie par le Config Server |

## 7. Exemples d'utilisation

API de chat (via la Gateway) :

| Entrée | Endpoint | Réponse |
|---|---|---|
| Texte | `GET /chatbot/chat?query=...` | texte |
| Image | `POST /chatbot/chat/image` (multipart `file`, `query` optionnel) | `{answer, transcription: null}` |
| Audio | `POST /chatbot/chat/audio` (multipart `file`) | `{answer, transcription}` |
| PDF | `POST /chatbot/chat/pdf` (multipart `file`, `query` optionnel) | `{answer, transcription: null}` |

```bash
curl "http://localhost:8080/chatbot/chat?query=Qui%20est%20le%20propriétaire%20du%20compte%20<id>%20?"
curl -F "file=@releve.pdf;type=application/pdf" -F "query=Quel est le solde final du relevé fourni ?" http://localhost:8080/chatbot/chat/pdf
```

Autres routes : `GET /ebank/bankAccounts`, `GET /ebank/bankAccounts/{id}` (compte + propriétaire via OpenFeign),
`GET /customers`, `GET /customers/{id}`.

Questions types : « Bonjour », « Donne-moi les informations du compte <id> », « Qui est le propriétaire du compte <id> ? »,
« Quelles sont les conditions du compte épargne ? », « À quelle heure ferment les agences Ebank le samedi ? ».

## 8. RAG

Ingestion au démarrage du chatbot (idempotente : empreinte SHA-256 par fichier, aucun doublon) :

```
PDF (rag/docs) → PagePdfDocumentReader → TokenTextSplitter (300 tokens) → text-embedding-3-small (1536) → pgvector (HNSW, cosinus)
```

L'outil local `searchBankDocuments` effectue la recherche sémantique (top-k 4, seuil de similarité 0.45) et renvoie les
extraits avec leur source ; l'agent répond uniquement à partir de ces extraits et cite les fichiers. Si rien n'est
trouvé : « Information non disponible dans la base documentaire. »

## 9. Multimodal

Indexation (même table vectorielle que les PDF) :

- **Image** (`rag/images`) → description sémantique fidèle par **GPT-4o Vision** → découpage par sections thématiques →
  embedding texte → pgvector. Il ne s'agit pas d'un embedding d'image de type CLIP : c'est la description textuelle
  générée qui est vectorisée.
- **Audio** (`rag/audio`) → transcription **Whisper** (speech-to-text) → chunks → embedding texte → pgvector.

Entrées utilisateur (Angular et Telegram) :

- **Image** : jointe au message et analysée par GPT-4o ; l'agent répond d'après l'image fournie.
- **Audio / note vocale** : transcrite par Whisper puis traitée comme une question.
- **PDF** (web) : texte extrait puis fourni à l'agent ; la réponse provient du document fourni.

## 10. Agentic AI / ReAct

- **Mind** : message système (objectif, méthode, règles), exemples few-shot, mémoire de conversation
  (`MessageChatMemoryAdvisor`), GPT-4o.
- **Goal** : pour chaque requête, un objectif et un critère de réussite sont définis à partir de la demande, des outils
  disponibles et du contenu fourni (PDF, image).
- **ReAct** (`ReActAdvisor`) : boucle bornée (`react.max-iterations=5`) Reasoning → Action (appel d'outils MCP / RAG) →
  Observation → évaluation du Goal. Arrêt quand le Goal est atteint, quand l'information est indisponible, ou à la limite
  d'itérations (réponse contrôlée, sans donnée non vérifiée). Chaque étape est tracée dans les logs (`[REACT]`).
- **Body** : outils MCP et outil RAG. Exemple multi-étapes : `getBankAccountById` → `customerId` → `getCustomerById` →
  réponse.

## 11. MCP

Le chatbot est client **MCP Streamable HTTP** de trois serveurs (endpoint `/mcp`) et découvre leurs outils au démarrage :

| Serveur | Outils |
|---|---|
| `ebank-service` | `getAllAccounts`, `getBankAccountById`, `saveAccount` |
| `customer-service` | `getCustomerById` |
| `mcp-server` (RH) | `getEmployee`, `getAllEmployees` |

Outil local supplémentaire : `searchBankDocuments` (RAG).

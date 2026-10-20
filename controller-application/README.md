# Controller application

Two independent projects:

- `backend/`: Spring Boot application built with Maven and JDK 25.
- `frontend/`: React and TypeScript application built with Vite and npm.

The frontend provides an English workspace with a game name field, an action
selector, and an activity log. Build Knowledge Base calls the Spring Boot backend,
which requests and validates a response from the local LM Studio server.
The projects run separately during development;
serving the frontend build through the backend is not wired up yet.
Technology decisions are maintained in [the ADR directory](../docs/adr/).

## Workspace behavior

- **Build Knowledge Base** shows a Start button, enabled after entering a
  non-blank game name. Start sends a REST request and reports progress, completion,
  or errors in the activity log. The inputs and Start button are disabled while
  the request is pending. A valid AI response is printed in the backend console;
  it is not saved as a knowledge base yet.
- **Build Chapters** shows a drop area and a keyboard-accessible file picker.
  Multiple videos can be selected, inspected by name and size, and removed.
  Selection accepts video MIME types or common video filename extensions;
  actual media validation belongs to the future processing service. Empty files
  and duplicates (same name, size, and last-modified timestamp) are skipped with
  feedback. Selected files are retained when switching actions.
- The bottom **Activity log** displays timestamped information, success,
  warning, and error messages. It retains the latest 500
  entries, follows new entries unless the user has scrolled up, and can be
  cleared.

Build Chapters remains UI-only. Videos are held as browser file references:
their contents are not read, copied, or uploaded. Form values, file selection,
and log entries are session-only and reset on page reload. Knowledge acquisition
currently uses a synchronous HTTP request, without background jobs or SSE.

## Backend

Install JDK 25 and Maven, set `JAVA_HOME` to the JDK directory, and make `mvn`
available on `PATH`.

From `controller-application/backend` in PowerShell:

```powershell
mvn spring-boot:run
```

If the LM Studio token has been saved with `ConvertFrom-SecureString` at
`%LOCALAPPDATA%\ProjectYCG\lmstudio-token.dpapi`, start the backend from the
repository root with:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\controller-application\start-backend.ps1
```

The execution policy override applies only to this PowerShell process. The
script decrypts the token for the current Windows user, passes it to the backend
through `LM_STUDIO_API_TOKEN`, and removes that environment variable when Maven
exits.

The server listens at `http://127.0.0.1:8080`.

Build and run the backend tests (including a local HTTP stub for LM Studio):

```powershell
mvn verify
```

### Build Knowledge Base API

```http
POST /api/knowledge-base/build
Content-Type: application/json

{"gameName":"Mortal Shell"}
```

`gameName` must be a non-blank string, at most 200 characters after trimming.
The current `KnowledgeBaseLookup.exists` implementation always returns `false`.
Its already-existing branch is covered by a test and skips the AI request.

The backend reads `src/main/resources/templates/knowledge_base_aquire_prompt.tpl`
as UTF-8, replaces `<?GAME_TITLE>` with the game name and `<?SOURCE_SITE>` with
`fextralife.com`, and sends a fresh streaming request to LM Studio's native
`POST /api/v1/chat`. Chat storage is disabled (`store: false`).

The controller reads the `chat.end` event's aggregated `result`. The last
`message` item in that result's `output` array is validated;
reasoning and tool outputs are not treated as the answer. The answer must be a
single JSON object with `bosses` and `sources` arrays. Each boss must have a
non-blank string `displayName` and a present `classification` field.
**Classification accepts any JSON value, including null.** Each source must be
an absolute HTTP(S) URL. Empty arrays are allowed. Markdown fences, malformed
JSON, trailing content, duplicate JSON keys, and missing required fields are
rejected. This checks structure, not the factual accuracy of the model's claims.

| HTTP status | Result |
| --- | --- |
| 200 | `{"status":"completed","message":"Knowledge base response validated and printed."}` |
| 200 | `{"status":"already_exists","message":"Knowledge base already exists"}` |
| 400 | Missing, malformed, or invalid game name/request |
| 415 | Unsupported request content type |
| 502 | LM Studio returned an HTTP error, malformed chat envelope, or invalid answer |
| 503 | LM Studio is unreachable or communication was interrupted |
| 504 | LM Studio request timed out |
| 500 | Prompt template or unexpected application error |

Errors use `{"code":"...","message":"..."}` with an English message. Validated
JSON is printed once through the backend logger; no output files, database
records, or subsequent processing are created. There are no automatic retries.
A timeout or closed browser does not guarantee that LM Studio stopped processing.

### LM Studio configuration

Defaults target the local Qwen3 model and the configured Parallel Search MCP
server. Environment variables can override connection settings:

| Environment variable | Default |
| --- | --- |
| `LM_STUDIO_BASE_URL` | `http://127.0.0.1:1234` |
| `LM_STUDIO_MODEL` | `qwen3-14b` |
| `LM_STUDIO_CONNECT_TIMEOUT` | `3s` |
| `LM_STUDIO_REQUEST_TIMEOUT` | `10m` |
| `LM_STUDIO_API_TOKEN` | Empty; no Authorization header |
| `LM_STUDIO_INTEGRATION_ID` | `mcp/parallel-search` |

The integration ID must match `mcp/<server label>` in LM Studio's `mcp.json`.
The chat request sets `reasoning` to `off` and `stream` to `true`. The controller
reads the final `chat.end` SSE event and validates its `result` message as JSON.
Enable **Allow calling servers from mcp.json** in LM Studio for this integration.
LM Studio requires **Require Authentication** when that setting is enabled.
Search credentials stay in LM Studio's configuration. If authentication is
enabled on its HTTP server, create a token in **Server Settings → Manage Tokens**
with permission to use the configured model and MCP server, then supply it as
`LM_STUDIO_API_TOKEN` in the backend process environment. The backend sends it in
the `Authorization: Bearer` header and does not log it.
An empty integration ID omits tool access, which means the prompt alone cannot
give the model live web search. Search sends the game research query to the
configured provider; no gameplay files are sent.

The controller does not download or explicitly load models; select an available
model in LM Studio. Protocol details:
[native chat API](https://lmstudio.ai/docs/developer/rest/chat) and
[MCP via API](https://lmstudio.ai/docs/developer/core/mcp).

## Frontend

Use Node.js 22.12+ (or 20.19+) with npm, consistent with `package.json`.
From `controller-application/frontend`:

```powershell
npm ci
npm run dev
```

Open `http://127.0.0.1:5173`.

Run the backend alongside the frontend. Vite proxies `/api` requests to
`http://127.0.0.1:8080` in both development and preview mode. The backend owns the
inference timeout. When serving `dist/` elsewhere, configure the same `/api`
route to the backend.

Type-check and create the production build:

```powershell
npm run build
```

The output is written to `frontend/dist/`. `npm run preview` serves that build at
`http://127.0.0.1:4173`.

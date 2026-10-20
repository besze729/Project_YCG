# Controller application

Two independent projects:

- `backend/`: Spring Boot application built with Maven and JDK 25.
- `frontend/`: React and TypeScript application built with Vite and npm.

The frontend provides an English workspace with a game name field, an action
selector, and an activity log. The backend scaffold contains no application
endpoints or processing logic. The projects run separately during development;
serving the frontend build through the backend is not wired up yet.
Technology decisions are maintained in [the ADR directory](../docs/adr/).

## Workspace behavior

- **Build Knowledge Base** shows a Start button, enabled after entering a
  non-blank game name. Starting reports that processing is not connected; it
  does not create a job or simulate a successful build.
- **Build Chapters** shows a drop area and a keyboard-accessible file picker.
  Multiple videos can be selected, inspected by name and size, and removed.
  Selection accepts video MIME types or common video filename extensions;
  actual media validation belongs to the future processing service. Empty files
  and duplicates (same name, size, and last-modified timestamp) are skipped with
  feedback. Selected files are retained when switching actions.
- The bottom **Activity log** displays timestamped information, success, and
  warning messages for workspace interactions. It retains the latest 500
  entries, follows new entries unless the user has scrolled up, and can be
  cleared.

This is a UI-only implementation. Videos are held as browser file references:
their contents are not read, copied, or uploaded. Form values, file selection,
and log entries are session-only and reset on page reload. No processing
service or REST/SSE integration is included yet.

## Backend

Install JDK 25 and Maven, set `JAVA_HOME` to the JDK directory, and make `mvn`
available on `PATH`.

From `controller-application/backend` in PowerShell:

```powershell
mvn spring-boot:run
```

The server listens at `http://127.0.0.1:8080`. Requests return HTTP 404 until
application endpoints or static content are added.

Build and run the generated application context smoke test:

```powershell
mvn verify
```

## Frontend

Use Node.js 22.12+ (or 20.19+) with npm, consistent with `package.json`.
From `controller-application/frontend`:

```powershell
npm ci
npm run dev
```

Open `http://127.0.0.1:5173`.

Type-check and create the production build:

```powershell
npm run build
```

The output is written to `frontend/dist/`. `npm run preview` serves that build at
`http://127.0.0.1:4173`.

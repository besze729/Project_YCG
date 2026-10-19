# Controller application

Two independent starter projects:

- `backend/`: Spring Boot application built with Maven and JDK 25.
- `frontend/`: React and TypeScript application built with Vite and npm.

This scaffold contains no application endpoints or processing logic. The frontend
currently displays only the project title. The projects run separately during
development; serving the frontend build through the backend is not wired up yet.
Technology decisions are maintained in [the ADR directory](../docs/adr/).

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

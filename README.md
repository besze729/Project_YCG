# Project YCG

From this directory, start the Python video service, controller backend, and
desktop application together:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start-all.ps1
```

The launcher starts the backend with `mvn spring-boot:run`. Maven checks whether
sources need compiling during startup; there is no separate package step by
default. Run with `-U` to build and package the backend before starting:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start-all.ps1 -U
```

Press **Ctrl+C in the launcher terminal** to stop all three modules. Closing
the desktop window also stops the two services. Startup output and errors for
each module are saved under `controller-application/.cache/master-launcher-*/`.
Launcher stdout includes wall-clock timestamps and elapsed time for each startup
and shutdown stage, including Maven build output with `-U`.
The launcher requires the existing Python `.venv`, frontend dependencies, Java,
Maven, and the LM Studio token file used by both the Java backend and Python
vision module. The Python launcher loads the same saved token automatically.
See the [controller guide](controller-application/README.md) for setup details.

Process creates a 720p proxy, detects boss health bars in coarse sampled frames,
then refines observed start/end boundaries at 5-second and 1-second spacing.
Derived intervals appear in the backend console and Activity log and are saved
with their evidence under each job's `*-boss-refinement/` directory. Boss naming
and chapter generation remain later steps.

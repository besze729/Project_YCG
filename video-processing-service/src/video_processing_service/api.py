"""Loopback HTTP adapter; source videos are read in place, never uploaded."""

import asyncio
from dataclasses import asdict
import json
import os
from pathlib import Path
from queue import Empty, Full, Queue
import shlex
import subprocess
from threading import Event, Lock, Thread

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator
from starlette.background import BackgroundTask

from .config import load_config
from .service import ProcessingProgress, create_proxy


class ProxyRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    gameName: str = Field(min_length=1, max_length=200)
    inputVideo: str
    outputVideo: str

    @field_validator("gameName")
    @classmethod
    def nonblank_game(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("gameName must not be blank")
        return value.strip()


def create_app() -> FastAPI:
    app = FastAPI(title="YCG Video Processing Service", version="0.1.0")
    busy = Lock()

    @app.get("/health")
    def health():
        return {"status": "ok"}

    @app.post("/api/proxy", response_class=StreamingResponse)
    async def proxy(body: ProxyRequest, request: Request):
        # This API is called by the local controller, not by arbitrary web pages.
        if request.headers.get("origin"):
            raise HTTPException(403, "Use the local controller to start processing.")
        source, output = Path(body.inputVideo), Path(body.outputVideo)
        if not source.is_absolute() or not output.is_absolute():
            raise HTTPException(400, "inputVideo and outputVideo must be absolute paths.")
        if not source.is_file():
            raise HTTPException(404, "Input video does not exist.")
        if output.suffix.lower() != ".mp4":
            raise HTTPException(400, "Proxy output must be an .mp4 file.")
        if output.exists():
            raise HTTPException(409, "Output already exists.")
        try:
            config = load_config(os.environ.get("YCG_VIDEO_CONFIG"))
        except ValueError as exc:
            raise HTTPException(500, str(exc)) from exc
        if not busy.acquire(blocking=False):
            raise HTTPException(409, "Another proxy is being processed.")

        queue: Queue[dict | None] = Queue(maxsize=64)
        disconnected = Event()

        def put(value: dict | None):
            while not disconnected.is_set():
                try:
                    queue.put(value, timeout=0.2)
                    return
                except Full:
                    pass
            raise RuntimeError("Controller disconnected from proxy processing.")

        def run():
            terminal: dict | None = None

            def progress(event: ProcessingProgress):
                nonlocal terminal
                data = asdict(event)
                data.update(gameName=body.gameName, inputVideo=str(source), outputVideo=str(output))
                if event.command:
                    data["commandLine"] = (subprocess.list2cmdline(event.command)
                                           if os.name == "nt" else shlex.join(event.command))
                if event.state in {"completed", "failed"}:
                    terminal = data
                else:
                    put(data)

            try:
                create_proxy(source, output, config, on_progress=progress)
            except Exception as exc:
                if terminal is None:
                    terminal = {"state": "failed", "detail": str(exc)}
            finally:
                busy.release()
                if not disconnected.is_set():
                    try:
                        if terminal is not None:
                            put(terminal)
                        put(None)
                    except RuntimeError:
                        pass

        async def stream():
            try:
                while True:
                    try:
                        item = await asyncio.to_thread(queue.get, True, 10)
                    except Empty:
                        item = {"state": "heartbeat"}
                    if item is None:
                        break
                    yield json.dumps(item, ensure_ascii=False, allow_nan=False) + "\n"
            finally:
                # The next progress callback interrupts FFmpeg and cleans staging.
                disconnected.set()

        Thread(target=run, daemon=True, name="proxy-worker").start()
        return StreamingResponse(stream(), media_type="application/x-ndjson",
                                 background=BackgroundTask(disconnected.set),
                                 headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"})

    return app


app = create_app()


def main():
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=int(os.environ.get("YCG_VIDEO_PORT", "8090")))


if __name__ == "__main__":
    main()

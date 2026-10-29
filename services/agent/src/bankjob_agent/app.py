from fastapi import FastAPI

from bankjob_agent.settings import Settings


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    app = FastAPI(title="Bank Job agent", version="0.1.0")

    @app.get("/healthz")
    def healthz() -> dict[str, str]:
        return {"status": "ok", "llm_mode": settings.llm_mode}

    @app.get("/v1/hello")
    def hello() -> dict[str, str]:
        return {"service": "agent", "message": "Penny is warming up her smile."}

    return app


app = create_app()

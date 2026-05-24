from fastapi import FastAPI
from pydantic import BaseModel, Field
from services.chat import generate_reply
from services.commentary import generate_commentary
from services.embed import embed_cache_metrics
from services.ner import ner_backend_status
from services.sentiment import analyze
from services.ner import extract_entities
from services.task_dispatch import cpu_dispatch_ready
from services.task_dispatch import get_executor_metrics
from services.task_dispatch import run_embed_task
from services.task_dispatch import run_semantic_rank_task
from services.task_dispatch import run_translate_task
from services.translation import ensure_translation_backend_async
from services.translation import translation_backend_status
from storage.chroma_client import add_news, query_similar, add_entity, query_entity_history

app = FastAPI(title="Hotspot Bot ML Server", version="1.0.0")


@app.on_event("startup")
def startup_warmups():
    ensure_translation_backend_async()


# ---- Request / Response Models ----

class EmbedRequest(BaseModel):
    texts: list[str]


class CommentaryRequest(BaseModel):
    content: str


class ChatMessage(BaseModel):
    role: str
    content: str


class ChatRequest(BaseModel):
    message: str
    history: list[ChatMessage] = Field(default_factory=list)
    system_prompt: str | None = None


class SentimentRequest(BaseModel):
    comments: list[str]


class NERRequest(BaseModel):
    text: str


class NewsStoreRequest(BaseModel):
    id: str
    text: str
    vector: list[float]
    metadata: dict = {}


class QuerySimilarRequest(BaseModel):
    vector: list[float]
    days: int = 7
    threshold: float = 0.8


class EntityStoreRequest(BaseModel):
    id: str
    name: str
    type: str
    vector: list[float]
    metadata: dict = {}


class QueryEntityRequest(BaseModel):
    vector: list[float]
    threshold: float = 0.75


class SemanticRankRequest(BaseModel):
    query: str
    candidates: list[str]
    top_k: int | None = None


class TranslateRequest(BaseModel):
    texts: list[str]
    text_type: str | None = None


# ---- Endpoints ----

@app.post("/api/embed")
async def api_embed(req: EmbedRequest):
    vectors = await run_embed_task(req.texts)
    return {"vectors": vectors}


@app.post("/api/semantic/rank")
async def api_semantic_rank(req: SemanticRankRequest):
    ranked = await run_semantic_rank_task(req.query, req.candidates, req.top_k)
    return {"matches": ranked}


@app.post("/api/translate")
async def api_translate(req: TranslateRequest):
    translations = await run_translate_task(req.texts, req.text_type)
    return {"translations": translations}


@app.post("/api/commentary")
def api_commentary(req: CommentaryRequest):
    result = generate_commentary(req.content)
    return {"commentary": result}


@app.post("/api/chat")
def api_chat(req: ChatRequest):
    result = generate_reply(
        user_message=req.message,
        history=[item.model_dump() for item in req.history],
        system_prompt=req.system_prompt,
    )
    return {"reply": result}


@app.post("/api/sentiment")
def api_sentiment(req: SentimentRequest):
    result = analyze(req.comments)
    return result


@app.post("/api/ner")
def api_ner(req: NERRequest):
    entities = extract_entities(req.text)
    return {"entities": entities}


@app.post("/api/news/add")
def api_add_news(req: NewsStoreRequest):
    add_news(req.id, req.text, req.vector, req.metadata)
    return {"status": "ok"}


@app.post("/api/news/similar")
def api_query_similar(req: QuerySimilarRequest):
    results = query_similar(req.vector, req.days, req.threshold)
    return {"matches": results}


@app.post("/api/entity/add")
def api_add_entity(req: EntityStoreRequest):
    add_entity(req.id, req.name, req.type, req.vector, req.metadata)
    return {"status": "ok"}


@app.post("/api/entity/history")
def api_query_entity(req: QueryEntityRequest):
    results = query_entity_history(req.vector, req.threshold)
    return {"matches": results}


@app.get("/api/health")
def health():
    return {"status": "ok"}


@app.get("/api/metrics/embedding-cache")
def embedding_cache_metrics_endpoint():
    return {"embedding_cache": embed_cache_metrics()}


@app.get("/api/metrics/executors")
def executor_metrics():
    return {"executors": get_executor_metrics()}


@app.get("/api/ready")
def ready():
    translation_status = translation_backend_status()
    executor_metrics = get_executor_metrics()
    dispatch_ready = cpu_dispatch_ready(executor_metrics)
    ner_status = ner_backend_status()
    return {
        "ready": translation_status in {"ready", "failed"} and dispatch_ready,
        "translation_status": translation_status,
        "cpu_dispatch_ready": dispatch_ready,
        "ner_status": ner_status,
        "embedding_cache": embed_cache_metrics(),
        "executors": executor_metrics,
    }

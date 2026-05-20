from fastapi import FastAPI
from pydantic import BaseModel, Field
from services.chat import generate_reply
from services.embed import encode
from services.commentary import generate_commentary
from services.sentiment import analyze
from services.ner import extract_entities
from storage.chroma_client import add_news, query_similar, add_entity, query_entity_history

app = FastAPI(title="Hotspot Bot ML Server", version="1.0.0")


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


# ---- Endpoints ----

@app.post("/api/embed")
def api_embed(req: EmbedRequest):
    vectors = encode(req.texts)
    return {"vectors": vectors}


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

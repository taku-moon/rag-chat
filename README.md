# RAG Chat

Spring AI 기반 RAG(Retrieval-Augmented Generation) 채팅 애플리케이션입니다.  
Ollama 로컬 LLM과 벡터 스토어를 활용하여 문서 기반 질의응답을 제공합니다.
<img width="3835" height="2058" alt="스크린샷 2026-02-08 030038" src="https://github.com/user-attachments/assets/cd228118-9352-4ddd-bc72-af271d45274b" />

## 기술 스택

| 구분              | 기술                                            |
|-----------------|-----------------------------------------------|
| Framework       | Spring Boot 3.5.10, Java 21                   |
| AI              | Spring AI 1.1.2, Ollama                       |
| Chat Model      | HyperCLOVAX-SEED-Text-Instruct-1.5B (Q4_K_M)  |
| Embedding Model | bge-m3 (1024 dim)                             |
| Vector Store    | Elasticsearch / SimpleVectorStore (in-memory) |
| Document Reader | Apache Tika (PDF, DOCX, TXT 등)                |
| Frontend        | Vanilla HTML/CSS/JS (SSE 기반 스트리밍)             |

## 사전 요구사항

- Java 21+
- [Ollama](https://ollama.com/) 설치 및 실행
- Elasticsearch 8.x (Elasticsearch 벡터스토어 사용 시)

## 프로젝트 구조

```
src/main/
├── java/com/example/rag_chat/
│   ├── RagChatApplication.java          # 애플리케이션 진입점
│   ├── config/
│   │   ├── RagConfig.java               # RAG ETL 파이프라인, 벡터스토어, Advisor 설정
│   │   ├── RagChatConfig.java           # ChatClient, 대화 메모리, CLI 모드 설정
│   │   └── LengthTextSplitter.java      # 문자 길이 기반 텍스트 분할기
│   ├── controller/
│   │   └── RagChatController.java       # REST API (/rag/call, /rag/stream)
│   └── service/
│       └── RagChatService.java          # 채팅 서비스 (call/stream)
└── resources/
    ├── application.yml                  # 애플리케이션 설정
    ├── rag-test-file.pdf                # RAG 테스트용 문서
    └── static/
        ├── index.html                   # 웹 채팅 UI
        ├── app.js                       # 프론트엔드 로직 (SSE 스트리밍 처리)
        └── style.css                    # 스타일
```

## 실행 방법

### 1. Ollama 모델 준비

```bash
ollama pull hf.co/rippertnt/HyperCLOVAX-SEED-Text-Instruct-1.5B-Q4_K_M-GGUF
ollama pull bge-m3
```

> `pull-model-strategy: when_missing` 설정이 되어 있어 앱 시작 시 자동으로 다운로드되지만, 미리 받아두시면 시작이 빠릅니다.

### 2. 벡터스토어 선택

**Elasticsearch 사용 (기본)**

Elasticsearch를 실행한 뒤 그대로 앱을 시작하시면 됩니다.

```yaml
# application.yml (기본값)
spring.elasticsearch.uris: http://localhost:9200
app.vectorstore.in-memory.enabled: false
```

**In-Memory 사용**

Elasticsearch 없이 테스트하실 경우 아래와 같이 설정하시면 됩니다.

```yaml
app.vectorstore.in-memory.enabled: true
```

### 3. ETL 파이프라인 (문서 로드)

최초 실행 시 문서를 벡터스토어에 로드해야 합니다.

```yaml
app.etl.pipeline.init: true
```

문서가 이미 로드된 경우 `false`로 변경하여 중복 로드를 방지하시기 바랍니다.

### 4. 앱 시작

```bash
./gradlew bootRun
```

## 사용 방법

### 웹 UI

브라우저에서 `http://localhost:8080`에 접속하시면 됩니다.

- **Stream / Call**: 응답 모드를 선택합니다 (스트리밍 또는 일괄 응답)
- **대화 ID**: 대화별 메모리를 구분하는 식별자입니다
- **시스템 프롬프트**: LLM에게 전달할 시스템 지시를 입력합니다 (선택)

### REST API

**POST /rag/call** - 일괄 응답

```bash
curl -X POST http://localhost:8080/rag/call \
  -H "Content-Type: application/json" \
  -d '{
    "conversationId": "test-1",
    "userPrompt": "원서 접수일은?"
  }'
```

**POST /rag/stream** - 스트리밍 응답 (SSE)

```bash
curl -X POST http://localhost:8080/rag/stream \
  -H "Content-Type: application/json" \
  -d '{
    "conversationId": "test-1",
    "userPrompt": "원서 접수일은?"
  }'
```

**요청 본문 필드**

| 필드                 | 필수 | 설명                                 |
|--------------------|----|------------------------------------|
| `conversationId`   | O  | 대화 식별자 (메모리 구분용)                   |
| `userPrompt`       | O  | 사용자 질문                             |
| `systemPrompt`     | X  | 시스템 프롬프트                           |
| `chatOptions`      | X  | LLM 옵션 (예: `{"temperature": 0.5}`) |
| `filterExpression` | X  | 벡터스토어 메타데이터 필터                     |

### CLI 모드

```yaml
app.cli.enabled: true
```

위와 같이 설정 후 앱을 시작하시면 터미널에서 대화형 챗봇이 실행됩니다.

## 아키텍처

### ETL 파이프라인

```
문서 (PDF/TXT)
  → TikaDocumentReader (텍스트 추출)
  → LengthTextSplitter (200자 청크, 100자 오버랩)
  → VectorStore (임베딩 후 저장)
```

### RAG 질의 흐름

```
사용자 질문
  → MessageChatMemoryAdvisor (대화 이력 주입, 최근 10개)
  → RetrievalAugmentationAdvisor
      → VectorStoreDocumentRetriever (유사도 검색, top-3)
      → ContextualQueryAugmenter (검색 결과로 프롬프트 보강)
  → Ollama LLM (응답 생성)
  → 사용자에게 반환
```

### 주요 설정값

| 설정                    | 기본값   | 설명                       |
|-----------------------|-------|--------------------------|
| `similarityThreshold` | 0.3   | 벡터 검색 최소 유사도             |
| `topK`                | 3     | 검색할 최대 문서 수              |
| `allowEmptyContext`   | false | 검색 결과 없을 시 "정보 없음" 응답 반환 |
| `chunkSize`           | 200   | 텍스트 분할 크기 (문자 수)         |
| `chunkOverlap`        | 100   | 청크 간 겹침 (문자 수)           |
| `maxMessages`         | 10    | 대화 메모리에 유지할 최대 메시지 수     |
| `temperature`         | 0.0   | LLM 응답 결정성 (0 = 결정적)     |

## 설정 파일 (application.yml)

```yaml
spring:
  elasticsearch:
    uris: http://localhost:9200
  ai:
    ollama:
      chat.options.model: hf.co/rippertnt/HyperCLOVAX-SEED-Text-Instruct-1.5B-Q4_K_M-GGUF
      embedding.options.model: bge-m3
    vectorstore:
      elasticsearch:
        index-name: spring-ai-document-index
        dimensions: 1024
        similarity: cosine

app:
  rag:
    documents-location-pattern: classpath:rag-test-file.pdf  # 로드할 문서
  vectorstore:
    in-memory:
      enabled: false        # true: SimpleVectorStore, false: Elasticsearch
  etl:
    pipeline:
      init: false           # true: 시작 시 문서 로드
  cli:
    enabled: false          # true: CLI 모드
    filter-expression: ""   # 벡터스토어 필터
```

## 참조

- [Ragconfig.md](src/main/java/com/example/rag_chat/config/Ragconfig.md)

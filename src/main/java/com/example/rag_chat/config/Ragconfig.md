# RagConfig.java

## 파일 개요

`RagConfig`는 RAG(Retrieval-Augmented Generation) 파이프라인 전체 구성을 담당하는 Spring `@Configuration` 클래스다.  
크게 두 가지 역할을 빈으로 정의한다.

1. **ETL(Extract-Transform-Load) 파이프라인**: 문서를 읽고, 청크로 분할하고, 메타데이터를 보강한 뒤 VectorStore에 저장하는 과정 (빈 1~6)
2. **RAG Advisor**: 사용자 질의 시 VectorStore에서 관련 문서를 검색하고, 프롬프트에 컨텍스트로 주입하는 과정 (빈 7~8)

---

## 1. documentReaders — 문서 읽기 (Extract)

```
@Bean
public DocumentReader[] documentReaders(
	@Value("${app.rag.documents-location-pattern}") String documentsLocationPattern
) throws IOException {
	Resource[] resources = new PathMatchingResourcePatternResolver().getResources(documentsLocationPattern);
	return Arrays.stream(resources).map(TikaDocumentReader::new).toArray(DocumentReader[]::new);
}
```

### 동작 흐름

1. **경로 패턴에 매칭되는 파일을 Resource로 찾는다.**
   `PathMatchingResourcePatternResolver`를 사용하여 `app.rag.documents-location-pattern`에 지정된 패턴(예:
   `classpath:rag-test-file.pdf`)과 일치하는 파일을 탐색한다.
   예를 들어 경로에 A.pdf(10p), B.pdf(20p), C.pdf(1p)가 있으면 `Resource[]`에 3개의 Resource가 담긴다.

2. **각 Resource를 TikaDocumentReader로 감싼다.**
   `Arrays.stream(resources).map(TikaDocumentReader::new)`로 1:1 매핑이 이루어진다.
    - A문서 Resource -> A문서 TikaDocumentReader
    - B문서 Resource -> B문서 TikaDocumentReader
    - C문서 Resource -> C문서 TikaDocumentReader

3. **최종적으로 DocumentReader[] 배열이 반환된다.**
   파일당 하나의 Reader가 생성되는 구조다.

### 핵심 포인트

이 단계는 **파일 단위**로 Reader를 만드는 단계다. A가 10페이지여도 `documentReaders()`는 페이지를 10개로 나누지 않는다.
이는 Reader 종류와 무관하다. `TikaDocumentReader`든 `PagePdfDocumentReader`든 `documentReaders()` 단계에서는 항상 **파일 1개 = Reader 1개**로
매핑된다.
Reader를 만드는 것과 Reader가 문서를 읽는 것은 별개 단계이고, 이 빈은 읽기 전 Reader 인스턴스를 준비하는 단계일 뿐이다.
페이지 분리 여부는 다음 단계에서 `DocumentReader.read()`가 어떤 단위로 Document를 만들어 주는지에 달려 있다.

### TikaDocumentReader vs PagePdfDocumentReader

| 구분                   | TikaDocumentReader          | PagePdfDocumentReader           |
|----------------------|-----------------------------|---------------------------------|
| 분리 단위                | 파일 전체 텍스트를 한 덩어리로 추출        | PDF를 페이지별로 읽어 페이지마다 Document 생성 |
| A.pdf(10p) read() 결과 | Document 1개 (10페이지 내용이 하나로) | Document 10개 (페이지별 1개)          |
| 페이지 경계 보장            | 보장하지 않음                     | 보장함                             |
| 청킹 결과                | 문자/토큰 기준으로만 분할됨             | 페이지 내부에서 추가 청킹                  |

**페이지 단위 분리가 RAG에서 유용한 이유:**

- **근거 추적이 쉬워진다.** 검색 결과로 나온 Document가 몇 페이지에서 왔는지 명확해지고, UI에서 "A문서 7페이지 근거"처럼 출처를 보여주기 쉽다.
- **청킹 품질이 안정적일 때가 많다.** 파일 전체를 길이 기준으로 자르면 문맥 경계가 페이지 중간에서 끊기거나, 표/그림 캡션이 섞여 이상한 조각이 생길 수 있다. 페이지별로 먼저 나누면 최소한 큰 구조 경계가
  보존된다.

**페이지 단위가 항상 정답은 아니다:**

- 페이지 한 장이 너무 길면 결국 추가 청킹이 필요하다. 1페이지에 표나 긴 텍스트가 몰려 있으면 컨텍스트 한계를 넘길 수 있다.
- 반대로 페이지가 너무 짧으면(제목만 있는 경우 등) 검색 단위가 과하게 잘게 쪼개져 노이즈가 늘 수 있다. 이런 경우는 "페이지 단위 + 최소 길이 병합" 같은 후처리가 도움이 된다.

### 구체적 예시: 파일 3개가 있을 때

> 전제: 경로에 A문서(10페이지), B문서(20페이지), C문서(1페이지) 3개 파일이 있고, `documentReaders()`가 각 파일마다 Reader를 하나씩 만든 상황이다. 즉
`DocumentReader[]`는 3개다.

**TikaDocumentReader를 쓸 때:**

`DocumentReader.read()` 결과는 보통 "파일 1개 -> Document 1개" 형태다.

```
A용 Reader.read() -> Documents(보통 1개)
B용 Reader.read() -> Documents(보통 1개)
C용 Reader.read() -> Documents(보통 1개)
```

3개의 문서 배열이 흐르고, 각 `read()`가 반환하는 리스트 크기는 보통 1개다.

**PagePdfDocumentReader를 쓸 때:**

`DocumentReader.read()` 결과가 "파일 1개 -> 페이지 수만큼 Document"가 된다.

```
A(10페이지) -> Document 10개
B(20페이지) -> Document 20개
C(1페이지)  -> Document 1개
```

전체로 보면 페이지 합인 31개 Document가 생성되는 구조다.

**정리하면:**

- Reader 개수는 항상 파일당 1개다.
- 하지만 각 `Reader.read()`가 반환하는 Document 리스트 크기는 Tika면 보통 파일당 1개, PagePdf면 보통 파일당 페이지 수만큼으로 달라진다.

---

## 2. textSplitter — 텍스트 분할 (Transform)

```
@Bean
public DocumentTransformer textSplitter() {
	//return new TokenTextSplitter();
	return new LengthTextSplitter(200, 100);
}
```

### 역할

ETL 파이프라인의 Transform 단계에서 Document를 더 작은 조각(Chunk)으로 나누는 `DocumentTransformer`를 제공한다.
`TextSplitter`는 `List<Document>`를 입력받아 `List<Document>`를 반환한다. 즉 원본 Document 1개가 청킹되면, 각 청크가 **새로운 Document 객체**로 만들어진다.
각 청크 Document는 원본 텍스트의 일부분을 text로 가지고, 원본 Document의 metadata를 상속받는다. 이 구조 덕분에 파이프라인 전체가 `Document` 타입으로 일관되게 흐를 수 있고, 이후
`keywordMetadataEnricher`나 `DocumentWriter`도 동일한 `List<Document>` 인터페이스로 처리할 수 있다.
RAG에서 청킹은 필수다. LLM의 컨텍스트 창이 무한하지 않기 때문에, 문서를 적절한 크기로 쪼개 벡터화해야 검색 정확도와 비용을 같이 관리할 수 있다.

### LengthTextSplitter(200, 100)의 의미

- **chunkSize=200**: 한 조각에 들어갈 텍스트 길이를 200으로 제한한다.
- **overlap=100**: 다음 조각이 이전 조각의 뒤 100을 포함하도록 겹치게 만든다.

겹침이 없으면 문서의 연결 정보가 끊기고, 겹침이 너무 크면 저장량과 중복이 늘어나 비용이 커진다.
200/100은 문맥 보존을 강하게 잡는 설정이라 검색 정확도를 우선하는 편에 가깝다.

`DocumentTransformer`로 등록했기 때문에 파이프라인에서 `.map(textSplitter)`처럼 함수형으로 적용된다.

### TokenTextSplitter를 쓰지 않은 이유

**TokenTextSplitter의 장점:**

- 토큰 기반 분할은 LLM 입력 길이 제한과 비용 예측이 쉽다.
- 모델이 실제로 이해하는 단위가 토큰이기 때문에 컨텍스트 창에 안전하게 맞추기에도 좋다.

**한글에서 겪는 실무 이슈:**
토큰 기반 분할은 토크나이저가 어떻게 토큰을 나누는지에 따라 문장 경계가 깨지기 쉽다.
특히 한국어는 조사, 어미, 띄어쓰기 변형 때문에 토큰 분해가 문장 경계와 잘 맞지 않는 경우가 많고, 그 결과로:

- 청크가 어색한 지점에서 끊겨 검색 품질이 떨어진다.
- 같은 의미가 여러 토큰 조각으로 흩어져 임베딩 품질이 불안정해진다.
- 모델/토크나이저가 바뀌면 청킹 결과가 같이 흔들린다.

이런 이유로 일관된 기준을 갖기 위해 길이 기반 분할을 직접 구현하는 선택이 나올 수 있다.

**길이 기반의 트레이드오프:**

- 장점: 토크나이저/모델이 바뀌어도 청킹 결과가 안정적이다. 한글에서 문장 흐름이 덜 깨질 수 있다.
- 단점: 실제 모델 토큰 수와 1:1 매칭이 아니어서, 프롬프트 구성 시 토큰 초과가 발생할 수 있다.

그래서 길이 기반을 쓰더라도 최종 프롬프트 생성 시 토큰 제한으로 한 번 더 안전장치를 두는 구조가 보통 더 안정적이다.

### textSplitter가 청킹하는 대상

textSplitter는 `DocumentReader`가 만든 **Document 단위**로 동작한다.
파이프라인에서 흐름이 `DocumentReader.read()` -> `textSplitter.transform()`이므로, Reader가 반환한 각 Document의 text를 잘라서 여러 Document로 바꾸는
역할을 한다.

**TikaDocumentReader를 쓸 때:**
보통 "큰 문서 한 덩어리"가 입력이 된다.
A.pdf(10페이지)를 Tika로 읽으면 대개 Document 1개로 나오고, textSplitter는 그 큰 텍스트 덩어리를 chunkSize 기준으로 쪼갠다. 즉 "큰 문서에서 청킹"이 된다.

**PagePdfDocumentReader를 쓸 때:**
보통 "페이지 단위 Document"가 입력이 된다.
A.pdf(10페이지)를 페이지 Reader로 읽으면 Document 10개가 되고, textSplitter는 각 페이지 Document 내부에서 추가로 청킹한다. 즉 "한 페이지 안에서 청킹"이 된다. 페이지가
짧으면 페이지 자체가 chunkSize 이하라서 추가 청킹이 거의 안 일어날 수도 있다.

**정리:**

- textSplitter는 페이지를 직접 나누는 컴포넌트가 아니다. Reader가 만든 Document 단위를 더 잘게 쪼개는 컴포넌트다.
- Reader가 "문서 전체"를 하나의 Document로 만들면 textSplitter는 큰 문서를 청킹한다.
- Reader가 "페이지별"로 Document를 만들면 textSplitter는 각 페이지 안에서 청킹한다.

---

## 3. keywordMetadataEnricher — 키워드 메타데이터 보강 (Transform)

```
@Bean
public DocumentTransformer keywordMetadataEnricher(ChatModel chatModel) {
	return new KeywordMetadataEnricher(chatModel, 4);
}
```

### 동작 방식

파이프라인 흐름이 `DocumentReader.read()` -> `textSplitter` -> `keywordMetadataEnricher` 순서이므로, 키워드 추출 시점에는 이미 문서가 여러 청크
Document로 쪼개져 있다.
`KeywordMetadataEnricher`는 입력으로 들어온 Document 리스트의 각 Document 텍스트를 기반으로 키워드를 뽑는다. 따라서 **"청크 1개 = ChatModel 호출 1번"** 구조가
된다.

- **입력**: `Document(text=청크 텍스트, metadata=기존 메타데이터)`
- **처리**: 각 Document의 text를 ChatModel 프롬프트에 넣어 키워드 N개를 생성하고, 생성된 키워드를 metadata에 추가한다.
- **출력**: `Document(text는 동일, metadata에 keywords 필드가 추가된 Document)`

인자의 **4는 키워드 개수**를 의미한다. 즉 청크마다 4개의 키워드를 생성해 metadata에 붙이는 설정이다.

### 실무에서 주의할 포인트

비용과 지연이 청크 수에 정비례한다.
예를 들어 한 파일이 청킹 후 1000개 청크가 되면, 키워드 추출도 1000번 ChatModel 호출이 된다.
운영에서는 다음과 같은 전략을 사용한다:

- 키워드/요약은 "원문 문서 단위"로 한 번만 만들고, 청크에는 상속만 시키기
- 특정 문서 타입에만 적용하기
- 배치 처리 + 캐시 적용하기
- 아예 키워드는 규칙 기반(형태소/빈도)으로 만들고 LLM 호출은 줄이기

> 현재 코드에서는 이 단계가 `initEtlPipeline`에서 주석 처리(`.map(keywordMetadataEnricher)`)되어 비활성화 상태다.

### DocumentTransformer 단계의 본질

`DocumentTransformer`는 Document 리스트를 입력받아 "검색과 생성에 유리한 형태"로 변환하는 단계다.
변환에는 보통 두 부류가 있다:

- **구조 변환**: 분할, 병합, 정규화 같은 형태 변경
- **의미 보강**: 키워드, 요약, 분류, 언어 감지 같은 메타데이터 추가

현재 파이프라인에서는 `textSplitter`가 구조 변환을, `keywordMetadataEnricher`가 의미 보강을 담당한다.

### 순서는 설계 선택이다

현재는 `read -> textSplitter -> keywordMetadataEnricher` 순서로, "청킹 후 메타데이터 보강"이다.
하지만 `read -> keywordMetadataEnricher -> textSplitter`로도 구성할 수 있다.
이 경우 키워드는 원문(큰 덩어리) 기준으로 한 번만 생성하고, 이후 청킹된 청크들이 그 메타데이터를 상속받게 만들 수 있다.
LLM 호출 수가 줄어 비용이 크게 감소한다는 장점이 있지만, 청크별 키워드가 아니라 문서 전체 키워드가 되므로 청크 단위 검색 보조로는 약해질 수 있다.

---

## 4. jsonConsoleDocumentWriter — 콘솔 JSON 출력 (Load)

```
@Bean
public DocumentWriter jsonConsoleDocumentWriter(ObjectMapper objectMapper) {
	return documents -> {
		System.out.println("======= [INFO] Writing JSON Console Document ========");
		try {
			System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(documents));
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
		System.out.println("====================================================");
	};
}
```

### 역할

`DocumentWriter`는 ETL의 **Load 단계** 컴포넌트다.
이 빈은 저장소에 적재하는 것이 아니라, 적재 직전 Document 리스트를 JSON으로 예쁘게 출력하는 **디버깅용 Writer**다.

### 동작 방식

1. 파이프라인에서 전달된 documents를 `ObjectMapper`로 pretty JSON 변환
2. 콘솔에 출력
3. 변환 실패 시 `RuntimeException`으로 올려서 ETL이 실패하도록 한다

### 출력되는 내용

보통 Document에는 아래 정보가 들어간다:

- **text**: 청킹된 텍스트 조각
- **metadata**: 파일명, 경로, 페이지 정보(Reader가 제공하면), 키워드 같은 부가 정보
- **score**: 검색 결과에서나 쓰는 값이라 적재 단계에서는 보통 의미가 없다

즉 **VectorStore에 들어가기 직전 Document가 어떤 모양인지 확인하는 로그**라고 이해하면 된다. 여기서 보여주는 값은 "임베딩 벡터"가 아니라 "원문 텍스트와 메타데이터를 담은 Document"다.

---

## 5. vectorStore — 벡터 저장소 (Load)

```
@Bean
@ConditionalOnProperty(prefix = "app.vectorstore.in-memory", name = "enabled", havingValue = "true")
public VectorStore vectorStore(EmbeddingModel embeddingModel) {
	return SimpleVectorStore.builder(embeddingModel).build();
}
```

### SimpleVectorStore의 저장 과정

`SimpleVectorStore.builder(embeddingModel).build()`는 내부적으로 Document를 저장할 때 `EmbeddingModel`을 사용해 벡터를 생성한다. 저장 시점에 아래가
일어난다:

1. Document의 텍스트를 `EmbeddingModel`에 넣는다.
2. 임베딩 벡터를 얻는다.
3. **벡터 + 원문 텍스트 + 메타데이터를 함께 저장한다.**

즉 "Document가 벡터로 완전히 바뀌어 저장된다"라기보다는, Document의 text가 `EmbeddingModel`로 벡터화되고 그 벡터가 검색용 인덱스로 저장되며, 원문 텍스트와 메타데이터도 함께 보관되는
것이다.

### jsonConsoleDocumentWriter와의 관계

콘솔에는 사람이 읽기 쉬운 Document 상태를 찍고, VectorStore에는 검색을 위한 벡터 인덱스가 추가된 형태로 저장된다.
`VectorStore`는 `DocumentWriter` 인터페이스를 구현하므로, ETL 파이프라인의 `documentWriters` 배열에 `jsonConsoleDocumentWriter`와 함께 자동으로
포함된다.

---

## 6. initEtlPipeline — ETL 파이프라인 실행

```
@Bean
@Order(1)
@ConditionalOnProperty(prefix = "app.etl.pipeline", name = "init", havingValue = "true")
public ApplicationRunner initEtlPipeline(
	DocumentReader[] documentReaders,
	DocumentTransformer textSplitter,
	DocumentTransformer keywordMetadataEnricher,
	DocumentWriter[] documentWriters
) { ... }
```

### 설정

- **`@Order(1)`**: CLI Runner보다 먼저 실행되도록 보장한다. ETL이 완료되어야 VectorStore에 데이터가 있고, 그래야 CLI에서 RAG 검색이 가능하다.

### 실행 흐름 A: 청킹 후 키워드 보강

```
return args ->
        Arrays.stream(documentReaders).map(DocumentReader::read)
                .map(textSplitter)
                .map(keywordMetadataEnricher)
                .forEach(documents -> Arrays.stream(documentWriters)
                        .forEach(documentWriter -> documentWriter.write(documents))
                );
```

1. **Extract**: `documentReaders` 배열의 각 Reader가 문서를 읽어 `List<Document>`를 생성한다.
2. **Transform (청킹)**: `textSplitter`가 Document를 chunkSize 기준으로 분할한다.
3. **Transform (키워드 보강)**: `keywordMetadataEnricher`가 **청킹된 각 청크**에 대해 ChatModel을 호출하여 키워드 메타데이터를 추가한다.
4. **Load**: `documentWriters` 배열의 모든 Writer에 문서를 전달한다. `VectorStore`에 임베딩 저장 + `jsonConsoleDocumentWriter`로 콘솔 출력이 동시에
   이루어진다.

이 순서는 청크별로 정밀한 키워드를 얻을 수 있지만, 청크 수만큼 LLM 호출이 발생하므로 비용과 지연이 크다.

**출력 결과 예시:**

> 원본 문서가 "Spring AI 소개 / 벡터 검색 원리 / 임베딩 모델 비교"를 다루고, 청킹 후 3개 청크가 생긴 경우

```
청크 1: { text: "Spring AI는 ...", metadata: { keywords: ["Spring AI", "프레임워크", "LLM", "통합"] } }
청크 2: { text: "벡터 검색은 ...", metadata: { keywords: ["벡터 검색", "유사도", "코사인", "인덱스"] } }
청크 3: { text: "임베딩 모델을 ...", metadata: { keywords: ["임베딩", "bge-m3", "차원", "변환"] } }
```

각 청크마다 LLM이 개별 호출되므로, **청크별로 다른 키워드**가 붙는다. 청크의 실제 내용에 맞는 정밀한 키워드가 생성된다.

### 실행 흐름 B: 키워드 보강 후 청킹

```
return args ->
        Arrays.stream(documentReaders).map(DocumentReader::read)
                .map(keywordMetadataEnricher)
                .map(textSplitter)
                .forEach(documents -> Arrays.stream(documentWriters)
                        .forEach(documentWriter -> documentWriter.write(documents))
                );
```

1. **Extract**: `documentReaders` 배열의 각 Reader가 문서를 읽어 `List<Document>`를 생성한다.
2. **Transform (키워드 보강)**: `keywordMetadataEnricher`가 **청킹 전 원본 Document** 단위로 ChatModel을 호출하여 키워드 메타데이터를 추가한다.
3. **Transform (청킹)**: `textSplitter`가 Document를 chunkSize 기준으로 분할한다. 이때 각 청크는 원본 Document의 키워드 메타데이터를 상속받는다.
4. **Load**: `documentWriters` 배열의 모든 Writer에 문서를 전달한다. `VectorStore`에 임베딩 저장 + `jsonConsoleDocumentWriter`로 콘솔 출력이 동시에
   이루어진다.

이 순서는 원본 문서 수만큼만 LLM 호출이 발생하므로 비용이 크게 감소한다. 다만 청크별 키워드가 아니라 문서 전체 키워드가 되므로, 청크 단위 검색 보조로는 약해질 수 있다.

**출력 결과 예시:**

> 동일한 원본 문서("Spring AI 소개 / 벡터 검색 원리 / 임베딩 모델 비교")에서 청킹 전 원본 전체를 기준으로 키워드를 뽑은 경우

```
청크 1: { text: "Spring AI는 ...", metadata: { keywords: ["Spring AI", "벡터 검색", "임베딩", "RAG"] } }
청크 2: { text: "벡터 검색은 ...", metadata: { keywords: ["Spring AI", "벡터 검색", "임베딩", "RAG"] } }
청크 3: { text: "임베딩 모델을 ...", metadata: { keywords: ["Spring AI", "벡터 검색", "임베딩", "RAG"] } }
```

원본 Document에서 한 번만 LLM 호출이 발생하고, 청킹 시 메타데이터가 상속되므로 **모든 청크에 동일한 키워드**가 붙는다. 비용은 줄지만, "벡터 검색" 청크에 "임베딩"이라는 관련 없는 키워드가 함께
붙는 등 정밀도가 떨어질 수 있다.

---

## 7. printDocumentsPostProcessor — 검색 결과 콘솔 출력

```
@Bean
@ConditionalOnProperty(prefix = "app.cli", name = "enabled", havingValue = "true")
public DocumentPostProcessor printDocumentsPostProcessor() {
	return (query, documents) -> {
		System.out.println("\n[ Search Results ]");
		System.out.println("===============================================");

		if (documents == null || documents.isEmpty()) {
			System.out.println("  No search results found.");
			System.out.println("===============================================");
			return documents;
		}

		for (int i = 0; i < documents.size(); i++) {
			Document document = documents.get(i);
			System.out.printf("▶ %d Document, Score: %.2f%n", i + 1, document.getScore());
			System.out.println("-----------------------------------------------");
			Optional.ofNullable(document.getText()).stream()
				.map(text -> text.split("\n")).flatMap(Arrays::stream)
				.forEach(line -> System.out.printf("%s%n", line));
			System.out.println("===============================================");
		}
		System.out.print("\n[ RAG 사용 응답 ]\n\n");
		return documents;
	};
}
```

### 역할

`DocumentPostProcessor`는 RAG 검색 후, 검색된 문서를 LLM에 전달하기 전에 **후처리**하는 컴포넌트다.
이 빈은 검색 결과를 가공하는 것이 아니라, CLI 환경에서 사용자가 **어떤 문서가 검색되었는지 콘솔로 확인**할 수 있도록 출력하는 디버깅/모니터링 용도다.

### @ConditionalOnProperty

`app.cli.enabled=true`일 때만 생성되는 조건부 빈이다.
`retrievalAugmentationAdvisor`에서 `Optional<DocumentPostProcessor>`로 주입받기 때문에, CLI 모드가 아니면 이 빈이 등록되지 않고 후처리 없이 동작한다.

### 동작 방식

1. 검색 결과가 없으면 "No search results found." 메시지를 출력하고 빈 리스트를 그대로 반환한다.
2. 검색 결과가 있으면 각 Document에 대해 다음을 출력한다:
    - **순번**: 몇 번째 검색 결과인지
    - **Score**: VectorStore에서 계산된 유사도 점수
    - **텍스트**: 청크의 실제 내용
3. 출력 후 documents를 **그대로 반환**한다. 문서를 필터링하거나 변형하지 않는다.

### 출력 예시

```
[ Search Results ]
===============================================
▶ 1 Document, Score: 0.82
-----------------------------------------------
Spring AI는 Spring 생태계에서 LLM을 쉽게 통합할 수
있도록 지원하는 프레임워크다.
===============================================
▶ 2 Document, Score: 0.65
-----------------------------------------------
VectorStore는 임베딩 벡터를 저장하고 유사도 기반으로
문서를 검색하는 저장소다.
===============================================

[ RAG 사용 응답 ]

(여기서부터 LLM의 응답이 스트리밍으로 출력된다)
```

---

## 8. retrievalAugmentationAdvisor — RAG Advisor

```
@Bean
public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(
	VectorStore vectorStore,
	ChatClient.Builder chatClientBuilder,
	Optional<DocumentPostProcessor> documentsPostProcessor
) { ...}
```

### 역할

`RetrievalAugmentationAdvisor`는 `ChatClient`에 Advisor로 등록되어, 사용자 질의 시 RAG 파이프라인을 자동으로 실행하는 핵심 빈이다.
사용자가 질문을 보내면 이 Advisor가 중간에 개입하여 질의 변환 -> 문서 검색 -> 프롬프트 보강의 흐름을 수행한 뒤 LLM에 전달한다.

### 구성 요소

#### 1. MultiQueryExpander — 질의 확장

```java
MultiQueryExpander queryExpander = MultiQueryExpander.builder()
	.chatClientBuilder(chatClientBuilder)
	.build();
```

하나의 사용자 질의를 LLM을 사용하여 **여러 변형 질의로 확장**한다.
예를 들어 "Spring AI란?"이라는 질의가 들어오면, "Spring AI의 주요 기능은?", "Spring AI 프레임워크 개요" 같은 변형 질의를 추가로 생성한다.
변형 질의 각각으로 VectorStore를 검색하므로, 하나의 질의로는 놓칠 수 있는 관련 문서를 더 넓게 찾아 **검색 재현율(Recall)을 높이는** 역할을 한다.
쿼리 확장은 보통 LLM을 이용해 "검색용 질문"을 생성하기 때문에, 검색 전에 LLM 호출이 추가로 들어간다.
정리하면, queryExpander를 켜면 검색 누락을 줄일 수 있지만 **LLM 호출 비용과 지연이 늘어난다.**

#### 2. TranslationQueryTransformer — 질의 번역

```java
TranslationQueryTransformer translationTransformer = TranslationQueryTransformer.builder()
	.chatClientBuilder(chatClientBuilder)
	.targetLanguage("korean")
	.build();
```

질의를 지정된 언어(한국어)로 번역하는 `QueryTransformer`다.
이걸 쓰는 목적은 **검색 언어 불일치 문제를 줄이는 것**이다. 문서가 영어인데 질문이 한국어면 임베딩 모델에 따라 검색 품질이 떨어질 수 있고, 번역을 한 번 거치면 검색이 안정될 수 있다.
역시 이 과정도 **LLM 또는 번역 모델 호출이 추가로 발생**할 수 있어 비용과 지연이 늘어난다.

#### 3. ContextualQueryAugmenter — 프롬프트 보강

```java
ContextualQueryAugmenter queryAugmenter = ContextualQueryAugmenter.builder()
	.allowEmptyContext(false)
	.build();
```

검색된 문서를 컨텍스트로 사용자 질의에 주입하는 역할을 한다.
`allowEmptyContext(true)`로 설정되면 검색 결과가 없더라도 질의를 그대로 LLM에 전달한다. `false`로 설정하면 검색 결과가 없을 때 "관련 정보를 찾을 수 없다"는 응답을 반환하게 된다.
이 옵션을 켜면 서비스가 끊기지 않는 장점이 있지만, **근거 없는 답변이 나갈 가능성이 커진다**는 단점이 있다.

#### 4. VectorStoreDocumentRetriever — 문서 검색

```java
VectorStoreDocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
	.vectorStore(vectorStore)
	.similarityThreshold(0.3)
	.topK(3)
	.build();
```

VectorStore에서 질의와 유사한 문서를 검색하는 핵심 컴포넌트다.

- **`similarityThreshold(0.3)`**: 유사도 0.3 이상인 문서만 반환한다. 임계값이 낮을수록 더 많은 문서가 반환되지만 관련성이 낮은 문서도 포함될 수 있다.
- **`topK(3)`**: 유사도가 높은 상위 3개 문서만 가져온다. 이 값이 클수록 더 많은 컨텍스트를 LLM에 전달하지만, 프롬프트 토큰 사용량과 노이즈가 늘어난다.

#### 5. DocumentPostProcessor — 검색 결과 후처리 (Optional)

```
if(documentsPostProcessor !=null&&documentsPostProcessor.isPresent()) {
	builder.documentPostProcessors(documentsPostProcessor.get());
}
```

`Optional<DocumentPostProcessor>`로 주입받아, 빈이 존재할 경우에만 후처리기를 등록한다.
현재 프로젝트에서는 `app.cli.enabled=true`일 때 `printDocumentsPostProcessor`가 등록되어, 검색된 문서의 순번, 유사도 점수, 텍스트 내용을 콘솔에 출력한다. CLI 모드가
아니면 후처리기 없이 동작한다.

### RAG Advisor의 전체 실행 흐름

```
사용자 질의
  ↓
[TranslationQueryTransformer] 질의를 한국어로 번역
  ↓
[MultiQueryExpander] 질의를 여러 변형으로 확장
  ↓
[VectorStoreDocumentRetriever] 각 변형 질의로 VectorStore 검색 (유사도 0.7 이상, 최대 3개)
  ↓
[DocumentPostProcessor] 검색 결과 후처리 (CLI 모드: 콘솔 출력)
  ↓
[ContextualQueryAugmenter] 검색된 문서를 컨텍스트로 질의에 주입
  ↓
LLM에 보강된 프롬프트 전달
  ↓
응답 생성
```

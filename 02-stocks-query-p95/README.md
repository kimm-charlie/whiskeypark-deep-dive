# 02. 상품 목록 조회 p95 19.9배 개선 — 성능 + 진단 정교화

> 진단 과정(slow query 집계 → EXPLAIN ANALYZE 분해)·대안 비교·측정치는 deep-dive 자료에 정리했습니다.
> 이 README는 **그 설계가 코드의 어디에 있는지**만 안내합니다.

## 한 줄 요약

`/stocks`(최다 호출 API)의 p95 **1602ms → 80.4ms (19.9배)**. 원인은 카테고리 필터에서
세트 상품 분기를 함께 처리하는 `OR + EXISTS` 구조가 반복 스캔을 만든 것이었습니다.
매핑 테이블에 **커버링 복합 인덱스**를 추가해 코드·API 변경 없이 해결했습니다.

## 진단 → 해결 흐름

```
Grafana p95/p99 → 14일 slow query 집계(/stocks 94.9%) → 읽기 복제본 EXPLAIN ANALYZE
→ 카테고리 매핑 확인이 1,606번 반복되는 구간 확인 → 복합 인덱스 (category_id, product_id)
→ 30일 access log로 병목 조건 사용 비율 확인 → k6 worst-case 부하 테스트로 다음 전환 기준 정리
```

## 추가로 확인한 기준

- 30일 access log 기준, 카테고리 필터가 병목 조건으로 쓰일 수 있는 요청은 5% 미만이었습니다.
- `10 VUs`가 sleep 없이 worst-case 조건으로 총 100건을 요청했을 때, 현재 데이터에서는 p95가 약 200ms 수준이었습니다.
- 이후 세트 상품의 구성 상품 관계를 확인하는 구간도 추가로 보였고, relation 조회용 복합 인덱스로 줄일 수 있음을 확인했습니다.
- 같은 store 재고 데이터를 10배로 늘렸을 때 p95가 약 1.30s까지 올라가, 이 구간부터 read table 또는 검색 엔진 도입을 검토할 기준으로 잡았습니다.
- Elasticsearch는 검색 품질/fuzzy matching 문제가 아니라면 우선순위가 낮다고 보고, 현재는 인덱스 적용으로 해결했습니다.

## 코드 맵 (설계 포인트 → 파일)

| 설계 포인트 | 코드 |
|------------|------|
| 문제의 `OR + EXISTS` 동적 쿼리 (`likeCategoryIds` directMatch/setMatch 분기) | [`StoreStockQueryDslRepository`](../src/main/java/be/weskey/module/member/store_stock/repository/StoreStockQueryDslRepository.java) |
| 조회 서비스 진입점 | [`StoreStockQueryService`](../src/main/java/be/weskey/module/member/store_stock/service/StoreStockQueryService.java) |
| 매핑 테이블 커버링 복합 인덱스 추가 | [`V44__add_index_to_wpcm_for_stocks_exists.sql`](./code/V44__add_index_to_wpcm_for_stocks_exists.sql) |
| 세트 상품 relation 조회 복합 인덱스 추가 | [`V56__add_index_to_whisky_product_relation_for_set_category_filter.sql`](./code/V56__add_index_to_whisky_product_relation_for_set_category_filter.sql) |

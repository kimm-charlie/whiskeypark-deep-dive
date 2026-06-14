# 02. 상품 목록 조회 p95 19.9배 개선 — 성능 + 진단 정교화

> 진단 과정(slow query 집계 → EXPLAIN ANALYZE 분해)·대안 비교·측정치는 deep-dive 자료에 정리했습니다.
> 이 README는 **그 설계가 코드의 어디에 있는지**만 안내합니다.

## 한 줄 요약

`/stocks`(최다 호출 API)의 p95 **1602ms → 80.4ms (19.9배)**. 원인은 카테고리 필터의
`OR + EXISTS`가 semi-join 최적화를 막아 매핑 테이블을 dependent subquery로 반복 풀스캔한 것 →
매핑 테이블에 **커버링 복합 인덱스** 추가로 해결(코드·API 변경 0).

## 진단 → 해결 흐름

```
Grafana p95/p99 → 14일 slow query 집계(/stocks 94.9%) → 읽기 복제본 EXPLAIN ANALYZE
→ 병목 = wpcm 반복 풀스캔(전체의 97.5%) → 복합 인덱스 (category_id, product_id)
```

## 코드 맵 (설계 포인트 → 파일)

| 설계 포인트 | 코드 |
|------------|------|
| 문제의 `OR + EXISTS` 동적 쿼리 (`likeCategoryIds` directMatch/setMatch 분기) | [`StoreStockQueryDslRepository`](../src/main/java/be/weskey/module/member/store_stock/repository/StoreStockQueryDslRepository.java) |
| 조회 서비스 진입점 | [`StoreStockQueryService`](../src/main/java/be/weskey/module/member/store_stock/service/StoreStockQueryService.java) |
| 매핑 테이블 커버링 복합 인덱스 추가 | [`V44__add_index_to_wpcm_for_stocks_exists.sql`](./code/V44__add_index_to_wpcm_for_stocks_exists.sql) |

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

운영 실측에서 p95는 **1,602ms → 80.4ms(19.9배)**, p99는 **2,001ms → 193.9ms(10.3배)**로 줄었습니다.
인덱스는 반복되던 풀스캔을 인덱스 탐색으로 바꿔 병목을 줄인 방법입니다. 다만 데이터가 계속 늘어나면
인덱스 탐색 비용도 함께 커질 수 있으므로, 현재 운영 요청량에서 어느 정도까지 충분한지 별도로 확인했습니다.

Grafana에서 확인한 상품 목록 API의 1분 기준 피크는 약 **0.86 RPS**였습니다. 이를 **1 RPS**로 올림하고,
실제 화면에서 선택 가능한 복수 카테고리 조합을 고정해 10분간 측정했습니다. 운영에서 복수 카테고리 요청은
0.2% 미만이지만, 검증에서는 모든 요청에 해당 조건을 적용해 카테고리 필터 경로를 보수적으로 확인했습니다.

| 재고 데이터 수 | p95 | p99 |
|---:|---:|---:|
| 2,198 | 52.3ms | 67.3ms |
| 10,990 (5배) | 80.8ms | 127.0ms |
| 21,980 (10배) | 103.4ms | 143.8ms |
| 43,960 (20배) | 174.5ms | 216.6ms |

데이터를 20배(약 44,000건)로 늘려도 p95는 약 3배만 증가했습니다. 이를 운영 기준점에 적용하면
운영 p95는 약 **270ms** 수준으로 추정됩니다. 따라서 현재 요청량 기준으로 재고 **4만~5만 건** 규모까지는
인덱스만으로 충분하다고 판단했습니다. 다만 재고뿐 아니라 RPS가 함께 증가하는지도 지속 모니터링하고,
필요한 시점에는 읽기 전용 테이블 또는 Elasticsearch 도입을 검토할 예정입니다.

## 코드 맵 (설계 포인트 → 파일)

| 설계 포인트 | 코드 |
|------------|------|
| 문제의 `OR + EXISTS` 동적 쿼리 (`likeCategoryIds` directMatch/setMatch 분기) | [`StoreStockQueryDslRepository`](../src/main/java/be/weskey/module/member/store_stock/repository/StoreStockQueryDslRepository.java) |
| 조회 서비스 진입점 | [`StoreStockQueryService`](../src/main/java/be/weskey/module/member/store_stock/service/StoreStockQueryService.java) |
| 매핑 테이블 커버링 복합 인덱스 추가 | [`V44__add_index_to_wpcm_for_stocks_exists.sql`](./code/V44__add_index_to_wpcm_for_stocks_exists.sql) |
| 세트 상품 relation 조회 복합 인덱스 추가 | [`V56__add_index_to_whisky_product_relation_for_set_category_filter.sql`](./code/V56__add_index_to_whisky_product_relation_for_set_category_filter.sql) |

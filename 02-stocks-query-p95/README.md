# 02. 상품 목록 조회 p95 19.9배 개선 — 성능 + 진단 정교화

## 문제

모니터링 대시보드(Grafana)에서 상품 목록 조회(`/stocks`) API의 응답시간이 p95 **1602ms**, p99 **2001ms**까지 치솟는 것을 발견했습니다. 서비스에서 가장 많이 호출되는 API라 우선순위를 높여 원인 추적을 시작했습니다.

## 원인 추적

진단은 `Grafana p95/p99 확인 → slow query log 집계 → EXPLAIN ANALYZE로 실행계획 분해` 순서로 진행했습니다. 처음에는 `Rows_examined`가 매우 커서 상품 후보를 훑는 구간을 의심했지만, 실행계획을 본 뒤 실제 병목이 `OR + EXISTS` 조건 안의 카테고리 매핑 테이블 반복 풀스캔이라는 점을 확인했습니다.

### Slow query log

14일치 slow query log를 다운로드해 합산했을 때, slow query 대부분이 `/stocks` 패턴이었습니다.

| 항목 | 값 |
|------|----|
| slow query 총 건수 | 1,383건 / 14일 |
| 상품 목록 조회 관련 비중 | 96.7% (1,337건) |
| `/stocks` 패턴 비중 | 94.9% (1,313건) |
| `/stocks` p50 Query_time | 1.11s |
| `/stocks` p95 Query_time | 2.12s |
| `/stocks` max Query_time | 4.03s |
| 대표 Rows_examined / Rows_sent | 약 4.9M / 10 |

이 시점에는 `Rows_examined`가 매우 커서 상품 후보를 훑는 구간을 1차 원인으로 의심했습니다. 하지만 slow log만으로는 어느 테이블·서브쿼리 단계가 실제 시간을 쓰는지 알 수 없어서, 운영 읽기 전용 복제본에서 `EXPLAIN ANALYZE`로 분해했습니다.

### 문제 쿼리

`EXPLAIN ANALYZE`는 실제 slow query 조건(`store_id=4`, `categoryIds=[4,7]`, `alcoholType=1`)으로 실행했습니다. 면접 자료에서는 전체 SQL보다 `likeCategoryIds()`가 만든 EXISTS 조건 조각만 남겼습니다.

```sql
-- categoryIds=[4,7] 중 category_id=4 조건만 축약
AND (
  EXISTS (
    SELECT 1
    FROM whisky_product_category_mapping wpcm
    WHERE wpcm.whisky_product_id = ss.whisky_product_id
      AND wpcm.whisky_product_category_id = 4
  )
  OR (
    wp.alcohol_type = 11 -- SET
    AND EXISTS (/* SET baseProduct의 category_id = 4 확인 */)
  )
)

-- category_id=7도 같은 OR-EXISTS 패턴으로 한 번 더 AND 누적
```

핵심은 카테고리 필터가 `직접 카테고리 매칭 EXISTS OR 세트 상품 baseProduct 카테고리 매칭 EXISTS` 형태였고, 이 조건이 categoryId마다 `AND`로 누적됐다는 점입니다. 실제 측정 쿼리는 여기에 `store_id`, `stock_quantity`, `is_hidden`, `is_deleted`, `alcoholType` 조건과 `ORDER BY sort_order LIMIT 11`이 함께 붙어 실행됐습니다.

### 실행계획 핵심

실행계획 전체를 길게 싣기보다, 판단에 필요한 부분만 남기면 아래와 같습니다.

```text
-> 상품 후보 스캔
   (rows=19922 loops=1, actual time=0.117..11.7)
-> Select #2 (subquery in condition; dependent)
   -> Table scan on whisky_product_category_mapping
      (rows=2433 loops=1606)
```

시간을 나눠보면 상품 후보를 훑는 구간은 약 16ms였고, `Select #2`의 카테고리 매핑 반복 풀스캔이 약 **1043ms(전체의 97.5%)**였습니다. 즉 slow log의 490만 `Rows_examined`는 상품 후보 테이블 하나에서 나온 값이 아니라, 카테고리 매핑 약 2,400행을 outer row 1,606개마다 반복 스캔한 누적 비용이었습니다.

대조군으로 `OR` 없는 단순 EXISTS 쿼리를 실행했을 때는 MySQL이 서브쿼리를 한 번 materialize하고 `<auto_distinct_key>`로 조회해 **21.4ms**에 끝났습니다. 이 비교로 `OR + EXISTS` 구조가 MySQL의 semi-join 최적화를 타지 못하게 만들고, 그 결과 dependent subquery로 fallback되며 `wpcm` 풀스캔을 반복한다는 점을 확인했습니다.

## 해결

해결 방향은 크게 두 가지였습니다. 하나는 `OR` 조건을 `UNION`으로 쪼개 쿼리 구조를 바꾸는 방법이고, 다른 하나는 반복 풀스캔이 발생한 매핑 테이블에 인덱스를 추가하는 방법이었습니다.

쿼리 재작성은 효과가 클 수 있지만 QueryDSL 구조 변경 범위가 컸습니다. 반면 인덱스 추가는 실행 계획에 직접 증거가 찍힌 병목만 겨냥할 수 있고, 코드·API 변경 없이 되돌리기도 쉬웠습니다. 그래서 먼저 매핑 테이블에 복합 인덱스를 추가했습니다.

```sql
CREATE INDEX idx_wpcm_category_product
  ON whisky_product_category_mapping (whisky_product_category_id, whisky_product_id);
```

두 컬럼 모두 `=` 조건이고 EXISTS가 확인하려는 값이 인덱스 안에 포함되어 있어, 테이블 본문을 다시 읽지 않고 인덱스만으로 판단할 수 있게 했습니다. 쿼리 재작성은 인덱스로 효과가 부족할 때 다음 단계로 남겨두었습니다.

## 결과

운영 DB 덤프를 올린 로컬 환경에서 같은 쿼리가 **1057ms → 14.7ms (71.9배)**로 줄어드는 것을 먼저 확인하고 배포했습니다.

운영 실측: **p95 1602ms → 80.4ms (19.9배)**, **p99 2001ms → 193.9ms (10.3배)**

## 관련 코드

| 파일 | 역할 |
|------|------|
| [`StoreStockQueryDslRepository.java`](../src/main/java/be/weskey/module/member/store_stock/repository/StoreStockQueryDslRepository.java) | 문제의 OR-EXISTS 동적 쿼리 (`likeCategoryIds`의 directMatch/setMatch 분기) |
| [`V44__add_index_to_wpcm_for_stocks_exists.sql`](./code/V44__add_index_to_wpcm_for_stocks_exists.sql) | 매핑 테이블 복합 인덱스 추가 마이그레이션 |

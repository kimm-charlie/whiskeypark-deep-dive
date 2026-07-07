# 04. 제품 도메인 재설계 — 도메인 통합과 하위호환 유지

> 이원화 배경·세트/변형 처리 판단·검산·TestFlight 검증은 deep-dive 자료에 정리했습니다.
> 이 README는 **그 설계가 코드/마이그레이션의 어디에 있는지**만 안내합니다.

## 한 줄 요약

정보 기반 주류 정보와 상품 기반 주류 정보가 나뉘어 있던 구조를 상품 중심 도메인으로 통합했습니다.
배포된 앱이 기대하는 기존 응답 필드는 `@Deprecated` 하위호환 계층으로 남겨 **앱 강제 업데이트 없이** 전환했습니다.
세트/변형은 relation 매핑 테이블로 명시하고, 이관 중 연결이 끊길 수 있는 노트 932건은 삭제하지 않고 회원 업로드 데이터로 보존했습니다.
되돌리기 어려운 `DROP TABLE`·레거시 컬럼 제거는 앱 완전 전환 후로 보류했습니다.

## 흐름

```
이원화(정보 기반 주류 정보 ↔ 상품 기반 주류 정보, 동기화 누락 위험)
→ 1차 배포(V20~V24): 컬럼 추가 · relation 생성 · 데이터 백필 (DROP 없음)
→ 검산 + TestFlight 구버전 검증
→ 기존 응답 필드와 구 도메인은 @Deprecated로 유지, 실제 DROP은 앱 완전 전환 후로 보류
```

## 코드 맵

상품 중심 도메인 엔티티:

| 엔티티 | 역할 |
|--------|------|
| [`WhiskyProduct`](../src/main/java/be/weskey/module/member/whisky_product/entity/WhiskyProduct.java) | 새 마스터 |
| [`WhiskyProductRelation`](../src/main/java/be/weskey/module/member/whisky_product_relation/entity/WhiskyProductRelation.java) | 세트/변형 관계 매핑 |
| [`WhiskyProductCategoryMapping`](../src/main/java/be/weskey/module/member/whisky_product_category_mapping/entity/WhiskyProductCategoryMapping.java) | 카테고리 매핑(참조 기준만 새 마스터로 이동) |

데이터 이관 마이그레이션 (Flyway, 재실행 가능하게 분할):

| 파일 | 역할 |
|------|------|
| [`V20__add_whisky_product_consolidation_columns.sql`](./code/V20__add_whisky_product_consolidation_columns.sql) | 통합용 컬럼 추가 |
| [`V21__create_whisky_product_relation.sql`](./code/V21__create_whisky_product_relation.sql) | 세트/변형 관계 매핑 테이블 신설 |
| [`V22__add_whisky_product_id_to_tasting_note.sql`](./code/V22__add_whisky_product_id_to_tasting_note.sql) | 테이스팅 노트에 새 마스터 ID 연결 |
| [`V23__add_whisky_product_id_to_category_mapping.sql`](./code/V23__add_whisky_product_id_to_category_mapping.sql) | 카테고리 매핑에 새 마스터 ID 연결 |
| [`V24__migrate_whisky_to_whisky_product.sql`](./code/V24__migrate_whisky_to_whisky_product.sql) | 본 데이터 이관 |

# 04. 제품 도메인 재설계 — 12개 테이블 무중단 이관과 하위호환 유지

> 이원화 배경·세트/변형 처리 판단·검산(orphan 0, 노트 수 등식)·TestFlight 검증은 deep-dive 자료에 정리했습니다.
> 이 README는 **그 설계가 코드/마이그레이션의 어디에 있는지**만 안내합니다.

## 한 줄 요약

마스터를 `whisky` → `whisky_product`로 승격(종속 테이블 이관, 레거시 엔티티 9종 `@Deprecated` 정리)하되,
배포된 앱이 기대하는 레거시 `whiskyId`는 `@Deprecated` 하위호환 계층으로 남겨 **앱 강제 업데이트 없이 무중단** 전환.
세트/변형은 1단계 **relation 매핑 테이블**로 명시. 이관 orphan 노트 932건은 삭제 대신 회원 업로드로 보존.
되돌리기 어려운 `DROP TABLE`·레거시 컬럼 제거는 앱 완전 전환 후로 **보류**(현재 미배포 — 레거시는 `@Deprecated`로 유지 중).

## 흐름

```
이원화(whisky ↔ whisky_product, 동기화 누락 위험)
→ 1차 배포(V20~V24): 컬럼 추가 · relation 생성 · 데이터 백필 (DROP 없음)
→ 검산(orphan 0 / category NULL 0 / 노트 수 등식) + TestFlight 구버전 검증
→ 레거시 whiskyId·구 테이블은 @Deprecated로 유지, 실제 DROP은 앱 완전 전환 후로 보류
```

## 코드 맵

새 마스터 도메인 엔티티:

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
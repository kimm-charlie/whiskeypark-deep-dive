-- =============================================================================
-- V25: Whisky 레거시 테이블 정리
-- 1차 배포(V20~V24) 후 데이터 검증과 앱 하위호환 확인을 마친 뒤 실행한다.
-- =============================================================================

-- 기존 컬럼 삭제
ALTER TABLE tasting_note DROP COLUMN whisky_id;
ALTER TABLE whisky_product_category_mapping DROP COLUMN whisky_product_whisky_mapping_id;

-- 레거시 테이블 삭제 (참조 역순)
DROP TABLE IF EXISTS whisky_info_mapping;
DROP TABLE IF EXISTS whisky_product_whisky_mapping;
DROP TABLE IF EXISTS whisky_recommend;
DROP TABLE IF EXISTS whisky_recommend_file;
DROP TABLE IF EXISTS whisky_recommend_title;
DROP TABLE IF EXISTS whisky_taste;
DROP TABLE IF EXISTS alcohol_whisky_info;
DROP TABLE IF EXISTS alcohol_wine_info;
DROP TABLE IF EXISTS whisky_info;
DROP TABLE IF EXISTS whisky_brand;
DROP TABLE IF EXISTS whisky_category;
DROP TABLE IF EXISTS price_location;
DROP TABLE IF EXISTS whisky;

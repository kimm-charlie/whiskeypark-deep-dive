-- /stocks 엔드포인트의 OR-EXISTS subquery 풀스캔 제거용 복합 인덱스
-- EXPLAIN ANALYZE에서 1043ms(전체 1069ms 중 97.5%) 차지하던 wpcm 풀스캔을 인덱스 시크로 전환
CREATE INDEX idx_wpcm_category_product
    ON whisky_product_category_mapping (whisky_product_category_id, whisky_product_id);

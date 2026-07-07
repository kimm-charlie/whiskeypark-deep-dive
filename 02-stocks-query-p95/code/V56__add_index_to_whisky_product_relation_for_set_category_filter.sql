-- /stocks 엔드포인트의 SET 상품 category/alcoholType EXISTS subquery 반복 스캔 제거용 복합 인덱스
-- product_id + is_deleted 조건으로 relation을 찾고 base_product_id를 다음 category mapping 조인에 사용한다.
CREATE INDEX idx_wpr_product_deleted_base
    ON whisky_product_relation (product_id, is_deleted, base_product_id);

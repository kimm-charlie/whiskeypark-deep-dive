CREATE TABLE whisky_product_relation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    base_product_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT false
);

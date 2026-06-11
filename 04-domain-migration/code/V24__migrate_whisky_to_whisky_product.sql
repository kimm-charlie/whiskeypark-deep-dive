-- =============================================================================
-- V24: Whisky → WhiskyProduct 데이터 마이그레이션
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Step 1. WhiskyProduct에 Whisky 데이터 이관 (1:1 매핑, 세트상품 제외)
-- -----------------------------------------------------------------------------
UPDATE whisky_product wp
JOIN whisky_product_whisky_mapping wpm ON wpm.whisky_product_id = wp.id AND wpm.is_deleted = false
JOIN whisky w ON w.id = wpm.whisky_id
SET wp.alcohol_type = w.alcohol_type,
    wp.alcohol = w.alcohol,
    wp.price = w.price,
    wp.star_id = w.star_id,
    wp.has_tasting_note = w.has_tasting_note
WHERE wp.id NOT IN (
    SELECT whisky_product_id FROM whisky_product_whisky_mapping
    WHERE is_deleted = false GROUP BY whisky_product_id HAVING COUNT(DISTINCT whisky_id) > 1
);

-- -----------------------------------------------------------------------------
-- Step 2. 세트상품 — AlcoholType.SET(11) 적용
-- -----------------------------------------------------------------------------
UPDATE whisky_product wp
SET wp.alcohol_type = 11
WHERE wp.id IN (
    SELECT whisky_product_id FROM whisky_product_whisky_mapping
    WHERE is_deleted = false GROUP BY whisky_product_id HAVING COUNT(DISTINCT whisky_id) > 1
);

-- -----------------------------------------------------------------------------
-- Step 3-1. 세트상품 → whisky_product_relation 생성
-- -----------------------------------------------------------------------------
INSERT INTO whisky_product_relation (product_id, base_product_id, created_at, is_deleted)
SELECT DISTINCT
    wpm_set.whisky_product_id,
    wpm_base.whisky_product_id,
    NOW(6),
    false
FROM whisky_product_whisky_mapping wpm_set
JOIN whisky_product_whisky_mapping wpm_base
    ON wpm_base.whisky_id = wpm_set.whisky_id
    AND wpm_base.is_deleted = false
    AND wpm_base.whisky_product_id NOT IN (
        SELECT whisky_product_id FROM whisky_product_whisky_mapping
        WHERE is_deleted = false GROUP BY whisky_product_id HAVING COUNT(DISTINCT whisky_id) > 1
    )
WHERE wpm_set.whisky_product_id IN (
    SELECT whisky_product_id FROM whisky_product_whisky_mapping
    WHERE is_deleted = false GROUP BY whisky_product_id HAVING COUNT(DISTINCT whisky_id) > 1
)
AND wpm_set.is_deleted = false;

-- -----------------------------------------------------------------------------
-- Step 3-2. 볼륨/패키징 variant → whisky_product_relation 생성
-- -----------------------------------------------------------------------------
INSERT INTO whisky_product_relation (product_id, base_product_id, created_at, is_deleted)
SELECT
    wpm.whisky_product_id AS product_id,
    base.base_product_id,
    NOW(6),
    false
FROM whisky_product_whisky_mapping wpm
JOIN (
    SELECT wpm2.whisky_id, MIN(wpm2.whisky_product_id) AS base_product_id
    FROM whisky_product_whisky_mapping wpm2
    WHERE wpm2.is_deleted = false
    AND wpm2.whisky_product_id NOT IN (
        SELECT whisky_product_id FROM whisky_product_whisky_mapping
        WHERE is_deleted = false GROUP BY whisky_product_id HAVING COUNT(DISTINCT whisky_id) > 1
    )
    GROUP BY wpm2.whisky_id
    HAVING COUNT(*) > 1
) base ON base.whisky_id = wpm.whisky_id
WHERE wpm.is_deleted = false
AND wpm.whisky_product_id NOT IN (
    SELECT whisky_product_id FROM whisky_product_whisky_mapping
    WHERE is_deleted = false GROUP BY whisky_product_id HAVING COUNT(DISTINCT whisky_id) > 1
)
AND wpm.whisky_product_id != base.base_product_id;

-- -----------------------------------------------------------------------------
-- Step 3-3. variant/세트상품 → is_tasting_note_enabled = false
-- -----------------------------------------------------------------------------
UPDATE whisky_product wp
SET wp.is_tasting_note_enabled = false
WHERE wp.id IN (
    SELECT product_id FROM whisky_product_relation WHERE is_deleted = false
);

-- -----------------------------------------------------------------------------
-- Step 4. TastingNote whisky_product_id 채우기 (MIN(whisky_product_id) 기준, 세트상품 제외)
-- -----------------------------------------------------------------------------
UPDATE tasting_note tn
JOIN (
    SELECT wpm.whisky_id, MIN(wpm.whisky_product_id) as whisky_product_id
    FROM whisky_product_whisky_mapping wpm
    WHERE wpm.is_deleted = false
    AND wpm.whisky_product_id NOT IN (
        SELECT whisky_product_id FROM whisky_product_whisky_mapping
        WHERE is_deleted = false GROUP BY whisky_product_id HAVING COUNT(DISTINCT whisky_id) > 1
    )
    GROUP BY wpm.whisky_id
) mapping ON tn.whisky_id = mapping.whisky_id
SET tn.whisky_product_id = mapping.whisky_product_id
WHERE tn.whisky_id IS NOT NULL AND tn.whisky_product_id IS NULL;

-- -----------------------------------------------------------------------------
-- Step 5-1. 썸네일 없는 orphan에 whisky 이미지 복사
-- -----------------------------------------------------------------------------
UPDATE tasting_note tn
JOIN whisky w ON w.id = tn.whisky_id
SET tn.thumbnail_image_url = w.image_url
WHERE tn.whisky_id IS NOT NULL
AND tn.whisky_product_id IS NULL
AND tn.is_deleted = false
AND tn.is_whisky_uploaded_by_member = false
AND tn.thumbnail_image_url IS NULL;

-- -----------------------------------------------------------------------------
-- Step 5-2. orphan tasting_note 1건당 WhiskyMemberUpload 1건 생성
-- -----------------------------------------------------------------------------
SET @max_wmu_id = (SELECT COALESCE(MAX(id), 0) FROM whisky_member_upload);

INSERT INTO whisky_member_upload (name, brand_name, alcohol_type, is_mapped, mapping_name)
SELECT w.korean_name, '', w.alcohol_type, false, NULL
FROM tasting_note tn
JOIN whisky w ON w.id = tn.whisky_id
WHERE tn.whisky_id IS NOT NULL
AND tn.whisky_product_id IS NULL
AND tn.is_deleted = false
AND tn.is_whisky_uploaded_by_member = false
ORDER BY tn.id;

-- -----------------------------------------------------------------------------
-- Step 5-3. orphan tasting_note에 whisky_member_upload_id 매핑
-- -----------------------------------------------------------------------------
UPDATE tasting_note tn
JOIN (
    SELECT tn_sub.id as tasting_note_id,
           wmu_sub.wmu_id
    FROM (
        SELECT id, ROW_NUMBER() OVER (ORDER BY id) as rn
        FROM tasting_note
        WHERE whisky_id IS NOT NULL
        AND whisky_product_id IS NULL
        AND is_deleted = false
        AND is_whisky_uploaded_by_member = false
    ) tn_sub
    JOIN (
        SELECT id as wmu_id, ROW_NUMBER() OVER (ORDER BY id) as rn
        FROM whisky_member_upload
        WHERE id > @max_wmu_id
    ) wmu_sub ON wmu_sub.rn = tn_sub.rn
) mapping ON mapping.tasting_note_id = tn.id
SET tn.whisky_member_upload_id = mapping.wmu_id,
    tn.is_whisky_uploaded_by_member = true;

-- -----------------------------------------------------------------------------
-- Step 6. WhiskyProductCategoryMapping whisky_product_id 채우기
-- -----------------------------------------------------------------------------

-- 6-1. orphan category_mapping 삭제 (whisky_product_whisky_mapping에 존재하지 않는 참조)
DELETE FROM whisky_product_category_mapping
WHERE whisky_product_whisky_mapping_id NOT IN (
    SELECT id FROM whisky_product_whisky_mapping
);

-- 6-2. whisky_product_id 채우기
UPDATE whisky_product_category_mapping wpcm
JOIN whisky_product_whisky_mapping wpm ON wpm.id = wpcm.whisky_product_whisky_mapping_id
SET wpcm.whisky_product_id = wpm.whisky_product_id;

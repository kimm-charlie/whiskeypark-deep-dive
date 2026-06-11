ALTER TABLE whisky_product ADD COLUMN is_tasting_note_enabled BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE whisky_product ADD COLUMN has_tasting_note BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE whisky_product ADD COLUMN alcohol_type INTEGER NOT NULL DEFAULT 1;
ALTER TABLE whisky_product ADD COLUMN alcohol VARCHAR(255);
ALTER TABLE whisky_product ADD COLUMN price BIGINT;
ALTER TABLE whisky_product ADD COLUMN star_id BIGINT;

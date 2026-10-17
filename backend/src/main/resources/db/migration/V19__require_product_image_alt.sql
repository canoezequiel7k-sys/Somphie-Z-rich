ALTER TABLE product
    ADD CONSTRAINT product_image_alt_required
        CHECK (
            image_path IS NULL
            OR (image_alt IS NOT NULL AND image_alt ~ '[^[:space:]]')
        );

-- Reconcile duplicate Yamaha Ténéré model identities created when
-- canonical frontmatter spelling changed from ASCII "Tenere" to "Ténéré".
-- Preserve the original stable model ids (4 and 5), including existing
-- Garage references, while moving knowledge and user data from 10 and 11.

-- Move knowledge documents onto the original stable model ids.
UPDATE motorcycle_knowledge_documents
SET model_id = 4
WHERE model_id = 10;

UPDATE motorcycle_knowledge_documents
SET model_id = 5
WHERE model_id = 11;

-- Preserve every physical motorcycle already stored in Garage.
UPDATE garage_vehicles
SET model_id = 4
WHERE model_id = 10;

UPDATE garage_vehicles
SET model_id = 5
WHERE model_id = 11;

-- Merge aliases without duplicating normalized aliases.
INSERT INTO motorcycle_model_aliases (model_id, alias, normalized_alias)
SELECT 4, alias, normalized_alias
FROM motorcycle_model_aliases
WHERE model_id = 10
ON CONFLICT (model_id, normalized_alias) DO NOTHING;

INSERT INTO motorcycle_model_aliases (model_id, alias, normalized_alias)
SELECT 5, alias, normalized_alias
FROM motorcycle_model_aliases
WHERE model_id = 11
ON CONFLICT (model_id, normalized_alias) DO NOTHING;

DELETE FROM motorcycle_model_aliases
WHERE model_id IN (10, 11);

-- The duplicate rows are now unreferenced.
DELETE FROM motorcycle_models
WHERE id IN (10, 11);

-- A slug is the stable accent/punctuation-insensitive identity of a model
-- within one manufacturer. Prevent this class of duplicate permanently.
ALTER TABLE motorcycle_models
ADD CONSTRAINT uq_motorcycle_models_manufacturer_slug
UNIQUE (manufacturer_id, slug);

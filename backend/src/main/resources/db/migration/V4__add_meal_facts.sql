-- Checkable per-serving facts for each meal. All columns are nullable and NULL means UNKNOWN
-- (never "zero" or "safe"): the search layer fails closed on unknown values when a budget or
-- allergen constraint is set.
--   price      USD per serving
--   protein_g  grams of protein per serving
--   calories   kcal per serving
--   allergens  JSON array of Allergen tokens (milk, egg, fish, shellfish, tree_nut, peanut,
--              wheat, soy, sesame). SQL NULL = unknown; [] = known to contain none.
ALTER TABLE meal_item
    ADD COLUMN price     DECIMAL(6,2) NULL DEFAULT NULL AFTER convenience,
    ADD COLUMN protein_g DECIMAL(5,1) NULL DEFAULT NULL AFTER price,
    ADD COLUMN calories  INT          NULL DEFAULT NULL AFTER protein_g,
    ADD COLUMN allergens JSON         NULL AFTER calories;

-- ILLUSTRATIVE DEMO VALUES ONLY - NOT REAL NUTRITION OR PRICE DATA.
-- They exist so the five seed meals can exercise budget / allergen / protein checks.
-- The name guard keeps this from touching rows that merely reuse an id in another database.
UPDATE meal_item SET price = 12.50, protein_g = 38.0, calories = 520,
                     allergens = JSON_ARRAY('milk', 'egg', 'fish', 'wheat')
WHERE id = 1 AND name = 'Grilled Chicken Caesar Salad';

UPDATE meal_item SET price = 11.00, protein_g = 28.0, calories = 980,
                     allergens = JSON_ARRAY('milk', 'wheat', 'soy', 'sesame')
WHERE id = 2 AND name = 'Classic Cheeseburger with Fries';

UPDATE meal_item SET price = 6.50, protein_g = 12.0, calories = 340,
                     allergens = JSON_ARRAY('milk')
WHERE id = 3 AND name = 'Overnight Oats with Berries';

UPDATE meal_item SET price = 13.00, protein_g = 22.0, calories = 780,
                     allergens = JSON_ARRAY('milk', 'wheat')
WHERE id = 4 AND name = 'Margherita Pizza';

UPDATE meal_item SET price = 9.50, protein_g = 18.0, calories = 610,
                     allergens = JSON_ARRAY('milk', 'wheat')
WHERE id = 5 AND name = 'Tomato Basil Soup with Grilled Cheese';

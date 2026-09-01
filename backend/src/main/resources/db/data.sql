-- Seed data ported from diet-agent: 4 PUBLIC meals + 1 PERSONAL meal (owner_user_id = 1).
-- created_at / updated_at rely on the column DEFAULT, so they are omitted here.
INSERT INTO meal_item (id, source_type, owner_user_id, name,
                       meal_time, mood, scene, health_goal, cuisine, taste, convenience) VALUES
(1, 'PUBLIC', NULL, 'Tomato and Egg Noodles',
 '["Lunch", "Dinner", "Any Meal"]', '["Tired", "Feeling Down"]', '["Work", "Campus", "Home"]',
 '["Light", "Stomach-Friendly", "Easy to Digest"]', '["Home-Style", "Noodles"]', '["Light", "Tomato"]', '["Quick", "Single Serving"]'),

(2, 'PUBLIC', NULL, 'Wontons in Clear Broth',
 '["Breakfast", "Lunch", "Dinner", "Any Meal"]', '["Tired", "No Appetite"]', '["Work", "Campus", "Home"]',
 '["Light", "Stomach-Friendly", "Warming"]', '["Snacks", "Soup & Congee"]', '["Light", "Savory"]', '["Quick", "Minimal Utensils"]'),

(3, 'PUBLIC', NULL, 'Chicken Breast Healthy Bowl',
 '["Lunch", "Dinner"]', '["Calm", "Want to Relax"]', '["Work", "Post-Workout"]',
 '["Fat Loss", "High Protein", "Low Oil", "Balanced"]', '["Healthy Food"]', '["Light", "Savory"]', '["Quick", "Single Serving"]'),

(4, 'PUBLIC', NULL, 'Mala Xiang Guo',
 '["Lunch", "Dinner", "Late-Night Snack"]', '["Happy", "Want to Treat Myself"]', '["Weekend", "Group Meal", "Late-Night Snack"]',
 '["Balanced", "Energy Boost"]', '["Sichuan", "Snacks"]', '["Spicy and Numbing", "Hearty"]', '["Leisurely", "Sharing"]'),

(5, 'PERSONAL', 1, 'Beef Stew with Potatoes',
 '["Dinner"]', '["Calm"]', '["Campus"]', '["Energy Boost"]', '["Hunan"]', '["Spicy"]', '[]');

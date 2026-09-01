-- Seed data: 4 PUBLIC meals + 1 PERSONAL meal (owner_user_id = 1). Western dishes;
-- every tag is a canonical value from the slot_option dictionary for that dimension.
-- created_at / updated_at rely on the column DEFAULT, so they are omitted here.
INSERT INTO meal_item (id, source_type, owner_user_id, name,
                       meal_time, mood, scene, health_goal, cuisine, taste, convenience) VALUES
(1, 'PUBLIC', NULL, 'Grilled Chicken Caesar Salad',
 '["Lunch", "Dinner"]', '["Calm", "Want to Relax"]', '["Work", "Post-Workout"]',
 '["Fat Loss", "High Protein", "Low Carb", "Balanced"]', '["Healthy Food", "Western"]', '["Savory", "Garlicky"]', '["Quick", "Single Serving"]'),

(2, 'PUBLIC', NULL, 'Classic Cheeseburger with Fries',
 '["Lunch", "Dinner"]', '["Happy", "Want to Treat Myself"]', '["Weekend", "Group Meal"]',
 '["Energy Boost"]', '["Western", "Fast Food"]', '["Savory", "Rich and Oily"]', '["Easy Takeout", "Sharing"]'),

(3, 'PUBLIC', NULL, 'Overnight Oats with Berries',
 '["Breakfast", "Brunch"]', '["Tired", "Calm"]', '["Home", "Commute"]',
 '["Light", "Low Sugar", "Easy to Digest", "Balanced"]', '["Healthy Food"]', '["Sweet", "Creamy"]', '["Quick", "Meal-Prep Friendly", "Single Serving"]'),

(4, 'PUBLIC', NULL, 'Margherita Pizza',
 '["Dinner", "Late-Night Snack"]', '["Happy", "Want to Relax"]', '["Weekend", "Group Meal", "Alone Time"]',
 '["Balanced"]', '["Western", "Fast Food"]', '["Tomato", "Savory", "Rich and Oily"]', '["Easy Takeout", "Sharing"]'),

(5, 'PERSONAL', 1, 'Tomato Basil Soup with Grilled Cheese',
 '["Lunch", "Dinner"]', '["Tired", "Feeling Down", "Stressed"]', '["Home", "Working Overtime"]',
 '["Warming", "Stomach-Friendly", "Light"]', '["Western", "Soup & Congee", "Home-Style"]', '["Tomato", "Creamy", "Savory"]', '["Quick", "Comfortable Dine-In"]');

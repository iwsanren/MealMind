-- The 9 generic Western-style cuisine options seeded by V1 (Chinese/Italian/Mexican/
-- Indian/Thai/Mediterranean/French/American/Middle Eastern) were already replaced in
-- the running dev database with localized Chinese regional cuisine names, but that
-- change was never reflected back into the seed file V1 was built from. This brings
-- V1's baseline content in line with what's actually deployed. No meal_item row
-- references any of the old values (checked: only "Healthy Food"/"Western"/
-- "Fast Food"/"Soup & Congee"/"Home-Style" are used), so no data cleanup is needed
-- beyond the option list itself.
UPDATE slot_option SET option_value = 'Sichuan' WHERE slot_name = 'cuisine' AND option_value = 'Chinese';
UPDATE slot_option SET option_value = 'Cantonese' WHERE slot_name = 'cuisine' AND option_value = 'Italian';
UPDATE slot_option SET option_value = 'Hunan' WHERE slot_name = 'cuisine' AND option_value = 'Mexican';
UPDATE slot_option SET option_value = 'Jiangzhe' WHERE slot_name = 'cuisine' AND option_value = 'Indian';
UPDATE slot_option SET option_value = 'Northeastern' WHERE slot_name = 'cuisine' AND option_value = 'Thai';
UPDATE slot_option SET option_value = 'Shandong' WHERE slot_name = 'cuisine' AND option_value = 'Mediterranean';
UPDATE slot_option SET option_value = 'Minnan' WHERE slot_name = 'cuisine' AND option_value = 'French';
UPDATE slot_option SET option_value = 'Yunnan' WHERE slot_name = 'cuisine' AND option_value = 'American';
UPDATE slot_option SET option_value = 'Xinjiang' WHERE slot_name = 'cuisine' AND option_value = 'Middle Eastern';

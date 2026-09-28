-- V2 mistakenly localized these 9 cuisine options to Chinese regional cuisine
-- names, based on stale data found in a running dev database. It turns out
-- commit fd62695 ("fix(seed-data): localize cuisine options") had already
-- deliberately reverted data.sql to the generic English cuisine list below -
-- that commit's message is misleading but its diff is the actual intended
-- state. This restores it. No meal_item row references any of the localized
-- values (only "Healthy Food"/"Western"/"Fast Food"/"Soup & Congee"/
-- "Home-Style" are used), so no data cleanup is needed beyond the option list.
UPDATE slot_option SET option_value = 'Chinese' WHERE slot_name = 'cuisine' AND option_value = 'Sichuan';
UPDATE slot_option SET option_value = 'Italian' WHERE slot_name = 'cuisine' AND option_value = 'Cantonese';
UPDATE slot_option SET option_value = 'Mexican' WHERE slot_name = 'cuisine' AND option_value = 'Hunan';
UPDATE slot_option SET option_value = 'Indian' WHERE slot_name = 'cuisine' AND option_value = 'Jiangzhe';
UPDATE slot_option SET option_value = 'Thai' WHERE slot_name = 'cuisine' AND option_value = 'Northeastern';
UPDATE slot_option SET option_value = 'Mediterranean' WHERE slot_name = 'cuisine' AND option_value = 'Shandong';
UPDATE slot_option SET option_value = 'French' WHERE slot_name = 'cuisine' AND option_value = 'Minnan';
UPDATE slot_option SET option_value = 'American' WHERE slot_name = 'cuisine' AND option_value = 'Yunnan';
UPDATE slot_option SET option_value = 'Middle Eastern' WHERE slot_name = 'cuisine' AND option_value = 'Xinjiang';

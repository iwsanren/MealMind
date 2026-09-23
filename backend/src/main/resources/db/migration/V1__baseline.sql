-- meal_item: one meal described by 7 independent tag dimensions.
-- Each dimension is a JSON array of tag strings, matched with JSON_OVERLAPS at query time.
CREATE TABLE meal_item (
                           id            BIGINT       NOT NULL AUTO_INCREMENT,
                           source_type   VARCHAR(16)  NOT NULL,               -- SourceMode: PERSONAL / PUBLIC
                           owner_user_id BIGINT       NULL DEFAULT NULL,       -- NULL for PUBLIC; set for PERSONAL
                           name          VARCHAR(128) NOT NULL,
                           meal_time     JSON         NOT NULL,
                           mood          JSON         NOT NULL,
                           scene         JSON         NOT NULL,
                           health_goal   JSON         NOT NULL,
                           cuisine       JSON         NOT NULL,
                           taste         JSON         NOT NULL,
                           convenience   JSON         NOT NULL,
                           created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                           updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                           PRIMARY KEY (id),
                           INDEX idx_public_meal_source (source_type),
                           INDEX idx_private_meal_source (owner_user_id, source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- slot_option: dictionary of the selectable tag options for the 7 meal dimensions.
-- One row = one option_value under one slot_name. The UI lists enabled rows ordered
-- by sort_order to render each dimension's choices; disabling a row hides it without
-- losing data.
CREATE TABLE slot_option (
                           id            BIGINT       NOT NULL AUTO_INCREMENT,
                           slot_name     VARCHAR(64)  NOT NULL,               -- dimension: mealTime/mood/scene/healthGoal/cuisine/taste/convenience
                           option_value  VARCHAR(64)  NOT NULL,               -- one legal English tag for that dimension
                           sort_order    INT          NOT NULL DEFAULT 0,     -- ascending display order in the UI
                           enabled       TINYINT      NOT NULL DEFAULT 1,     -- 0 = temporarily hidden, row kept
                           created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                           updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                           PRIMARY KEY (id),
                           UNIQUE KEY uk_slot_option (slot_name, option_value),
                           INDEX idx_slot_enabled (slot_name, enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- session_state: multi-turn conversation state (phase, accumulated slots, last
-- recommendations). Primary key is business-generated ("sess_" + UUID hex), not
-- an auto-increment id, since the session id is minted before the first INSERT
-- and handed back to the caller. slots is a JSON object: the 7 tag dimensions
-- plus a "_meta" sub-object carrying sourceMode (see SessionStateService).
CREATE TABLE session_state (
                           id                   VARCHAR(64) NOT NULL,                -- "sess_" + UUID hex
                           user_id              BIGINT      NOT NULL,
                           phase                VARCHAR(32) NOT NULL,                -- SessionPhase: START/CLARIFY/RECOMMEND/PLAN
                           slots                JSON        NOT NULL,                -- 7 dimensions + _meta.sourceMode
                           last_recommendations JSON        NOT NULL,                -- meal ids from the last recommend round
                           created_at           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                           updated_at           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                           PRIMARY KEY (id),
                           INDEX idx_session_user (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- recommend_feedback: append-only log of a user's reaction (like/dislike/accept/
-- ignore, etc. - defined by the frontend, backend only checks non-blank) to a
-- recommended meal. Feeds future recommendation/ranking tuning; nothing reads
-- it yet. item_id is a soft reference to meal_item.id - no FK constraint, same
-- as the reference design - so a meal can be deleted without invalidating the
-- historical feedback row.
CREATE TABLE recommend_feedback (
                           id            BIGINT       NOT NULL AUTO_INCREMENT,
                           user_id       BIGINT       NOT NULL,
                           session_id    VARCHAR(64)  NOT NULL,
                           item_id       BIGINT       NULL DEFAULT NULL,        -- soft reference to meal_item.id, no FK
                           action        VARCHAR(32)  NOT NULL,                 -- free string: like/dislike/accept/ignore/... (frontend-defined)
                           rating        INT          NULL DEFAULT NULL,
                           reason        VARCHAR(512) NULL DEFAULT NULL,
                           created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                           PRIMARY KEY (id),
                           INDEX idx_feedback_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- diet_request_trace: one row per Orchestrator request/turn, recording what
-- happened (trace_json - the raw event list) and, optionally, a human label
-- of what SHOULD have happened (expected_intent/expected_slots/
-- expected_clarify_action) for later evaluation/tuning. Writing a row is a
-- future concern (Part 2's TraceScope, once the agent-call library is in
-- place); this table + the query/label API around it is the only part built
-- now.
CREATE TABLE diet_request_trace (
                           id                      BIGINT       NOT NULL AUTO_INCREMENT,
                           trace_id                VARCHAR(128) NOT NULL,
                           session_id              VARCHAR(64)  NOT NULL,
                           user_id                 BIGINT       NOT NULL,
                           status                  VARCHAR(32)  NOT NULL,
                           event_count             INT          NOT NULL DEFAULT 0,
                           duration_ms             BIGINT       NULL DEFAULT NULL,
                           error_message           TEXT         NULL,
                           trace_json              JSON         NOT NULL,
                           created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                           updated_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                           expected_intent         VARCHAR(64)  NULL DEFAULT NULL,
                           expected_slots          JSON         NULL,
                           expected_clarify_action VARCHAR(32)  NULL DEFAULT NULL,
                           labeled_by              BIGINT       NULL DEFAULT NULL,
                           labeled_at              DATETIME     NULL DEFAULT NULL,
                           label_note              VARCHAR(512) NULL DEFAULT NULL,
                           PRIMARY KEY (id),
                           UNIQUE KEY uk_request_trace (trace_id),
                           INDEX idx_request_trace_session (session_id, created_at),
                           INDEX idx_request_trace_user (user_id, created_at),
                           INDEX idx_request_trace_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

-- Seed data: 91 slot_option rows = the canonical option list for each of the 7 meal
-- dimensions. enabled defaults to 1; created_at / updated_at rely on the column
-- DEFAULT, so they are omitted here. sort_order steps by 10 within a dimension so
-- new options can be inserted between existing ones later. Every English label used
-- by a meal_item seed row above appears here verbatim.
INSERT INTO slot_option (id, slot_name, option_value, sort_order) VALUES
-- mealTime (8)
(1, 'mealTime', 'Breakfast', 10),
(2, 'mealTime', 'Brunch', 20),
(3, 'mealTime', 'Lunch', 30),
(4, 'mealTime', 'Afternoon Tea', 40),
(5, 'mealTime', 'Dinner', 50),
(6, 'mealTime', 'Late-Night Snack', 60),
(7, 'mealTime', 'Snack', 70),
(8, 'mealTime', 'Any Meal', 80),
-- mood (10)
(9, 'mood', 'Tired', 10),
(10, 'mood', 'Irritable', 20),
(11, 'mood', 'Happy', 30),
(12, 'mood', 'Anxious', 40),
(13, 'mood', 'Feeling Down', 50),
(14, 'mood', 'Calm', 60),
(15, 'mood', 'Stressed', 70),
(16, 'mood', 'No Appetite', 80),
(17, 'mood', 'Want to Relax', 90),
(18, 'mood', 'Want to Treat Myself', 100),
-- scene (10)
(19, 'scene', 'Work', 10),
(20, 'scene', 'Campus', 20),
(21, 'scene', 'Home', 30),
(22, 'scene', 'Weekend', 40),
(23, 'scene', 'Working Overtime', 50),
(24, 'scene', 'Post-Workout', 60),
(25, 'scene', 'Commute', 70),
(26, 'scene', 'Group Meal', 80),
(27, 'scene', 'Alone Time', 90),
(28, 'scene', 'Travel', 100),
-- healthGoal (14)
(29, 'healthGoal', 'Fat Loss', 10),
(30, 'healthGoal', 'Light', 20),
(31, 'healthGoal', 'Stomach-Friendly', 30),
(32, 'healthGoal', 'High Protein', 40),
(33, 'healthGoal', 'Balanced', 50),
(34, 'healthGoal', 'Cooling', 60),
(35, 'healthGoal', 'Low Oil', 70),
(36, 'healthGoal', 'Low Sodium', 80),
(37, 'healthGoal', 'Low Sugar', 90),
(38, 'healthGoal', 'Energy Boost', 100),
(39, 'healthGoal', 'Muscle Gain', 110),
(40, 'healthGoal', 'Low Carb', 120),
(41, 'healthGoal', 'Easy to Digest', 130),
(42, 'healthGoal', 'Warming', 140),
-- cuisine (24)
(43, 'cuisine', 'Chinese', 10),
(44, 'cuisine', 'Italian', 20),
(45, 'cuisine', 'Mexican', 30),
(46, 'cuisine', 'Indian', 40),
(47, 'cuisine', 'Thai', 50),
(48, 'cuisine', 'Mediterranean', 60),
(49, 'cuisine', 'French', 70),
(50, 'cuisine', 'American', 80),
(51, 'cuisine', 'Middle Eastern', 90),
(52, 'cuisine', 'Healthy Food', 100),
(53, 'cuisine', 'Western', 110),
(54, 'cuisine', 'Japanese', 120),
(55, 'cuisine', 'Korean', 130),
(56, 'cuisine', 'Southeast Asian', 140),
(57, 'cuisine', 'Hot Pot', 150),
(58, 'cuisine', 'BBQ', 160),
(59, 'cuisine', 'Seafood', 170),
(60, 'cuisine', 'Vegetarian', 180),
(61, 'cuisine', 'Home-Style', 190),
(62, 'cuisine', 'Street Food', 200),
(63, 'cuisine', 'Noodles', 210),
(64, 'cuisine', 'Soup & Congee', 220),
(65, 'cuisine', 'Fast Food', 230),
(66, 'cuisine', 'Dessert', 240),
-- taste (15)
(67, 'taste', 'Spicy', 10),
(68, 'taste', 'Mild Spicy', 20),
(69, 'taste', 'Medium Spicy', 30),
(70, 'taste', 'Numbing Spicy', 40),
(71, 'taste', 'Sweet', 50),
(72, 'taste', 'Sweet and Sour', 60),
(73, 'taste', 'Savory', 70),
(74, 'taste', 'Umami', 80),
(75, 'taste', 'Savory Sauce', 90),
(76, 'taste', 'Garlicky', 100),
(77, 'taste', 'Tomato', 110),
(78, 'taste', 'Curry', 120),
(79, 'taste', 'Creamy', 130),
(80, 'taste', 'Rich and Oily', 140),
(81, 'taste', 'Smoky', 150),
-- convenience (10)
(82, 'convenience', 'Quick', 10),
(83, 'convenience', 'Leisurely', 20),
(84, 'convenience', 'Easy Takeout', 30),
(85, 'convenience', 'Comfortable Dine-In', 40),
(86, 'convenience', 'Short Wait', 50),
(87, 'convenience', 'Minimal Utensils', 60),
(88, 'convenience', 'Single Serving', 70),
(89, 'convenience', 'Sharing', 80),
(90, 'convenience', 'Meal-Prep Friendly', 90),
(91, 'convenience', 'Eat on the Go', 100);

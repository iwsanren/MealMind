-- meal_item: one meal described by 7 independent tag dimensions.
-- Each dimension is a JSON array of tag strings, matched with JSON_OVERLAPS at query time.
-- NOTE: spring.sql.init.mode=always runs this on every startup, so DROP+CREATE
-- rebuilds the table (and wipes its data) each boot. Seed rows live in db/data.sql.
DROP TABLE IF EXISTS meal_item;

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
-- losing data. Same DROP+CREATE-on-every-startup rebuild as meal_item above; seed
-- rows live in db/data.sql.
DROP TABLE IF EXISTS slot_option;

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
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

-- session_state: multi-turn conversation state (phase, accumulated slots, last
-- recommendations). Primary key is business-generated ("sess_" + UUID hex), not
-- an auto-increment id, since the session id is minted before the first INSERT
-- and handed back to the caller. slots is a JSON object: the 7 tag dimensions
-- plus a "_meta" sub-object carrying sourceMode (see SessionStateService).
-- Same DROP+CREATE-on-every-startup rebuild as the tables above.
DROP TABLE IF EXISTS session_state;

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
-- historical feedback row. Same DROP+CREATE-on-every-startup rebuild as the
-- tables above.
DROP TABLE IF EXISTS recommend_feedback;

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
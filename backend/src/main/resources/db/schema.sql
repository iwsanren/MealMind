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
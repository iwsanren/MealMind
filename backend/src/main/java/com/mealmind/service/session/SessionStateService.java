package com.mealmind.service.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mealmind.entity.SessionRow;
import com.mealmind.enums.SessionPhase;
import com.mealmind.enums.SourceMode;
import com.mealmind.exception.MealException;
import com.mealmind.mapper.SessionMapper;
import com.mealmind.model.SessionState;
import com.mealmind.model.SlotBundle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Reads and writes session_state. The (future) Orchestrator is the only
 * caller: loadOrCreate() at the start of a turn, save() at the end of it.
 * Named SessionStateService (not SessionService) to leave that name free for
 * a later message-history service (an individual agent service), which is a separate concern.
 */
@Service
public class SessionStateService {

    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() {
    };

    private final SessionMapper sessionMapper;
    private final ObjectMapper objectMapper;

    // slots is a JSON *object* (7 lists + _meta), not the JSON *array* shape
    // JsonService handles for meal_item's tag columns, so this injects Jackson
    // directly instead of routing through JsonService.
    public SessionStateService(SessionMapper sessionMapper, ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.objectMapper = objectMapper;
    }

    /** New session: fresh id, START phase, empty slots, empty recommendations. */
    @Transactional
    public SessionState create(Long userId, SourceMode sourceMode) {
        String sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "");
        SessionState state = SessionState.fresh(sessionId, userId, sourceMode);
        insert(state);
        return state;
    }

    /**
     * No sessionId -> create. sessionId given but not found for this user ->
     * create using that sessionId (lets a caller pick its own id up front).
     * Found -> deserialize the persisted row back into a SessionState.
     */
    public SessionState loadOrCreate(String sessionId, Long userId, SourceMode sourceMode) {
        if (sessionId == null || sessionId.isBlank()) {
            return create(userId, sourceMode);
        }
        SessionRow row = sessionMapper.findById(sessionId, userId);
        if (row == null) {
            SessionState state = SessionState.fresh(sessionId, userId, sourceMode);
            insert(state);
            return state;
        }
        return fromRow(row, sourceMode);
    }

    /** Persists a state the caller has already updated. 0 rows affected -> the id/user pair doesn't exist. */
    @Transactional
    public void save(SessionState state) {
        int updated = sessionMapper.update(toRow(state));
        if (updated == 0) {
            throw new MealException("Session state save failed: session not found for this user");
        }
    }

    private void insert(SessionState state) {
        sessionMapper.insert(toRow(state));
    }

    private SessionState fromRow(SessionRow row, SourceMode requestSourceMode) {
        JsonNode root = parseObject(row.getSlots());
        JsonNode meta = root.path("_meta");
        SourceMode sourceMode = parseSourceMode(meta.path("sourceMode").asText(null), requestSourceMode);
        SlotBundle slots = new SlotBundle(
                readStringList(root, "mealTime"),
                readStringList(root, "mood"),
                readStringList(root, "scene"),
                readStringList(root, "healthGoal"),
                readStringList(root, "cuisine"),
                readStringList(root, "taste"),
                readStringList(root, "convenience")
        );
        return new SessionState(
                row.getId(),
                row.getUserId(),
                parsePhase(row.getPhase()),
                sourceMode,
                slots,
                parseLongList(row.getLastRecommendations())
        );
    }

    private SessionRow toRow(SessionState state) {
        SessionRow row = new SessionRow();
        row.setId(state.sessionId());
        row.setUserId(state.userId());
        row.setPhase(state.phase().name());
        row.setSlots(toSlotsJson(state));
        row.setLastRecommendations(toJson(state.lastRecommendations()));
        return row;
    }

    /** 7 dimensions at the top level, plus a "_meta" object carrying sourceMode. */
    private String toSlotsJson(SessionState state) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("mealTime", objectMapper.valueToTree(state.slots().mealTime()));
        root.set("mood", objectMapper.valueToTree(state.slots().mood()));
        root.set("scene", objectMapper.valueToTree(state.slots().scene()));
        root.set("healthGoal", objectMapper.valueToTree(state.slots().healthGoal()));
        root.set("cuisine", objectMapper.valueToTree(state.slots().cuisine()));
        root.set("taste", objectMapper.valueToTree(state.slots().taste()));
        root.set("convenience", objectMapper.valueToTree(state.slots().convenience()));
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("sourceMode", state.sourceMode() == null ? null : state.sourceMode().name());
        root.set("_meta", meta);
        return root.toString();
    }

    private JsonNode parseObject(String json) {
        try {
            return json == null || json.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(json);
        } catch (Exception e) {
            throw new MealException("Failed to parse session slots JSON", e);
        }
    }

    private List<String> readStringList(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (!node.isArray()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(node.toString(), new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new MealException("Failed to parse session slots JSON", e);
        }
    }

    private List<Long> parseLongList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, LONG_LIST);
        } catch (Exception e) {
            throw new MealException("Failed to parse session's last recommendations JSON", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            throw new MealException("Failed to serialize session state to JSON", e);
        }
    }

    /** Dirty/unknown phase text falls back to START rather than failing the whole read. */
    private SessionPhase parsePhase(String phase) {
        try {
            return phase == null ? SessionPhase.START : SessionPhase.valueOf(phase);
        } catch (IllegalArgumentException ignored) {
            return SessionPhase.START;
        }
    }

    /** Persisted sourceMode wins; falls back to the caller-supplied one when missing or dirty. */
    private SourceMode parseSourceMode(String savedSourceMode, SourceMode requestSourceMode) {
        try {
            return savedSourceMode == null || savedSourceMode.isBlank()
                    ? requestSourceMode
                    : SourceMode.valueOf(savedSourceMode);
        } catch (IllegalArgumentException ignored) {
            return requestSourceMode;
        }
    }
}

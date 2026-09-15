package com.mealmind.service.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealmind.entity.SessionRow;
import com.mealmind.enums.SessionPhase;
import com.mealmind.enums.SourceMode;
import com.mealmind.mapper.SessionMapper;
import com.mealmind.model.SessionState;
import com.mealmind.model.SlotBundle;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionStateServiceTest {

    /**
     * Map-backed stand-in for the real MyBatis mapper. Avoids Mockito for a
     * 3-method interface, and a stateful fake fits a round-trip test (write,
     * then read back) better than a pile of when()/thenReturn() stubs.
     */
    private static class InMemorySessionMapper implements SessionMapper {
        private final Map<String, SessionRow> rows = new HashMap<>();

        @Override
        public int insert(SessionRow row) {
            rows.put(row.getId(), copyOf(row));
            return 1;
        }

        @Override
        public SessionRow findById(String id, Long userId) {
            SessionRow row = rows.get(id);
            return (row == null || !row.getUserId().equals(userId)) ? null : copyOf(row);
        }

        @Override
        public int update(SessionRow row) {
            SessionRow existing = rows.get(row.getId());
            if (existing == null || !existing.getUserId().equals(row.getUserId())) {
                return 0;
            }
            rows.put(row.getId(), copyOf(row));
            return 1;
        }

        // put a row directly, bypassing insert() - used to seed dirty/legacy data
        void seed(SessionRow row) {
            rows.put(row.getId(), copyOf(row));
        }

        private SessionRow copyOf(SessionRow source) {
            SessionRow copy = new SessionRow();
            copy.setId(source.getId());
            copy.setUserId(source.getUserId());
            copy.setPhase(source.getPhase());
            copy.setSlots(source.getSlots());
            copy.setLastRecommendations(source.getLastRecommendations());
            copy.setCreatedAt(source.getCreatedAt());
            copy.setUpdatedAt(source.getUpdatedAt());
            return copy;
        }
    }

    private final InMemorySessionMapper sessionMapper = new InMemorySessionMapper();
    private final SessionStateService sessionStateService = new SessionStateService(sessionMapper, new ObjectMapper());

    @Test
    void createProducesTheExpectedDefaults() {
        SessionState state = sessionStateService.create(42L, SourceMode.PERSONAL);

        assertThat(state.sessionId()).startsWith("sess_");
        assertThat(state.userId()).isEqualTo(42L);
        assertThat(state.phase()).isEqualTo(SessionPhase.START);
        assertThat(state.sourceMode()).isEqualTo(SourceMode.PERSONAL);
        assertThat(state.slots().isEmpty()).isTrue();
        assertThat(state.lastRecommendations()).isEmpty();
    }

    @Test
    void saveThenLoadOrCreateRoundTripsEveryField() {
        SessionState created = sessionStateService.create(7L, SourceMode.PUBLIC);

        SlotBundle updatedSlots = new SlotBundle(
                List.of("Dinner"), List.of("Happy"), List.of("Home"),
                List.of("Balanced"), List.of("Western"), List.of("Savory"), List.of("Quick"));
        SessionState updated = new SessionState(
                created.sessionId(), created.userId(), SessionPhase.RECOMMEND,
                created.sourceMode(), updatedSlots, List.of(101L, 102L));

        sessionStateService.save(updated);
        SessionState reloaded = sessionStateService.loadOrCreate(created.sessionId(), 7L, SourceMode.PUBLIC);

        assertThat(reloaded.sessionId()).isEqualTo(updated.sessionId());
        assertThat(reloaded.userId()).isEqualTo(updated.userId());
        assertThat(reloaded.phase()).isEqualTo(SessionPhase.RECOMMEND);
        assertThat(reloaded.sourceMode()).isEqualTo(SourceMode.PUBLIC);
        assertThat(reloaded.slots()).isEqualTo(updatedSlots);
        assertThat(reloaded.lastRecommendations()).containsExactly(101L, 102L);
    }

    @Test
    void dirtyPhaseTextFallsBackToStart() {
        SessionRow garbage = new SessionRow();
        garbage.setId("sess_garbage");
        garbage.setUserId(1L);
        garbage.setPhase("not_a_real_phase");
        garbage.setSlots("{}");
        garbage.setLastRecommendations("[]");
        garbage.setCreatedAt(LocalDateTime.now());
        garbage.setUpdatedAt(LocalDateTime.now());
        sessionMapper.seed(garbage);

        SessionState state = sessionStateService.loadOrCreate("sess_garbage", 1L, SourceMode.PUBLIC);

        assertThat(state.phase()).isEqualTo(SessionPhase.START);
    }
}

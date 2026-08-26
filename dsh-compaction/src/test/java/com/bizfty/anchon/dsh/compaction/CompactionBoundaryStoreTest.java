package com.bizfty.anchon.dsh.compaction;

import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.storage.InMemoryStorageBackend;
import com.bizfty.anchon.dsh.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 压缩遮蔽边界存储：读写往返、缺省 0、非正数不写。
 */
class CompactionBoundaryStoreTest {

    private CompactionBoundaryStore store() {
        @SuppressWarnings("unchecked")
        ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> sp = mock(ObjectProvider.class);
        when(sp.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        return new CompactionBoundaryStore(new StorageService(sp));
    }

    @Test
    void absentBoundaryDefaultsToZero() {
        assertEquals(0, store().read(SessionId.of("s_new")));
    }

    @Test
    void writeReadRoundtrip() {
        CompactionBoundaryStore store = store();
        SessionId id = SessionId.of("s_round");
        store.write(id, 31);
        assertEquals(31, store.read(id));
        // 覆盖写
        store.write(id, 47);
        assertEquals(47, store.read(id));
    }

    @Test
    void nonPositiveWriteIsIgnored() {
        CompactionBoundaryStore store = store();
        SessionId id = SessionId.of("s_zero");
        store.write(id, 0);
        assertEquals(0, store.read(id));
    }

    @Test
    void boundariesArePerSession() {
        CompactionBoundaryStore store = store();
        store.write(SessionId.of("s_a"), 5);
        assertEquals(0, store.read(SessionId.of("s_b")));
        assertEquals(5, store.read(SessionId.of("s_a")));
    }
}

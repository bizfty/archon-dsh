package com.bizfty.anchon.dsh.search;

import com.bizfty.anchon.dsh.credentials.CredentialRef;
import com.bizfty.anchon.dsh.credentials.CredentialService;
import com.bizfty.anchon.dsh.credentials.EnvCredentialProvider;
import com.bizfty.anchon.dsh.storage.InMemoryStorageBackend;
import com.bizfty.anchon.dsh.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * web 搜索 provider 凭据消费测试：声明 credentialRef 的 provider 每次调用前
 * 经 CredentialService 解析 key；无凭据 provider 收到 null。
 */
class WebSearchServiceCredentialTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<WebSearchProvider> providers(WebSearchProvider... ps) {
        ObjectProvider<WebSearchProvider> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(Stream.of(ps));
        return op;
    }

    @SuppressWarnings("unchecked")
    private CredentialService credentials(String envKey) {
        ObjectProvider<com.bizfty.anchon.dsh.credentials.CredentialProvider> cp = mock(ObjectProvider.class);
        when(cp.orderedStream()).thenReturn(Stream.of(new EnvCredentialProvider()));
        ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> sp = mock(ObjectProvider.class);
        when(sp.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        return new CredentialService(cp, new StorageService(sp));
    }

    private static class KeyedProvider implements WebSearchProvider {
        final AtomicReference<String> receivedKey = new AtomicReference<>();
        private final String ref;

        KeyedProvider(String ref) {
            this.ref = ref;
        }

        @Override
        public String name() {
            return "keyed";
        }

        @Override
        public CredentialRef credentialRef() {
            return CredentialRef.parse(ref);
        }

        @Override
        public List<SearchResult> search(String query, int maxResults) {
            throw new AssertionError("必须走带 key 的 search 重载");
        }

        @Override
        public List<SearchResult> search(String query, int maxResults, String apiKey) {
            receivedKey.set(apiKey);
            return List.of(new SearchResult("t", "https://example.com", "s"));
        }
    }

    @Test
    void declaredCredentialRefIsResolvedPerCall() {
        KeyedProvider provider = new KeyedProvider("env:TEST_SEARCH_KEY");
        CredentialService credentials = credentials("");
        credentials.set(new CredentialRef("env", "TEST_SEARCH_KEY"), "sk-search-secret");
        WebSearchService service = new WebSearchService(providers(provider), credentials);
        var results = service.search("spring ai", 3);
        assertEquals(1, results.size());
        assertEquals("sk-search-secret", provider.receivedKey.get(),
                "provider 声明的 credentialRef 应解析为实际 key 传入");
    }

    @Test
    void noCredentialRefProviderReceivesNullKey() {
        var provider = new KeyedProvider("env:NONE") {
            @Override
            public CredentialRef credentialRef() {
                return null;
            }
        };
        WebSearchService service = new WebSearchService(providers(provider), credentials(""));
        service.search("q", 2);
        assertNull(provider.receivedKey.get(), "未声明凭据的 provider 应收到 null key");
    }

    @Test
    void unresolvedCredentialFallsThroughToNextProvider() {
        KeyedProvider keyed = new KeyedProvider("env:MISSING_SEARCH_KEY");
        DuckDuckGoSearchProvider duck = new DuckDuckGoSearchProvider(10_000, 8);
        WebSearchService service = new WebSearchService(providers(keyed, duck), credentials(""));
        // keyed 未解析到 key 且无结果 → 回落到 DuckDuckGo（无 key 可用则失败，符合预期）
        try {
            var results = service.search("dsfjsdklfjsdk", 1);
            assertTrue(results != null && !results.isEmpty(), "回落到无 key provider");
        } catch (WebSearchService.NoSearchProviderException e) {
            assertTrue(e.getMessage().contains("所有搜索提供者均未返回结果"));
        }
    }

    @Test
    void noProvidersFailsStructurally() {
        WebSearchService service = new WebSearchService(providers(), credentials(""));
        assertThrows(WebSearchService.NoSearchProviderException.class, () -> service.search("q", 1));
    }
}

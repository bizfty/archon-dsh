package com.example.dsh.search;

import com.example.dsh.credentials.CredentialRef;
import com.example.dsh.credentials.CredentialService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WebSearchService {

    private final ObjectProvider<WebSearchProvider> providers;
    private final CredentialService credentials;

    public WebSearchService(ObjectProvider<WebSearchProvider> providers, CredentialService credentials) {
        this.providers = providers;
        this.credentials = credentials;
    }

    public List<WebSearchProvider.SearchResult> search(String query, int maxResults) {
        var list = providers.orderedStream().toList();
        if (list.isEmpty()) {
            throw new NoSearchProviderException("未配置搜索提供者");
        }
        for (WebSearchProvider provider : list) {
            try {
                String apiKey = resolveApiKey(provider);
                List<WebSearchProvider.SearchResult> results = provider.search(query, maxResults, apiKey);
                if (results != null && !results.isEmpty()) {
                    return results;
                }
            } catch (RuntimeException ignored) {
            }
        }
        throw new NoSearchProviderException("所有搜索提供者均未返回结果");
    }

    /** 每操作解析：provider 声明了 credentialRef 才解析（未配置 → 空，交给 provider 决定）。 */
    private String resolveApiKey(WebSearchProvider provider) {
        CredentialRef ref = provider.credentialRef();
        if (ref == null) {
            return null;
        }
        return credentials.resolve(ref).orElse(null);
    }

    public static final class NoSearchProviderException extends RuntimeException {
        public NoSearchProviderException(String message) {
            super(message);
        }
    }
}

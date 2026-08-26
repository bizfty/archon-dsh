package com.bizfty.anchon.dsh.search;

import com.bizfty.anchon.dsh.credentials.CredentialRef;

public interface WebSearchProvider {

    String name();

    record SearchResult(String title, String url, String snippet) {
    }

    /**
     * 本 provider 需要的凭据引用（如付费搜索 API 的 key）；无需凭据的 provider
     * （如 DuckDuckGo）返回 null。
     * <p>
     * 由 {@link WebSearchService} 在每次调用前经 CredentialService 解析并传给
     * {@link #search(String, int, String)} — 每操作解析、引用不落配置。
     */
    default CredentialRef credentialRef() {
        return null;
    }

    java.util.List<SearchResult> search(String query, int maxResults);

    /**
     * 带解析后 API key 的搜索。默认实现忽略 key 回落无 key 搜索 —
     * 无凭据 provider 无需覆写。
     *
     * @param apiKey 经凭据缝解析的 key（provider 未声明 credentialRef 时为 null）
     */
    default java.util.List<SearchResult> search(String query, int maxResults, String apiKey) {
        return search(query, maxResults);
    }
}

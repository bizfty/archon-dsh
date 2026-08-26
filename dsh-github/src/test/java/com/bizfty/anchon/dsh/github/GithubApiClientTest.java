package com.bizfty.anchon.dsh.github;

import com.bizfty.anchon.dsh.credentials.CredentialService;
import com.bizfty.anchon.dsh.github.properties.GithubProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GitHub token 解析测试：明文优先、凭据引用回退、缺失返回 null。
 */
class GithubApiClientTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<CredentialService> providerOf(CredentialService service) {
        ObjectProvider<CredentialService> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(service);
        return op;
    }

    private CredentialService inMemoryCredentials() {
        @SuppressWarnings("unchecked")
        ObjectProvider<com.bizfty.anchon.dsh.credentials.CredentialProvider> op =
                mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(java.util.stream.Stream.of(
                new com.bizfty.anchon.dsh.credentials.EnvCredentialProvider()));
        return new CredentialService(op);
    }

    @Test
    void plainTokenWins() {
        GithubProperties props = new GithubProperties();
        props.setToken("plain-token");
        GithubApiClient client = new GithubApiClient(props, providerOf(inMemoryCredentials()));
        assertEquals("plain-token", client.resolveToken());
    }

    @Test
    void credentialRefResolvesToken() {
        GithubProperties props = new GithubProperties();
        props.setCredentialRef("env:GITHUB_TOKEN");
        CredentialService credentials = inMemoryCredentials();
        credentials.set(new com.bizfty.anchon.dsh.credentials.CredentialRef("env", "GITHUB_TOKEN"), "cred-token");
        GithubApiClient client = new GithubApiClient(props, providerOf(credentials));
        assertEquals("cred-token", client.resolveToken());
    }

    @Test
    void nothingConfiguredReturnsNull() {
        GithubApiClient client = new GithubApiClient(new GithubProperties(), providerOf(inMemoryCredentials()));
        assertNull(client.resolveToken());
    }

    @Test
    void missingCredentialServiceReturnsNull() {
        GithubProperties props = new GithubProperties();
        props.setCredentialRef("env:GITHUB_TOKEN");
        GithubApiClient client = new GithubApiClient(props, providerOf(null));
        assertNull(client.resolveToken());
    }
}

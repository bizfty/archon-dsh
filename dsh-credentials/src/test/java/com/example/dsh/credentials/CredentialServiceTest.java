package com.example.dsh.credentials;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭据服务测试：引用解析、每操作解析、describe 不带值、set/unset 覆盖。
 */
class CredentialServiceTest {

    @Test
    void resolvesFromEnvProvider() {
        CredentialService service = new CredentialService(
                objectProvider(new EnvCredentialProvider()));
        // HOME 一定存在
        Optional<String> value = service.resolve(new CredentialRef("env", "HOME"));
        assertTrue(value.isPresent());
        assertTrue(value.get().startsWith("/"));
    }

    @Test
    void missingKeyIsAbsent() {
        CredentialService service = new CredentialService(
                objectProvider(new EnvCredentialProvider()));
        assertTrue(service.resolve(new CredentialRef("env", "DSH_NO_SUCH_KEY_XYZ")).isEmpty());
    }

    @Test
    void describeNeverRevealsValue() {
        CredentialService service = new CredentialService(objectProvider(new FixedProvider("token-abc")));
        String desc = service.describe(new CredentialRef("fixed", "api_key"));
        assertTrue(desc.contains("fixed:api_key"));
        assertFalse(desc.contains("token-abc"), "describe 不应包含值");
    }

    @Test
    void setOverridesAndUnsetRestores() {
        CredentialService service = new CredentialService(objectProvider());
        CredentialRef ref = new CredentialRef("env", "DSH_TEST_KEY_OVERRIDE");
        assertTrue(service.resolve(ref).isEmpty());
        service.set(ref, "secret-1");
        assertEquals("secret-1", service.resolve(ref).orElse(""));
        service.unset(ref);
        assertTrue(service.resolve(ref).isEmpty());
    }

    @Test
    void parseRefFormats() {
        assertEquals(new CredentialRef("env", "OPENAI_API_KEY"), CredentialRef.parse("OPENAI_API_KEY"));
        assertEquals(new CredentialRef("file", "k1"), CredentialRef.parse("file:k1"));
    }

    private static org.springframework.beans.factory.ObjectProvider<CredentialProvider> objectProvider(
            CredentialProvider... providers) {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<CredentialProvider> op =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(op.orderedStream()).thenReturn(java.util.stream.Stream.of(providers));
        return op;
    }

    private static final class FixedProvider implements CredentialProvider {
        private final String value;

        FixedProvider(String value) {
            this.value = value;
        }

        @Override
        public String name() {
            return "fixed";
        }

        @Override
        public java.util.Optional<String> resolve(String key) {
            return java.util.Optional.of(value);
        }
    }
}

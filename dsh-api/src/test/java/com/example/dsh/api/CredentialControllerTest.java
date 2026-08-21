package com.example.dsh.api;

import com.example.dsh.credentials.CredentialRef;
import com.example.dsh.credentials.CredentialService;
import com.example.dsh.credentials.EnvCredentialProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 凭据端点测试：describe 不带值、set/unset 覆盖。
 */
class CredentialControllerTest {

    @SuppressWarnings("unchecked")
    private CredentialService serviceWith(EnvCredentialProvider... providers) {
        ObjectProvider<com.example.dsh.credentials.CredentialProvider> op =
                mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(Stream.of(providers));
        return new CredentialService(op);
    }

    @Test
    void describeNeverRevealsValue() {
        CredentialController controller = new CredentialController(serviceWith(new EnvCredentialProvider()));
        var resp = controller.describe("HOME");
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        String description = String.valueOf(resp.getBody().get("description"));
        assertTrue(!description.contains(System.getenv("HOME")), "describe 不应包含值");
    }

    @Test
    void setAndUnsetRoundTrip() {
        CredentialController controller = new CredentialController(serviceWith(new EnvCredentialProvider()));
        var setResp = controller.set(new CredentialController.SetRequest("env:DSH_TEST_CRED_API", "secret-x"));
        assertTrue(setResp.getStatusCode().is2xxSuccessful());
        var descResp = controller.describe("DSH_TEST_CRED_API");
        assertEquals(true, descResp.getBody().get("configured"));
        var unsetResp = controller.unset("DSH_TEST_CRED_API");
        assertTrue(unsetResp.getStatusCode().is2xxSuccessful());
        assertEquals(false, controller.describe("DSH_TEST_CRED_API").getBody().get("configured"));
    }

    @Test
    void invalidRefIsBadRequest() {
        CredentialController controller = new CredentialController(serviceWith());
        assertTrue(controller.describe("").getStatusCode().is4xxClientError());
    }
}

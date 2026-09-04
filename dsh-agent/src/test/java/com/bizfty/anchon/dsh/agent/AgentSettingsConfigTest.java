package com.bizfty.anchon.dsh.agent;

import com.bizfty.anchon.dsh.settings.SettingDescriptor;
import com.bizfty.anchon.dsh.settings.SettingsService;
import com.bizfty.anchon.dsh.storage.InMemoryStorageBackend;
import com.bizfty.anchon.dsh.storage.StorageService;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent 设置 schema 接入测试：装配后默认值与 AgentLoopProperties 现值一致（行为零漂移）、
 * 描述符齐全、覆盖后分层语义仍生效、注册幂等。
 */
class AgentSettingsConfigTest {

    @SuppressWarnings("unchecked")
    private SettingsService service() {
        ObjectProvider<com.bizfty.anchon.dsh.storage.StorageBackend> op = mock(ObjectProvider.class);
        when(op.orderedStream()).thenReturn(Stream.of(new InMemoryStorageBackend()));
        return new SettingsService(new StorageService(op), JsonMapper.builder().build());
    }

    @Test
    void registersDefaultsEqualToPropertiesAndDescriptors() {
        AgentLoopProperties properties = new AgentLoopProperties(5, 0.55, 50, 3);
        SettingsService settings = service();
        new AgentSettingsConfig(properties, settings);

        // 默认值与 properties 现值一致（effectiveXxx 读 settings 后行为零漂移）
        assertEquals(0.55, settings.get("agent", "temperature"));
        assertEquals(5, settings.getInt("agent", "max-steps", 0));
        assertEquals(3, settings.getInt("agent", "max-parallel-tool-calls", 0));

        // 描述符齐全且保序
        List<SettingDescriptor> descriptors = settings.describe("agent");
        assertEquals(List.of("temperature", "max-steps", "max-parallel-tool-calls"),
                descriptors.stream().map(SettingDescriptor::key).toList());
        SettingDescriptor temperature = descriptors.get(0);
        assertEquals("number", temperature.type());
        assertEquals(0.0, temperature.min());
        assertEquals(2.0, temperature.max());
        assertEquals(0.1, temperature.step());

        // all("agent") 含默认（此前为空，现设置页可显示）
        Map<String, Object> all = settings.all("agent");
        assertEquals(0.55, all.get("temperature"));
        assertEquals(5, all.get("max-steps"));
    }

    @Test
    void overrideStillWinsAfterRegistration() {
        AgentLoopProperties properties = new AgentLoopProperties(5, 0.55, 50, 3);
        SettingsService settings = service();
        new AgentSettingsConfig(properties, settings);

        settings.set("agent", "temperature", "0.3");
        assertEquals(0.3, settings.get("agent", "temperature"), "覆盖仍优先于默认");
    }

    @Test
    void repeatedRegistrationIsIdempotent() {
        AgentLoopProperties properties = new AgentLoopProperties(5, 0.55, 50, 3);
        SettingsService settings = service();
        new AgentSettingsConfig(properties, settings);
        new AgentSettingsConfig(properties, settings);

        assertTrue(settings.describedNamespaces().equals(List.of("agent")));
        assertEquals(3, settings.describe("agent").size(), "重复装配不累积描述符");
        assertEquals(0.55, settings.get("agent", "temperature"));
    }
}

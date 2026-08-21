package com.example.dsh;

import com.example.dsh.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class WorkflowRegisteredTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void workflowToolRegistered() {
        List<String> names = toolRegistry.toolNames();
        System.out.println("TOOLS: " + names);
        assertTrue(names.contains("workflow"), "缺少 workflow 工具: " + names);
    }
}

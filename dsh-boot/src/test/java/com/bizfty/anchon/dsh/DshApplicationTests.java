package com.bizfty.anchon.dsh;

import com.bizfty.anchon.dsh.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应用装配冒烟测试：全部模块 bean 能装配，工具注册表包含能力工具。
 */
@SpringBootTest
class DshApplicationTests {

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void contextLoads() {
        // 上下文装配成功即通过
    }

    @Test
    void capabilityToolsRegistered() {
        List<String> names = toolRegistry.toolNames();
        System.out.println("REGISTERED TOOLS: " + names);
        for (String expected : List.of("todo_write", "exit_plan_mode", "read_file", "write_file",
                "glob", "grep", "bash", "skill", "ask_user_question",
                "subagent", "list_agents", "send_message", "web_fetch", "web_search",
                "run_code", "workflow", "create_goal", "update_goal", "get_goal",
                "browser_navigate", "browser_click", "browser_screenshot",
                "github_list_issues", "postgres_query", "mysql_query")) {
            assertTrue(names.contains(expected), "缺少工具: " + expected);
        }
    }
}

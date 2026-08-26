package com.bizfty.anchon.dsh.code;

import java.util.List;

/**
 * run_code 运行结果（对应 DSH CodeRunResult：logs + result）。
 */
public record CodeRunResult(List<String> logs, Object result, String error) {

    public boolean failed() {
        return error != null && !error.isBlank();
    }
}

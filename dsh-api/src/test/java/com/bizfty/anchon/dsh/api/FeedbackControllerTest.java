package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.core.event.SessionEventBus;
import com.bizfty.anchon.dsh.feedback.FeedbackService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反馈端点测试：记录与查询。
 */
class FeedbackControllerTest {

    @Test
    void recordAndList() {
        FeedbackController controller = new FeedbackController(
                new FeedbackService(new SessionEventBus()));
        var resp = controller.record(new FeedbackController.RecordRequest("sess_1", "msg_1", 5, "好"));
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        assertEquals(1, controller.list("sess_1").size());
        assertEquals(1, controller.list(null).size());
    }

    @Test
    void invalidRatingIsBadRequest() {
        FeedbackController controller = new FeedbackController(
                new FeedbackService(new SessionEventBus()));
        var resp = controller.record(new FeedbackController.RecordRequest("sess_1", "msg_1", 99, "x"));
        assertTrue(resp.getStatusCode().is4xxClientError());
    }
}

package com.ll.agent.text2sql.model;

/** 流水线中一个阶段的执行记录，方便定位「哪一步慢 / 哪一步出错」。 */
public class PipelineStep {

    public final String stage;
    public final long elapsedMs;
    public final String detail;

    public PipelineStep(String stage, long elapsedMs, String detail) {
        this.stage = stage;
        this.elapsedMs = elapsedMs;
        this.detail = detail;
    }
}

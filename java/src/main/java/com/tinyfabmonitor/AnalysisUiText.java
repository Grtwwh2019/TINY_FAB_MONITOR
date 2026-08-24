package com.tinyfabmonitor;

import java.util.List;

final class AnalysisUiText {
    static final String[] COLUMNS = {"FAB ID", "FAB 描述", "Thread / Level", "状态", "分析类型", "当天完成时间",
        "基准完成时间", "依赖就绪偏移", "关键延迟依赖", "等待原因", "同Thread主阻塞", "阻塞并集时长",
        "执行差", "等待差", "就绪→R差", "任务完成偏移差", "延迟贡献", "结论"};

    static final int[] WIDTHS = {135, 210, 140, 65, 125, 155, 165, 130, 250, 220, 240, 125,
        115, 115, 115, 135, 115, 290};

    private static final String[] TOOLTIPS = {
        "任务 FAB ID。", "FAB 描述。", "任务所属 Thread ID 与 Level No。", "当前业务日期中的最新状态。",
        "结论置信度：精确执行、R区间、历史辅助、仅完成时间或数据不足。",
        "当前分析日期中任务真实进入 R 的时间。", "单基准显示真实 R；多日基准显示相对启动作业 R 的平均完成偏移。",
        "当天依赖就绪业务时刻减去基准依赖就绪业务时刻；正值表示依赖更晚就绪。",
        "直接前置依赖中，真实 R 时间最晚并决定本任务就绪时刻的任务；并列时全部显示。",
        "区分等待前置依赖、同Thread其他Level占用、无明确阻塞、数据冲突或数据不足。",
        "等待观察区间内重叠最长的同Thread、不同Level任务。只使用真实 I→R/当前I区间。",
        "所有同Thread阻塞重叠区间的并集时长；重叠部分不会重复累计。",
        "当天真实/估算执行时长减去基准执行时长。", "当天依赖就绪→I等待减去基准等待；缺少I时不精确计算。",
        "当天依赖就绪→R区间减去基准区间；缺少I时可用，但不能精确拆分等待和执行。",
        "任务 R 相对各自启动作业 R 的偏移差；用于批次内部慢点定位，不是整体delay口径。",
        "关键路径上扣除最晚上游延迟后，本任务新增的非负延迟候选。", "结合状态、时间证据和基准样本得到的主要判断。"
    };

    private AnalysisUiText() {}

    static String tooltip(int modelColumn) {
        return modelColumn < 0 || modelColumn >= TOOLTIPS.length ? null : TOOLTIPS[modelColumn];
    }

    static String help() {
        return "耗时分析指标说明\n\n" +
            "整体 delay\n只比较指定结束作业在两个业务日期中的真实 R 时分秒（跨午夜按次日小时计算）。启动作业 R 不参与整体 delay 判定。\n\n" +
            "启动对齐应完成时间\n当前启动作业真实 R + 基准批次耗时。用于进度和内部慢点分析，不产生第二个 delay 结论。\n\n" +
            "依赖就绪时间\n所有直接前置依赖中最晚的真实 R 时间。存在未完成依赖时，任务尚未就绪。Level 20 依赖路径在递归分析中截止。\n\n" +
            "依赖就绪偏移\n当天依赖就绪业务时刻 - 基准依赖就绪业务时刻。正值表示上游依赖比基准更晚完成。多日基准按有效日期平均，并显示样本数。\n\n" +
            "调度等待时间\n依赖全部就绪后，到本任务真实进入 I 的时间。没有 I 时不能精确拆分等待和执行。\n\n" +
            "同 Thread 阻塞\n同一 Thread、不同 Level 的任务，其真实 I→R（或当前 I→现在）区间与等待区间重叠。Level 20 也必须有真实 I 证据。多个区间按并集合并，不重复计时。\n\n" +
            "执行持续时间\n本任务真实 I→R。缺少 I 时，历史典型值只能作为低置信度辅助，不能作为确定阻塞证据。\n\n" +
            "就绪→R区间\n依赖就绪到任务 R 的总区间。即使缺少 I 仍可比较，但不能准确判断其中多少是调度等待、多少是自身执行。\n\n" +
            "关键延迟依赖与递归链\n从决定就绪时刻的直接依赖向上递归。并列依赖全部保留；遇到启动作业、Level 20、无更多依赖、循环或数据不足时停止。完整链在右键详情中查看。\n\n" +
            "任务完成偏移差\n任务 R 相对各自启动作业 R 的偏移差，用于定位批次内部哪个任务变慢；它与整体 delay 的结束作业时分秒口径不同。\n\n" +
            "证据等级\n“事实”来自真实 I、R、状态和区间重叠；“推断”来自仅 R 或历史典型值；证据不足时不强行归因。表格中的 -- 可能表示不适用、缺少数据或无法可靠计算，右键详情会说明原因。";
    }

    static String details(Models.AnalysisTaskMetric m) {
        StringBuilder text = new StringBuilder();
        text.append("FAB：").append(m.fabId).append("\n描述：").append(empty(m.fabDescription))
            .append("\nThread / Level：").append(m.threadId).append(" / ").append(m.levelNo)
            .append("\n状态：").append(m.status).append("\n分析类型：").append(m.confidence)
            .append("\n基准有效样本：").append(m.baselineSampleCount).append(" 天")
            .append("\n结论：").append(m.reason).append("\n等待原因：").append(m.waitReason)
            .append("\n证据：").append(empty(m.evidence))
            .append("\n\n当天 R：").append(UiFormat.dateTime(m.completedAt))
            .append("\n基准 R：").append(m.baselineCompletionAverage ? "多日平均（见完成偏移）" : UiFormat.dateTime(m.baselineCompletedAt))
            .append("\n当天依赖就绪：").append(UiFormat.dateTime(m.readinessAt))
            .append("\n基准依赖就绪：").append(m.baselineCompletionAverage ? "多日平均（见依赖就绪偏移）" : UiFormat.dateTime(m.baselineReadinessAt))
            .append("\n依赖就绪偏移：").append(signed(m.readinessClockDeltaSeconds))
            .append("\n关键延迟依赖：").append(empty(m.keyDelayedDependency));
        appendList(text, "\n递归延迟链：", m.delayedDependencyChains);
        appendList(text, "\n未完成直接依赖：", m.incompleteDependencies);
        text.append("\n\n当天就绪→R：").append(duration(m.readyToCompleteSeconds))
            .append("\n基准就绪→R：").append(duration(m.baselineReadyToCompleteSeconds))
            .append("\n就绪→R差：").append(signed(m.readyToCompleteDeltaSeconds))
            .append("\n当天执行：").append(duration(m.executionSeconds)).append(m.executionEstimated ? "（估算）" : "")
            .append("\n基准执行：").append(duration(m.baselineExecutionSeconds)).append(m.baselineExecutionEstimated ? "（含估算）" : "")
            .append("\n执行差：").append(signed(m.executionDeltaSeconds))
            .append("\n当天等待：").append(duration(m.waitSeconds)).append(m.waitEstimated ? "（估算）" : "")
            .append("\n基准等待：").append(duration(m.baselineWaitSeconds)).append(m.baselineWaitEstimated ? "（含估算）" : "")
            .append("\n等待差：").append(signed(m.waitDeltaSeconds))
            .append("\n任务完成偏移差：").append(signed(m.completionDelaySeconds))
            .append("\n延迟贡献：").append(UiFormat.duration(m.delayContributionSeconds))
            .append("\n\n同Thread主阻塞：").append(empty(m.primaryThreadBlocker))
            .append("\n阻塞区间并集：").append(duration(m.threadBlockedSeconds))
            .append("\n调度数据冲突：").append(m.schedulingConflict ? "是（其他Level与本任务真实I→R重叠）" : "否");
        if (!m.threadBlockers.isEmpty()) {
            text.append("\n全部同Thread阻塞区间：");
            for (Models.AnalysisBlocker blocker : m.threadBlockers) {
                text.append("\n  - ").append(blocker.fabId).append(" ").append(empty(blocker.fabDescription))
                    .append("（").append(blocker.threadId).append("/").append(blocker.levelNo).append("）")
                    .append("：").append(UiFormat.dateTime(blocker.startedAt)).append(" → ")
                    .append(blocker.ongoing ? "当前仍在 I" : UiFormat.dateTime(blocker.completedAt))
                    .append("，与等待区间重叠 ").append(UiFormat.duration(blocker.overlapSeconds));
            }
        }
        if (!m.startBasis.isEmpty()) text.append("\n\n估算依据：").append(m.startBasis);
        return text.toString();
    }

    private static void appendList(StringBuilder text, String title, List<String> values) {
        text.append(title);
        if (values == null || values.isEmpty()) { text.append("--"); return; }
        for (String value : values) text.append("\n  - ").append(value);
    }

    private static String duration(Long seconds) { return seconds == null ? "--（缺少数据或不适用）" : UiFormat.duration(seconds); }
    private static String signed(Long seconds) { return seconds == null ? "--（无法可靠计算）" : (seconds >= 0 ? "+" : "-") + UiFormat.duration(Math.abs(seconds)); }
    private static String empty(String value) { return value == null || value.trim().isEmpty() || "--".equals(value) ? "--" : value; }
}

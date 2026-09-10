package com.tinyfabmonitor;

import java.util.List;

final class AnalysisUiText {
    static final String[] COLUMNS = {"FAB ID", "FAB 描述", "Thread / Level", "状态", "当天 R", "基准 R",
        "完成时刻差", "启动对齐完成差", "当天依赖就绪", "基准依赖就绪", "依赖就绪偏移",
        "就绪后完成间隔差", "当天关键前置依赖", "基准关键前置依赖", "数据质量", "人工核对建议"};

    static final int[] WIDTHS = {135, 210, 140, 65, 155, 155, 120, 140, 155, 155, 130, 150,
        260, 260, 170, 260};

    private static final String[] TOOLTIPS = {
        "任务 FAB ID。", "FAB 描述。", "任务所属 Thread ID 与 Level No。", "分析日期中的最新状态。",
        "分析日期中数据库记录的有效、非占位 R 时间。", "基准日期中同一完整任务键的有效、非占位 R 时间。",
        "两个业务日期中任务 R 完成时刻的差；跨午夜会保留次日偏移。",
        "任务 R 分别减去各自启动作业 R 后再相减，用于排除批次启动时间不同的影响。",
        "当天所有直接前置依赖中最晚的有效 R 时间。", "基准日期所有直接前置依赖中最晚的有效 R 时间。",
        "依赖就绪时间分别按各自启动作业 R 对齐后的差。正值表示上游整体更晚。",
        "（当天任务R－当天依赖就绪）－（基准任务R－基准依赖就绪）。它只能定位区间变长，不能自动认定原因。",
        "当天最晚完成、实际决定本任务依赖就绪时刻的直接前置任务；并列时全部显示。",
        "基准日期最晚完成、实际决定本任务依赖就绪时刻的直接前置任务；并列时全部显示。",
        "说明该行能否可靠比较，以及依赖数据是否缺失或有歧义。",
        "基于纯 R 时间给出的人工核对方向，不会自动认定执行、调度等待或同 Thread 阻塞原因。"
    };

    private AnalysisUiText() {}

    static String tooltip(int modelColumn) {
        return modelColumn < 0 || modelColumn >= TOOLTIPS.length ? null : TOOLTIPS[modelColumn];
    }

    static String help() {
        return "耗时分析指标说明（纯 R 模式）\n\n" +
            "整体 delay\n只比较指定结束作业在分析日期和基准日期中的有效 R 完成时刻。分析日期更晚就是 delay；跨午夜按连续时间处理。\n\n" +
            "启动对齐应完成时间\n当前启动作业 R + 基准批次耗时。它用于批次内部对齐和进度参照，不产生第二个 delay 结论。启动作业缺少真实 R 时不分析。\n\n" +
            "启动对齐完成差\n先计算每个任务 R 相对各自启动作业 R 的偏移，再比较两天。这样不会把批次整体启动较晚误当成某个任务内部变慢。\n\n" +
            "依赖就绪时间\n所有直接前置依赖中最晚的有效 R 时间。并列最晚的依赖全部保留。依赖缺失、未 R、占位时间或同一 FAB 映射到多个任务时，不强行计算。纯 R ETA 固定在 Level 40 截止，Level 40 必须有有效 R。\n\n" +
            "精确分解\n当依赖数据完整时：启动对齐完成差 = 依赖就绪偏移 + 就绪后完成间隔差。这是 R 时间的数学分解，不代表已经判断出任务实际执行慢、调度等待或同 Thread 阻塞。\n\n" +
            "关键前置依赖与递归链\n从当天实际决定依赖就绪的前置任务向上递归，并列分支全部保留；纯 R ETA 最远递归到 Level 40，遇到循环、数据不足或映射歧义时停止。右键任务可查看完整链和缺失项。\n\n" +
            "ETA 与 Checkpoint\n未完成结束作业使用历史“依赖就绪→R”区间计算 P50/P75。任务已经就绪时，只保留总时长大于当前已等待时间的历史样本；若全部排除，则停止点预测，下一次自动刷新作为 Checkpoint，并提示人工检查。ETA 不参与正式 delay 判定。\n\n" +
            "关注阈值\n只影响高亮和人工核对建议，不改变任何原始差值，也不改变结束作业的整体 delay 结论。\n\n" +
            "数据边界\n本页不读取或推算 I 时间，不自动判断等待原因或同 Thread 阻塞。未完成 ETA 只使用有效 R、依赖 DAG 与本地已有历史样本，不为 ETA 额外查询历史日期。";
    }

    static String details(Models.AnalysisTaskMetric m) {
        StringBuilder text = new StringBuilder();
        text.append("FAB：").append(m.fabId).append("\n描述：").append(empty(m.fabDescription))
            .append("\nThread / Level：").append(m.threadId).append(" / ").append(m.levelNo)
            .append("\n状态：").append(m.status)
            .append("\n数据质量：").append(m.dataQuality)
            .append("\n人工核对建议：").append(m.recommendation)
            .append("\n证据口径：").append(empty(m.evidence))
            .append("\n基准有效样本：").append(m.baselineSampleCount).append(" 天")
            .append("\n\n当天 R：").append(UiFormat.dateTime(m.completedAt))
            .append("\n基准 R：").append(m.baselineCompletionAverage ? "多日平均（见偏移）" : UiFormat.dateTime(m.baselineCompletedAt))
            .append("\n完成时刻差：").append(signed(m.completionClockDeltaSeconds))
            .append("\n启动对齐完成差：").append(signed(m.completionDelaySeconds))
            .append("\n\n当天依赖就绪：").append(UiFormat.dateTime(m.readinessAt))
            .append("\n基准依赖就绪：").append(m.baselineCompletionAverage ? "多日平均（见偏移）" : UiFormat.dateTime(m.baselineReadinessAt))
            .append("\n依赖就绪偏移：").append(signed(m.readinessClockDeltaSeconds))
            .append("\n当天就绪后完成间隔：").append(duration(m.readyToCompleteSeconds))
            .append("\n基准就绪后完成间隔：").append(duration(m.baselineReadyToCompleteSeconds))
            .append("\n就绪后完成间隔差：").append(signed(m.readyToCompleteDeltaSeconds))
            .append("\n\n当天关键前置依赖：").append(empty(m.targetReadinessDependency))
            .append("\n基准关键前置依赖：").append(empty(m.baselineReadinessDependency));
        appendList(text, "\n递归关键链：", m.delayedDependencyChains);
        appendList(text, "\n未完成或缺失的直接依赖：", m.incompleteDependencies);
        appendList(text, "\n依赖映射歧义：", m.ambiguousDependencies);
        text.append("\n\n说明：以上只做 R 时间事实对比和区间分解；具体变慢原因需要人工核对。");
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

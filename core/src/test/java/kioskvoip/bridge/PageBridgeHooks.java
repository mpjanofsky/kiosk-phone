// SPDX-License-Identifier: Apache-2.0
package kioskvoip.bridge;

/** Test access to PageBridge's package-private hooks from other packages. */
public final class PageBridgeHooks {
    private PageBridgeHooks() { }

    public interface Runner { String run(CallButtons.Button button); }

    public static void urlRunner(PageBridge bridge, Runner runner) { bridge.setUrlRunner(runner::run); }
}

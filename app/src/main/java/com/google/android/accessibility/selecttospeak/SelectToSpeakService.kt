package com.google.android.accessibility.selecttospeak

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Companion accessibility service whose only job is to be *enabled*.
 *
 * @Created by fork author on Sep 23, 2026.
 *
 * @Warning
 *  ! This class is **not** Google code and comes from no Google library. It
 *  ! lives in an Android build's source tree, published by this fork, and its
 *  ! package name is borrowed on purpose: the app behaviour it works around is
 *  ! keyed on exactly this fully qualified class name.
 *  ! zh-CN: 本类**不是** Google 的代码, 也不来自任何 Google 库. 它属于本 fork 的源码树,
 *  ! 其包名是刻意借用的: 它所绕开的应用行为正是以这个完整类名为判据.
 *
 * @Why
 *  ! Some apps decide whether to publish their view hierarchy by looking at the
 *  ! list of enabled accessibility services. WeChat is the observed one: with
 *  ! only the regular AutoJs6 service enabled it exposes a single empty
 *  ! placeholder node, and it does so for every client -- the framework's own
 *  ! `uiautomator` included. Measured on this device (WeChat 8.0.78):
 *  !  - {AutoJs6 AccessibilityServiceUsher}                      -> empty tree
 *  !  - {AutoJs6 Usher + Huawei VanAccessibilityService}          -> empty tree
 *  !  - {AutoJs6 Usher + a same-named service in our own package} -> empty tree
 *  !  - {AutoJs6 Usher + org.auto.god/<this very class name>}     -> full tree
 *  ! Only the last one flips the decision, which is why this companion keeps
 *  ! the foreign package path: a merely similar name is not enough.
 *  ! zh-CN: 某些应用会依据"当前启用了哪些无障碍服务"来决定是否发布自己的视图树.
 *  ! 实测微信就是如此: 只启用常规的 AutoJs6 服务时, 它只暴露一个空的占位节点,
 *  ! 且对所有客户端都如此 (含系统自带的 `uiautomator`). 本机实测 (微信 8.0.78):
 *  !  - {AutoJs6 AccessibilityServiceUsher}                     -> 空树
 *  !  - {AutoJs6 Usher + 华为 VanAccessibilityService}           -> 空树
 *  !  - {AutoJs6 Usher + 同名的自家包内服务}                      -> 空树
 *  !  - {AutoJs6 Usher + org.auto.god/本类名}                    -> 完整树
 *  ! 只有最后一种能反转该决定, 这正是本伴生服务保留外来包路径的原因:
 *  ! 仅仅"名字相像"是不够的.
 *
 * @Note
 *  ! It does nothing at runtime on purpose: no events, and no touching of the
 *  ! core service singleton. Everything the fork needs is still served by
 *  ! `org.autojs.autojs.core.accessibility.AccessibilityServiceUsher`, and
 *  ! disabling this companion simply restores the previous behaviour.
 *  ! zh-CN: 它在运行时刻意什么都不做: 不订阅事件, 也不触碰核心服务的单例.
 *  ! fork 需要的功能依旧由 `AccessibilityServiceUsher` 提供;
 *  ! 关闭本伴生服务即恢复原有行为.
 */
class SelectToSpeakService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        /* Deliberately empty: this service exists to appear in the enabled list. */
    }

    override fun onInterrupt() {
        /* Deliberately empty. */
    }

    override fun onServiceConnected() {
        Log.i(TAG, "companion service connected")
    }

    companion object {

        private val TAG = SelectToSpeakService::class.java.simpleName

    }

}
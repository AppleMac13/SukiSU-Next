package com.sukisu.nova.ui.screen.about

import androidx.compose.runtime.Composable

/**
 * 关于页与主页「了解」页共用同一份内容 (assets/learn_more.txt)，
 * 不再单独维护检查更新、贡献者名单、源码/频道入口等条目。
 */
@Composable
fun AboutScreen() {
    LearnMoreScreen()
}

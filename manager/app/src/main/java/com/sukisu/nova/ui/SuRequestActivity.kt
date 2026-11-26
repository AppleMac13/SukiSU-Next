package com.sukisu.nova.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.WindowManager
import com.sukisu.nova.Natives

/**
 * 超级用户授权对话框。
 *
 * KernelSU-Next 的内核没有"待审批的 su 请求"这种状态：uid 要么在白名单里，
 * 要么 su 静默以原身份执行。因此 root 桥在收到未授权 uid 的 su 时用 `am start`
 * 拉起本 Activity，用户点击后由它写入 profile 并回传决定，su 进程再重试提权。
 */
class SuRequestActivity : Activity() {

    private var uid: Int = -1
    private var key: String = ""
    private var name: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )

        uid = intent.getStringExtra("su_uid")?.toIntOrNull() ?: -1
        key = intent.getStringExtra("su_key") ?: ""
        name = intent.getStringExtra("su_name") ?: ""
        val cmd = intent.getStringExtra("su_cmd") ?: ""

        if (uid <= 0) {
            finish()
            return
        }

        val requester = when {
            key.isNotEmpty() -> "$key  (uid $uid)"
            name.isNotEmpty() -> "$name  (uid $uid)"
            else -> "uid $uid"
        }

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("超级用户请求")
            .setMessage(
                "$requester\n\n请求执行：\n$cmd\n\n是否授予超级用户权限？"
            )
            .setCancelable(false)
            .setPositiveButton("允许") { _, _ -> decide(true) }
            .setNegativeButton("拒绝") { _, _ -> decide(false) }
            .create()

        dialog.setOnDismissListener { finish() }
        dialog.show()
    }

    private fun decide(allow: Boolean) {
        if (allow) {
            runCatching {
                val profileKey = key.ifEmpty { name.ifEmpty { "uid$uid" } }
                val profile = Natives.getAppProfile(profileKey, uid)?.copy(allowSu = true)
                if (profile != null) Natives.setAppProfile(profile)
            }
        }
        Natives.suRequestDecision(uid, allow)
        finish()
    }
}

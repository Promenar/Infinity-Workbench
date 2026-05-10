package com.promenar.nexara.data.local.inference

import android.os.Build

object GpuDetector {
    fun supportsVulkan(): Boolean {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java, String::class.java)
            val vulkanDriver = getMethod.invoke(null, "ro.hardware.vulkan", "") as String
            vulkanDriver.isNotBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        } catch (_: Exception) {
            Build.HARDWARE.contains("qcom", ignoreCase = true) ||
                Build.HARDWARE.contains("exynos", ignoreCase = true) ||
                Build.HARDWARE.contains("mt6", ignoreCase = true)
        }
    }

    fun recommendedThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores >= 8 -> 6
            cores >= 6 -> 4
            else -> 2
        }
    }
}

package com.promenar.nexara.ui.avatar

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.promenar.nexara.R
import com.yalantis.ucrop.UCropActivity

/**
 * 为头像裁剪提供由应用自身控制的完成动作，并让系统栏与当前应用主题保持一致。
 */
class AvatarCropActivity : UCropActivity() {
    private var completionAction: AppCompatTextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val surface = intent.getIntExtra(AvatarCropIntentFactory.EXTRA_SURFACE_COLOR, Color.BLACK)
        @Suppress("DEPRECATION")
        window.navigationBarColor = surface
        val lightSystemBars = intent.getBooleanExtra(
            AvatarCropIntentFactory.EXTRA_LIGHT_SYSTEM_BARS,
            false,
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }
        applyToolbarInsets()
        installCompletionAction()
        findViewById<Toolbar>(com.yalantis.ucrop.R.id.toolbar)?.post {
            installCompletionAction()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val created = super.onCreateOptionsMenu(menu)
        menu.findItem(com.yalantis.ucrop.R.id.menu_crop)?.isVisible = false
        hideLibraryMenu()
        return created
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val prepared = super.onPrepareOptionsMenu(menu)
        val cropAction = menu.findItem(com.yalantis.ucrop.R.id.menu_crop)
        completionAction?.visibility = if (cropAction?.isVisible == true) View.VISIBLE else View.GONE
        cropAction?.isVisible = false
        hideLibraryMenu()
        completionAction?.bringToFront()
        return prepared
    }

    private fun installCompletionAction() {
        val toolbar = findViewById<Toolbar>(com.yalantis.ucrop.R.id.toolbar) ?: return
        if (completionAction == null) {
            completionAction = AppCompatTextView(this).apply {
                id = R.id.avatar_crop_completion
                layoutParams = Toolbar.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(56),
                    Gravity.END or Gravity.CENTER_VERTICAL,
                )
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(
                    intent.getIntExtra(AvatarCropIntentFactory.EXTRA_ON_SURFACE_COLOR, Color.WHITE),
                )
                isClickable = true
                isFocusable = true
                visibility = View.GONE
                setOnClickListener { cropAndSaveImage() }
                toolbar.addView(this)
            }
        }
        completionAction?.apply {
            text = completionTitle()
            contentDescription = completionTitle()
            bringToFront()
        }
        hideLibraryMenu()
        invalidateOptionsMenu()
    }

    private fun applyToolbarInsets() {
        val toolbar = findViewById<Toolbar>(com.yalantis.ucrop.R.id.toolbar) ?: return
        val baseHeight = toolbar.layoutParams.height
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.layoutParams = (view.layoutParams as RelativeLayout.LayoutParams).apply {
                topMargin = statusBarTop
                height = baseHeight
            }
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)
    }

    private fun completionTitle(): String =
        intent?.getStringExtra(AvatarCropIntentFactory.EXTRA_COMPLETION_TITLE)
            ?: getString(R.string.avatar_crop_use_photo)

    private fun hideLibraryMenu() {
        val toolbar = findViewById<Toolbar>(com.yalantis.ucrop.R.id.toolbar) ?: return
        for (index in 0 until toolbar.childCount) {
            (toolbar.getChildAt(index) as? ActionMenuView)?.visibility = View.GONE
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

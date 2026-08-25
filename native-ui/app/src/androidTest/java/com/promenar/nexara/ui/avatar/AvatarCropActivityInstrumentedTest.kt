package com.promenar.nexara.ui.avatar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.promenar.nexara.R
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AvatarCropActivityInstrumentedTest {

    @Test
    fun cropperShowsExplicitCompletionAndWritesSquareAvatar() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "avatar-crop-source.png")
        val destination = File(context.cacheDir, "avatar-crops/device-result.jpg").apply {
            parentFile?.mkdirs()
            delete()
        }
        val bitmap = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(35, 110, 220))
            FileOutputStream(source).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }

        val completion = context.getString(R.string.avatar_crop_use_photo)
        val intent = AvatarCropIntentFactory.create(
            context = context,
            source = Uri.fromFile(source),
            destination = Uri.fromFile(destination),
            title = context.getString(R.string.avatar_crop_title),
            completionTitle = completion,
            palette = AvatarCropPalette(
                surface = Color.rgb(18, 18, 20),
                onSurface = Color.WHITE,
                primary = Color.rgb(170, 155, 255),
                scrim = 0xb3000000.toInt(),
                outline = Color.LTGRAY,
            ),
        )

        ActivityScenario.launch<AvatarCropActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    completion,
                    activity.intent?.getStringExtra(AvatarCropIntentFactory.EXTRA_COMPLETION_TITLE),
                )
                val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(
                    com.yalantis.ucrop.R.id.toolbar,
                )
                assertEquals(Color.rgb(18, 18, 20), (toolbar.background as ColorDrawable).color)
                assertEquals(
                    android.view.View.GONE,
                    activity.findViewById<android.view.View>(
                        com.yalantis.ucrop.R.id.controls_wrapper,
                    ).visibility,
                )
            }
            onView(withId(R.id.avatar_crop_completion)).check(matches(isDisplayed()))
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val loadDeadline = SystemClock.uptimeMillis() + 10_000
            var imageLoaded = false
            while (!imageLoaded && SystemClock.uptimeMillis() < loadDeadline) {
                scenario.onActivity { activity ->
                    imageLoaded = activity.findViewById<android.widget.ImageView>(
                        com.yalantis.ucrop.R.id.image_view_crop,
                    ).drawable != null
                }
                if (!imageLoaded) SystemClock.sleep(50)
            }
            assertTrue(imageLoaded)
            scenario.onActivity { activity ->
                val completionAction = activity.findViewById<android.view.View>(
                    R.id.avatar_crop_completion,
                )
                assertEquals(
                    Color.WHITE,
                    (completionAction as android.widget.TextView).currentTextColor,
                )
                val statusBarBottom = ViewCompat.getRootWindowInsets(completionAction)
                    ?.getInsets(WindowInsetsCompat.Type.statusBars())
                    ?.top
                    ?: 0
                val location = IntArray(2)
                completionAction.getLocationOnScreen(location)
                assertTrue(location[1] + completionAction.height / 2f > statusBarBottom)
            }
            onView(withId(R.id.avatar_crop_completion)).perform(click())
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (!destination.isFile && SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(50)
            }
        }

        assertTrue(destination.isFile)
        val result = BitmapFactory.decodeFile(destination.absolutePath)
        assertTrue(result != null)
        try {
            assertEquals(result.width, result.height)
            assertTrue(result.width in 1..AvatarCropIntentFactory.OUTPUT_SIZE)
        } finally {
            result.recycle()
        }
    }
}

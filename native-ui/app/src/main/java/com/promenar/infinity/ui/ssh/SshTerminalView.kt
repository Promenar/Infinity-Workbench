package com.promenar.infinity.ui.ssh

import android.content.Context
import android.graphics.Canvas
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.infinityMonospaceTypeface
import com.termux.terminal.TextStyle as TerminalTextStyle
import com.termux.view.TerminalRenderer
import kotlin.math.max

/** 渲染器与输入均连接远端 PTY，视图本身没有本地命令执行入口。 */
internal class SshTerminalView(context: Context) : View(context) {
    var onInput: (String, Boolean, Boolean) -> Unit = { _, _, _ -> }
    var onGridChanged: (TerminalGrid) -> Unit = {}
    private var buffer: RemoteTerminalBuffer? = null
    private var updateCallback: (() -> Unit)? = null
    private var activeConnection: BaseInputConnection? = null
    private var topRow = 0
    private var scrollRemainder = 0f
    private val renderer = TerminalRenderer(
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics).toInt(),
        infinityMonospaceTypeface(context),
    )
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            performClick()
            return true
        }

        override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val emulator = buffer?.emulator ?: return true
            val distance = distanceY + scrollRemainder
            val delta = (distance / renderer.fontLineSpacing).toInt()
            scrollRemainder = distance - delta * renderer.fontLineSpacing
            topRow = (topRow + delta).coerceIn(-emulator.screen.activeTranscriptRows, 0)
            invalidate()
            return true
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isSaveEnabled = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        contentDescription = context.getString(R.string.ssh_terminal_accessibility)
        val inset = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt()
        setPadding(inset, inset, inset, inset)
    }

    fun attach(next: RemoteTerminalBuffer) {
        if (buffer === next) return
        detachObserver()
        buffer = next
        topRow = 0
        val observer: () -> Unit = {
            if (topRow < 0) topRow = max(-next.emulator.screen.activeTranscriptRows, topRow - next.emulator.scrollCounter)
            postInvalidateOnAnimation()
        }
        updateCallback = observer
        next.onChanged = observer
        updateGrid()
        invalidate()
    }

    fun detach() {
        detachObserver()
        buffer = null
        onInput = { _, _, _ -> }
        onGridChanged = {}
        activeConnection?.editable?.clear()
        activeConnection?.closeConnection()
        activeConnection = null
        clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
    }

    private fun detachObserver() {
        buffer?.let { if (it.onChanged === updateCallback) it.onChanged = null }
        updateCallback = null
    }

    fun showKeyboard() {
        if (!isEnabled) return
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun scrollToLatest() {
        topRow = 0
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        showKeyboard()
        return true
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateGrid()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    private fun updateGrid() {
        val emulator = buffer?.emulator ?: return
        val grid = TerminalInput.grid(
            width - paddingLeft - paddingRight,
            height - paddingTop - paddingBottom - renderer.fontLineSpacing,
            renderer.fontWidth,
            renderer.fontLineSpacing,
        )
        if (grid.columns != emulator.mColumns || grid.rows != emulator.mRows) {
            emulator.resize(grid.columns, grid.rows)
            topRow = topRow.coerceIn(-emulator.screen.activeTranscriptRows, 0)
        }
        onGridChanged(grid)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val emulator = buffer?.emulator ?: return
        canvas.drawColor(emulator.mColors.mCurrentColors[
            if (emulator.isReverseVideo) TerminalTextStyle.COLOR_INDEX_FOREGROUND else TerminalTextStyle.COLOR_INDEX_BACKGROUND
        ])
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        renderer.render(emulator, canvas, topRow, -1, -1, -1, -1)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        val consumed = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return consumed || super.onTouchEvent(event)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flush()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flush()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (editable?.isNotEmpty() == true) return super.deleteSurroundingText(beforeLength, afterLength)
                if (beforeLength > 0) onInput("\u007F".repeat(beforeLength.coerceAtMost(128)), false, false)
                if (afterLength > 0) onInput("\u001B[3~".repeat(afterLength.coerceAtMost(128)), false, false)
                return true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (editable?.isNotEmpty() == true) return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                return deleteSurroundingText(beforeLength, afterLength)
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                onInput("\r", false, false)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean = dispatchKeyEvent(event)

            private fun flush() {
                val pending = editable ?: return
                if (pending.isNotEmpty()) {
                    onInput(pending.toString(), false, false)
                    pending.clear()
                }
            }
        }.also { activeConnection = it }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val emulator = buffer?.emulator ?: return super.onKeyDown(keyCode, event)
        val special = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> "\u007F"
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001B[3~"
            KeyEvent.KEYCODE_ESCAPE -> "\u001B"
            KeyEvent.KEYCODE_TAB -> if (event.isShiftPressed) "\u001B[Z" else "\t"
            KeyEvent.KEYCODE_DPAD_UP -> TerminalInput.arrow('A', emulator.isCursorKeysApplicationMode)
            KeyEvent.KEYCODE_DPAD_DOWN -> TerminalInput.arrow('B', emulator.isCursorKeysApplicationMode)
            KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalInput.arrow('C', emulator.isCursorKeysApplicationMode)
            KeyEvent.KEYCODE_DPAD_LEFT -> TerminalInput.arrow('D', emulator.isCursorKeysApplicationMode)
            KeyEvent.KEYCODE_MOVE_HOME -> "\u001B[H"
            KeyEvent.KEYCODE_MOVE_END -> "\u001B[F"
            KeyEvent.KEYCODE_PAGE_UP -> "\u001B[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001B[6~"
            KeyEvent.KEYCODE_F1 -> "\u001BOP"
            KeyEvent.KEYCODE_F2 -> "\u001BOQ"
            KeyEvent.KEYCODE_F3 -> "\u001BOR"
            KeyEvent.KEYCODE_F4 -> "\u001BOS"
            KeyEvent.KEYCODE_F5 -> "\u001B[15~"
            KeyEvent.KEYCODE_F6 -> "\u001B[17~"
            KeyEvent.KEYCODE_F7 -> "\u001B[18~"
            KeyEvent.KEYCODE_F8 -> "\u001B[19~"
            KeyEvent.KEYCODE_F9 -> "\u001B[20~"
            KeyEvent.KEYCODE_F10 -> "\u001B[21~"
            KeyEvent.KEYCODE_F11 -> "\u001B[23~"
            KeyEvent.KEYCODE_F12 -> "\u001B[24~"
            else -> null
        }
        if (special != null) {
            onInput(special, false, false)
            return true
        }
        val character = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        if (character != 0 && character and KeyCharacterMap.COMBINING_ACCENT == 0) {
            onInput(String(Character.toChars(character)), event.isCtrlPressed, event.isAltPressed)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

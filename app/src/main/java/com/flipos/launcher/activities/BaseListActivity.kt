package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.ui.SoftKeyBar
import com.flipos.launcher.util.SoftKeyRouter
import com.flipos.launcher.util.applyFakeBold
import com.flipos.launcher.util.hideNavigationBar
import com.flipos.launcher.util.toastIfUnknownKey

/**
 * Shared scaffolding for the vertical list screens (Options, Hide Apps,
 * Shortcuts, App Picker): a title bar, a [RecyclerView] of focusable rows and
 * the bottom [SoftKeyBar]. Subclasses populate the adapter and set the labels.
 *
 * Also owns the one key map every list screen shares: Left soft key runs
 * [onPrimaryKey], Right soft key and MENU run [onOptionsKey], and the hardware
 * Back key goes back. Subclasses override the hooks rather than onKeyDown.
 */
abstract class BaseListActivity : AppCompatActivity() {

    protected lateinit var titleView: TextView
    protected lateinit var listView: RecyclerView
    protected lateinit var softKeys: SoftKeyBar

    /** The accent color applied this onCreate, so [onResume] can detect a change and [recreate]. */
    private var appliedAccentColor: LauncherPrefs.AccentColor? = null

    /** The theme mode applied this onCreate, so [onResume] can detect a change and [recreate]. */
    private var appliedThemeMode: LauncherPrefs.ThemeMode? = null

    /**
     * True once [onResume] has triggered a [recreate] for an accent or theme
     * mode change. Subclasses overriding [onResume] should `return` early
     * when this is set so they don't do resume work (reloads, focus) on the
     * dying instance.
     */
    protected var isRecreatingForAccent = false
        private set

    private val softKeyRouter = SoftKeyRouter(onPrimary = { onPrimaryKey() }, onOptions = { onOptionsKey() })

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = LauncherPrefs(this)
        // Must happen before super.onCreate(): unlike the accent-color
        // overlay below (which merges one attribute onto whatever theme is
        // already resolved), a real light/dark switch needs the
        // AppCompat.Light family itself active before AppCompatActivity's
        // own onCreate resolves it - so AlertDialog's own default chrome
        // (background, buttons), not just this app's own content, renders
        // light too.
        val themeMode = prefs.getThemeMode()
        appliedThemeMode = themeMode
        if (themeMode.themeRes != 0) setTheme(themeMode.themeRes)
        super.onCreate(savedInstanceState)
        val accent = prefs.getAccentColor()
        appliedAccentColor = accent
        if (accent.themeOverlayRes != 0) theme.applyStyle(accent.themeOverlayRes, true)
        // No motion anywhere: instant navigation is snappier on these phones.
        theme.applyStyle(R.style.ThemeOverlay_FlipLauncher_NoAnimations, true)
        setContentView(R.layout.activity_list)
        titleView = findViewById(R.id.title)
        listView = findViewById(R.id.list)
        softKeys = findViewById(R.id.soft_keys)
        softKeys.setOnLeftClick { onPrimaryKey() }
        softKeys.setOnRightClick { onOptionsKey() }
        findViewById<View>(android.R.id.content).applyFakeBold()
        // Only the rows take focus; the container itself never should.
        listView.isFocusable = false
        listView.layoutManager = LinearLayoutManager(this)
        // No row animations - changes apply instantly.
        listView.itemAnimator = null
    }

    override fun onResume() {
        super.onResume()
        // The accent color or theme mode may have changed in Settings while
        // this activity was backgrounded; both only apply at onCreate, so
        // recreate to pick up either.
        val prefs = LauncherPrefs(this)
        if (prefs.getAccentColor() != appliedAccentColor || prefs.getThemeMode() != appliedThemeMode) {
            isRecreatingForAccent = true
            recreate()
            return
        }
        window.hideNavigationBar()
    }

    override fun onPause() {
        super.onPause()
        softKeyRouter.reset()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.hideNavigationBar() else softKeyRouter.reset()
    }

    /** Left soft key: the screen's primary action. No-op by default (the label stays blank). */
    protected open fun onPrimaryKey() {}

    /** Right soft key (or MENU): the screen's Options. No-op by default (the label stays blank). */
    protected open fun onOptionsKey() {}

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (softKeyRouter.onKeyDown(keyCode, event)) return true
        toastIfUnknownKey(keyCode, event)
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (softKeyRouter.onKeyUp(keyCode, event)) return true
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Adapter position of the focused row, or [RecyclerView.NO_POSITION] (-1)
     * when nothing is focused. Callers must guard against -1 so a soft-key
     * action after focus loss doesn't fall back to row 0.
     */
    protected fun focusedPosition(): Int {
        val child = listView.focusedChild ?: return RecyclerView.NO_POSITION
        return listView.getChildAdapterPosition(child)
    }

    protected fun focusFirst() {
        listView.post {
            if (listView.focusedChild == null) {
                val lm = listView.layoutManager ?: return@post
                // Skip non-focusable rows (e.g. section headers) so focus lands on
                // the first real item rather than silently failing on a header.
                for (i in 0 until lm.itemCount) {
                    val view = lm.findViewByPosition(i)
                    if (view != null && view.isFocusable && view.requestFocus()) return@post
                }
                listView.requestFocus()
            }
        }
    }
}

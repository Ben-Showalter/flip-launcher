package com.flipos.launcher.activities

import com.flipos.launcher.R

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flipos.launcher.data.BuiltInIcons
import com.flipos.launcher.data.IconShapeRenderer
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.ui.IconGridAdapter
import com.flipos.launcher.ui.SoftKeyBar
import com.flipos.launcher.util.SoftKeyRouter
import com.flipos.launcher.util.applyFakeBold
import com.flipos.launcher.util.hideNavigationBar
import com.flipos.launcher.util.toastIfUnknownKey

/**
 * Lets the user replace one app's icon with one of the launcher's own icons
 * (see [BuiltInIcons]), or put back its original.
 */
class IconPickerActivity : AppCompatActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var appKey: String
    private lateinit var titleView: TextView
    private lateinit var grid: RecyclerView
    private lateinit var adapter: IconGridAdapter

    /** No primary action or Options here; still consumes both halves of each soft-key press. */
    private val softKeyRouter = SoftKeyRouter(onPrimary = {}, onOptions = {})

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = LauncherPrefs(this)
        // Must happen before super.onCreate() - see BaseListActivity's
        // identical setTheme() call for why a real theme switch (not just
        // an attribute overlay) is needed for AlertDialog's own chrome to
        // render light too.
        prefs.getThemeMode().let { if (it.themeRes != 0) setTheme(it.themeRes) }
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(EXTRA_APP_KEY)
        if (key == null) {
            finish()
            return
        }
        appKey = key
        prefs.getAccentColor().let { if (it.themeOverlayRes != 0) theme.applyStyle(it.themeOverlayRes, true) }
        theme.applyStyle(R.style.ThemeOverlay_FlipLauncher_NoAnimations, true)

        // Reuses the App Drawer's title/grid/soft-key layout; its page indicator
        // isn't relevant here so it's hidden.
        setContentView(R.layout.activity_app_drawer)
        titleView = findViewById(R.id.title)
        grid = findViewById(R.id.apps_grid)
        grid.itemAnimator = null
        grid.isFocusable = false
        findViewById<View>(android.R.id.content).applyFakeBold()
        findViewById<View>(R.id.page_indicator).visibility = View.GONE
        val softKeys = findViewById<SoftKeyBar>(R.id.soft_keys)

        adapter = IconGridAdapter(
            onClick = { name -> applyIcon(name) },
            // Shown clipped to the launcher's tile, as they'll look in the drawer.
            iconLoader = { name ->
                BuiltInIcons.loadIcon(this, name)?.let {
                    IconShapeRenderer.render(
                        context = this,
                        source = it,
                        shape = LauncherPrefs.IconShape.SQUIRCLE,
                        wrapEnabled = true,
                        legacyBackgroundEnabled = true,
                    )
                }
            },
        )
        grid.layoutManager = GridLayoutManager(this, COLUMNS)
        grid.adapter = adapter

        softKeys.setLabels(null, null, null)

        titleView.text = getString(R.string.icon_picker_change)
        chooseSource()
    }

    override fun onResume() {
        super.onResume()
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (softKeyRouter.onKeyDown(keyCode, event)) return true
        toastIfUnknownKey(keyCode, event)
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (softKeyRouter.onKeyUp(keyCode, event)) return true
        return super.onKeyUp(keyCode, event)
    }

    /** Straight to the icons; an app with a custom icon first gets "Original icon", to undo it. */
    private fun chooseSource() {
        if (prefs.getIconOverride(appKey) == null) {
            showBuiltIn()
            return
        }
        val items = arrayOf(getString(R.string.icon_picker_original), getString(R.string.icon_picker_built_in))
        AlertDialog.Builder(this)
            .setTitle(R.string.icon_picker_change)
            .setItems(items) { _, which -> if (which == 0) resetToOriginal() else showBuiltIn() }
            .setOnCancelListener { finish() }
            .show()
    }

    /** Drops this app's custom icon, back to its own (what Reset Icon used to do). */
    private fun resetToOriginal() {
        prefs.clearIconOverride(appKey)
        Toast.makeText(this, R.string.icon_picker_reset, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun showBuiltIn() {
        titleView.text = getString(R.string.icon_picker_built_in)
        adapter.submit(BuiltInIcons.names())
        focusFirstIcon()
    }

    /** Parks focus on the first icon so the grid is immediately D-pad navigable. */
    private fun focusFirstIcon() {
        grid.post {
            if (grid.focusedChild == null) grid.layoutManager?.findViewByPosition(0)?.requestFocus()
        }
    }

    private fun applyIcon(name: String) {
        prefs.setIconOverride(appKey, BuiltInIcons.PACK_ID, name)
        Toast.makeText(this, R.string.icon_picker_applied, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_APP_KEY = "app_key"
        private const val COLUMNS = 4
    }
}

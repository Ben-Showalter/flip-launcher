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
import com.flipos.launcher.data.IconPackRepository
import com.flipos.launcher.data.LauncherPrefs
import com.flipos.launcher.ui.IconGridAdapter
import com.flipos.launcher.ui.SoftKeyBar
import com.flipos.launcher.util.BackgroundLoader
import com.flipos.launcher.util.SoftKeyRouter
import com.flipos.launcher.util.applyFakeBold
import com.flipos.launcher.util.hideNavigationBar
import com.flipos.launcher.util.toastIfUnknownKey

/**
 * Lets the user replace one app's icon with any icon from an installed icon
 * pack, or from the launcher's own bundled icon set (always available, even
 * with no icon pack installed).
 */
class IconPickerActivity : AppCompatActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var appKey: String
    private lateinit var titleView: TextView
    private lateinit var grid: RecyclerView
    private lateinit var adapter: IconGridAdapter
    private var currentPack: String? = null
    private val loader = BackgroundLoader()

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
        if (!prefs.isAnimationsEnabled()) {
            theme.applyStyle(R.style.ThemeOverlay_FlipLauncher_NoAnimations, true)
        }

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
            iconLoader = { name ->
                when (currentPack) {
                    null -> null
                    BuiltInIcons.PACK_ID -> BuiltInIcons.loadIcon(this, name)
                    else -> IconPackRepository.loadIcon(this, currentPack!!, name)
                }
            },
        )
        grid.layoutManager = GridLayoutManager(this, COLUMNS)
        grid.adapter = adapter

        softKeys.setLabels(null, null, null)

        titleView.text = getString(R.string.icon_picker_choose_pack)
        choosePack()
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

    override fun onDestroy() {
        loader.cancel()
        super.onDestroy()
    }

    private fun choosePack() {
        loader.load(
            produce = { IconPackRepository.getInstalledIconPacks(this) },
            consume = { packs ->
                if (isDestroyed) return@load
                // The bundled set is always offered first, so there's something
                // to pick from even with no icon pack installed.
                val labels = listOf(getString(R.string.icon_picker_built_in)) + packs.map { it.label }
                val packageNames = listOf(BuiltInIcons.PACK_ID) + packs.map { it.packageName }
                if (labels.size == 1) {
                    openSource(packageNames[0], labels[0])
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.icon_picker_choose_pack)
                        .setItems(labels.toTypedArray()) { _, which -> openSource(packageNames[which], labels[which]) }
                        .setOnCancelListener { finish() }
                        .show()
                }
            },
        )
    }

    private fun openSource(packageName: String, label: String) {
        currentPack = packageName
        titleView.text = label
        if (packageName == BuiltInIcons.PACK_ID) {
            adapter.submit(BuiltInIcons.names())
            focusFirstIcon()
            return
        }
        loader.load(
            produce = { IconPackRepository.getDrawableNames(this, packageName) },
            consume = { names ->
                if (isDestroyed) return@load
                adapter.submit(names)
                focusFirstIcon()
            },
        )
    }

    /** Parks focus on the first icon so the grid is immediately D-pad navigable. */
    private fun focusFirstIcon() {
        grid.post {
            if (grid.focusedChild == null) grid.layoutManager?.findViewByPosition(0)?.requestFocus()
        }
    }

    private fun applyIcon(name: String) {
        val pack = currentPack ?: return
        prefs.setIconOverride(appKey, pack, name)
        Toast.makeText(this, R.string.icon_picker_applied, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_APP_KEY = "app_key"
        private const val COLUMNS = 4
    }
}

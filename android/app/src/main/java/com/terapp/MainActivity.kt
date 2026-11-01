package com.terapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

    private lateinit var topAppBar: MaterialToolbar
    private lateinit var sessionTabs: TabLayout
    private lateinit var terminalView: TerminalView
    private lateinit var terminalViewSecondary: TerminalView
    private lateinit var splitDivider: View
    private lateinit var extraKeysView: ExtraKeysView

    private val sessions = mutableListOf<TerminalSession>()
    private var activeSessionIndex = 0
    private var isWakeLockEnabled = false
    private var isSplitViewEnabled = false
    private lateinit var activeFocusedTerminal: TerminalView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        setContentView(R.layout.activity_main)

        topAppBar = findViewById(R.id.topAppBar)
        sessionTabs = findViewById(R.id.sessionTabs)
        terminalView = findViewById(R.id.terminalView)
        terminalViewSecondary = findViewById(R.id.terminalViewSecondary)
        splitDivider = findViewById(R.id.splitDivider)
        extraKeysView = findViewById(R.id.extraKeysView)

        setSupportActionBar(topAppBar)

        activeFocusedTerminal = terminalView

        // Connect ExtraKeysView to TerminalView
        terminalView.extraKeysView = extraKeysView
        terminalViewSecondary.extraKeysView = extraKeysView
        extraKeysView.onKeyAction = { actionKey ->
            activeFocusedTerminal.sendSpecialKey(actionKey)
        }

        terminalView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) activeFocusedTerminal = terminalView
        }
        terminalViewSecondary.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) activeFocusedTerminal = terminalViewSecondary
        }

        // Setup session tabs listener
        sessionTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val index = tab?.position ?: return
                if (index in sessions.indices) {
                    switchToSession(index)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {
                // Tapping active tab prompts to rename
                promptRenameActiveSession()
            }
        })

        // Initialize userland & create initial session
        BootstrapManager.ensureBootstrap(this)
        createNewSession("Session 1")

        // Start Foreground Service so terminal sessions survive in background
        TerminalService.start(this, isWakeLockEnabled)
    }

    private fun createNewSession(title: String, isFailSafe: Boolean = false) {
        val home = BootstrapManager.getHomeDir(this)
        home.mkdirs()

        val cols = if (terminalView.cols > 0) terminalView.cols else 80
        val rows = if (terminalView.rows > 0) terminalView.rows else 24

        val newSession = TerminalSession(
            id = System.currentTimeMillis().toString(),
            name = title,
            cols = cols,
            rows = rows,
            workingDir = home,
            context = this,
            isFailSafe = isFailSafe
        )

        val sessionIdx = sessions.size
        sessions.add(newSession)

        val tab = sessionTabs.newTab().setText(title)
        sessionTabs.addTab(tab)

        // Setup session callbacks
        newSession.onTitleChanged = { updatedTitle ->
            runOnUiThread {
                val idx = sessions.indexOf(newSession)
                if (idx in 0 until sessionTabs.tabCount) {
                    sessionTabs.getTabAt(idx)?.text = updatedTitle
                    if (idx == activeSessionIndex) {
                        topAppBar.subtitle = updatedTitle
                    }
                }
            }
        }

        newSession.onBell = {
            runOnUiThread {
                triggerHapticBell()
            }
        }

        newSession.onSessionFinished = { s ->
            runOnUiThread {
                val idx = sessions.indexOf(s)
                if (idx in 0 until sessionTabs.tabCount) {
                    sessionTabs.getTabAt(idx)?.text = "${s.name} [Exited]"
                }
            }
        }

        switchToSession(sessions.size - 1)
        sessionTabs.selectTab(tab)
        terminalView.requestFocus()
    }

    private fun triggerHapticBell() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(40)
        }
    }

    private fun switchToSession(index: Int) {
        if (index in sessions.indices) {
            activeSessionIndex = index
            val current = sessions[index]
            terminalView.session = current
            topAppBar.subtitle = current.name

            if (isSplitViewEnabled && sessions.size > 1) {
                val secondaryIdx = if (index == 0) 1 else 0
                terminalViewSecondary.session = sessions[secondaryIdx]
            }
        }
    }

    private fun promptRenameActiveSession() {
        if (sessions.isEmpty()) return
        val current = sessions[activeSessionIndex]
        val input = EditText(this).apply {
            setText(current.name)
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_rename_session)
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    current.name = newName
                    sessionTabs.getTabAt(activeSessionIndex)?.text = newName
                    topAppBar.subtitle = newName
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun closeActiveSession() {
        if (sessions.isEmpty()) return
        val current = sessions[activeSessionIndex]
        current.destroy()
        sessions.removeAt(activeSessionIndex)
        sessionTabs.removeTabAt(activeSessionIndex)

        if (sessions.isNotEmpty()) {
            val newIdx = activeSessionIndex.coerceAtMost(sessions.size - 1)
            switchToSession(newIdx)
        } else {
            createNewSession("Session 1")
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_new_session -> {
                createNewSession("Session ${sessions.size + 1}")
                true
            }
            R.id.action_failsafe_session -> {
                createNewSession("FailSafe ${sessions.size + 1}", isFailSafe = true)
                true
            }
            R.id.action_rename_session -> {
                promptRenameActiveSession()
                true
            }
            R.id.action_copy -> {
                copyTerminalOutput()
                true
            }
            R.id.action_paste -> {
                pasteToTerminal()
                true
            }
            R.id.action_split_view -> {
                toggleSplitView(item)
                true
            }
            R.id.action_theme -> {
                showThemeDialog()
                true
            }
            R.id.action_zoom_in -> {
                activeFocusedTerminal.fontSizeSp += 1.5f
                true
            }
            R.id.action_zoom_out -> {
                activeFocusedTerminal.fontSizeSp -= 1.5f
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            R.id.action_wakelock -> {
                isWakeLockEnabled = !isWakeLockEnabled
                item.isChecked = isWakeLockEnabled
                TerminalService.start(this, isWakeLockEnabled)
                val status = if (isWakeLockEnabled) "WakeLock Enabled" else "WakeLock Disabled"
                Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_clear -> {
                val s = activeFocusedTerminal.session
                if (s != null) {
                    s.feedBytes("\u001b[2J\u001b[H".toByteArray())
                    activeFocusedTerminal.invalidate()
                }
                true
            }
            R.id.action_reset -> {
                activeFocusedTerminal.session?.writeBytes(byteArrayOf(0x03)) // Ctrl+C
                true
            }
            R.id.action_kill -> {
                closeActiveSession()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun copyTerminalOutput() {
        val clipMgr = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = activeFocusedTerminal.session?.getVisibleText().orEmpty()
        if (text.isNotEmpty() && clipMgr != null) {
            val clip = ClipData.newPlainText("TerApp Terminal", text)
            clipMgr.setPrimaryClip(clip)
            Toast.makeText(this, "Terminal output copied to clipboard", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pasteToTerminal() {
        val clipMgr = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipMgr?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val pasteText = clip.getItemAt(0).coerceToText(this).toString()
            if (pasteText.isNotEmpty()) {
                activeFocusedTerminal.session?.writeInput(pasteText)
            }
        } else {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleSplitView(item: MenuItem) {
        isSplitViewEnabled = !isSplitViewEnabled
        item.isChecked = isSplitViewEnabled

        if (isSplitViewEnabled) {
            if (sessions.size < 2) {
                createNewSession("Session 2")
            }
            val secondaryIdx = if (activeSessionIndex == 0) 1 else 0
            terminalViewSecondary.session = sessions[secondaryIdx]
            terminalViewSecondary.visibility = View.VISIBLE
            splitDivider.visibility = View.VISIBLE
            Toast.makeText(this, "Split view enabled", Toast.LENGTH_SHORT).show()
        } else {
            terminalViewSecondary.visibility = View.GONE
            splitDivider.visibility = View.GONE
            activeFocusedTerminal = terminalView
            terminalView.requestFocus()
            Toast.makeText(this, "Split view disabled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showThemeDialog() {
        val themeNames = ThemeManager.THEMES.map { it.name }.toTypedArray()
        val currentIndex = ThemeManager.THEMES.indexOf(ThemeManager.currentTheme)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_theme)
            .setSingleChoiceItems(themeNames, currentIndex) { dialog, which ->
                ThemeManager.currentTheme = ThemeManager.THEMES[which]
                terminalView.invalidate()
                terminalViewSecondary.invalidate()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAboutDialog() {
        val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("TerApp ${VersionManager.CURRENT_VERSION}")
            .setMessage(
                "Modern High-Performance Terminal\n\n" +
                "• Version: ${VersionManager.CURRENT_VERSION} (Build ${VersionManager.BUILD_CODE})\n" +
                "• Architecture: $arch\n" +
                "• Engine: Native Rust VT100 / ANSI CSI Parser\n" +
                "• PTY Subprocess: POSIX /dev/ptmx\n" +
                "• Userland: BusyBox 1.36.1 POSIX + pkg Manager\n" +
                "• Repository: github.com/jeremy776/TerApp\n\n" +
                "Checking release status..."
            )
            .setPositiveButton("Close", null)
            .setNeutralButton("View Releases") { _, _ ->
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("${VersionManager.REPO_URL}/releases")
                )
                try { startActivity(intent) } catch (ignored: Exception) {}
            }
            .show()

        VersionManager.checkForUpdates { isLatest, latestVer, _ ->
            runOnUiThread {
                if (dialog.isShowing) {
                    val statusText = if (isLatest) {
                        "Status: You are running the latest version (${VersionManager.CURRENT_VERSION})."
                    } else {
                        "Update Available: $latestVer\nInstalled: ${VersionManager.CURRENT_VERSION}\nTap 'View Releases' to download."
                    }
                    dialog.setMessage(
                        "Modern High-Performance Terminal\n\n" +
                        "• Version: ${VersionManager.CURRENT_VERSION} (Build ${VersionManager.BUILD_CODE})\n" +
                        "• Architecture: $arch\n" +
                        "• Engine: Native Rust VT100 / ANSI CSI Parser\n" +
                        "• PTY Subprocess: POSIX /dev/ptmx\n" +
                        "• Userland: BusyBox 1.36.1 POSIX + pkg Manager\n" +
                        "• Repository: github.com/jeremy776/TerApp\n\n" +
                        statusText
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        for (s in sessions) {
            s.destroy()
        }
        sessions.clear()
        if (!isWakeLockEnabled) {
            TerminalService.stop(this)
        }
    }
}

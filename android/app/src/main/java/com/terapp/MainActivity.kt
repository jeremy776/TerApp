package com.terapp

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

    private lateinit var topAppBar: MaterialToolbar
    private lateinit var sessionTabs: TabLayout
    private lateinit var terminalView: TerminalView
    private lateinit var extraKeysView: ExtraKeysView

    private val sessions = mutableListOf<TerminalSession>()
    private var activeSessionIndex = 0
    private var isWakeLockEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        topAppBar = findViewById(R.id.topAppBar)
        sessionTabs = findViewById(R.id.sessionTabs)
        terminalView = findViewById(R.id.terminalView)
        extraKeysView = findViewById(R.id.extraKeysView)

        setSupportActionBar(topAppBar)

        // Connect ExtraKeysView to TerminalView
        terminalView.extraKeysView = extraKeysView
        extraKeysView.onKeyAction = { actionKey ->
            terminalView.sendSpecialKey(actionKey)
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
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Create initial session
        BootstrapManager.ensureBootstrap(this)
        createNewSession("Session 1")

        // Start Foreground Service so terminal sessions survive in background
        TerminalService.start(this, isWakeLockEnabled)
    }

    private fun createNewSession(title: String) {
        val home = BootstrapManager.getHomeDir(this)
        home.mkdirs()
        val newSession = TerminalSession(
            id = System.currentTimeMillis().toString(),
            name = title,
            cols = terminalView.cols,
            rows = terminalView.rows,
            workingDir = home,
            context = this
        )
        sessions.add(newSession)

        val tab = sessionTabs.newTab().setText(title)
        sessionTabs.addTab(tab)

        switchToSession(sessions.size - 1)
        sessionTabs.selectTab(tab)
        terminalView.requestFocus()
    }

    private fun switchToSession(index: Int) {
        if (index in sessions.indices) {
            activeSessionIndex = index
            val current = sessions[index]
            terminalView.session = current
            topAppBar.subtitle = current.name
        }
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
            R.id.action_theme -> {
                showThemeDialog()
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
                val s = terminalView.session
                if (s != null) {
                    s.feedBytes("\u001b[2J\u001b[H".toByteArray())
                    terminalView.invalidate()
                }
                true
            }
            R.id.action_reset -> {
                terminalView.session?.writeBytes(byteArrayOf(0x03)) // Ctrl+C
                true
            }
            R.id.action_kill -> {
                closeActiveSession()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showThemeDialog() {
        val themeNames = ThemeManager.THEMES.map { it.name }.toTypedArray()
        val currentIndex = ThemeManager.THEMES.indexOf(ThemeManager.currentTheme)

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_theme)
            .setSingleChoiceItems(themeNames, currentIndex) { dialog, which ->
                ThemeManager.currentTheme = ThemeManager.THEMES[which]
                terminalView.invalidate()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

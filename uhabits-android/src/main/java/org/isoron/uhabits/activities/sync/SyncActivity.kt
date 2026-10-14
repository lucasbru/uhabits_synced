/*
 * Copyright (C) 2016-2026 Loop Habit Tracker contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.activities.sync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.sync.SyncException
import org.isoron.uhabits.databinding.SyncActivityBinding
import org.isoron.uhabits.sync.SyncApi
import org.isoron.uhabits.sync.SyncManager
import org.isoron.uhabits.utils.applyRootViewInsets
import org.isoron.uhabits.utils.setupToolbar
import java.io.IOException

/**
 * Account screen for cloud sync: sign in (OAuth providers or email), see the
 * sync status, sign out, delete the account, and choose the server.
 *
 * Also receives the sign-in deep link: org.isoron.uhabits.sync://auth?code=…
 */
class SyncActivity : AppCompatActivity() {
    private lateinit var binding: SyncActivityBinding
    private lateinit var manager: SyncManager
    private val scope: CoroutineScope = MainScope()
    private var busy: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val component = (application as HabitsApplication).component
        manager = component.syncManager
        val themeSwitcher = AndroidThemeSwitcher(this, component.preferences)
        themeSwitcher.apply()

        binding = SyncActivityBinding.inflate(LayoutInflater.from(this))
        binding.root.setupToolbar(
            toolbar = binding.toolbar,
            title = getString(R.string.sync_title),
            color = PaletteColor(11),
            theme = themeSwitcher.currentTheme
        )
        binding.root.applyRootViewInsets()
        setContentView(binding.root)

        binding.googleButton.setOnClickListener { startOAuth("google") }
        binding.githubButton.setOnClickListener { startOAuth("github") }
        binding.microsoftButton.setOnClickListener { startOAuth("microsoft") }
        binding.emailButton.setOnClickListener { startEmail() }
        binding.codeButton.setOnClickListener { verifyCode() }
        binding.syncNowButton.setOnClickListener { syncNow() }
        binding.signOutButton.setOnClickListener { launchAction { manager.signOut() } }
        binding.deleteAccountButton.setOnClickListener { confirmDelete() }
        binding.serverSaveButton.setOnClickListener {
            manager.account.serverUrl = binding.serverInput.text.toString()
            loadProviders()
            render()
        }
        binding.serverInput.setText(manager.account.serverUrl)

        scope.launch {
            manager.running.combine(manager.changes) { _, _ -> }.collect { render() }
        }
        handleDeepLink(intent)
        loadProviders()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------

    private fun render() {
        val account = manager.account
        val signedIn = account.isSignedIn
        binding.signedOut.visibility = if (signedIn) View.GONE else View.VISIBLE
        binding.signedIn.visibility = if (signedIn) View.VISIBLE else View.GONE
        val working = manager.running.value || busy?.isActive == true
        binding.progress.visibility = if (working) View.VISIBLE else View.GONE

        if (signedIn) {
            binding.accountText.text = account.email
                ?.let { getString(R.string.sync_signed_in_as, it) }
                ?: getString(R.string.sync_signed_in)
            binding.statusText.text = when {
                manager.running.value -> getString(R.string.sync_syncing)
                account.lastSyncAt == 0L -> getString(R.string.sync_never_synced)
                else -> getString(
                    R.string.sync_last_synced,
                    DateUtils.getRelativeTimeSpanString(account.lastSyncAt)
                )
            }
        } else {
            val email = account.pendingEmail
            val pending = account.pendingLoginId != null && email != null
            binding.codeSection.visibility = if (pending) View.VISIBLE else View.GONE
            if (pending) binding.codeHint.text = getString(R.string.sync_code_sent, email)
        }

        val error = when {
            !account.isConfigured -> getString(R.string.sync_server_missing)
            else -> errorMessage(account.lastError)
        }
        binding.errorText.text = error
        binding.errorText.visibility = if (error == null) View.GONE else View.VISIBLE
        setButtonsEnabled(!working && account.isConfigured)
    }

    private fun errorMessage(code: String?): String? = when (code) {
        null -> null
        SyncManager.ERROR_NETWORK -> getString(R.string.sync_error_network)
        SyncManager.ERROR_SERVER -> getString(R.string.sync_error_server)
        SyncManager.ERROR_CLOCK -> getString(R.string.sync_error_clock)
        SyncManager.ERROR_SIGNED_OUT -> getString(R.string.sync_error_signed_out)
        else -> getString(R.string.sync_error_generic, code)
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        listOf(
            binding.googleButton, binding.githubButton, binding.microsoftButton,
            binding.emailButton, binding.codeButton, binding.syncNowButton,
            binding.signOutButton, binding.deleteAccountButton
        ).forEach { it.isEnabled = enabled }
    }

    private fun loadProviders() {
        if (!manager.account.isConfigured) return
        scope.launch {
            val providers = try {
                withContext(Dispatchers.IO) { manager.api.providers() }
            } catch (e: Exception) {
                null
            }
            val oauth = providers?.oauth ?: emptyList()
            binding.googleButton.visibility = if ("google" in oauth) View.VISIBLE else View.GONE
            binding.githubButton.visibility = if ("github" in oauth) View.VISIBLE else View.GONE
            binding.microsoftButton.visibility = if ("microsoft" in oauth) View.VISIBLE else View.GONE
            binding.emailSection.visibility =
                if (providers == null || providers.email) View.VISIBLE else View.GONE
        }
    }

    /** Runs [block] as a coroutine, showing progress and any error. */
    private fun launchAction(block: suspend () -> Unit) {
        if (busy?.isActive == true) return
        manager.account.lastError = null
        busy = scope.launch {
            render()
            var message: String? = null
            try {
                block()
            } catch (e: SyncApi.ApiException) {
                if (e.code == "invalid_code") {
                    message = getString(R.string.sync_error_code)
                } else {
                    manager.account.lastError = if (e.status >= 500) SyncManager.ERROR_SERVER else e.code
                }
            } catch (e: IOException) {
                manager.account.lastError = SyncManager.ERROR_NETWORK
            } catch (e: SyncException) {
                // SyncManager already recorded the error.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                manager.account.lastError = e.message ?: e.javaClass.simpleName
            }
            busy = null
            render()
            if (message != null) showError(message)
        }
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------------
    // Sign-in flows
    // ---------------------------------------------------------------------

    private fun startOAuth(provider: String) {
        val verifier = SyncApi.newCodeVerifier()
        manager.account.pendingVerifier = verifier
        manager.account.pendingLoginId = null
        val uri = manager.api.oauthStartUri(provider, SyncApi.codeChallenge(verifier))
        CustomTabsIntent.Builder().build().launchUrl(this, uri)
    }

    private fun startEmail() {
        val email = binding.emailInput.text.toString().trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError(getString(R.string.sync_error_invalid_email))
            return
        }
        launchAction {
            val verifier = SyncApi.newCodeVerifier()
            val loginId = withContext(Dispatchers.IO) {
                manager.api.startEmail(email, SyncApi.codeChallenge(verifier))
            }
            manager.account.pendingVerifier = verifier
            manager.account.pendingLoginId = loginId
            manager.account.pendingEmail = email
        }
    }

    private fun verifyCode() {
        val code = binding.codeInput.text.toString().trim()
        val loginId = manager.account.pendingLoginId ?: return
        val verifier = manager.account.pendingVerifier ?: return
        launchAction {
            val tokens = withContext(Dispatchers.IO) {
                manager.api.verifyEmailCode(loginId, code, verifier)
            }
            manager.completeSignIn(tokens)
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme != "org.isoron.uhabits.sync") return
        intent.data = null // handle once
        val error = data.getQueryParameter("error")
        if (error != null) {
            manager.account.lastError = error
            render()
            return
        }
        val code = data.getQueryParameter("code") ?: return
        val verifier = manager.account.pendingVerifier ?: return
        launchAction {
            val tokens = withContext(Dispatchers.IO) { manager.api.exchangeCode(code, verifier) }
            manager.completeSignIn(tokens)
        }
    }

    // ---------------------------------------------------------------------

    private fun syncNow() {
        launchAction { withContext(Dispatchers.IO) { manager.syncNow() } }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.sync_delete_account)
            .setMessage(R.string.sync_delete_confirm)
            .setPositiveButton(R.string.sync_delete_account) { _, _ -> launchAction { manager.deleteAccount() } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

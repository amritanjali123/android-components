/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package mozilla.components.feature.app.links

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import androidx.fragment.app.FragmentManager
import mozilla.components.browser.session.SelectionAwareSessionObserver
import mozilla.components.browser.session.Session
import mozilla.components.browser.session.SessionManager
import mozilla.components.concept.engine.request.RequestInterceptor
import mozilla.components.feature.app.links.RedirectDialogFragment.Companion.FRAGMENT_TAG
import mozilla.components.support.base.feature.LifecycleAwareFeature

/**
 * This feature implements use cases for detecting and handling redirects to external apps. The user
 * is asked to confirm her intention before leaving the app. These include the Android Intents,
 * custom schemes and support for [Intent.CATEGORY_BROWSABLE] `http(s)` URLs.
 *
 * In the case of Android Intents that are not installed, and with no fallback, the user is prompted
 * to search the installed market place.
 *
 * It provides use cases to detect and open links openable in third party non-browser apps.
 *
 * It provides a [RequestInterceptor] to do the detection and asking of consent.
 *
 * It requires: a [Context], and a [FragmentManager].
 */
class AppLinksFeature(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val sessionId: String? = null,
    private val fragmentManager: FragmentManager? = null,
    private var dialog: RedirectDialogFragment = SimpleRedirectDialogFragment.newInstance()
) : SelectionAwareSessionObserver(sessionManager), LifecycleAwareFeature {

    private val useCases = AppLinksUseCases(context)

    override fun onUrlChanged(session: Session, url: String) {
        val redirect = useCases.appLinkRedirect.invoke(url)

        if (redirect.hasExternalApp()) {
            showDialog(redirect, session)
        }
    }

    /**
     * Starts observing app links on the selected session.
     */
    override fun start() {
        observeIdOrSelected(sessionId)

        findPreviousDialogFragment()?.let {
            reAttachOnStartDownloadListener(it)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDialog(redirect: AppLinkRedirect, session: Session) {
        val intent = redirect.appIntent ?: return

        val doOpenApp = {
            useCases.openAppLink.invoke(redirect)
        }

        if (!session.private) {
            doOpenApp()
            return
        }

        dialog.setAppLinkRedirect(redirect)
        dialog.onConfirmRedirect = doOpenApp
        dialog.onDismiss(object : DialogInterface {
            override fun dismiss() {
                redirect.webUrl?.let {
                    sessionManager.getOrCreateEngineSession(session).loadUrl(it)
                }
            }

            override fun cancel() {
                dismiss()
            }
        })

        if (!isAlreadyADialogCreated()) {
            dialog.show(fragmentManager, FRAGMENT_TAG)
        }
    }

    private fun isAlreadyADialogCreated(): Boolean {
        return findPreviousDialogFragment() != null
    }

    private fun reAttachOnStartDownloadListener(previousDialog: RedirectDialogFragment?) {
        previousDialog?.apply {
            this@AppLinksFeature.dialog = this
            val selectedSession = sessionManager.selectedSession
            selectedSession?.download?.consume {
                onDownload(selectedSession, it)
                false
            }
        }
    }

    private fun findPreviousDialogFragment(): RedirectDialogFragment? {
        return fragmentManager?.findFragmentByTag(FRAGMENT_TAG) as? RedirectDialogFragment
    }
}

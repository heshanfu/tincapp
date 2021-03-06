/*
 * tinc app, an Android binding and user interface for the tinc mesh VPN daemon
 * Copyright (C) 2017-2018 Pacien TRAN-GIRARD
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.pacien.tincapp.activities

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.support.v4.widget.SwipeRefreshLayout
import android.support.v7.app.AlertDialog
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import kotlinx.android.synthetic.main.base.*
import kotlinx.android.synthetic.main.dialog_decrypt_keys.view.*
import kotlinx.android.synthetic.main.fragment_list_view.*
import kotlinx.android.synthetic.main.fragment_network_list_header.*
import org.pacien.tincapp.R
import org.pacien.tincapp.context.AppPaths
import org.pacien.tincapp.extensions.Android.setElements
import org.pacien.tincapp.intent.Actions
import org.pacien.tincapp.intent.BroadcastMapper
import org.pacien.tincapp.service.TincVpnService
import org.pacien.tincapp.utils.TincKeyring

/**
 * @author pacien
 */
class StartActivity : BaseActivity() {
  companion object {
    private const val PERMISSION_REQUEST = 0
  }

  private val networkList = object : AdapterView.OnItemClickListener, SwipeRefreshLayout.OnRefreshListener {
    private var networkListAdapter: ArrayAdapter<String>? = null

    fun init() {
      networkListAdapter = ArrayAdapter(this@StartActivity, R.layout.fragment_list_item)
      layoutInflater.inflate(R.layout.fragment_list_view, main_content)
      list_wrapper.setOnRefreshListener(this)
      list.addHeaderView(layoutInflater.inflate(R.layout.fragment_network_list_header, list, false), null, false)
      list.addFooterView(View(this@StartActivity), null, false)
      list.adapter = networkListAdapter
      list.onItemClickListener = this
    }

    fun destroy() {
      networkListAdapter = null
    }

    override fun onRefresh() {
      val networks = AppPaths.confDir()?.list()?.toList() ?: emptyList()
      runOnUiThread {
        networkListAdapter?.setElements(networks)
        setPlaceholderVisibility()
        list_wrapper.isRefreshing = false
      }
    }

    override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
      connectionStarter.tryStart(netName = (view as TextView).text.toString(), displayStatus = true)
    }

    private fun setPlaceholderVisibility() = if (networkListAdapter?.isEmpty != false) {
      network_list_placeholder.text = getListPlaceholderText()
      network_list_placeholder.visibility = View.VISIBLE
    } else {
      network_list_placeholder.visibility = View.GONE
    }

    private fun getListPlaceholderText() = if (!AppPaths.storageAvailable()) {
      getText(R.string.message_storage_unavailable)
    } else {
      getText(R.string.message_no_network_configuration_found)
    }
  }

  private val connectionStarter = object {
    private var netName: String? = null
    private var passphrase: String? = null
    private var displayStatus = false

    fun displayStatus() = displayStatus

    fun tryStart(netName: String? = null, passphrase: String? = null, displayStatus: Boolean? = null) {
      if (netName != null) this.netName = netName
      this.passphrase = passphrase
      if (displayStatus != null) this.displayStatus = displayStatus

      val permissionRequestIntent = VpnService.prepare(this@StartActivity)
      if (permissionRequestIntent != null)
        return startActivityForResult(permissionRequestIntent, PERMISSION_REQUEST)

      if (TincKeyring.needsPassphrase(this.netName!!) && this.passphrase == null)
        return askForPassphrase()

      startVpn(this.netName!!, this.passphrase)
    }

    private fun askForPassphrase() {
      layoutInflater.inflate(R.layout.dialog_decrypt_keys, main_content, false).let { dialog ->
        AlertDialog.Builder(this@StartActivity)
          .setTitle(R.string.title_unlock_private_keys).setView(dialog)
          .setPositiveButton(R.string.action_unlock) { _, _ -> tryStart(passphrase = dialog.passphrase.text.toString()) }
          .setNegativeButton(R.string.action_cancel, { _, _ -> Unit })
          .show()
      }
    }

    private fun startVpn(netName: String, passphrase: String? = null) {
      connectDialog = showProgressDialog(R.string.message_starting_vpn)
      TincVpnService.connect(netName, passphrase)
    }
  }

  private val broadcastMapper = BroadcastMapper(mapOf(
    Actions.EVENT_CONNECTED to this::onVpnStart,
    Actions.EVENT_ABORTED to this::onVpnStartError))

  private var connectDialog: ProgressDialog? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    networkList.init()

    if (intent.action == Actions.ACTION_CONNECT && intent.data?.schemeSpecificPart != null)
      connectionStarter.tryStart(intent.data.schemeSpecificPart, intent.data.fragment, false)
  }

  override fun onCreateOptionsMenu(m: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_start, m)
    return super.onCreateOptionsMenu(m)
  }

  override fun onDestroy() {
    networkList.destroy()
    connectDialog?.dismiss()
    super.onDestroy()
  }

  override fun onStart() {
    super.onStart()
    networkList.onRefresh()
  }

  override fun onResume() {
    super.onResume()
    if (TincVpnService.isConnected()) openStatusActivity(false)
    broadcastMapper.register()
  }

  override fun onPause() {
    broadcastMapper.unregister()
    super.onPause()
  }

  override fun onActivityResult(request: Int, result: Int, data: Intent?): Unit = when (request) {
    PERMISSION_REQUEST -> if (result == Activity.RESULT_OK) connectionStarter.tryStart() else Unit
    else -> throw IllegalArgumentException("Result for unknown request received.")
  }

  fun openConfigureActivity(@Suppress("UNUSED_PARAMETER") i: MenuItem) =
    startActivity(Intent(this, ConfigureActivity::class.java))

  private fun onVpnStart() {
    connectDialog?.dismiss()
    if (connectionStarter.displayStatus()) openStatusActivity()
    finish()
  }

  private fun onVpnStartError() {
    connectDialog?.dismiss()
  }

  private fun openStatusActivity(transition: Boolean = true) =
    startActivity(
      Intent(this, StatusActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        .apply { if (!transition) addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION) })
}

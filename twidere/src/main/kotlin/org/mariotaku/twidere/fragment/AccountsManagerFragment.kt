package org.mariotaku.twidere.fragment

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.support.v4.app.LoaderManager
import android.support.v4.content.Loader
import android.view.*
import android.view.ContextMenu.ContextMenuInfo
import android.widget.AdapterView
import android.widget.AdapterView.AdapterContextMenuInfo
import kotlinx.android.synthetic.main.layout_draggable_list_with_empty_view.*
import org.mariotaku.sqliteqb.library.Expression
import org.mariotaku.twidere.Constants.*
import org.mariotaku.twidere.R
import org.mariotaku.twidere.activity.ColorPickerDialogActivity
import org.mariotaku.twidere.activity.SignInActivity
import org.mariotaku.twidere.adapter.AccountDetailsAdapter
import org.mariotaku.twidere.annotation.Referral
import org.mariotaku.twidere.constant.IntentConstants
import org.mariotaku.twidere.constant.SharedPreferenceConstants.KEY_NEW_DOCUMENT_API
import org.mariotaku.twidere.extension.getAccountKey
import org.mariotaku.twidere.extension.setActivated
import org.mariotaku.twidere.extension.setColor
import org.mariotaku.twidere.extension.setPosition
import org.mariotaku.twidere.loader.AccountDetailsLoader
import org.mariotaku.twidere.model.AccountDetails
import org.mariotaku.twidere.provider.TwidereDataStore.*
import org.mariotaku.twidere.provider.TwidereDataStore.DirectMessages.Inbox
import org.mariotaku.twidere.provider.TwidereDataStore.DirectMessages.Outbox
import org.mariotaku.twidere.util.IntentUtils
import org.mariotaku.twidere.util.Utils
import org.mariotaku.twidere.util.support.AccountManagerSupport

/**
 * Sort and toggle account availability
 * Created by mariotaku on 14/10/26.
 */
class AccountsManagerFragment : BaseSupportFragment(), LoaderManager.LoaderCallbacks<List<AccountDetails>>,
        AdapterView.OnItemClickListener {

    private lateinit var adapter: AccountDetailsAdapter
    private var selectedAccount: AccountDetails? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setHasOptionsMenu(true)
        val am = AccountManager.get(context)
        adapter = AccountDetailsAdapter(context).apply {
            Utils.configBaseAdapter(context, this)
            setSortEnabled(true)
            setSwitchEnabled(true)
            accountToggleListener = { pos, checked ->
                val item = getItem(pos)
                item.activated = checked
                item.account.setActivated(am, checked)
            }
        }
        listView.adapter = adapter
        listView.isDragEnabled = true
        listView.onItemClickListener = this
        listView.setDropListener { from, to ->
            adapter.drop(from, to)
            for (i in 0 until adapter.count) {
                val item = adapter.getItem(i)
                item.account.setActivated(am, item.activated)
                item.account.setPosition(am, i)
            }
        }
        listView.setOnCreateContextMenuListener(this)
        listView.emptyView = emptyView
        emptyText.setText(R.string.no_account)
        emptyIcon.setImageResource(R.drawable.ic_info_error_generic)
        setListShown(false)

        loaderManager.initLoader(0, null, this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_SET_COLOR -> {
                if (resultCode != Activity.RESULT_OK || data == null || selectedAccount == null)
                    return
                val am = AccountManager.get(context)
                selectedAccount?.account?.setColor(am, data.getIntExtra(EXTRA_COLOR, Color.WHITE))
                return
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.add_account -> {
                val intent = Intent(INTENT_ACTION_TWITTER_LOGIN)
                intent.setClass(activity, SignInActivity::class.java)
                startActivity(intent)
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_accounts_manager, menu)
    }

    override fun onContextItemSelected(item: MenuItem?): Boolean {
        val menuInfo = item!!.menuInfo as? AdapterContextMenuInfo ?: return false
        val details = adapter.getItem(menuInfo.position) ?: return false
        selectedAccount = details
        when (item.itemId) {
            R.id.set_color -> {
                val intent = Intent(activity, ColorPickerDialogActivity::class.java)
                intent.putExtra(EXTRA_COLOR, details.color)
                intent.putExtra(EXTRA_ALPHA_SLIDER, false)
                startActivityForResult(intent, REQUEST_SET_COLOR)
            }
            R.id.delete -> {
                val f = AccountDeletionDialogFragment()
                val args = Bundle()
                args.putParcelable(EXTRA_ACCOUNT, details.account)
                f.arguments = args
                f.show(childFragmentManager, FRAGMENT_TAG_ACCOUNT_DELETION)
            }
        }
        return false
    }

    override fun onItemClick(parent: AdapterView<*>, view: View, position: Int, id: Long) {
        val account = adapter.getItem(position)
        IntentUtils.openUserProfile(context, account.user, null, preferences.getBoolean(KEY_NEW_DOCUMENT_API),
                Referral.SELF_PROFILE)
    }

    override fun onCreateLoader(id: Int, args: Bundle?): Loader<List<AccountDetails>> {
        return AccountDetailsLoader(context)
    }

    override fun onLoaderReset(loader: Loader<List<AccountDetails>>) {

    }

    override fun onLoadFinished(loader: Loader<List<AccountDetails>>, data: List<AccountDetails>) {
        adapter.apply {
            clear()
            addAll(data)
        }
        setListShown(true)
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenuInfo) {
        if (menuInfo !is AdapterContextMenuInfo) return
        val account = adapter.getItem(menuInfo.position)!!
        menu.setHeaderTitle(account.user.name)
        val inflater = MenuInflater(v.context)
        inflater.inflate(R.menu.action_manager_account, menu)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.layout_draggable_list_with_empty_view, container, false)
    }

    private fun setListShown(shown: Boolean) {
        listContainer.visibility = if (shown) View.VISIBLE else View.GONE
        progressContainer.visibility = if (shown) View.GONE else View.VISIBLE
    }

    class AccountDeletionDialogFragment : BaseDialogFragment(), DialogInterface.OnClickListener {

        override fun onClick(dialog: DialogInterface, which: Int) {
            val account: Account = arguments.getParcelable(IntentConstants.EXTRA_ACCOUNT)
            val resolver = context.contentResolver
            val am = AccountManager.get(context)
            when (which) {
                DialogInterface.BUTTON_POSITIVE -> {
                    val accountKey = account.getAccountKey(am)
                    AccountManagerSupport.removeAccount(am, account, activity, null, null)
                    val where = Expression.equalsArgs(AccountSupportColumns.ACCOUNT_KEY).sql
                    val whereArgs = arrayOf(accountKey.toString())
                    // Also delete tweets related to the account we previously
                    // deleted.
                    resolver.delete(Statuses.CONTENT_URI, where, whereArgs)
                    resolver.delete(Mentions.CONTENT_URI, where, whereArgs)
                    resolver.delete(Inbox.CONTENT_URI, where, whereArgs)
                    resolver.delete(Outbox.CONTENT_URI, where, whereArgs)
                }
            }
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val context = context
            val builder = AlertDialog.Builder(context)
            builder.setNegativeButton(android.R.string.cancel, null)
            builder.setPositiveButton(android.R.string.ok, this)
            builder.setTitle(R.string.account_delete_confirm_title)
            builder.setMessage(R.string.account_delete_confirm_message)
            return builder.create()
        }

    }

    companion object {

        private val FRAGMENT_TAG_ACCOUNT_DELETION = "account_deletion"
    }
}
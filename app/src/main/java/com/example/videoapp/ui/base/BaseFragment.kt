package com.example.videoapp.ui.base

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Observer
import com.example.videoapp.BR
import com.example.videoapp.R
import com.example.videoapp.utils.DialogUtils

abstract class BaseFragment<ViewBinding : ViewDataBinding, ViewModel : BaseViewModel> : Fragment() {

    lateinit var viewBinding: ViewBinding

    abstract val viewModel: ViewModel

    @get:LayoutRes
    abstract val layoutId: Int

    var loadingDialog: AlertDialog? = null
    var messageDialog: AlertDialog? = null

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        viewBinding = DataBindingUtil.inflate(inflater, layoutId, container, false)
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewBinding.apply {
            setVariable(BR.viewModel, viewModel)
            root.isClickable = true
            lifecycleOwner = viewLifecycleOwner
            executePendingBindings()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        loadingDialog = DialogUtils.createLoadingDialog(context, false)
        viewModel.apply {
            isLoading.observe(viewLifecycleOwner, Observer {
                handleShowLoading(it == true)
            })
            errorMessage.observe(viewLifecycleOwner, Observer {
                hideLoading()
                if (it != null && it.isNotBlank()) {
                    handleShowErrorMessage(it)
                }
            })
            noInternetConnectionEvent.observe(viewLifecycleOwner, Observer {
                //                handleShowErrorMessage(getString(R.string.no_internet_connection))
            })
            connectTimeoutEvent.observe(viewLifecycleOwner, Observer {
                //                handleShowErrorMessage(getString(R.string.connect_timeout))
            })
            forceUpdateAppEvent.observe(viewLifecycleOwner, Observer {
                //                handleShowErrorMessage(getString(R.string.force_update_app))
            })
            serverMaintainEvent.observe(viewLifecycleOwner, Observer {
                //                handleShowErrorMessage(getString(R.string.server_maintain_message))
            })
        }
    }

    open fun handleShowLoading(isLoading: Boolean) {
        if (isLoading) showLoading() else hideLoading()
    }

    fun handleShowErrorMessage(message: String) {
        messageDialog = DialogUtils.showMessage(
                context = context,
                message = message,
                textPositive = getString(R.string.ok)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.onDestroy()
    }

    fun showLoading() {
        hideLoading()
        loadingDialog?.show()
    }

    fun hideLoading() {
        if (loadingDialog != null && loadingDialog?.isShowing == true) {
            loadingDialog?.cancel()
        }
    }

    override fun onPause() {
        messageDialog?.dismiss()
        super.onPause()
    }

    /**
     * fragment transaction
     */

    fun findFragment(TAG: String): Fragment? {
        return activity?.supportFragmentManager?.findFragmentByTag(TAG)
    }

    fun findChildFragment(parentFragment: Fragment = this, TAG: String): Fragment? {
        return parentFragment.childFragmentManager.findFragmentByTag(TAG)
    }

    fun addFragment(
            fragment: Fragment, TAG: String?, addToBackStack: Boolean = false,
            transit: Int = -1
    ) {
        activity?.supportFragmentManager?.beginTransaction()
                ?.add(R.id.container, fragment, TAG)
                ?.apply {
                    commitTransaction(this, addToBackStack, transit)
                }
    }

    fun replaceFragment(
            fragment: Fragment, TAG: String?, addToBackStack: Boolean = false,
            transit: Int = -1
    ) {
        activity?.supportFragmentManager?.beginTransaction()
                ?.replace(R.id.container, fragment, TAG)
                ?.apply {
                    commitTransaction(this, addToBackStack, transit)
                }
    }

    fun replaceChildFragment(
            parentFragment: Fragment = this, containerViewId: Int,
            fragment: Fragment, TAG: String?, addToBackStack: Boolean = false, transit: Int = -1
    ) {
        val transaction = parentFragment.childFragmentManager.beginTransaction().replace(
                containerViewId, fragment, TAG
        )
        commitTransaction(transaction, addToBackStack, transit)
    }

    fun addChildFragment(
            parentFragment: Fragment = this, containerViewId: Int,
            fragment: Fragment, TAG: String?, addToBackStack: Boolean = false, transit: Int = -1
    ) {
        val transaction = parentFragment.childFragmentManager.beginTransaction().add(
                containerViewId, fragment, TAG
        )
        commitTransaction(transaction, addToBackStack, transit)
    }

    @SuppressLint("WrongConstant")
    fun showDialogFragment(
            dialogFragment: DialogFragment, TAG: String?,
            addToBackStack: Boolean = false, transit: Int = -1
    ) {
        val transaction = activity?.supportFragmentManager?.beginTransaction()
        if (addToBackStack) transaction?.addToBackStack(TAG)
        if (transit != -1) transaction?.setTransition(transit)
        transaction?.let { dialogFragment.show(it, TAG) }
    }

    @SuppressLint("WrongConstant")
    private fun commitTransaction(
            transaction: FragmentTransaction, addToBackStack: Boolean = false,
            transit: Int = -1
    ) {
        if (addToBackStack) transaction.addToBackStack(null)
        if (transit != -1) transaction.setTransition(transit)
        transaction.commit()
    }

    fun popChildFragment(parentFragment: Fragment = this) {
        parentFragment.childFragmentManager.popBackStack()
    }

    open fun onBack(): Boolean {
        return false
    }
}
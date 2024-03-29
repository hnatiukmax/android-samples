package dev.hnatiuk.android.samples.navigation.complexsample.pages.main

import android.os.Parcelable
import android.view.LayoutInflater
import androidx.navigation.NavController
import androidx.navigation.NavGraph
import dev.hnatiuk.android.samples.core.extensions.toast
import dev.hnatiuk.android.samples.navigation.complexsample.graphs.EntryDestination
import dev.hnatiuk.android.samples.navigation.complexsample.graphs.getEntryGraph
import dev.hnatiuk.android.samples.navigation.databinding.ActivityNavigationMainBinding
import dev.hnatiuk.android.samples.navigation.lib.BaseHostActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.parcelize.Parcelize

@Parcelize
data class MainNavigationActivityArg(
    val isSignIn: Boolean
) : Parcelable

@AndroidEntryPoint
class MainNavigationActivity : BaseHostActivity<ActivityNavigationMainBinding>() {

    private val arg by lazy {
        intent.getParcelableExtra<MainNavigationActivityArg>("arg")
            ?: throw IllegalArgumentException("No argument for EntryNavigationActivity")
    }

    override val bindingFactory: (LayoutInflater) -> ActivityNavigationMainBinding
        get() = ActivityNavigationMainBinding::inflate

    override fun ActivityNavigationMainBinding.initUI() {
        toast(arg.isSignIn.toString())
    }

    override fun NavController.getGraph(): NavGraph {
        val startDestination = if (arg.isSignIn) EntryDestination.TABS else EntryDestination.SIGN_IN
        return navController.getEntryGraph(startDestination)
    }
}
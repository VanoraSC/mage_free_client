package magefree.feature.game.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import magefree.feature.game.board.ManualPassPolicy
import magefree.feature.game.board.PassPolicy

/**
 * Hilt provisioning for `:feature:game`.
 *
 * It binds exactly one thing: the [PassPolicy] the board answers priority prompts with (requirements
 * §14.1). That is the whole point of the seam — **when stops and configurable auto-pass arrive, this
 * binding is what changes**, and nothing in the ViewModel or on the screen has to.
 *
 * Scoped to the ViewModel component because the policy belongs to one board's lifetime; a future
 * policy that reads persisted stop settings would take them as constructor parameters here.
 */
@Module
@InstallIn(ViewModelComponent::class)
object BoardModule {
    /** Everything explicit and manual, as this release ships (§9.1, §14.1). */
    @Provides
    fun passPolicy(): PassPolicy = ManualPassPolicy
}

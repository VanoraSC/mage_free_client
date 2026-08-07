package magefree.feature.tables.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt wiring for `:feature:tables`. Intentionally **empty**: every dependency the tables ViewModels
 * inject is already provided elsewhere in the singleton graph —
 * [magefree.network.table.TableClient] by `:core:network`'s `NetworkModule` (story 0037), and
 * [magefree.decks.DeckRepository] / [magefree.decks.legality.DeckLegality] by `:core:decks`'s
 * `DeckModule` (story 0033). The module exists so the feature's Hilt surface is discoverable in one
 * place and gains a provision seam without a structural change if one is later needed.
 */
@Module
@InstallIn(SingletonComponent::class)
object TablesFeatureModule

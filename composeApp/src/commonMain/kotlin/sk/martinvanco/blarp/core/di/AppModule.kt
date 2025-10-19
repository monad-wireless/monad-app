package sk.martinvanco.blarp.core.di

import org.koin.dsl.module
import sk.martinvanco.blarp.detail_screen.presentation.DetailScreenModel
import sk.martinvanco.blarp.home_screen.presentation.HomeScreenModel

val appModule = module {
    factory { HomeScreenModel() }
    factory { (itemName: String) -> DetailScreenModel(itemName) }
}

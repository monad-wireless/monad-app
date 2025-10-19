package sk.martinvanco.blarp.news.presentation

import cafe.adriel.voyager.core.model.StateScreenModel

class NewsScreenModel : StateScreenModel<NewsState>(NewsState()) {

    fun onEvent(event: NewsEvent) {
        // TODO: Implement event handling
    }
}

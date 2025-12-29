package sk.martinvanco.blarp.screens.detail

import androidx.lifecycle.ViewModel
import sk.martinvanco.blarp.data.MuseumObject
import sk.martinvanco.blarp.data.MuseumRepository
import kotlinx.coroutines.flow.Flow

class DetailViewModel(private val museumRepository: MuseumRepository) : ViewModel() {
    fun getObject(objectId: Int): Flow<MuseumObject?> =
        museumRepository.getObjectById(objectId)
}

package io.github.steelahhh.feature.character.search

import android.os.Parcelable
import androidx.annotation.StringRes
import com.spotify.mobius.First
import com.spotify.mobius.First.first
import com.spotify.mobius.Init
import com.spotify.mobius.Next
import com.spotify.mobius.Next.dispatch
import com.spotify.mobius.Next.next
import com.spotify.mobius.Update
import com.spotify.mobius.rx2.RxMobius
import io.github.steelahhh.R
import io.github.steelahhh.core.BaseEffectHandler
import io.github.steelahhh.core.Navigator
import io.github.steelahhh.core.consumer
import io.github.steelahhh.feature.character.detail.CharacterController
import io.github.steelahhh.feature.character.repository.CharacterRepository
import io.github.steelahhh.feature.character.search.model.CharacterUi
import io.github.steelahhh.feature.character.search.model.toUi
import io.reactivex.Observable
import io.reactivex.ObservableTransformer
import kotlinx.android.parcel.Parcelize

object SearchFeature {
    @Parcelize
    data class Model(
        val page: Int = 0,
        val query: String = "",
        val characters: List<CharacterUi> = listOf(),
        @Transient val isLoading: Boolean = false,
        @StringRes
        @Transient
        val errorRes: Int = R.string.start_typing
    ) : Parcelable

    sealed class Event {
        object StartedLoading : Event()
        data class ErrorLoading(@StringRes val message: Int) : Event()
        data class LoadCharacters(val query: String) : Event()
        data class LoadNextCharacters(val query: String, val page: Int) : Event()
        data class DisplayCharacters(
            val characters: List<CharacterUi>,
            val nextPage: Int = 0
        ) : Event()

        data class OpenCharacterDetail(val character: CharacterUi) : Event()
    }

    sealed class Effect {
        data class LoadCharacters(val query: String) : Effect()
        data class LoadNextCharacters(val query: String, val page: Int) : Effect()
        data class NavigateToCharacterDetail(val character: CharacterUi) : Effect()
    }

    object SearchInitializer : Init<Model, Effect> {
        override fun init(model: Model): First<Model, Effect> = first(model)
    }

    object SearchUpdater : Update<Model, Event, Effect> {
        override fun update(model: Model, event: Event): Next<Model, Effect> = when (event) {
            is Event.StartedLoading -> next(
                model.copy(
                    isLoading = true,
                    errorRes = -1
                )
            )
            is Event.LoadCharacters -> next(
                model.copy(
                    query = event.query,
                    characters = listOf()
                ),
                setOf(
                    SearchFeature.Effect.LoadCharacters(event.query)
                )
            )
            is Event.LoadNextCharacters -> next(
                model.copy(
                    query = event.query
                ),
                setOf(
                    SearchFeature.Effect.LoadNextCharacters(
                        event.query,
                        event.page
                    )
                )
            )
            is Event.ErrorLoading -> next(
                model.copy(
                    characters = listOf(),
                    isLoading = false,
                    errorRes = event.message
                )
            )
            is Event.DisplayCharacters -> next(
                model.copy(
                    page = event.nextPage,
                    characters = event.characters,
                    isLoading = false
                )
            )
            is Event.OpenCharacterDetail -> dispatch(
                setOf(
                    SearchFeature.Effect.NavigateToCharacterDetail(event.character)
                )
            )
        }
    }

    class SearchEffectHandler(
        private val repository: CharacterRepository,
        private val navigator: Navigator
    ) : BaseEffectHandler<Effect, Event>() {
        override fun create(): ObservableTransformer<Effect, Event> {
            return RxMobius.subtypeEffectHandler<Effect, Event>()
                .addTransformer(SearchFeature.Effect.LoadCharacters::class.java) { effect ->
                    effect
                        .flatMap { (query: String) ->
                            if (query.isEmpty()) Observable.just(
                                SearchFeature.Event.ErrorLoading(
                                    R.string.start_typing
                                )
                            )
                            else repository.findCharacters(query).toObservable()
                                .map<Event> { page ->
                                    val characters = page.characters.map { it.toUi() }
                                    if (characters.isEmpty()) SearchFeature.Event.ErrorLoading(
                                        R.string.query_result_empty
                                    )
                                    else SearchFeature.Event.DisplayCharacters(
                                        characters,
                                        page.nextPage
                                    )
                                }
                                .startWith(SearchFeature.Event.StartedLoading)
                                .onErrorReturn {
                                    SearchFeature.Event.ErrorLoading(
                                        R.string.server_error
                                    )
                                }
                        }
                }
                .addTransformer(SearchFeature.Effect.LoadNextCharacters::class.java) { effect ->
                    effect.flatMap {
                        repository.findCharacters(it.query, it.page).toObservable()
                            .map<Event> { page ->
                                val characters = page.characters.map { it.toUi() }
                                SearchFeature.Event.DisplayCharacters(
                                    characters,
                                    page.nextPage
                                )
                            }
                    }
                }
                .consumer(SearchFeature.Effect.NavigateToCharacterDetail::class.java) { effect ->
                    navigator.pushController(CharacterController.newInstance(effect.character.id))
                }
                .build()
        }
    }
}
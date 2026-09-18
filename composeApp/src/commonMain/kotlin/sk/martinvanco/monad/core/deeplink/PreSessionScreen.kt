package sk.martinvanco.monad.core.deeplink

/**
 * A screen the app shows before there is a session. A parked deep link waits for it to go away.
 *
 * Marks the four screens a participant can be looking at while not signed in: the splash, the
 * onboarding run, the login form and the registration form. `App()` routes a parked link only
 * when the top of the navigator stack is *not* one of these.
 *
 * WHY A MARKER RATHER THAN AN AUTH FLAG. "Is there a session?" is not the question a deep link
 * needs answered. The question is "may this screen be replaced right now?", and the splash is the
 * case that proves the difference: it decides the start screen asynchronously and then calls
 * `navigationManager.replace(...)`, which swaps whatever is on top. A link routed at that moment
 * is destroyed by a navigation that was already in flight, whether or not a token existed. The
 * marker names exactly the screens that do that, so the rule is checked by the compiler at each
 * one rather than inferred from state that says something else.
 *
 * WHY NOT AN ALLOW-LIST OF SIGNED-IN SCREENS. There are two signed-in entry points today
 * (`MainContainerScreen` and, on a resume, `ActiveQuestScreen`) and every future one would have to
 * be remembered. Forgetting to add a screen here fails the safe way — a link is routed onto a
 * screen that can take it — while forgetting to add one to an allow-list would strand the link
 * for ever.
 */
interface PreSessionScreen

package org.phonemesh.app.demo

import java.util.Locale

/** What the demo people say, in the language they were written to (English or French, the app's languages). */
object DemoScript {
    /** The app's own language tags (MessageCodec.canonical): "fr" or "en-US". */
    fun tag(language: String): String = if (fr(language)) "fr" else "en-US"

    fun defaultLanguage(): String = tag(Locale.getDefault().language)

    private fun fr(language: String) = language.startsWith("fr")

    fun metGreeting(language: String) =
        if (fr(language)) "Content de t'avoir rencontré ! Maintenant on peut s'écrire." else "Good to meet you! Now we can write to each other."

    fun linkGreeting(who: DemoPerson.Who, language: String) = when (who) {
        DemoPerson.Who.YAW -> if (fr(language)) "Merci d'avoir ouvert mon lien. Ça marche !" else "Thanks for opening my link. It works!"
        else -> if (fr(language)) "Bonjour, c'est ${who.label}." else "Hello, it's ${who.label}."
    }

    private val replies = mapOf(
        "en" to listOf("Got it, thank you!", "Yes, see you at the market.", "I'm on my way.", "Good idea. Tomorrow morning?", "OK, I will tell the others."),
        "fr" to listOf("Bien reçu, merci !", "Oui, à tout à l'heure au marché.", "J'arrive.", "Bonne idée. Demain matin ?", "D'accord, je préviens les autres."),
    )

    private val news = mapOf(
        "en" to listOf("The road to the clinic is open again.", "Can you bring the water can tonight?", "The meeting is moved to Friday."),
        "fr" to listOf("La route du dispensaire est de nouveau ouverte.", "Tu peux apporter le bidon d'eau ce soir ?", "La réunion est déplacée à vendredi."),
    )

    private val shared = mapOf(
        "en" to listOf("Good morning everyone!", "Is anyone going to the market today?", "Rain is coming this afternoon.", "Who has a ladder I can borrow?"),
        "fr" to listOf("Bonjour à tous !", "Quelqu'un va au marché aujourd'hui ?", "La pluie arrive cet après-midi.", "Qui peut me prêter une échelle ?"),
    )

    private fun pick(table: Map<String, List<String>>, language: String, n: Int) =
        table.getValue(if (fr(language)) "fr" else "en").let { it[Math.floorMod(n, it.size)] }

    fun reply(who: DemoPerson.Who, language: String, n: Int) = pick(replies, language, n + who.ordinal)
    fun news(who: DemoPerson.Who, language: String, n: Int) = pick(news, language, n + who.ordinal)
    fun sharedPost(language: String, n: Int) = pick(shared, language, n)

    fun stockDirect(language: String) = if (fr(language)) "Bonjour, c'est Sena. Tu as une minute ?" else "Hi, it's Sena. Do you have a minute?"

    /** Sena's answer to a private text; the app tags its own texts [pm1:fr] or [pm1:en-US]. */
    fun stockReply(text: String) = if (text.startsWith("[pm1:fr")) "Merci, bien reçu." else "Thanks, got it."

    /** What someone using the stock Meshtastic app writes: plain text, no name, no language. */
    fun stockText(language: String) = if (fr(language)) "Salut, quelqu'un m'entend ?" else "Hi, can anyone hear me?"
}
